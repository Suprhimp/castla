/**
 * Canvas Renderer
 * Renders VideoFrame objects to a canvas with proper aspect ratio
 */
class CanvasRenderer {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.fitMode = 'contain';
        this.videoWidth = 0;
        this.videoHeight = 0;
        this.renderX = 0;
        this.renderY = 0;
        this.renderWidth = 0;
        this.renderHeight = 0;
        this.frameCount = 0;
        this.lastFpsTime = performance.now();
        this.currentFps = 0;
    }

    setFitMode(mode) {
        const nextMode = mode === 'cover' ? 'cover' : 'contain';
        if (this.fitMode === nextMode) return;
        this.fitMode = nextMode;
        if (this.videoWidth > 0 && this.videoHeight > 0) {
            this.updateLayout();
        }
    }

    /**
     * Render a VideoFrame to the canvas
     * @param {VideoFrame} frame
     */
    render(frame) {
        const sourceWidth = frame.displayWidth || frame.width;
        const sourceHeight = frame.displayHeight || frame.height;

        if (sourceWidth !== this.videoWidth || sourceHeight !== this.videoHeight) {
            this.videoWidth = sourceWidth;
            this.videoHeight = sourceHeight;
            this.updateLayout();
        } else if (this.canvas.width !== this.canvas.clientWidth || this.canvas.height !== this.canvas.clientHeight) {
            this.updateLayout();
        }

        this.ctx.drawImage(frame, this.renderX, this.renderY, this.renderWidth, this.renderHeight);
        if (typeof frame.close === 'function') {
            frame.close();
        }

        this.frameCount++;
        this.updateFps();
    }

    updateLayout() {
        // Use the canvas element's own CSS size, not the parent container.
        // When the ad banner is visible, the canvas is shorter than the container
        // due to flex layout — using container size would cause touch Y offset.
        const canvasWidth = this.canvas.clientWidth;
        const canvasHeight = this.canvas.clientHeight;

        this.canvas.width = canvasWidth;
        this.canvas.height = canvasHeight;

        // Calculate aspect-ratio-correct rendering area (letterbox/pillarbox)
        const videoAspect = this.videoWidth / this.videoHeight;
        const canvasAspect = canvasWidth / canvasHeight;

        if (this.fitMode === 'cover') {
            if (videoAspect > canvasAspect) {
                this.renderHeight = canvasHeight;
                this.renderWidth = canvasHeight * videoAspect;
                this.renderX = (canvasWidth - this.renderWidth) / 2;
                this.renderY = 0;
            } else {
                this.renderWidth = canvasWidth;
                this.renderHeight = canvasWidth / videoAspect;
                this.renderX = 0;
                this.renderY = (canvasHeight - this.renderHeight) / 2;
            }
        } else if (videoAspect > canvasAspect) {
            // Video is wider — letterbox (black bars top/bottom)
            this.renderWidth = canvasWidth;
            this.renderHeight = canvasWidth / videoAspect;
            this.renderX = 0;
            this.renderY = (canvasHeight - this.renderHeight) / 2;
        } else {
            // Video is taller — pillarbox (black bars left/right)
            this.renderHeight = canvasHeight;
            this.renderWidth = canvasHeight * videoAspect;
            this.renderX = (canvasWidth - this.renderWidth) / 2;
            this.renderY = 0;
        }

        // Clear canvas (for letterbox/pillarbox bars)
        this.ctx.fillStyle = '#000';
        this.ctx.fillRect(0, 0, canvasWidth, canvasHeight);
    }

    /**
     * Convert canvas coordinates to normalized video coordinates (0-1)
     */
    canvasToVideo(canvasX, canvasY) {
        const x = (canvasX - this.renderX) / this.renderWidth;
        const y = (canvasY - this.renderY) / this.renderHeight;
        return {
            x: Math.max(0, Math.min(1, x)),
            y: Math.max(0, Math.min(1, y)),
            inBounds: x >= 0 && x <= 1 && y >= 0 && y <= 1
        };
    }

    updateFps() {
        const now = performance.now();
        if (now - this.lastFpsTime >= 1000) {
            this.currentFps = this.frameCount;
            this.frameCount = 0;
            this.lastFpsTime = now;
        }
    }

    getFps() {
        return this.currentFps;
    }

    destroy() {
        this.ctx = null;
    }
}
