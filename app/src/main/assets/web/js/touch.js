/**
 * Touch Event Handler
 * Captures touch/pointer events on the canvas and sends them via WebSocket
 * Uses binary protocol (11 bytes) for minimal latency and rAF-based throttling
 */
class TouchHandler {
    static ACTION_DOWN = 0;
    static ACTION_UP = 1;
    static ACTION_MOVE = 2;

    constructor(canvas, renderer, controlSocket, pane = "primary") {
        this.canvas = canvas;
        this.renderer = renderer;
        this.controlSocket = controlSocket;
        this.pane = pane;
        this.pendingMoves = new Map(); // pointerId -> {action, x, y}
        this.rafId = null;
        this.mouseDown = false;

        // Last tap position (viewport coords) — used by Bubble Composer
        this.lastTap = null;

        // Pre-allocate binary buffer for touch events (reused)
        this._buf = new ArrayBuffer(11);
        this._view = new DataView(this._buf);

        this.bindEvents();
        this.startRAFLoop();
    }

    bindEvents() {
        // Prefer Pointer Events (better coalescing, pressure support)
        if (window.PointerEvent) {
            this.canvas.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_DOWN);
            });
            this.canvas.addEventListener('pointermove', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_MOVE);
            });
            this.canvas.addEventListener('pointerup', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_UP);
            });
            this.canvas.addEventListener('pointercancel', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_UP);
            });
            // Prevent default touch behavior (scrolling, zooming)
            this.canvas.style.touchAction = 'none';
        } else {
            // Fallback: Touch events
            this.canvas.addEventListener('touchstart', (e) => this._onTouch(e, TouchHandler.ACTION_DOWN), { passive: false });
            this.canvas.addEventListener('touchmove', (e) => this._onTouch(e, TouchHandler.ACTION_MOVE), { passive: false });
            this.canvas.addEventListener('touchend', (e) => this._onTouch(e, TouchHandler.ACTION_UP), { passive: false });
            this.canvas.addEventListener('touchcancel', (e) => this._onTouch(e, TouchHandler.ACTION_UP), { passive: false });
        }

        // Mouse fallback for desktop testing
        if (!window.PointerEvent) {
            this.canvas.addEventListener('mousedown', (e) => {
                this.mouseDown = true;
                this._sendMouse(e, TouchHandler.ACTION_DOWN);
            });
            this.canvas.addEventListener('mousemove', (e) => {
                if (this.mouseDown) this._sendMouse(e, TouchHandler.ACTION_MOVE);
            });
            this.canvas.addEventListener('mouseup', (e) => {
                this.mouseDown = false;
                this._sendMouse(e, TouchHandler.ACTION_UP);
            });
        }
    }

    startRAFLoop() {
        const tick = () => {
            // Flush coalesced move events — one per pointer per frame
            for (const [id, data] of this.pendingMoves) {
                this._sendBinary(data.action, id, data.x, data.y);
            }
            this.pendingMoves.clear();
            this.rafId = requestAnimationFrame(tick);
        };
        this.rafId = requestAnimationFrame(tick);
    }

    _onPointer(event, actionCode) {
        const rect = this.canvas.getBoundingClientRect();
        const coords = this._toNormalized(event.clientX, event.clientY, rect);

        if (actionCode === TouchHandler.ACTION_UP && coords.inBounds) {
            this.lastTap = { clientX: event.clientX, clientY: event.clientY, ts: Date.now() };
        }

        if (actionCode === TouchHandler.ACTION_MOVE) {
            // Coalesce: only store latest position per pointer, sent on next rAF
            if (coords.inBounds) {
                this.pendingMoves.set(event.pointerId, {
                    action: actionCode, x: coords.x, y: coords.y
                });
            }
        } else {
            // down/up: send immediately
            if (coords.inBounds || actionCode === TouchHandler.ACTION_UP) {
                this._sendBinary(actionCode, event.pointerId, coords.x, coords.y);
            }
        }
    }

    _onTouch(event, actionCode) {
        event.preventDefault();
        const rect = this.canvas.getBoundingClientRect();

        for (let i = 0; i < event.changedTouches.length; i++) {
            const touch = event.changedTouches[i];
            const coords = this._toNormalized(touch.clientX, touch.clientY, rect);

            if (actionCode === TouchHandler.ACTION_UP && coords.inBounds) {
                this.lastTap = { clientX: touch.clientX, clientY: touch.clientY, ts: Date.now() };
            }

            if (actionCode === TouchHandler.ACTION_MOVE) {
                if (coords.inBounds) {
                    this.pendingMoves.set(touch.identifier, {
                        action: actionCode, x: coords.x, y: coords.y
                    });
                }
            } else if (coords.inBounds || actionCode === TouchHandler.ACTION_UP) {
                this._sendBinary(actionCode, touch.identifier, coords.x, coords.y);
            }
        }
    }

    _sendMouse(event, actionCode) {
        const rect = this.canvas.getBoundingClientRect();
        const coords = this._toNormalized(event.clientX, event.clientY, rect);

        if (actionCode === TouchHandler.ACTION_UP && coords.inBounds) {
            this.lastTap = { clientX: event.clientX, clientY: event.clientY, ts: Date.now() };
        }

        if (actionCode === TouchHandler.ACTION_MOVE) {
            if (coords.inBounds) {
                this.pendingMoves.set(0, { action: actionCode, x: coords.x, y: coords.y });
            }
        } else if (coords.inBounds || actionCode === TouchHandler.ACTION_UP) {
            this._sendBinary(actionCode, 0, coords.x, coords.y);
        }
    }

    /** Convert client coordinates to normalized 0-1 video coordinates */
    _toNormalized(clientX, clientY, rect) {
        if (this.renderer && typeof this.renderer.canvasToVideo === 'function') {
            return this.renderer.canvasToVideo(clientX - rect.left, clientY - rect.top);
        }

        // MSE mode: <video> uses object-fit:contain — account for letterboxing
        const el = this.canvas;
        if (el.tagName === 'VIDEO' && el.videoWidth > 0 && el.videoHeight > 0) {
            const videoAspect = el.videoWidth / el.videoHeight;
            const elemAspect = rect.width / rect.height;
            let renderX, renderY, renderW, renderH;

            if (videoAspect > elemAspect) {
                // Video wider than element — black bars top/bottom
                renderW = rect.width;
                renderH = rect.width / videoAspect;
                renderX = 0;
                renderY = (rect.height - renderH) / 2;
            } else {
                // Video taller — black bars left/right
                renderH = rect.height;
                renderW = rect.height * videoAspect;
                renderX = (rect.width - renderW) / 2;
                renderY = 0;
            }

            const x = (clientX - rect.left - renderX) / renderW;
            const y = (clientY - rect.top - renderY) / renderH;
            return {
                x: Math.max(0, Math.min(1, x)),
                y: Math.max(0, Math.min(1, y)),
                inBounds: x >= 0 && x <= 1 && y >= 0 && y <= 1
            };
        }

        // Fallback: direct element mapping
        const x = (clientX - rect.left) / rect.width;
        const y = (clientY - rect.top) / rect.height;
        return {
            x: Math.max(0, Math.min(1, x)),
            y: Math.max(0, Math.min(1, y)),
            inBounds: x >= 0 && x <= 1 && y >= 0 && y <= 1
        };
    }

    /** Send touch event as 11-byte binary: [action:u8][id:u8][x:f32LE][y:f32LE][pane:u8] */
    _sendBinary(action, id, x, y) {
        if (!this.controlSocket || this.controlSocket.readyState !== WebSocket.OPEN) {
            if (this._logCount === undefined) this._logCount = 0;
            if (this._logCount++ < 5) console.warn('[Touch] Socket not open, state:', this.controlSocket?.readyState);
            return;
        }
        this._view.setUint8(0, action);
        this._view.setUint8(1, id & 0xFF);
        this._view.setFloat32(2, x, true); // little-endian
        this._view.setFloat32(6, y, true);
        this._view.setUint8(10, this.pane === "secondary" ? 1 : 0);
        this.controlSocket.send(this._buf);
    }

    destroy() {
        if (this.rafId !== null) {
            cancelAnimationFrame(this.rafId);
            this.rafId = null;
        }
        this.pendingMoves.clear();
    }
}
