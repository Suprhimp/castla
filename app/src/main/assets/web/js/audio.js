/**
 * Castla - Audio Player (Opus via WebCodecs + raw PCM fallback)
 *
 * Protocol: each WebSocket binary message has a header:
 *   0x00 + JSON = config: {"codec":"opus"|"pcm","sampleRate":48000,"channels":2}
 *   0x01 + u32 LE timestamp(ms) + audio data = Opus or PCM Int16 LE
 *
 * Flow:
 *   1. User taps unmute → AudioContext created + resumed (autoplay policy)
 *   2. WebSocket connects → first message is JSON config (0x00)
 *   3a. Opus: AudioDecoder.configure({codec:'opus'}) → decode → schedule playback
 *   3b. PCM:  Int16 → Float32 → AudioBuffer → schedule playback
 */
class AudioPlayer {
    constructor() {
        this.audioCtx = null;
        this.decoder = null;
        this.socket = null;
        this.sampleRate = 48000;
        this.channels = 2;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        this.mode = null; // 'opus' or 'pcm'
        // Jitter buffer: WiFi ~10ms + JS GC ~30ms + OS scheduling ~20ms + margin ~60ms
        this.JITTER_BUFFER_SEC = 0.12;
        this.MAX_LATENCY = 0.4;
        this.OPUS_FRAME_DURATION_US = 20000; // 20ms
        this.clockOffset = null; // EMA server-to-client clock offset for A/V sync
    }

