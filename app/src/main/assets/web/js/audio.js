/**
 * Castla - Audio Player (WebCodecs)
 * Receives raw AAC-LC frames via WebSocket, decodes with AudioDecoder,
 * and plays through Web Audio API with low-latency scheduling.
 */
class AudioPlayer {
    constructor() {
        this.audioCtx = null;
        this.decoder = null;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        this.isPlaying = false;
        this.socket = null;
        // AAC-LC frame = 1024 samples @ 44100Hz ~ 23.22ms
        this.FRAME_DURATION_US = 23219;
        // Max buffer before catch-up skip (seconds)
        this.MAX_LATENCY = 0.5;
    }

    /**
     * Initialize AudioContext (starts suspended per autoplay policy).
     * Returns true if supported and initialized.
     */
    init() {
        try {
            this.audioCtx = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: 44100,
                latencyHint: 'interactive'
            });
            this.nextPlayTime = 0;
            this.isPlaying = false;
            this.timestampUs = 0;
            console.log('[Audio] AudioContext created, state:', this.audioCtx.state);
            return true;
        } catch (e) {
            console.error('[Audio] Failed to create AudioContext:', e);
            return false;
        }
    }

    /**
     * Resume AudioContext (must be called from user gesture) and start WebSocket + decoder.
     */
    async startFromUserGesture(wsUrl) {
        if (!this.audioCtx) {
            if (!this.init()) return false;
        }

        // Resume AudioContext — requires user gesture context
        if (this.audioCtx.state === 'suspended') {
            await this.audioCtx.resume();
            console.log('[Audio] AudioContext resumed');
        }

        // Set up WebCodecs AudioDecoder
        if (!this._initDecoder()) return false;

        // Connect WebSocket
        this._connectSocket(wsUrl);
        return true;
    }

    /**
     * Initialize WebCodecs AudioDecoder for AAC-LC 44100Hz stereo.
     */
    _initDecoder() {
        if (typeof AudioDecoder === 'undefined') {
            console.warn('[Audio] WebCodecs AudioDecoder not available, falling back to decodeAudioData');
            this.decoder = null;
            return true; // will use fallback path
        }

        try {
            this.decoder = new AudioDecoder({
                output: (audioData) => this._handleDecodedAudio(audioData),
                error: (e) => console.error('[Audio] Decoder error:', e)
            });

            this.decoder.configure({
                codec: 'mp4a.40.2', // AAC-LC
                sampleRate: 44100,
                numberOfChannels: 2,
                // AudioSpecificConfig for AAC-LC, 44100Hz, 2ch
                // objectType=2(5bits) freqIdx=4(4bits) chanCfg=2(4bits) pad=000(3bits)
                // = 00010 0100 0010 000 = 0x12 0x10
                description: new Uint8Array([0x12, 0x10])
            });

            console.log('[Audio] WebCodecs AudioDecoder configured');
            return true;
        } catch (e) {
            console.error('[Audio] Failed to configure AudioDecoder:', e);
            this.decoder = null;
            return true; // will use fallback
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

            // Create AudioBuffer and copy PCM data
            const audioBuffer = this.audioCtx.createBuffer(channels, frames, sampleRate);
            for (let c = 0; c < channels; c++) {
                const channelData = new Float32Array(frames);
                audioData.copyTo(channelData, { planeIndex: c });
                audioBuffer.copyToChannel(channelData, c);
            }

            this._scheduleBuffer(audioBuffer);
        } catch (e) {
            // Skip corrupt frames silently
        } finally {
            // CRITICAL: prevent OOM — must close WebCodecs AudioData
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
            // Buffer underrun or first play — start with small lead
            this.nextPlayTime = now + 0.05;
        } else if (this.nextPlayTime > now + this.MAX_LATENCY) {
            // Too much latency accumulated — skip to live edge
            console.warn('[Audio] Latency exceeded, jumping to live edge');
            this.nextPlayTime = now + 0.05;
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
            if (!(event.data instanceof ArrayBuffer)) return;

            if (this.decoder && this.decoder.state === 'configured') {
                // WebCodecs path: feed raw AAC as EncodedAudioChunk
                const chunk = new EncodedAudioChunk({
                    type: 'key', // all AAC frames are independently decodable
                    timestamp: this.timestampUs,
                    data: event.data
                });
                this.timestampUs += this.FRAME_DURATION_US;
                this.decoder.decode(chunk);
            } else {
                // Fallback: decodeAudioData (wrap in ADTS)
                this._fallbackDecode(new Uint8Array(event.data));
            }
        };

        this.socket.onclose = () => {
            console.log('[Audio] WebSocket disconnected');
        };
    }

    /**
     * Fallback: wrap raw AAC in ADTS header and use decodeAudioData.
     * Used when WebCodecs AudioDecoder is not available (older browsers).
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
     * Wrap raw AAC-LC frame in 7-byte ADTS header.
     * Only used in fallback path (decodeAudioData requires container).
     */
    _wrapADTS(rawAAC) {
        const frameLength = rawAAC.length + 7;
        const adts = new Uint8Array(frameLength);
        adts[0] = 0xFF;
        adts[1] = 0xF1; // MPEG-4, Layer 0, no CRC
        adts[2] = ((1 << 6) | (4 << 2) | (0 << 1) | ((2 >> 2) & 0x01));
        adts[3] = ((2 & 0x03) << 6) | ((frameLength >> 11) & 0x03);
        adts[4] = (frameLength >> 3) & 0xFF;
        adts[5] = ((frameLength & 0x07) << 5) | 0x1F;
        adts[6] = 0xFC;
        adts.set(rawAAC instanceof Uint8Array ? rawAAC : new Uint8Array(rawAAC), 7);
        return adts;
    }

    /**
     * Stop everything — decoder, socket, audio context.
     */
    stop() {
        if (this.decoder && this.decoder.state !== 'closed') {
            try { this.decoder.close(); } catch (_) {}
        }
        this.decoder = null;

        if (this.socket) {
            this.socket.onclose = null;
            this.socket.close();
            this.socket = null;
        }

        if (this.audioCtx && this.audioCtx.state !== 'closed') {
            this.audioCtx.close().catch(() => {});
        }
        this.audioCtx = null;
        this.isPlaying = false;
        this.nextPlayTime = 0;
        this.timestampUs = 0;
        console.log('[Audio] Stopped');
    }

    static isSupported() {
        return !!(window.AudioContext || window.webkitAudioContext);
    }
}
