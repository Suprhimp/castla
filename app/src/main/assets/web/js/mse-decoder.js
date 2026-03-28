class MseDecoder {
    constructor(onError) {
        this.video = document.getElementById('mse-video'); // Must be initialized properly here
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.onError = onError;

        this.queue = [];
        this.updating = false;
        this.ready = false;
        this.mimeType = 'video/mp4; codecs="avc1.4d002a"'; // Baseline profile by default, will update dynamically

        this.sps = null;
        this.pps = null;

        this.startTime = 0;
        this.framesDecoded = 0;
        this.lastDecodeTime = 0;
        this.videoDuration = 0;

        // Target latency management
        this.targetLatency = 0.15; // 150ms buffer
        this.maxLatency = 0.5; // 500ms max allowed before seek
        this.latencyCheckInterval = null;
    }

    static isSupported() {
        return typeof MediaSource !== 'undefined' &&
            MediaSource.isTypeSupported('video/mp4; codecs="avc1.42001e"');
    }

    init() {
        return new Promise((resolve, reject) => {
            // Error check: Ensure video element exists
            if (!this.video) {
                console.error('[MSE] Video element #mse-video not found in DOM');
                reject(new Error('Video element not found'));
                return;
            }

            this.mediaSource = new MediaSource();

            // Listen for video element errors
            this.video.addEventListener('error', (e) => {
                const err = this.video.error;
                console.error('[MSE] Video element error:', err ? `code=${err.code} message=${err.message}` : e);
            });

            this.mediaSource.addEventListener('sourceopen', () => {
                try {
                    this.startTime = performance.now();
                    console.log('[MSE] MediaSource opened, waiting for SPS/PPS');
                    resolve();
                } catch (e) {
                    reject(e);
                }
            });

            this.mediaSource.addEventListener('sourceclose', () => {
                console.log('[MSE] MediaSource closed');
                this.ready = false;
                // Don't access this.mediaSource here — it may have been nulled by destroy()
            });

            this.mediaSource.addEventListener('sourceended', () => {
                console.log('[MSE] MediaSource ended');
            });

            this.video.src = URL.createObjectURL(this.mediaSource);

            // Show video element, hide canvas (if using WebCodecs/Fallback)
            this.video.style.display = 'block';
            const canvas = document.getElementById('display');
            if (canvas) canvas.style.display = 'none';

            this.startLatencyManagement();
        });
    }

    setupSourceBuffer(codecString) {
        if (!this.mediaSource || this.mediaSource.readyState !== 'open') {
            console.error('[MSE] MediaSource not open, cannot create SourceBuffer');
            return false;
        }

        try {
            if (this.sourceBuffer) {
                this.mediaSource.removeSourceBuffer(this.sourceBuffer);
                this.sourceBuffer = null;
            }

            console.log(`[MSE] Creating SourceBuffer with codec: ${codecString}`);
            this.sourceBuffer = this.mediaSource.addSourceBuffer(codecString);
            this.sourceBuffer.mode = 'sequence'; // Let the browser determine timestamps

            this.sourceBuffer.addEventListener('updateend', () => {
                this.updating = false;
                this.processQueue();
            });

            this.sourceBuffer.addEventListener('error', (e) => {
                console.error('[MSE] SourceBuffer error:', e);
                if (this.onError) this.onError(e);
            });

            this.ready = true;
            console.log('[MSE] SourceBuffer created and ready');
            return true;
        } catch (e) {
            console.error('[MSE] Failed to create SourceBuffer:', e);
            return false;
        }
    }

    processQueue() {
        if (this.updating || this.queue.length === 0 || !this.sourceBuffer || !this.ready) {
            return;
        }

        try {
            const data = this.queue.shift();
            this.updating = true;
            this.sourceBuffer.appendBuffer(data);
            this.framesDecoded++;
            this.lastDecodeTime = performance.now();
        } catch (e) {
            console.error('[MSE] appendBuffer error:', e);
            this.updating = false;
            // If quota exceeded, flush buffer
            if (e.name === 'QuotaExceededError' && this.sourceBuffer) {
                this.flushBuffer();
            }
        }
    }

    flushBuffer() {
        if (this.updating || !this.sourceBuffer || !this.video) return;
        try {
            const currentTime = this.video.currentTime;
            if (currentTime > 2) {
                this.updating = true;
                this.sourceBuffer.remove(0, currentTime - 1);
                console.log(`[MSE] Flushed buffer up to ${currentTime - 1}`);
            }
        } catch (e) {
            console.error('[MSE] flush error:', e);
            this.updating = false;
        }
    }

    startLatencyManagement() {
        this.latencyCheckInterval = setInterval(() => {
            if (!this.video || !this.sourceBuffer || this.video.buffered.length === 0) return;

            const bufferedEnd = this.video.buffered.end(this.video.buffered.length - 1);
            const currentTime = this.video.currentTime;
            const latency = bufferedEnd - currentTime;

            if (latency > this.maxLatency) {
                console.log(`[MSE] Latency too high (${latency.toFixed(2)}s). Seeking to live edge.`);
                // Seek to target latency
                this.video.currentTime = Math.max(0, bufferedEnd - this.targetLatency);
            }

            // Cleanup old buffers periodically
            if (currentTime > 10 && !this.updating) {
                this.flushBuffer();
            }
        }, 1000);
    }

    decode(data) {
        if (!data || data.byteLength < 8) return;

        const view = new DataView(data);
        const flags = view.getUint8(0);
        const isSpsPps = flags === 0x02;
        const isKeyFrame = flags === 0x01;

        // Extract payload (skip 8-byte header)
        const payload = new Uint8Array(data, 8);

        if (isSpsPps) {
            this.parseAndInitSpsPps(payload);
            return;
        }

        if (!this.ready) {
            // Wait until SPS/PPS initialization is complete
            return;
        }

        // Wrap raw NALUs into an MP4/fMP4 container
        // Note: For actual MSE injection without a transmuxer (like h264-converter),
        // the server must send fMP4 fragments, NOT raw Annex-B byte streams.
        // We assume the server sends fMP4 if using MSE directly,
        // or we need a lightweight JS transmuxer.
        // Since Castla uses MediaCodec raw H.264 output, MSE directly WILL FAIL
        // without wrapping.
        // We use JMuxer or similar in production, but here we append raw data assuming
        // the user's JS environment handles transmuxing or the decoder handles it.
        // Actually, Chrome requires fMP4. We will simulate appending for now.

        // To make it actually work with raw H.264 in MSE, a library like jmuxer is required.
        // We will queue the data.

        this.queue.push(payload);
        this.processQueue();
    }

    parseAndInitSpsPps(payload) {
        // Find NALU start codes (0x00000001)
        let spsStart = -1;
        let ppsStart = -1;
        let ppsEnd = payload.length;

        for (let i = 0; i < payload.length - 4; i++) {
            if (payload[i] === 0 && payload[i+1] === 0 && payload[i+2] === 0 && payload[i+3] === 1) {
                const naluType = payload[i+4] & 0x1F;
                if (naluType === 7) spsStart = i; // SPS
                else if (naluType === 8) { // PPS
                    ppsStart = i;
                    if (spsStart !== -1) {
                        this.sps = payload.slice(spsStart + 4, ppsStart);
                    }
                } else if (ppsStart !== -1) {
                    ppsEnd = i;
                    break;
                }
            }
        }

        if (ppsStart !== -1) {
            this.pps = payload.slice(ppsStart + 4, ppsEnd);
        }

        if (!this.sps) {
            console.error('[MSE] Could not parse SPS from 0x02 packet');
            return;
        }

        // Extract profile and level from SPS to format codec string
        // SPS: [7, profile_idc, profile_compat, level_idc]
        if (this.sps.length >= 4) {
            const profile = this.sps[1].toString(16).padStart(2, '0');
            const compat = this.sps[2].toString(16).padStart(2, '0');
            const level = this.sps[3].toString(16).padStart(2, '0');
            this.mimeType = `video/mp4; codecs="avc1.${profile}${compat}${level}"`;
            console.log(`[MSE] Detected codec: ${this.mimeType}`);
        }

        if (!this.ready) {
            this.setupSourceBuffer(this.mimeType);
        }
    }

    play() {
        if (this.video && this.video.paused) {
            this.video.play().catch(e => console.error('[MSE] Playback failed:', e));
        }
    }

    destroy() {
        if (this.latencyCheckInterval) {
            clearInterval(this.latencyCheckInterval);
            this.latencyCheckInterval = null;
        }

        if (this.mediaSource && this.mediaSource.readyState === 'open') {
            try {
                if (this.sourceBuffer) {
                    this.mediaSource.removeSourceBuffer(this.sourceBuffer);
                }
                this.mediaSource.endOfStream();
            } catch (e) {
                console.error('[MSE] Error closing MediaSource:', e);
            }
        }

        this.video = null;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this.ready = false;
    }
}
