/**
 * Canvas Renderer
 * Renders VideoFrame objects to a canvas with proper aspect ratio
 */
class CanvasRenderer {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
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

    /**
     * Render a VideoFrame to the canvas
     * @param {VideoFrame} frame
     */
    render(frame) {
        if (frame.displayWidth !== this.videoWidth || frame.displayHeight !== this.videoHeight) {
            this.videoWidth = frame.displayWidth;
            this.videoHeight = frame.displayHeight;
            this.updateLayout();
        }

        this.ctx.drawImage(frame, this.renderX, this.renderY, this.renderWidth, this.renderHeight);
        frame.close(); // CRITICAL: release GPU memory

        this.frameCount++;
        this.updateFps();
    }

    updateLayout() {
        // Match canvas to container size
        const container = this.canvas.parentElement;
        const containerWidth = container.clientWidth;
        const containerHeight = container.clientHeight;

        this.canvas.width = containerWidth;
        this.canvas.height = containerHeight;

        // Calculate aspect-ratio-correct rendering area (letterbox/pillarbox)
        const videoAspect = this.videoWidth / this.videoHeight;
        const containerAspect = containerWidth / containerHeight;

        if (videoAspect > containerAspect) {
            // Video is wider — pillarbox (black bars top/bottom)
            this.renderWidth = containerWidth;
            this.renderHeight = containerWidth / videoAspect;
            this.renderX = 0;
            this.renderY = (containerHeight - this.renderHeight) / 2;
        } else {
            // Video is taller — letterbox (black bars left/right)
            this.renderHeight = containerHeight;
            this.renderWidth = containerHeight * videoAspect;
            this.renderX = (containerWidth - this.renderWidth) / 2;
            this.renderY = 0;
        }

        // Clear canvas (for letterbox/pillarbox bars)
        this.ctx.fillStyle = '#000';
        this.ctx.fillRect(0, 0, containerWidth, containerHeight);
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
