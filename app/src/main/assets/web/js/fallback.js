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
        this.frameCount = 0;
        this.startTime = 0;
    }

    static isSupported() {
        return typeof createImageBitmap === 'function';
    }

    async init(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.startTime = performance.now();
        console.log('[Fallback] MJPEG decoder initialized');
    }

    /**
     * Decode a frame received from WebSocket.
     * Expects: 1-byte header + JPEG image data
     */
    decode(data) {
        const payload = data.slice(1); // strip 1-byte header

        const blob = new Blob([payload], { type: 'image/jpeg' });
        createImageBitmap(blob).then((bitmap) => {
            if (this.canvas && this.ctx) {
                if (this.canvas.width !== bitmap.width || this.canvas.height !== bitmap.height) {
                    this.canvas.width = bitmap.width;
                    this.canvas.height = bitmap.height;
                }
                this.ctx.drawImage(bitmap, 0, 0);
                bitmap.close();
                this.frameCount++;
            }
        }).catch((_) => {
            // Silent — expected when receiving non-JPEG data during mode negotiation
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
        this.canvas = null;
        this.ctx = null;
    }
}
