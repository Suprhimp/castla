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
    }

    static isSupported() {
        return typeof createImageBitmap === 'function';
    }

    async init(canvas) {
        this.canvas = canvas;
        this.renderer = new CanvasRenderer(canvas);
        this.ctx = canvas.getContext('2d');
        this.startTime = performance.now();
        console.log('[Fallback] MJPEG decoder initialized');
    }

    /**
     * Decode a frame received from WebSocket.
     * Expects: 8-byte header + JPEG image data
     */
    decode(data) {
        const view = new Uint8Array(data);
        if (view.length < 9) return;
        if (view[0] === 0x02) return; // skip SPS/PPS config (not relevant for MJPEG)
        const payload = data.slice(8); // strip 8-byte header

        const blob = new Blob([payload], { type: 'image/jpeg' });
        createImageBitmap(blob).then((bitmap) => {
            if (this.canvas && this.ctx && this.renderer) {
                this.renderer.render(bitmap);
                this.frameCount++;
                if (this.onFrame) {
                    this.onFrame();
                }
            } else {
                console.error('[Fallback] canvas or ctx is null');
            }
        }).catch((err) => {
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

    destroy() {
        this.renderer?.destroy?.();
        this.renderer = null;
        this.canvas = null;
        this.ctx = null;
    }
}
