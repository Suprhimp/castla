/**
 * Touch Event Handler
 * Captures touch events on the canvas and sends them via WebSocket
 */
class TouchHandler {
    constructor(canvas, renderer, controlSocket) {
        this.canvas = canvas;
        this.renderer = renderer;
        this.controlSocket = controlSocket;
        this.lastMoveTime = 0;
        this.minMoveInterval = 1000 / 60; // max 60 events/sec

        this.bindEvents();
    }

    bindEvents() {
        this.canvas.addEventListener('touchstart', (e) => this.onTouch(e, 'down'), { passive: false });
        this.canvas.addEventListener('touchmove', (e) => this.onTouch(e, 'move'), { passive: false });
        this.canvas.addEventListener('touchend', (e) => this.onTouch(e, 'up'), { passive: false });
        this.canvas.addEventListener('touchcancel', (e) => this.onTouch(e, 'up'), { passive: false });

        // Mouse fallback for desktop testing
        this.mouseDown = false;
        this.canvas.addEventListener('mousedown', (e) => {
            this.mouseDown = true;
            this.sendMouseEvent(e, 'down');
        });
        this.canvas.addEventListener('mousemove', (e) => {
            if (this.mouseDown) this.sendMouseEvent(e, 'move');
        });
        this.canvas.addEventListener('mouseup', (e) => {
            this.mouseDown = false;
            this.sendMouseEvent(e, 'up');
        });
    }

    onTouch(event, action) {
        event.preventDefault();

        // Throttle move events
        if (action === 'move') {
            const now = performance.now();
            if (now - this.lastMoveTime < this.minMoveInterval) return;
            this.lastMoveTime = now;
        }

        const rect = this.canvas.getBoundingClientRect();

        for (let i = 0; i < event.changedTouches.length; i++) {
            const touch = event.changedTouches[i];
            const coords = this._toNormalized(touch.clientX, touch.clientY, rect);

            if (coords.inBounds || action === 'up') {
                this.send({
                    type: 'touch',
                    action: action,
                    x: coords.x,
                    y: coords.y,
                    id: touch.identifier
                });
            }
        }
    }

    sendMouseEvent(event, action) {
        const rect = this.canvas.getBoundingClientRect();
        const coords = this._toNormalized(event.clientX, event.clientY, rect);

        if (coords.inBounds || action === 'up') {
            this.send({
                type: 'touch',
                action: action,
                x: coords.x,
                y: coords.y,
                id: 0
            });
        }
    }

    /** Convert client coordinates to normalized 0-1 video coordinates */
    _toNormalized(clientX, clientY, rect) {
        if (this.renderer && typeof this.renderer.canvasToVideo === 'function') {
            return this.renderer.canvasToVideo(clientX - rect.left, clientY - rect.top);
        }
        // Direct element mapping (for video element / MSE mode)
        const x = (clientX - rect.left) / rect.width;
        const y = (clientY - rect.top) / rect.height;
        return {
            x: Math.max(0, Math.min(1, x)),
            y: Math.max(0, Math.min(1, y)),
            inBounds: x >= 0 && x <= 1 && y >= 0 && y <= 1
        };
    }

    send(event) {
        if (this.controlSocket && this.controlSocket.readyState === WebSocket.OPEN) {
            this.controlSocket.send(JSON.stringify(event));
        }
    }

    destroy() {
        // Events will be GC'd with the canvas
    }
}
