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
    let audioSocket = null;
    let decoder = null;
    let renderer = null;
    let touchHandler = null;
    let audioPlayer = null;
    let reconnectTimer = null;
    let statsTimer = null;
    let overlayTimer = null;
    let pingTimer = null;
    let viewportTimer = null;
    let codecMode = 'h264'; // 'h264' (WebCodecs), 'mse' (MSE fMP4), 'mjpeg'
    let lastViewport = { width: 0, height: 0 };
    let awaitingNewDecoder = false;
    let controlReady = false;
    let firstFrameReceived = false;

    const host = window.location.host;

    function setStatus(text, className) {
        statusEl.textContent = text;
        statusEl.className = className || '';
    }

    function checkReady() {
        if (controlReady && firstFrameReceived) {
            // Everything ready — show the video, hide loading
            overlay.classList.add('hidden');
            const videoEl = document.getElementById('mse-video');
            if (videoEl) videoEl.style.opacity = '1';
            canvas.style.opacity = '1';
        }
    }

    function showOverlay() {
        overlay.classList.remove('hidden');
        clearTimeout(overlayTimer);
        overlayTimer = setTimeout(() => overlay.classList.add('hidden'), 3000);
    }

    async function initDecoder() {
        const videoEl = document.getElementById('mse-video');

        if (H264Decoder.isSupported()) {
            // Best: WebCodecs — lowest latency, direct Canvas rendering
            console.log('[Main] Using WebCodecs H.264 decoder');
            renderer = new CanvasRenderer(canvas);
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
            codecMode = 'h264';
        } else if (typeof MseDecoder !== 'undefined' && MseDecoder.isSupported()) {
            // Good: MSE — browser HW H.264 decode, works over HTTP, same server stream
            console.log('[Main] Using MSE H.264 decoder (no WebCodecs)');
            canvas.style.display = 'none';
            videoEl.style.display = 'block';
            decoder = new MseDecoder(
                videoEl,
                (error) => {
                    console.error('[Main] MSE error:', error);
                    if (videoSocket && videoSocket.readyState === WebSocket.OPEN) {
                        videoSocket.send('requestKeyframe');
                    }
                }
            );
            await decoder.init();
            codecMode = 'mse';
        } else if (typeof FallbackDecoder !== 'undefined' && FallbackDecoder.isSupported()) {
            // Fallback: MJPEG — requires server-side re-encoding, high bandwidth
            console.log('[Main] Using MJPEG fallback');
            decoder = new FallbackDecoder(
                null,
                (error) => console.error('[Main] Fallback error:', error)
            );
            await decoder.init(canvas);
            codecMode = 'mjpeg';
        } else {
            throw new Error(
                'No supported decoder available. ' +
                'This browser requires WebCodecs (Chromium 94+), MSE, or createImageBitmap.'
            );
        }
    }

    function connectVideo() {
        const wsUrl = `ws://${host}/ws/video`;
        console.log('[Main] Connecting video:', wsUrl);
        setStatus('Connecting...', '');

        videoSocket = new WebSocket(wsUrl);
        videoSocket.binaryType = 'arraybuffer';

        videoSocket.onopen = () => {
            console.log('[Main] Video connected');
            setStatus('Loading...', '');
            if (kbBtn) kbBtn.style.display = 'block';
        };

        videoSocket.onmessage = async (event) => {
            if (event.data instanceof ArrayBuffer) {
                if (!decoder) {
                    console.warn('[Main] Frame arrived but no decoder');
                    return;
                }
                const v = new Uint8Array(event.data);
                if (v.length > 0 && v[0] === 0x01 && !firstFrameReceived) {
                    console.log('[Main] First keyframe received, size=' + v.length);
                    firstFrameReceived = true;
                    checkReady();
                }
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
        const wsUrl = `ws://${host}/ws/control`;
        controlSocket = new WebSocket(wsUrl);

        controlSocket.onopen = () => {
            console.log('[Main] Control connected');
            controlReady = true;
            checkReady();
            if (codecMode === 'mjpeg') {
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
                console.log('[Main] Requested MJPEG mode from server');
            }

            // Send viewport dimensions to server
            sendViewport();

            const touchTarget = codecMode === 'mse'
                ? document.getElementById('mse-video') : canvas;
            touchHandler = new TouchHandler(touchTarget, renderer, controlSocket);

            // Keepalive ping every 15s to prevent idle timeout
            clearInterval(pingTimer);
            pingTimer = setInterval(() => {
                if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                    controlSocket.send(JSON.stringify({ type: 'ping' }));
                }
            }, 15000);
        };

        controlSocket.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'resolutionChanged') {
                    console.log('[Main] Server resolution changed:', msg.width, 'x', msg.height);
                    // Don't destroy decoder here — MSE decoder handles resolution change
                    // internally by detecting SPS change in the video stream.
                    // Just request a keyframe so the decoder gets new SPS/PPS quickly.
                    if (videoSocket && videoSocket.readyState === WebSocket.OPEN) {
                        videoSocket.send('requestKeyframe');
                    }
                }
            } catch (e) {
                // Ignore non-JSON messages
            }
        };

        controlSocket.onclose = () => {
            console.log('[Main] Control disconnected');
        };
    }

    function connectAudio() {
        if (!AudioPlayer.isSupported()) {
            console.log('[Main] Web Audio not supported, skipping audio');
            return;
        }

        audioPlayer = new AudioPlayer();
        if (!audioPlayer.init()) return;

        const wsUrl = `ws://${host}/ws/audio`;
        audioSocket = new WebSocket(wsUrl);
        audioSocket.binaryType = 'arraybuffer';

        audioSocket.onopen = () => {
            console.log('[Main] Audio connected');
            // Resume AudioContext on first user interaction (browser autoplay policy)
            const resumeAudio = () => {
                audioPlayer.resume();
                document.removeEventListener('touchstart', resumeAudio);
                document.removeEventListener('click', resumeAudio);
            };
            document.addEventListener('touchstart', resumeAudio, { once: true });
            document.addEventListener('click', resumeAudio, { once: true });
        };

        audioSocket.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer && audioPlayer) {
                audioPlayer.feed(new Uint8Array(event.data));
            }
        };

        audioSocket.onclose = () => {
            console.log('[Main] Audio disconnected');
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
        connectAudio();
    }

    function cleanup() {
        clearInterval(pingTimer);
        clearTimeout(viewportTimer);
        awaitingNewDecoder = false;
        controlReady = false;
        firstFrameReceived = false;
        lastViewport = { width: 0, height: 0 };
        if (videoSocket) {
            videoSocket.onclose = null; // prevent reconnect loop
            videoSocket.close();
        }
        if (controlSocket) {
            controlSocket.close();
        }
        if (audioSocket) {
            audioSocket.close();
        }
        if (audioPlayer) {
            audioPlayer.stop();
            audioPlayer = null;
        }
    }

    function startStats() {
        statsTimer = setInterval(() => {
            let fps = 0;
            if (codecMode === 'h264' && renderer) {
                fps = renderer.getFps();
            } else if (decoder && typeof decoder.getFps === 'function') {
                fps = decoder.getFps();
            }
            const modeLabel = { h264: 'H.264', mse: 'MSE H.264', mjpeg: 'MJPEG' }[codecMode] || codecMode;
            statsEl.textContent = `${fps} FPS (${modeLabel})`;
        }, 1000);
    }

    function sendViewport() {
        const w = window.innerWidth;
        const h = window.innerHeight;
        if (w === lastViewport.width && h === lastViewport.height) return;
        lastViewport = { width: w, height: h };
        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(JSON.stringify({ type: 'viewport', width: w, height: h }));
            console.log('[Main] Sent viewport:', w, 'x', h);
        }
    }

    // Handle window resize — update renderer layout + send viewport (debounced 500ms)
    window.addEventListener('resize', () => {
        if (renderer && renderer.videoWidth > 0) {
            renderer.updateLayout();
        }
        clearTimeout(viewportTimer);
        viewportTimer = setTimeout(() => sendViewport(), 500);
    });

    // Handle visibility change — request keyframe when tab becomes visible
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && videoSocket && videoSocket.readyState === WebSocket.OPEN) {
            videoSocket.send('requestKeyframe');
        }
    });

    // Touch on canvas shows overlay briefly
    canvas.addEventListener('touchstart', () => showOverlay(), { passive: true });

    // --- Tesla native keyboard integration ---
    const kbInput = document.getElementById('keyboard-input');
    const kbBtn = document.getElementById('keyboard-btn');

    if (kbBtn && kbInput) {
        kbBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            kbInput.style.pointerEvents = 'auto';
            kbInput.focus();
            // Tesla browser should pop up its native keyboard
        });

        // Capture input and send to server
        kbInput.addEventListener('input', (e) => {
            const text = e.target.value;
            if (text && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'textInput', text: text }));
                kbInput.value = ''; // clear for next input
            }
        });

        // Also handle Enter key
        kbInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'textInput', text: '\n' }));
                e.preventDefault();
            }
        });

        // Hide keyboard input pointer events when blurred
        kbInput.addEventListener('blur', () => {
            kbInput.style.pointerEvents = 'none';
        });
    }

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
