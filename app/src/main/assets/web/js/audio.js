/**
 * Castla - Audio Player (WebCodecs)
 *
 * Protocol: each WebSocket binary message has a 1-byte header:
 *   0x00 = Codec Specific Data (AudioSpecificConfig from MediaCodec)
 *   0x01 = Encoded AAC-LC audio frame
 *
 * Flow:
 *   1. First message (0x00) delivers the CSD → used as AudioDecoder.configure({ description })
 *   2. Subsequent messages (0x01) are fed as EncodedAudioChunk to the decoder
 *   3. Decoded PCM is scheduled for gapless playback via Web Audio API
 */
class AudioPlayer {
    constructor() {
        this.audioCtx = null;
        this.decoder = null;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        this.socket = null;
        this.csd = null; // AudioSpecificConfig from Android encoder
        this.useWebCodecs = typeof AudioDecoder !== 'undefined';
        // AAC-LC frame = 1024 samples @ 44100Hz ~ 23.22ms
        this.FRAME_DURATION_US = 23219;
        // Jitter buffer: intentional delay to absorb WiFi packet jitter
        this.JITTER_BUFFER_SEC = 0.3;
        // Max buffer before catch-up skip (seconds)
        this.MAX_LATENCY = 1.0;
    }

    /**
     * Initialize AudioContext (starts suspended per autoplay policy).
     */
    init() {
        try {
            this.audioCtx = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: 44100,
                latencyHint: 'interactive'
            });
            this.nextPlayTime = 0;
            this.timestampUs = 0;
            this.csd = null;
            console.log('[Audio] AudioContext created, state:', this.audioCtx.state);
            return true;
        } catch (e) {
            console.error('[Audio] Failed to create AudioContext:', e);
            return false;
        }
    }

    /**
     * Resume AudioContext (must be called from user gesture) and connect WebSocket.
     * Decoder is configured lazily when the first CSD message arrives.
     */
    async startFromUserGesture(wsUrl) {
        if (!this.audioCtx) {
            if (!this.init()) return false;
        }

        if (this.audioCtx.state === 'suspended') {
            await this.audioCtx.resume();
            console.log('[Audio] AudioContext resumed');
        }

        this._connectSocket(wsUrl);
        return true;
    }

    /**
     * Configure WebCodecs AudioDecoder using the CSD received from Android.
     */
    _configureDecoder(csd) {
        if (!this.useWebCodecs) return false;

        try {
            this.decoder = new AudioDecoder({
                output: (audioData) => this._handleDecodedAudio(audioData),
                error: (e) => {
                    console.error('[Audio] Decoder error:', e);
                    // Reset decoder on error so next CSD re-configures
                    this.decoder = null;
                    this.csd = null;
                }
            });

            this.decoder.configure({
                codec: 'mp4a.40.2', // AAC-LC
                sampleRate: 44100,
                numberOfChannels: 2,
                description: csd
            });

            console.log('[Audio] AudioDecoder configured with CSD from encoder (' + csd.length + ' bytes)');
            return true;
        } catch (e) {
            console.error('[Audio] Failed to configure AudioDecoder:', e);
            this.decoder = null;
            this.useWebCodecs = false;
            return false;
        }
    }

    /**
     * Handle decoded PCM AudioData — schedule playback via Web Audio API.
     */
    _handleDecodedAudio(audioData) {
        if (!this.audioCtx || this.audioCtx.state === 'closed') {
            audioData.close();
            return;
        }

        try {
            const channels = audioData.numberOfChannels;
            const frames = audioData.numberOfFrames;
            const sampleRate = audioData.sampleRate;

            const audioBuffer = this.audioCtx.createBuffer(channels, frames, sampleRate);
            for (let c = 0; c < channels; c++) {
                const channelData = new Float32Array(frames);
                audioData.copyTo(channelData, { planeIndex: c });
                audioBuffer.copyToChannel(channelData, c);
            }

            this._scheduleBuffer(audioBuffer);
        } catch (e) {
            // Skip corrupt frames
        } finally {
            // CRITICAL: prevent OOM on Tesla browser
            audioData.close();
        }
    }

    /**
     * Schedule an AudioBuffer for gapless playback with catch-up logic.
     */
    _scheduleBuffer(audioBuffer) {
        const source = this.audioCtx.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(this.audioCtx.destination);

        const now = this.audioCtx.currentTime;

        if (this.nextPlayTime < now) {
            // Buffer underrun or first play — re-buffer with jitter margin
            console.warn('[Audio] Buffer underrun, re-buffering', this.JITTER_BUFFER_SEC + 's');
            this.nextPlayTime = now + this.JITTER_BUFFER_SEC;
        } else if (this.nextPlayTime > now + this.MAX_LATENCY) {
            // Too much latency — skip to live edge
            console.warn('[Audio] Latency exceeded, jumping to live edge');
            this.nextPlayTime = now + this.JITTER_BUFFER_SEC;
        }

        source.start(this.nextPlayTime);
        this.nextPlayTime += audioBuffer.duration;
    }

    /**
     * Connect to audio WebSocket endpoint.
     */
    _connectSocket(wsUrl) {
        if (this.socket) {
            this.socket.onclose = null;
            this.socket.close();
        }

        this.socket = new WebSocket(wsUrl);
        this.socket.binaryType = 'arraybuffer';

        this.socket.onopen = () => {
            console.log('[Audio] WebSocket connected');
        };

        this.socket.onmessage = (event) => {
            if (!(event.data instanceof ArrayBuffer) || event.data.byteLength < 2) return;

            const view = new Uint8Array(event.data);
            const type = view[0];
            const payload = view.subarray(1);

            if (type === 0x00) {
                // CSD (AudioSpecificConfig) from MediaCodec
                this.csd = new Uint8Array(payload);
                console.log('[Audio] Received CSD:', Array.from(this.csd).map(b => b.toString(16).padStart(2, '0')).join(' '));
                this._configureDecoder(this.csd);
                return;
            }

            // type === 0x01: encoded AAC frame
            if (this.decoder && this.decoder.state === 'configured') {
                // WebCodecs path
                try {
                    const chunk = new EncodedAudioChunk({
                        type: 'key',
                        timestamp: this.timestampUs,
                        data: payload
                    });
                    this.timestampUs += this.FRAME_DURATION_US;
                    this.decoder.decode(chunk);
                } catch (e) {
                    // Skip bad frames
                }
            } else {
                // Fallback: decodeAudioData with ADTS wrapper
                this._fallbackDecode(payload);
            }
        };

        this.socket.onclose = () => {
            console.log('[Audio] WebSocket disconnected');
        };
    }

    /**
     * Fallback for browsers without WebCodecs AudioDecoder.
     */
    async _fallbackDecode(rawAAC) {
        if (!this.audioCtx || this.audioCtx.state === 'closed') return;

        try {
            const adtsFrame = this._wrapADTS(rawAAC);
            const audioBuffer = await this.audioCtx.decodeAudioData(adtsFrame.buffer);
            this._scheduleBuffer(audioBuffer);
        } catch (e) {
            // Skip corrupt/partial frames
        }
    }

    /**
     * Wrap raw AAC-LC frame in 7-byte ADTS header (fallback path only).
     */
    _wrapADTS(rawAAC) {
        const frameLength = rawAAC.length + 7;
        const adts = new Uint8Array(frameLength);
        adts[0] = 0xFF;
        adts[1] = 0xF1;
        adts[2] = ((1 << 6) | (4 << 2) | (0 << 1) | ((2 >> 2) & 0x01));
        adts[3] = ((2 & 0x03) << 6) | ((frameLength >> 11) & 0x03);
        adts[4] = (frameLength >> 3) & 0xFF;
        adts[5] = ((frameLength & 0x07) << 5) | 0x1F;
        adts[6] = 0xFC;
        adts.set(rawAAC instanceof Uint8Array ? rawAAC : new Uint8Array(rawAAC), 7);
        return adts;
    }

    stop() {
        if (this.decoder && this.decoder.state !== 'closed') {
            try { this.decoder.close(); } catch (_) {}
        }
        this.decoder = null;
        this.csd = null;

        if (this.socket) {
            this.socket.onclose = null;
            this.socket.close();
            this.socket = null;
        }

        if (this.audioCtx && this.audioCtx.state !== 'closed') {
            this.audioCtx.close().catch(() => {});
        }
        this.audioCtx = null;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        console.log('[Audio] Stopped');
    }

    static isSupported() {
        return !!(window.AudioContext || window.webkitAudioContext);
    }
}
