/**
 * WebCodecs H.264 Decoder
 * Decodes raw H.264 NAL units using hardware-accelerated VideoDecoder
 */
class H264Decoder {
    constructor(onFrame, onError) {
        this.onFrame = onFrame;
        this.onError = onError;
        this.decoder = null;
        this.configured = false;
        this.frameCount = 0;
        this.startTime = 0;
    }

    static isSupported() {
        return typeof VideoDecoder !== 'undefined';
    }

    async init() {
        if (!H264Decoder.isSupported()) {
            throw new Error('WebCodecs VideoDecoder not available');
        }

        // Check H.264 Baseline support
        const support = await VideoDecoder.isConfigSupported({
            codec: 'avc1.42001e', // Baseline profile, level 3.0
            optimizeForLatency: true,
            hardwareAcceleration: 'prefer-hardware'
        });

        if (!support.supported) {
            throw new Error('H.264 Baseline not supported');
        }

        this.decoder = new VideoDecoder({
            output: (frame) => {
                this.frameCount++;
                this.onFrame(frame);
            },
            error: (e) => {
                console.error('[Decoder] Error:', e);
                this.onError(e);
            }
        });

        this.decoder.configure({
            codec: 'avc1.42001e',
            optimizeForLatency: true,
            hardwareAcceleration: 'prefer-hardware'
        });

        this.configured = true;
        this.startTime = performance.now();
        console.log('[Decoder] Initialized with WebCodecs');
    }

    /**
     * Decode a frame received from WebSocket
     * @param {ArrayBuffer} data - Binary data: 1-byte header + H.264 NAL units
     */
    decode(data) {
        if (!this.configured || !this.decoder || this.decoder.state === 'closed') {
            return;
        }

        const view = new Uint8Array(data);
        if (view.length < 2) return;

        const isKeyFrame = view[0] === 0x01;
        const nalData = data.slice(1); // Remove our 1-byte header

        try {
            const chunk = new EncodedVideoChunk({
                type: isKeyFrame ? 'key' : 'delta',
                timestamp: performance.now() * 1000, // microseconds
                data: nalData
            });

            // Drop frames if decoder is backing up
            if (this.decoder.decodeQueueSize > 3) {
                console.warn('[Decoder] Queue backing up:', this.decoder.decodeQueueSize);
                return;
            }

            this.decoder.decode(chunk);
        } catch (e) {
            console.error('[Decoder] Decode error:', e);
            this.onError(e);
        }
    }

    getFps() {
        const elapsed = (performance.now() - this.startTime) / 1000;
        if (elapsed < 1) return 0;
        return Math.round(this.frameCount / elapsed);
    }

    resetStats() {
        this.frameCount = 0;
        this.startTime = performance.now();
    }

    destroy() {
        if (this.decoder && this.decoder.state !== 'closed') {
            try {
                this.decoder.close();
            } catch (_) {}
        }
        this.decoder = null;
        this.configured = false;
    }
}
