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
        this._pointerIdMap = new Map(); // browser pointerId -> android pointerId (0-31)
        this._nextPointerId = 0;

        // Last tap position (viewport coords) — used by Bubble Composer
        this.lastTap = null;

        // Pre-allocate binary buffer for touch events (reused)
        this._buf = new ArrayBuffer(11);
        this._view = new DataView(this._buf);

        this.bindEvents();
    }

    bindEvents() {
        // Store bound handlers so we can remove them in destroy()
        this._handlers = [];
        const addEvent = (target, type, fn, opts) => {
            target.addEventListener(type, fn, opts);
            this._handlers.push({ target, type, fn, opts });
        };

        // Prefer Pointer Events (better coalescing, pressure support)
        if (window.PointerEvent) {
            addEvent(this.canvas, 'pointerdown', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_DOWN);
            });
            addEvent(this.canvas, 'pointermove', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_MOVE);
            });
            addEvent(this.canvas, 'pointerup', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_UP);
            });
            addEvent(this.canvas, 'pointercancel', (e) => {
                e.preventDefault();
                this._onPointer(e, TouchHandler.ACTION_UP);
            });
            // Prevent default touch behavior (scrolling, zooming)
            this.canvas.style.touchAction = 'none';
        } else {
            // Fallback: Touch events
            addEvent(this.canvas, 'touchstart', (e) => this._onTouch(e, TouchHandler.ACTION_DOWN), { passive: false });
            addEvent(this.canvas, 'touchmove', (e) => this._onTouch(e, TouchHandler.ACTION_MOVE), { passive: false });
            addEvent(this.canvas, 'touchend', (e) => this._onTouch(e, TouchHandler.ACTION_UP), { passive: false });
            addEvent(this.canvas, 'touchcancel', (e) => this._onTouch(e, TouchHandler.ACTION_UP), { passive: false });
        }

        // Mouse fallback for desktop testing
        if (!window.PointerEvent) {
            addEvent(this.canvas, 'mousedown', (e) => {
                this.mouseDown = true;
                this._sendMouse(e, TouchHandler.ACTION_DOWN);
            });
            addEvent(this.canvas, 'mousemove', (e) => {
                if (this.mouseDown) this._sendMouse(e, TouchHandler.ACTION_MOVE);
            });
            addEvent(this.canvas, 'mouseup', (e) => {
                this.mouseDown = false;
                this._sendMouse(e, TouchHandler.ACTION_UP);
            });
        }
    }

    /** Schedule a single rAF flush — only runs when there are pending moves */
    _scheduleFlush() {
        if (this.rafId !== null) return; // already scheduled
        this.rafId = requestAnimationFrame(() => {
            this.rafId = null;
            for (const [id, data] of this.pendingMoves) {
                this._sendBinary(data.action, id, data.x, data.y);
            }
            this.pendingMoves.clear();
        });
    }

    _mapPointerId(browserId, actionCode) {
        if (actionCode === TouchHandler.ACTION_DOWN) {
            if (!this._pointerIdMap.has(browserId)) {
                this._pointerIdMap.set(browserId, this._nextPointerId % 32);
                this._nextPointerId++;
            }
        }
        const mapped = this._pointerIdMap.get(browserId) ?? 0;
        if (actionCode === TouchHandler.ACTION_UP) {
            this._pointerIdMap.delete(browserId);
        }
        return mapped;
    }

    _onPointer(event, actionCode) {
        const rect = this.canvas.getBoundingClientRect();
        const coords = this._toNormalized(event.clientX, event.clientY, rect);
        const pid = this._mapPointerId(event.pointerId, actionCode);

        if (actionCode === TouchHandler.ACTION_UP && coords.inBounds) {
            this.lastTap = { clientX: event.clientX, clientY: event.clientY, ts: Date.now() };
        }

        if (actionCode === TouchHandler.ACTION_MOVE) {
            if (coords.inBounds) {
                this.pendingMoves.set(pid, {
                    action: actionCode, x: coords.x, y: coords.y
                });
                this._scheduleFlush();
            }
        } else {
            if (coords.inBounds || actionCode === TouchHandler.ACTION_UP) {
                this._sendBinary(actionCode, pid, coords.x, coords.y);
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

            const tid = this._mapPointerId(touch.identifier, actionCode);
            if (actionCode === TouchHandler.ACTION_MOVE) {
                if (coords.inBounds) {
                    this.pendingMoves.set(tid, {
                        action: actionCode, x: coords.x, y: coords.y
                    });
                    this._scheduleFlush();
                }
            } else if (coords.inBounds || actionCode === TouchHandler.ACTION_UP) {
                this._sendBinary(actionCode, tid, coords.x, coords.y);
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
                this._scheduleFlush();
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
        if (action !== 2) console.log(`[Touch] ${this.pane} action=${action} id=${id} x=${x.toFixed(3)} y=${y.toFixed(3)}`);
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
        // Remove all event listeners
        if (this._handlers) {
            for (const h of this._handlers) {
                h.target.removeEventListener(h.type, h.fn, h.opts);
            }
            this._handlers = [];
        }
        this._pointerIdMap.clear();
        this._nextPointerId = 0;
    }
}
