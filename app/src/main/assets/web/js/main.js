/**
 * Castla - Main Client
 * Connects to the Android phone's WebSocket server and displays the mirrored screen
 */
(function() {
    'use strict';

    const canvas = document.getElementById('display');
    const statusEl = document.getElementById('status');
    const statsEl = document.getElementById('stats');
    const overlay = document.getElementById('overlay');

    let videoSocket = null;
    let controlSocket = null;
    let decoder = null;
    let renderer = null;
    let touchHandler = null;
    let reconnectTimer = null;
    let statsTimer = null;
    let overlayTimer = null;

    const host = window.location.host;
    const token = new URLSearchParams(window.location.search).get('token') || '';

    function setStatus(text, className) {
        statusEl.textContent = text;
        statusEl.className = className || '';
    }

    function showOverlay() {
        overlay.classList.remove('hidden');
        clearTimeout(overlayTimer);
        overlayTimer = setTimeout(() => overlay.classList.add('hidden'), 3000);
    }

    async function initDecoder() {
        renderer = new CanvasRenderer(canvas);

        if (H264Decoder.isSupported()) {
            console.log('[Main] Using WebCodecs decoder');
            decoder = new H264Decoder(
                (frame) => renderer.render(frame),
                (error) => {
                    console.error('[Main] Decoder error, requesting keyframe:', error);
                    if (videoSocket && videoSocket.readyState === WebSocket.OPEN) {
                        videoSocket.send('requestKeyframe');
                    }
                }
            );
            await decoder.init();
        } else {
            // WebCodecs not available — show unsupported message
            // Tesla browser (Chromium 109) supports WebCodecs, so this should rarely trigger
            throw new Error(
                'WebCodecs API not available. ' +
                'This browser requires Chromium 94+ (Tesla browser is supported).'
            );
        }
    }

    function connectVideo() {
        const wsUrl = `ws://${host}/ws/video?token=${encodeURIComponent(token)}`;
        console.log('[Main] Connecting video:', wsUrl);
        setStatus('Connecting...', '');

        videoSocket = new WebSocket(wsUrl);
        videoSocket.binaryType = 'arraybuffer';

        videoSocket.onopen = () => {
            console.log('[Main] Video connected');
            setStatus('Connected', 'connected');
            showOverlay();
        };

        videoSocket.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                decoder.decode(event.data);
            }
        };

        videoSocket.onclose = () => {
            console.log('[Main] Video disconnected');
            setStatus('Disconnected', 'error');
            showOverlay();
            scheduleReconnect();
        };

        videoSocket.onerror = (e) => {
            console.error('[Main] Video error:', e);
            setStatus('Connection Error', 'error');
        };
    }

    function connectControl() {
        const wsUrl = `ws://${host}/ws/control?token=${encodeURIComponent(token)}`;
        controlSocket = new WebSocket(wsUrl);

        controlSocket.onopen = () => {
            console.log('[Main] Control connected');
            touchHandler = new TouchHandler(canvas, renderer, controlSocket);
        };

        controlSocket.onclose = () => {
            console.log('[Main] Control disconnected');
        };
    }

    function scheduleReconnect() {
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(() => {
            console.log('[Main] Reconnecting...');
            connect();
        }, 2000);
    }

    function connect() {
        cleanup();
        connectVideo();
        connectControl();
    }

    function cleanup() {
        if (videoSocket) {
            videoSocket.onclose = null; // prevent reconnect loop
            videoSocket.close();
        }
        if (controlSocket) {
            controlSocket.close();
        }
    }

    function startStats() {
        statsTimer = setInterval(() => {
            const fps = renderer ? renderer.getFps() : 0;
            statsEl.textContent = `${fps} FPS`;
        }, 1000);
    }

    // Handle window resize
    window.addEventListener('resize', () => {
        if (renderer && renderer.videoWidth > 0) {
            renderer.updateLayout();
        }
    });

    // Handle visibility change — request keyframe when tab becomes visible
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && videoSocket && videoSocket.readyState === WebSocket.OPEN) {
            videoSocket.send('requestKeyframe');
        }
    });

    // Touch on canvas shows overlay briefly
    canvas.addEventListener('touchstart', () => showOverlay(), { passive: true });

    // Start
    async function main() {
        try {
            await initDecoder();
            connect();
            startStats();
            console.log('[Main] Castla client started');
        } catch (e) {
            console.error('[Main] Init failed:', e);
            setStatus('Init Failed: ' + e.message, 'error');
        }
    }

    main();
})();
