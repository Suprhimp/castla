/**
 * Frame Pacer — sits between decoder output and renderer.
 * Buffers decoded VideoFrames for a profile-dependent duration,
 * then releases them at a steady cadence via requestAnimationFrame.
 *
 * Profiles:
 *   low_latency : ~30 ms target buffer
 *   balanced    : ~80 ms target buffer  (default)
 *   smooth      : ~130 ms target buffer
 */
class FramePacer {

    static PROFILES = {
        low_latency: { targetBufferMs: 30,  maxBufferMs: 80,   label: 'Low Latency' },
        balanced:    { targetBufferMs: 80,  maxBufferMs: 200,  label: 'Balanced' },
        smooth:      { targetBufferMs: 130, maxBufferMs: 300,  label: 'Smooth' }
    };

    static DEFAULT_PROFILE = 'balanced';

    constructor(renderFn) {
        this._renderFn = renderFn;        // (VideoFrame) => void
        this._buffer = [];                 // { frame, receiveTime }
        this._profile = FramePacer.DEFAULT_PROFILE;
        this._running = false;
        this._rafId = null;
        this._lastRenderTime = 0;
        this._decoder = null;              // optional H264Decoder ref for unified metrics

        // Metrics
        this._droppedFrames = 0;
        this._renderedFrames = 0;
        this._totalLatency = 0;            // sum of render delays for averaging
        this._metricsStartTime = performance.now();
        this._lastMetricsLog = 0;
    }

    get profile() { return this._profile; }
    get profileConfig() { return FramePacer.PROFILES[this._profile]; }

    setProfile(name) {
        if (!FramePacer.PROFILES[name]) return;
        this._profile = name;
        console.log(`[FramePacer] Profile set to: ${name} (target ${FramePacer.PROFILES[name].targetBufferMs}ms)`);
    }

    /**
     * Attach a decoder reference for unified metrics logging.
     * The decoder's backlog metrics will be included in periodic logs.
     */
    setDecoder(decoder) {
        this._decoder = decoder;
    }

    /**
     * Enqueue a decoded VideoFrame.
     */
    push(frame) {
        const now = performance.now();
        this._buffer.push({ frame, receiveTime: now });

        // Eagerly drop frames older than maxBuffer on ingest.
        // This prevents unbounded growth even if the rAF loop is stalled.
        const { maxBufferMs } = this.profileConfig;
        while (this._buffer.length > 1) {
            const age = now - this._buffer[0].receiveTime;
            if (age > maxBufferMs) {
                const stale = this._buffer.shift();
                this._closeFrame(stale.frame);
                this._droppedFrames++;
            } else {
                break;
            }
        }

        if (!this._running) this._startLoop();
    }

    /**
     * Flush buffer — used on seek / stream reset.
     */
    flush() {
        for (const entry of this._buffer) {
            this._closeFrame(entry.frame);
        }
        this._buffer.length = 0;
    }

    destroy() {
        this._running = false;
        if (this._rafId) {
            cancelAnimationFrame(this._rafId);
            this._rafId = null;
        }
        this.flush();
    }

    // ── Metrics ──

    getMetrics() {
        const elapsed = (performance.now() - this._metricsStartTime) / 1000;
        const avgLatency = this._renderedFrames > 0
            ? (this._totalLatency / this._renderedFrames).toFixed(1)
            : 0;
        return {
            profile: this._profile,
            droppedFrames: this._droppedFrames,
            renderedFrames: this._renderedFrames,
            bufferDepth: this._buffer.length,
            avgRenderDelayMs: parseFloat(avgLatency),
            totalLatency: this._totalLatency,
            elapsedSec: Math.round(elapsed)
        };
    }

    resetMetrics() {
        this._droppedFrames = 0;
        this._renderedFrames = 0;
        this._totalLatency = 0;
        this._metricsStartTime = performance.now();
    }

    // ── Internal ──

    _startLoop() {
        if (this._running) return;
        this._running = true;
        this._tick();
    }

    _tick() {
        if (!this._running) return;

        this._rafId = requestAnimationFrame(() => {
            this._processBuffer();
            this._tick();
        });
    }

    _processBuffer() {
        if (this._buffer.length === 0) {
            this._running = false;
            if (this._rafId) {
                cancelAnimationFrame(this._rafId);
                this._rafId = null;
            }
            return;
        }

        const now = performance.now();
        const { targetBufferMs, maxBufferMs } = this.profileConfig;

        // ── Phase 1: Age-based stale drop ──
        // Drop any frames older than maxBuffer, keeping at least the newest one.
        // This is the primary overflow protection — time, not count.
        while (this._buffer.length > 1) {
            const age = now - this._buffer[0].receiveTime;
            if (age > maxBufferMs) {
                const stale = this._buffer.shift();
                this._closeFrame(stale.frame);
                this._droppedFrames++;
            } else {
                break;
            }
        }

        // ── Phase 2: Readiness check ──
        // Only render when the oldest frame has been buffered ≥ targetBuffer,
        // OR the buffer has accumulated enough frames that waiting would pile up.
        const oldestAge = now - this._buffer[0].receiveTime;
        if (oldestAge < targetBufferMs && this._buffer.length < 3) {
            return; // not ready — wait for next rAF
        }

        // ── Phase 3: Render one frame ──
        const entry = this._buffer.shift();
        const delay = now - entry.receiveTime;

        try {
            this._renderFn(entry.frame);
        } catch (e) {
            console.error('[FramePacer] Render error:', e);
            this._closeFrame(entry.frame);
        }

        this._renderedFrames++;
        this._totalLatency += delay;
        this._lastRenderTime = now;

        // ── Phase 4: Catch-up drop for remaining overdue frames ──
        // After rendering, if remaining frames are still older than target,
        // keep only the freshest one(s) to prevent snowballing latency.
        if (this._buffer.length > 1) {
            const nextAge = now - this._buffer[0].receiveTime;
            if (nextAge > targetBufferMs * 1.5) {
                // Multiple frames are overdue — keep only the latest
                const maxKeep = this._profile === 'smooth' ? 2 : 1;
                while (this._buffer.length > maxKeep) {
                    const stale = this._buffer.shift();
                    this._closeFrame(stale.frame);
                    this._droppedFrames++;
                }
            }
        }

        // Periodic unified metrics log (every 10 seconds)
        if (now - this._lastMetricsLog > 10000) {
            this._lastMetricsLog = now;
            const m = this.getMetrics();
            let logLine = `[Playback] ${m.profile} | pacer: rendered=${m.renderedFrames} dropped=${m.droppedFrames} buf=${m.bufferDepth} avgDelay=${m.avgRenderDelayMs}ms`;
            if (this._decoder && this._decoder.getBacklogMetrics) {
                const d = this._decoder.getBacklogMetrics();
                logLine += ` | decoder: queueSize=${d.decodeQueueSize} backlogDrops=${d.backlogDrops}`;
            }
            console.log(logLine);
        }
    }

    _closeFrame(frame) {
        if (frame && typeof frame.close === 'function') {
            try { frame.close(); } catch (_) {}
        }
    }
}
