const host = window.location.host;

let videoSocket = null;
let controlSocket = null;
let audioPlayer = null;
let touchHandler = null;

let decoder = null;
let isLauncherMode = true; // start in launcher mode
let codecMode = 'h264'; // Default to h264, switch to mjpeg if needed
let streamPolicy = {
    isPremium: false,
    fitMode: 'contain',
    autoFit: false,
    layoutMode: 'single',
    showAdBanner: false
};

document.addEventListener('DOMContentLoaded', async () => {
    console.log('[Main] DOM Loaded, initializing components...');

    const webLauncher = document.getElementById('web-launcher');
    const homeBtn = document.getElementById('home-btn');
    const overlay = document.getElementById('overlay');
    const statusText = document.getElementById('status');
    const launcherLoading = document.getElementById('launcher-loading');
    const launcherContent = document.getElementById('launcher-content');
    const canvas = document.getElementById('display');
    const adBanner = document.getElementById('ad-banner');

    function setStatus(message, type = '') {
        if (!statusText) return;
        statusText.textContent = message;
        statusText.className = type;
    }

    function showOverlay() {
        if (!overlay) return;
        overlay.classList.remove('hidden');
    }

    function hideOverlay() {
        if (!overlay) return;
        overlay.classList.add('hidden');
    }

    function getActiveRenderer() {
        return decoder && decoder.renderer ? decoder.renderer : null;
    }

    function applyStreamPolicy(config = {}) {
        streamPolicy = {
            ...streamPolicy,
            ...config
        };

        document.body.dataset.fitMode = streamPolicy.fitMode;
        document.body.dataset.layoutMode = streamPolicy.layoutMode;

        if (adBanner) {
            adBanner.style.display = streamPolicy.showAdBanner ? 'block' : 'none';
        }

        const renderer = getActiveRenderer();
        renderer?.setFitMode?.(streamPolicy.fitMode);

        requestAnimationFrame(() => sendViewportSize());
    }

    function requestPurchase(source = 'web') {
        console.log(`[Main] Purchase requested from ${source}`);
        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(JSON.stringify({ type: 'requestPurchase' }));
        }
        showLauncherNotice('Premium feature — upgrade to unlock.');
    }

    let firstFrameReceived = false;
    let launchTimeout = null;

    function clearLaunchTimeout() {
        if (launchTimeout) {
            clearTimeout(launchTimeout);
            launchTimeout = null;
        }
    }

    function showLauncherNotice(message) {
        if (!launcherLoading) return;
        launcherLoading.textContent = message;
        launcherLoading.style.display = 'block';
    }

    function hideLauncherNotice() {
        if (!launcherLoading) return;
        launcherLoading.style.display = 'none';
    }

    hideOverlay();

    async function initDecoder() {
        console.log('[Main] Initializing decoders...');

        // Tesla browser has MediaSource, but doesn't support raw Annex-B H.264 without an fMP4 transmuxer.
        // We will force MJPEG fallback for now if WebCodecs (VideoDecoder) is not available.
        if (typeof WebCodecs !== 'undefined' || window.VideoDecoder) {
            console.log('[Main] Using WebCodecs Decoder');
            const renderer = new CanvasRenderer(canvas);
            renderer.setFitMode(streamPolicy.fitMode);
            decoder = new H264Decoder(
                (frame) => renderer.render(frame),
                (error) => console.error('[Main] Decoder error:', error)
            );
            decoder.renderer = renderer;
            await decoder.init(canvas);
            codecMode = 'h264';
        } else if (typeof createImageBitmap !== 'undefined') {
            console.log('[Main] Using MJPEG fallback (WebCodecs not supported)');
            decoder = new FallbackDecoder(
                () => {
                    // This callback fires when the first successful JPEG is drawn!
                    if (!firstFrameReceived) {
                        console.log('[Main] First MJPEG image successfully drawn!');
                        firstFrameReceived = true;
                        checkReady();
                    }
                },
                (error) => console.error('[Main] Fallback error:', error)
            );
            await decoder.init(canvas);
            decoder.renderer?.setFitMode?.(streamPolicy.fitMode);
            codecMode = 'mjpeg';

            // Show canvas explicitly for MJPEG
            canvas.style.display = 'block';
            const mseVideo = document.getElementById('mse-video');
            if (mseVideo) mseVideo.style.display = 'none';

            // Tell the server we want MJPEG frames
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }
        } else {
            throw new Error(
                'No supported decoder available. ' +
                'This browser requires WebCodecs (Chromium 94+) or createImageBitmap.'
            );
        }
    }

    function connectVideo() {
        const wsUrl = `ws://${host}/ws/video`;
        console.log('[Main] Connecting video:', wsUrl);
        // Don't show status overlay if we are in Launcher mode
        if (!isLauncherMode) setStatus('Connecting...', '');

        videoSocket = new WebSocket(wsUrl);
        videoSocket.binaryType = 'arraybuffer';

        videoSocket.onopen = () => {
            console.log('[Main] Video connected');
            if (!isLauncherMode) setStatus('Loading...', '');

            // If we are using MJPEG, tell the server immediately upon connection
            if (codecMode === 'mjpeg' && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                console.log('[Main] Sending initial MJPEG codec mode request');
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }
        };

        videoSocket.onmessage = async (event) => {
            if (event.data instanceof ArrayBuffer) {
                if (!decoder) {
                    console.warn('[Main] Frame arrived but no decoder');
                    return;
                }

                // If using H.264/WebCodecs, we detect first keyframe based on flags.
                // For MJPEG, the FallbackDecoder will fire its callback when the first image successfully draws,
                // so we don't guess based on bytes here.
                if (codecMode === 'h264') {
                    const v = new Uint8Array(event.data);
                    if (v.length > 0 && v[0] === 0x01 && !firstFrameReceived) {
                        console.log('[Main] First keyframe received, size=' + v.length);
                        firstFrameReceived = true;
                        checkReady();
                    }
                }

                // Even for MJPEG, if we get data, we try to decode.
                decoder.decode(event.data);
            }
        };

        videoSocket.onclose = () => {
            console.log('[Main] Video disconnected');
            if (!isLauncherMode) {
                setStatus('Disconnected', 'error');
                showOverlay();
            }
            scheduleReconnect();
        };

        videoSocket.onerror = (error) => {
            console.error('[Main] Video WebSocket error:', error);
        };
    }

    function checkReady() {
        if (firstFrameReceived) {
            clearLaunchTimeout();
            console.log('[Main] All streams ready, hiding overlay');
            const mseVideo = document.getElementById('mse-video');
            if (codecMode === 'mjpeg') {
                canvas.style.opacity = '1';
                if (mseVideo) mseVideo.style.opacity = '0';
            } else {
                if (mseVideo) mseVideo.style.opacity = '1';
                canvas.style.opacity = '0';
            }
            hideOverlay();
            if (decoder && decoder.play) {
                decoder.play();
            }
        }
    }

    let reconnectTimer = null;
    let isReconnecting = false;
    function scheduleReconnect() {
        if (isReconnecting) return;
        isReconnecting = true;
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(() => {
            console.log('[Main] Attempting reconnect...');
            isReconnecting = false;
            if (videoSocket && videoSocket.readyState === WebSocket.CLOSED) connectVideo();
            if (controlSocket && controlSocket.readyState === WebSocket.CLOSED) connectControl();
            if (audioPlayer && (!audioPlayer.socket || audioPlayer.socket.readyState === WebSocket.CLOSED)) {
                audioPlayer.startFromUserGesture(`ws://${host}/ws/audio`);
            }
        }, 3000);
    }

    let resizeTimer = null;
    function sendViewportSize() {
        if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;

        if (codecMode === 'mjpeg' && !streamPolicy.autoFit) {
            console.log(`[Main] Skipped viewport auto-fit for MJPEG free mode: ${canvas.clientWidth}x${canvas.clientHeight}`);
            return;
        }

        const width = Math.round(canvas.clientWidth || window.innerWidth);
        const height = Math.round(canvas.clientHeight || window.innerHeight);
        // Don't send invalid sizes (e.g. 0x0)
        if (width <= 0 || height <= 0) return;

        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => {
            console.log(`[Main] Sending viewport size: ${width}x${height} fit=${streamPolicy.fitMode} layout=${streamPolicy.layoutMode}`);
            controlSocket.send(JSON.stringify({
                type: 'viewport',
                width: width,
                height: height,
                fitMode: streamPolicy.fitMode,
                layoutMode: streamPolicy.layoutMode
            }));
        }, 500); // 500ms debounce
    }

    function connectControl() {
        const wsUrl = `ws://${host}/ws/control`;
        console.log('[Main] Connecting control:', wsUrl);
        controlSocket = new WebSocket(wsUrl);

        controlSocket.onopen = () => {
            console.log('[Main] Control connected');

            // Re-instantiate touchHandler with correct socket to prevent 'Socket not open' error
            if (touchHandler) {
                touchHandler.unbindEvents();
            }
            const renderer = (decoder && decoder.renderer) ? decoder.renderer : null;
            touchHandler = new TouchHandler(canvas, renderer, controlSocket);
            touchHandler.bindEvents();

            sendViewportSize();

            // Re-apply codec mode if MJPEG was chosen during init
            if (codecMode === 'mjpeg') {
                console.log('[Main] Sending MJPEG codec mode request after control connect');
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }

            // Also load launcher apps if not yet loaded
            if (isLauncherMode) {
                loadLauncherApps();
            }
        };

        controlSocket.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                // Listen for resolution changes
                if (msg.type === 'resolutionChanged') {
                    console.log(`[Main] Server resolution changed to ${msg.width}x${msg.height}`);
                    // FallbackDecoder handles size changes internally during render
                } else if (msg.type === 'showKeyboard') {
                    // Could dim UI or show visual indicator
                } else if (msg.type === 'hideKeyboard') {
                    // Hide visual indicator
                }
            } catch (e) {
                console.error('[Main] Control message parsing failed:', e);
            }
        };

        controlSocket.onclose = () => {
            console.log('[Main] Control disconnected');
            scheduleReconnect();
        };

        controlSocket.onerror = (error) => {
            console.error('[Main] Control WebSocket error:', error);
        };
    }

    // --- Web Launcher Code ---
    async function loadLauncherApps() {
        console.log('[Launcher] Fetching apps...');
        try {
            const response = await fetch('/api/apps');
            if (!response.ok) throw new Error('Network response was not ok');
            const data = await response.json();

            // If Premium isn't active, we still list all apps but lock Video/Music/Other.
            // (Handled server-side usually, but we will rely on UI tags returned or just lock them blindly if we know status.
            // For now, the API returns isPremium=true/false alongside apps.)
            const isPremium = data.isPremium || false;
            const apps = data.apps || [];

            applyStreamPolicy({
                isPremium,
                fitMode: data.fitMode || (isPremium ? 'cover' : 'contain'),
                autoFit: data.autoFit === true,
                layoutMode: data.layoutMode || 'single',
                showAdBanner: data.showAdBanner === true
            });

            renderLauncherApps(apps, isPremium);
        } catch (err) {
            console.error('[Launcher] Failed to fetch apps:', err);
            launcherLoading.textContent = 'Failed to load apps. Try refreshing.';
        }
    }

    function renderLauncherApps(apps, isPremium) {
        launcherContent.innerHTML = ''; // Clear previous
        const grouped = {
            'NAVIGATION': { title: 'Navigation', color: '#4CAF50', items: [] },
            'VIDEO': { title: 'Video', color: '#FF5722', items: [], locked: !isPremium },
            'MUSIC': { title: 'Music', color: '#9C27B0', items: [], locked: !isPremium },
            'OTHER': { title: 'Apps', color: '#9E9E9E', items: [], locked: !isPremium }
        };

        apps.forEach(app => {
            if (grouped[app.category]) {
                grouped[app.category].items.push(app);
            } else {
                grouped['OTHER'].items.push(app);
            }
        });

        Object.keys(grouped).forEach(key => {
            const group = grouped[key];
            if (group.items.length === 0) return;

            const section = document.createElement('div');
            section.className = 'category-section';

            const header = document.createElement('div');
            header.className = 'category-header';
            const bar = document.createElement('div');
            bar.className = 'category-bar';
            bar.style.backgroundColor = group.color;
            const title = document.createElement('div');
            title.className = 'category-title';
            title.textContent = group.title;

            header.appendChild(bar);
            header.appendChild(title);
            section.appendChild(header);

            const grid = document.createElement('div');
            grid.className = 'app-grid';

            group.items.forEach(app => {
                const cell = document.createElement('div');
                cell.className = 'app-cell';
                if (group.locked) cell.classList.add('locked');

                const icon = document.createElement('img');
                icon.className = 'app-icon';
                icon.src = `/api/icon?pkg=${app.packageName}`;
                icon.loading = 'lazy';
                cell.appendChild(icon);

                const label = document.createElement('div');
                label.className = 'app-label';
                label.textContent = app.label;
                cell.appendChild(label);

                cell.addEventListener('click', () => {
                    if (group.locked) {
                        requestPurchase(`locked:${app.packageName}`);
                        return;
                    }
                    launchApp(app);
                });

                grid.appendChild(cell);
            });

            section.appendChild(grid);
            launcherContent.appendChild(section);
        });

        hideLauncherNotice();
        launcherContent.style.display = 'block';
        if (isLauncherMode) {
            webLauncher.classList.remove('hidden');
        }
    }

    function launchApp(app) {
        const pkgName = app.packageName;
        const componentName = app.componentName || null;
        console.log(`[Launcher] Launching app: ${pkgName} (${componentName || 'package-only'})`);

        // Hide launcher, show video player
        isLauncherMode = false;
        webLauncher.classList.add('hidden');
        homeBtn.style.display = 'block';

        // RESET firstFrameReceived so the "Loading..." overlay can be hidden upon the next frame.
        firstFrameReceived = false;
        clearLaunchTimeout();

        // Ensure UI transitions are processed before blocking threads
        setTimeout(() => {
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                // Send the launch command to start the app AND trigger MediaProjection/VirtualDisplay frames
                const message = {
                    type: 'launchApp',
                    pkg: pkgName
                };
                if (componentName) {
                    message.componentName = componentName;
                }
                controlSocket.send(JSON.stringify(message));

                // Force codec mode request just in case to ensure frames start flowing
                if (codecMode === 'mjpeg') {
                    controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
                }

                sendViewportSize();

                // Wait for stream to start
                setStatus('Loading...', '');
                showOverlay();
                launchTimeout = setTimeout(() => {
                    if (firstFrameReceived) return;

                    console.warn(`[Launcher] Timed out waiting for first frame after launching ${pkgName}`);
                    isLauncherMode = true;
                    webLauncher.classList.remove('hidden');
                    homeBtn.style.display = 'none';
                    hideOverlay();
                    showLauncherNotice('Launch timed out. Try again.');
                }, 5000);
            } else {
                console.error('[Launcher] Control socket not connected!');
                isLauncherMode = true;
                webLauncher.classList.remove('hidden');
                homeBtn.style.display = 'none';
                showLauncherNotice('Connection lost. Try again.');
            }
        }, 50);
    }

    function goHome() {
        console.log('[Main] Going home to launcher');
        isLauncherMode = true;
        clearLaunchTimeout();
        webLauncher.classList.remove('hidden');
        homeBtn.style.display = 'none';
        hideOverlay();
        firstFrameReceived = false; // Reset frame state

        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(JSON.stringify({ type: 'goHome' }));
        }
    }

    homeBtn.addEventListener('click', () => {
        goHome();
    });

    if (adBanner) {
        adBanner.addEventListener('click', () => requestPurchase('banner'));
    }

    // Handle resize events to update Android's virtual display resolution
    window.addEventListener('resize', sendViewportSize);

    // Initial setups
    try {
        await initDecoder();
        connectVideo();
        connectControl();
    } catch (e) {
        setStatus(e.message, 'error');
        showOverlay();
        console.error('[Main] Initialization failed:', e);
    }

    // Audio setup on user interaction
    // The AudioCapture must be instantiated first, but it requires user gesture to start playing
    // on most modern browsers. We wrap the entire page to listen for the first tap.
    audioPlayer = new AudioPlayer();

    // Make sure we explicitly hide unmute-overlay
    const unmuteOverlay = document.getElementById('unmute-overlay');
    const startAudio = async () => {
        if (!audioPlayer.socket || audioPlayer.socket.readyState === WebSocket.CLOSED) {
            await audioPlayer.startFromUserGesture(`ws://${host}/ws/audio`);
        }
        document.removeEventListener('click', startAudio);
        document.removeEventListener('touchstart', startAudio);

        // Hide the unmute overlay!
        if (unmuteOverlay) {
            unmuteOverlay.classList.add('hidden');
            unmuteOverlay.style.display = 'none';
        }
    };

    // Bind to the overlay so tapping the unmute button triggers it
    if (unmuteOverlay) {
        unmuteOverlay.addEventListener('click', startAudio);
        unmuteOverlay.addEventListener('touchstart', startAudio);
    }
    // Also bind globally as fallback
    document.addEventListener('click', startAudio);
    document.addEventListener('touchstart', startAudio);

    // Now that video element (mse-video) or canvas is definitely created/found:
    const mseVideo = document.getElementById('mse-video');
    if (mseVideo) mseVideo.style.pointerEvents = 'none'; // let touches go through to canvas
    if (canvas) canvas.style.pointerEvents = 'auto';     // ensure canvas catches touch
});