    async startFromUserGesture(wsUrl) {
        try {
            this.audioCtx = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: this.sampleRate,
                latencyHint: 'interactive'
            });
            if (this.audioCtx.state === 'suspended') {
                await this.audioCtx.resume();
            }
            console.log('[Audio] AudioContext ready, state:', this.audioCtx.state);
            this.nextPlayTime = 0;
            this.timestampUs = 0;
            this.mode = null;
            this._connectSocket(wsUrl);
            return true;
        } catch (e) {
            console.error('[Audio] Failed to start:', e);
            this.stop();
            return false;
        }
    }

    _configureOpus() {
        if (typeof AudioDecoder === 'undefined') {
            console.warn('[Audio] WebCodecs AudioDecoder not available');
            return false;
        }
        try {
            this.decoder = new AudioDecoder({
                output: (audioData) => this._handleDecodedAudio(audioData),
                error: (e) => {
                    console.error('[Audio] Opus decoder error:', e);
                    this.decoder = null;
                }
            });
            this.decoder.configure({
                codec: 'opus',
                sampleRate: this.sampleRate,
                numberOfChannels: this.channels
            });
            this.mode = 'opus';
            console.log('[Audio] Opus decoder configured');
            return true;
        } catch (e) {
            console.error('[Audio] Opus decoder failed:', e);
            this.decoder = null;
            return false;
        }
    }

    _handleDecodedAudio(audioData) {
        if (!this.audioCtx || this.audioCtx.state === 'closed') {
            audioData.close();
            return;
        }
        try {
            const ch = audioData.numberOfChannels;
            const frames = audioData.numberOfFrames;
            const sr = audioData.sampleRate;
            const buf = this.audioCtx.createBuffer(ch, frames, sr);
            for (let c = 0; c < ch; c++) {
                const cd = new Float32Array(frames);
                audioData.copyTo(cd, { planeIndex: c });
                buf.copyToChannel(cd, c);
            }
            this._scheduleBuffer(buf);
        } catch (e) {
            // skip
        } finally {
            audioData.close();
        }
    }

    _playPCM(arrayBuffer) {
        if (!this.audioCtx || this.audioCtx.state === 'closed') return;
        const int16 = new Int16Array(arrayBuffer);
        const frameCount = Math.floor(int16.length / this.channels);
        if (frameCount === 0) return;
        const buf = this.audioCtx.createBuffer(this.channels, frameCount, this.sampleRate);
        for (let ch = 0; ch < this.channels; ch++) {
            const cd = buf.getChannelData(ch);
            for (let i = 0; i < frameCount; i++) {
                cd[i] = int16[i * this.channels + ch] / 32768.0;
            }
        }
        this._scheduleBuffer(buf);
    }

    _scheduleBuffer(audioBuffer) {
        const source = this.audioCtx.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(this.audioCtx.destination);
        const now = this.audioCtx.currentTime;
        if (this.nextPlayTime < now) {
            this.nextPlayTime = now + this.JITTER_BUFFER_SEC;
        } else if (this.nextPlayTime > now + this.MAX_LATENCY) {
            this.nextPlayTime = now + this.JITTER_BUFFER_SEC;
        }
        source.start(this.nextPlayTime);
        this.nextPlayTime += audioBuffer.duration;
    }

    _connectSocket(wsUrl) {
        if (this.socket) { this.socket.onclose = null; this.socket.close(); }
        this.socket = new WebSocket(wsUrl);
        this.socket.binaryType = 'arraybuffer';

        this.socket.onopen = () => console.log('[Audio] WebSocket connected');

        this.socket.onmessage = (event) => {
            if (!(event.data instanceof ArrayBuffer) || event.data.byteLength < 2) return;
            const view = new Uint8Array(event.data);
            const type = view[0];

            if (type === 0x00) {
                // JSON config
                try {
                    const json = new TextDecoder().decode(view.subarray(1));
                    const config = JSON.parse(json);
                    this.sampleRate = config.sampleRate || 48000;
                    this.channels = config.channels || 2;
                    console.log('[Audio] Config:', json);

                    // Recreate AudioContext if sample rate changed
                    if (this.audioCtx && this.audioCtx.sampleRate !== this.sampleRate) {
                        this.audioCtx.close().catch(() => {});
                        this.audioCtx = new (window.AudioContext || window.webkitAudioContext)({
                            sampleRate: this.sampleRate,
                            latencyHint: 'interactive'
                        });
                        this.audioCtx.resume().catch(() => {});
                        this.nextPlayTime = 0;
                    }

                    if (config.codec === 'opus') {
                        if (!this._configureOpus()) {
                            // Opus not available — ask server to switch to PCM
                            console.warn('[Audio] Opus not supported, requesting PCM fallback');
                            if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                                this.socket.send('requestPcm');
                            }
                            // mode stays null until server sends new PCM config
                            return;
                        }
                    } else {
                        this.mode = 'pcm';
                        console.log('[Audio] PCM mode');
                    }
                } catch (e) {
                    console.error('[Audio] Bad config:', e);
                }
                return;
            }

            // type === 0x01: audio data with 5-byte header [0x01][tsMs u32 LE] + audio
            if (event.data.byteLength < 6) return;
            const dv = new DataView(event.data);
            const serverTsMs = dv.getUint32(1, true); // LE timestamp
            const audioPayload = event.data.slice(5);

            // EMA clock offset for A/V sync
            const clientNow = performance.now();
            const currentOffset = clientNow - serverTsMs;
            if (this.clockOffset === null) {
                this.clockOffset = currentOffset;
            } else {
                this.clockOffset = this.clockOffset * 0.95 + currentOffset * 0.05;
            }

            if (this.mode === 'opus' && this.decoder && this.decoder.state === 'configured') {
                try {
                    const chunk = new EncodedAudioChunk({
                        type: 'key',
                        timestamp: this.timestampUs,
                        data: audioPayload
                    });
                    this.timestampUs += this.OPUS_FRAME_DURATION_US;
                    this.decoder.decode(chunk);
                } catch (e) { /* skip bad frame */ }
            } else if (this.mode === 'pcm') {
                this._playPCM(audioPayload);
            }
        };

        this.socket.onclose = () => console.log('[Audio] WebSocket disconnected');
    }

    stop() {
        if (this.decoder && this.decoder.state !== 'closed') {
            try { this.decoder.close(); } catch (_) {}
        }
        this.decoder = null;
        if (this.socket) { this.socket.onclose = null; this.socket.close(); this.socket = null; }
        if (this.audioCtx && this.audioCtx.state !== 'closed') {
            this.audioCtx.close().catch(() => {});
        }
        this.audioCtx = null;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        this.mode = null;
        console.log('[Audio] Stopped');
    }

    static isSupported() {
        return !!(window.AudioContext || window.webkitAudioContext);
    }
}
