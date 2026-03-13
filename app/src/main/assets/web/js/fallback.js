/**
 * MSE Fallback Decoder
 * Uses Media Source Extensions for browsers without WebCodecs
 * Higher latency (~100-300ms) but wider compatibility
 */
class FallbackDecoder {
    constructor(onReady, onError) {
        this.onReady = onReady;
        this.onError = onError;
        this.video = null;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this.isAppending = false;
    }

    static isSupported() {
        return typeof MediaSource !== 'undefined' &&
            MediaSource.isTypeSupported('video/mp4; codecs="avc1.42001e"');
    }

    async init(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');

        // Create hidden video element for MSE playback
        this.video = document.createElement('video');
        this.video.muted = true;
        this.video.autoplay = true;
        this.video.playsInline = true;
        this.video.style.display = 'none';
        document.body.appendChild(this.video);

        this.mediaSource = new MediaSource();
        this.video.src = URL.createObjectURL(this.mediaSource);

        return new Promise((resolve, reject) => {
            this.mediaSource.addEventListener('sourceopen', () => {
                try {
                    this.sourceBuffer = this.mediaSource.addSourceBuffer('video/mp4; codecs="avc1.42001e"');
                    this.sourceBuffer.mode = 'sequence';
                    this.sourceBuffer.addEventListener('updateend', () => this.processQueue());
                    console.log('[Fallback] MSE initialized');
                    resolve();
                } catch (e) {
                    reject(e);
                }
            });
            this.mediaSource.addEventListener('error', reject);
        });
    }

    /**
     * Feed raw H.264 data (with our 1-byte header stripped)
     * NOTE: MSE requires fMP4 segments, not raw NAL units.
     * A proper implementation would use jMuxer to mux NAL units into fMP4.
     * This is a placeholder for future jMuxer integration.
     */
    decode(data) {
        // Strip our 1-byte header
        const nalData = data.slice(1);
        this.queue.push(nalData);
        this.processQueue();
    }

    processQueue() {
        if (this.isAppending || this.queue.length === 0) return;
        if (!this.sourceBuffer || this.sourceBuffer.updating) return;

        this.isAppending = true;
        const data = this.queue.shift();

        try {
            this.sourceBuffer.appendBuffer(data);
        } catch (e) {
            console.error('[Fallback] Append error:', e);
            this.isAppending = false;
        }

        this.sourceBuffer.addEventListener('updateend', () => {
            this.isAppending = false;
            // Draw video frame to canvas
            if (this.video.videoWidth > 0) {
                this.canvas.width = this.video.videoWidth;
                this.canvas.height = this.video.videoHeight;
                this.ctx.drawImage(this.video, 0, 0);
            }
            this.processQueue();
        }, { once: true });
    }

    destroy() {
        if (this.video) {
            this.video.pause();
            this.video.remove();
        }
        if (this.mediaSource && this.mediaSource.readyState === 'open') {
            try { this.mediaSource.endOfStream(); } catch (_) {}
        }
    }
}
