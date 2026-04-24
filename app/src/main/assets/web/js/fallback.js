/**
 * MJPEG Fallback Decoder
 * For browsers without WebCodecs — decodes individual JPEG frames.
 * MJPEG has higher bandwidth but works universally and has low latency (~30-80ms).
 *
 * When this fallback is active, the client negotiates MJPEG mode with the server
 * via the control socket. The server then sends JPEG frames instead of H.264.
 */
class FallbackDecoder {
    constructor(onFrame, onError) {
        this.onFrame = onFrame;
        this.onError = onError;
        this.canvas = null;
        this.ctx = null;
        this.renderer = null;
        this.frameCount = 0;
        this.startTime = 0;

        // Quality tracking for auto-scale decisions
        this._decoding = false;       // true while createImageBitmap is in-flight
        this._droppedFrames = 0;      // frames skipped because previous decode was still running
        this._renderedFrames = 0;     // successfully rendered frames
        this._lateFrames = 0;         // frames where decode latency exceeded threshold
        this._totalDecodeLatency = 0; // sum of decode times (ms) for averaging
        this._metricsStartTime = 0;
    }

    static isSupported() {
        return typeof createImageBitmap === 'function';
    }

    async init(canvas) {
        this.canvas = canvas;
        this.renderer = new CanvasRenderer(canvas);
        this.ctx = canvas.getContext('2d');
        this.startTime = performance.now();
        this._metricsStartTime = performance.now();
        console.log('[Fallback] MJPEG decoder initialized');
    }

    /**
     * Decode a frame received from WebSocket.
     * Expects: 8-byte header + JPEG image data
     *
     * If a previous decode is still in-flight, the incoming frame is dropped
     * and counted as a quality signal for auto-scale.
     */
    decode(data) {
        const view = new Uint8Array(data);
        if (view.length < 9) return;
        if (view[0] === 0x02) return; // skip SPS/PPS config (not relevant for MJPEG)

        // Silently drop non-JPEG payloads. During the startup window, the server
        // may briefly emit H.264 frames before it processes the client's
        // `codec: mjpeg` control message — those frames would otherwise raise
        // `InvalidStateError` in createImageBitmap and spam the console.
        if (view[8] !== 0xFF || view[9] !== 0xD8) return;

        // Drop frame if previous decode is still running
        if (this._decoding) {
            this._droppedFrames++;
            return;
        }

        const payload = data.slice(8); // strip 8-byte header
        const decodeStart = performance.now();
        this._decoding = true;

        const blob = new Blob([payload], { type: 'image/jpeg' });
        createImageBitmap(blob).then((bitmap) => {
            this._decoding = false;
            const decodeTime = performance.now() - decodeStart;
            this._totalDecodeLatency += decodeTime;

            // Frames taking >50ms to decode are "late" — a quality signal
            if (decodeTime > 50) {
                this._lateFrames++;
            }

            if (this.canvas && this.ctx && this.renderer) {
                this.renderer.render(bitmap);
                this.frameCount++;
                this._renderedFrames++;
                if (this.onFrame) {
                    this.onFrame();
                }
            } else {
                console.error('[Fallback] canvas or ctx is null');
            }
        }).catch((err) => {
            this._decoding = false;
            console.error('[Fallback] createImageBitmap error:', err);
            if (this.onError) {
                this.onError(err);
            }
        });
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

    /**
     * Backlog-compatible metrics — same shape as H264Decoder.getBacklogMetrics().
     * MJPEG has no hardware decode queue, so decodeQueueSize is always 0.
     * backlogDrops uses _lateFrames (>50ms decode) rather than _droppedFrames
     * to avoid double-counting with droppedFrames in quality reports.
     */
    getBacklogMetrics() {
        return {
            backlogHits: this._lateFrames,
            backlogDrops: this._lateFrames,
            decodeQueueSize: 0
        };
    }

    /**
     * Pacer-compatible metrics — same shape as FramePacer.getMetrics().
     * Used by the quality report when FramePacer is not present (MJPEG path).
     */
    getMetrics() {
        const avgLatency = this._renderedFrames > 0
            ? parseFloat((this._totalDecodeLatency / this._renderedFrames).toFixed(1))
            : 0;
        return {
            profile: 'n/a',
            droppedFrames: this._droppedFrames,
            renderedFrames: this._renderedFrames,
            bufferDepth: 0,
            avgRenderDelayMs: avgLatency,
            totalLatency: this._totalDecodeLatency
        };
    }

    resetMetrics() {
        this._droppedFrames = 0;
        this._renderedFrames = 0;
        this._lateFrames = 0;
        this._totalDecodeLatency = 0;
        this._metricsStartTime = performance.now();
    }

    destroy() {
        this.renderer?.destroy?.();
        this.renderer = null;
        this.canvas = null;
        this.ctx = null;
    }
}
