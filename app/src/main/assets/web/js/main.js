const host = window.location.host;

let videoSocket = null;
let controlSocket = null;
let audioPlayer = null;
let touchHandler = null;
let secondaryVideoSocket = null;
let secondaryTouchHandler = null;
let secondaryDecoder = null;

// Split strategy: 'dual_stream' = two VDs with separate video streams
const SPLIT_STRATEGY = 'dual_stream';

let decoder = null;
let currentPrimaryApp = null;
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
    const playerShell = document.getElementById('player-shell');
    const streamPane = document.getElementById('stream-pane');
    const browserSplitPane = document.getElementById('browser-split-pane');
    const secondaryCanvas = document.getElementById('display-secondary');
    const splitDivider = document.getElementById('split-divider');
    const splitResetBtn = document.getElementById('split-reset-btn');
    const splitCloseBtn = document.getElementById('split-close-btn');

    // Split drawer
    const splitDrawer = document.getElementById('split-drawer');
    const splitHandle = document.getElementById('split-handle');
    const splitAppList = document.getElementById('split-app-list');

    const DEFAULT_BROWSER_SPLIT_RATIO = 0.42;
    let browserSplitState = {
        active: false,
        app: null,
        url: null,
        ratio: DEFAULT_BROWSER_SPLIT_RATIO,
        resizing: false,
        fitMode: 'cover',
        lockedPrimaryViewport: null,
        lockedSecondaryViewport: null,
        preset: null
    };

    const BROWSER_PRESETS = [
        { label: 'YouTube', url: 'https://m.youtube.com' },
        { label: 'Netflix', url: 'https://www.netflix.com' },
        { label: 'Disney+', url: 'https://www.disneyplus.com' },
        { label: 'Wavve', url: 'https://m.wavve.com' },
        { label: 'TVING', url: 'https://www.tving.com' },
        { label: 'Coupang Play', url: 'https://www.coupangplay.com' },
        { label: 'Google', url: 'https://www.google.com' }
    ];

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

    function getActiveSecondaryRenderer() {
        return secondaryDecoder && secondaryDecoder.renderer ? secondaryDecoder.renderer : null;
    }

    function getEffectivePrimaryFitMode() {
        return browserSplitState.active ? 'contain' : streamPolicy.fitMode;
    }

    function getEffectiveSecondaryFitMode() {
        return browserSplitState.active ? browserSplitState.fitMode : streamPolicy.fitMode;
    }

    function alignDimension(value) {
        return Math.max(320, (Math.round(value) + 15) & ~15);
    }

    function buildLockedViewport(width, height, aspectRatio = null) {
        let nextWidth = Math.max(1, Math.round(width));
        let nextHeight = Math.max(1, Math.round(height));
        if (aspectRatio && Number.isFinite(aspectRatio) && aspectRatio > 0) {
            nextWidth = Math.max(nextWidth, Math.round(nextHeight * aspectRatio));
        }
        return {
            width: alignDimension(nextWidth),
            height: alignDimension(nextHeight)
        };
    }

    function getAppLayoutHints(app) {
        const packageName = app?.packageName || '';
        const category = app?.category || '';
        const label = (app?.label || '').toLowerCase();
        const isMapApp =
            category === 'NAVIGATION' ||
            packageName.includes('map') ||
            packageName.includes('nmap') ||
            packageName.includes('waze') ||
            label.includes('지도') ||
            label.includes('map');
        const isVideoApp =
            category === 'VIDEO' ||
            packageName.includes('youtube') ||
            packageName.includes('netflix') ||
            packageName.includes('tving') ||
            packageName.includes('wavve') ||
            packageName.includes('disney') ||
            label.includes('youtube') ||
            label.includes('netflix');
        const isMailOrFeed =
            packageName.includes('gmail') ||
            packageName.includes('mail') ||
            packageName.includes('outlook') ||
            packageName.includes('news') ||
            packageName.includes('reddit') ||
            packageName.includes('x.com') ||
            label.includes('gmail') ||
            label.includes('mail');

        return { isMapApp, isVideoApp, isMailOrFeed };
    }

    function getAppPreferredAspectRatio(app, role = 'secondary') {
        const { isMapApp, isVideoApp, isMailOrFeed } = getAppLayoutHints(app);

        if (isMapApp) return 9 / 16;
        if (isVideoApp) return role === 'secondary' ? 0.62 : 0.58;
        if (isMailOrFeed) return 0.56;
        return role === 'primary' ? 9 / 16 : 0.58;
    }

    function computePrimaryPaneRatio(primaryAspectRatio) {
        const shellWidth = Math.round(playerShell?.clientWidth || window.innerWidth || 0);
        const shellHeight = Math.round(playerShell?.clientHeight || window.innerHeight || 0);
        if (shellWidth <= 0 || shellHeight <= 0 || !Number.isFinite(primaryAspectRatio) || primaryAspectRatio <= 0) {
            return DEFAULT_BROWSER_SPLIT_RATIO;
        }

        const desiredPrimaryWidth = Math.round(shellHeight * primaryAspectRatio);
        const minPrimaryWidth = Math.round(shellWidth * 0.25);
        const maxPrimaryWidth = Math.max(minPrimaryWidth, shellWidth - 320);
        const clampedPrimaryWidth = Math.max(minPrimaryWidth, Math.min(maxPrimaryWidth, desiredPrimaryWidth));
        return clampedPrimaryWidth / shellWidth;
    }

    function resolveSplitPreset(primaryApp, secondaryApp) {
        const primaryAspectRatio = getAppPreferredAspectRatio(primaryApp, 'primary');
        const secondaryAspectRatio = getAppPreferredAspectRatio(secondaryApp, 'secondary');
        const primaryHints = getAppLayoutHints(primaryApp);
        const secondaryHints = getAppLayoutHints(secondaryApp);

        let ratio = computePrimaryPaneRatio(primaryAspectRatio);
        if (primaryHints.isMapApp && secondaryHints.isVideoApp) {
            ratio = 0.31;
        } else if (primaryHints.isMapApp) {
            ratio = 0.33;
        }

        return {
            ratio: Math.max(0.25, Math.min(0.75, ratio)),
            secondaryAspectRatio,
            primaryAspectRatio,
            secondaryAspectRatio
        };
    }

    function lockBrowserSplitViewports(app = browserSplitState.app) {
        if (!browserSplitState.active) return;
        const preset = resolveSplitPreset(currentPrimaryApp, app);
        browserSplitState.preset = preset;

        // Use current ratio (may have been changed by divider drag), not preset ratio
        const activeRatio = browserSplitState.ratio;
        const { primaryWidth, secondaryWidth, shellHeight } = getDesiredSplitWidths(activeRatio);
        const primaryHeight = Math.round(streamPane?.clientHeight || canvas?.clientHeight || shellHeight || window.innerHeight || 0);

        if (primaryWidth <= 0 || primaryHeight <= 0) {
            return;
        }

        browserSplitState.lockedPrimaryViewport = buildLockedViewport(
            primaryWidth,
            primaryHeight
        );

        if (SPLIT_STRATEGY === 'dual_stream') {
            const secondaryHeight = Math.round(browserSplitPane?.clientHeight || shellHeight || 0);
            if (secondaryWidth > 0 && secondaryHeight > 0) {
                browserSplitState.lockedSecondaryViewport = buildLockedViewport(
                    secondaryWidth,
                    secondaryHeight,
                    preset.secondaryAspectRatio
                );
            }
        }
    }

    function updateSplitFitButton() {
        // No-op: fit button removed in browser-only split
    }

    function applyActiveFitModes() {
        const primaryFitMode = getEffectivePrimaryFitMode();
        const secondaryFitMode = getEffectiveSecondaryFitMode();
        document.body.dataset.fitMode = browserSplitState.active ? secondaryFitMode : primaryFitMode;
        getActiveRenderer()?.setFitMode?.(primaryFitMode);
        getActiveSecondaryRenderer()?.setFitMode?.(secondaryFitMode);
        updateSplitFitButton();
    }

    function getSplitShellSize() {
        const shellWidth = Math.round(playerShell?.clientWidth || window.innerWidth || 0);
        const shellHeight = Math.round(playerShell?.clientHeight || window.innerHeight || 0);
        return { shellWidth, shellHeight };
    }

    function getDesiredSplitWidths(ratio = browserSplitState.ratio) {
        const { shellWidth, shellHeight } = getSplitShellSize();
        if (shellWidth <= 0 || shellHeight <= 0) {
            return { primaryWidth: 0, secondaryWidth: 0, shellWidth, shellHeight };
        }
        const minPrimaryWidth = 320;
        const minSecondaryWidth = 320;
        const desiredPrimaryWidth = Math.round(shellWidth * ratio);
        const maxPrimaryWidth = Math.max(minPrimaryWidth, shellWidth - minSecondaryWidth);
        const primaryWidth = Math.max(minPrimaryWidth, Math.min(maxPrimaryWidth, desiredPrimaryWidth));
        const secondaryWidth = Math.max(minSecondaryWidth, shellWidth - primaryWidth);
        return { primaryWidth, secondaryWidth, shellWidth, shellHeight };
    }

    function setBrowserSplitRatio(nextRatio) {
        const ratio = Math.max(0.25, Math.min(0.75, nextRatio));
        browserSplitState.ratio = ratio;
        const { primaryWidth, shellWidth } = getDesiredSplitWidths(ratio);
        if (primaryWidth > 0 && shellWidth > 0) {
            playerShell?.style.setProperty('--split-left-width', `${primaryWidth}px`);
        } else {
            playerShell?.style.setProperty('--split-left-width', `${Math.round(ratio * 1000) / 10}%`);
        }
    }

    function isDualStreamCapable(app) {
        return !!app;
    }

    function destroySecondaryTransport() {
        if (secondaryTouchHandler) {
            secondaryTouchHandler.destroy();
            secondaryTouchHandler = null;
        }
        if (secondaryVideoSocket) {
            try { secondaryVideoSocket.close(); } catch (_) {}
            secondaryVideoSocket = null;
        }
        if (secondaryDecoder) {
            secondaryDecoder.destroy?.();
            secondaryDecoder = null;
        }
        if (secondaryCanvas) {
            const ctx = secondaryCanvas.getContext('2d');
            ctx?.clearRect(0, 0, secondaryCanvas.width || secondaryCanvas.clientWidth || 0, secondaryCanvas.height || secondaryCanvas.clientHeight || 0);
        }
    }

    async function initSecondaryDecoder() {
        if (!secondaryCanvas) return null;
        if (secondaryDecoder) {
            secondaryDecoder.destroy?.();
            secondaryDecoder = null;
        }

        if (typeof WebCodecs !== 'undefined' || window.VideoDecoder) {
            const renderer = new CanvasRenderer(secondaryCanvas);
            renderer.setFitMode(getEffectiveSecondaryFitMode());
            secondaryDecoder = new H264Decoder(
                (frame) => renderer.render(frame),
                (error) => console.error('[Main] Secondary decoder error:', error)
            );
            secondaryDecoder.renderer = renderer;
            await secondaryDecoder.init(secondaryCanvas);
        } else if (typeof createImageBitmap !== 'undefined') {
            secondaryDecoder = new FallbackDecoder(
                () => {},
                (error) => console.error('[Main] Secondary fallback error:', error)
            );
            await secondaryDecoder.init(secondaryCanvas);
            secondaryDecoder.renderer?.setFitMode?.(getEffectiveSecondaryFitMode());
        }
        return secondaryDecoder;
    }

    function connectSecondaryVideo() {
        if (!browserSplitState.active) return;
        if (secondaryVideoSocket) {
            try { secondaryVideoSocket.close(); } catch (_) {}
        }
        const wsUrl = `ws://${host}/ws/video?channel=secondary`;
        secondaryVideoSocket = new WebSocket(wsUrl);
        secondaryVideoSocket.binaryType = 'arraybuffer';
        secondaryVideoSocket.onmessage = async (event) => {
            if (event.data instanceof ArrayBuffer && secondaryDecoder) {
                secondaryDecoder.decode(event.data);
            }
        };
        secondaryVideoSocket.onclose = () => {
            if (browserSplitState.active) scheduleReconnect();
        };
        secondaryVideoSocket.onerror = (error) => console.error('[Main] Secondary video WebSocket error:', error);
    }

    function sendSecondaryLaunchRequest() {
        if (!browserSplitState.active || !browserSplitState.app) return;
        if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;

        const app = browserSplitState.app;
        const message = {
            type: 'launchApp',
            pkg: app.packageName,
            splitMode: true,
            pane: 'secondary'
        };
        if (app.componentName) message.componentName = app.componentName;
        controlSocket.send(JSON.stringify(message));
    }

    async function enableBrowserSplit(app) {
        if (!app) return;

        if (SPLIT_STRATEGY === 'freeform') {
            // Single-VD freeform split: both apps on the same VD, single stream
            browserSplitState.active = true;
            browserSplitState.app = app;
            browserSplitState.fitMode = 'contain';
            browserSplitState.lockedPrimaryViewport = null;
            browserSplitState.lockedSecondaryViewport = null;
            streamPolicy.layoutMode = 'freeform_split';
            document.body.dataset.layoutMode = streamPolicy.layoutMode;
            console.log(`[Main] Freeform split: primary=${currentPrimaryApp?.packageName || 'unknown'} split=${app?.packageName || 'unknown'}`);

            // Single canvas shows both apps — add freeform-split class for close button
            playerShell?.classList.add('freeform-split');
            // Send split app launch request to server
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'launchApp',
                    pkg: app.packageName,
                    splitMode: true,
                    pane: 'primary'
                };
                if (app.componentName) message.componentName = app.componentName;
                controlSocket.send(JSON.stringify(message));
            }
            return;
        }

        // Legacy dual-stream path
        destroySecondaryTransport();
        browserSplitState.active = true;
        browserSplitState.app = app;
        browserSplitState.fitMode = 'contain';
        browserSplitState.lockedPrimaryViewport = null;
        browserSplitState.lockedSecondaryViewport = null;
        browserSplitState.preset = resolveSplitPreset(currentPrimaryApp, app);
        streamPolicy.layoutMode = 'browser_split';
        document.body.dataset.layoutMode = streamPolicy.layoutMode;
        const initialRatio = browserSplitState.preset.ratio || browserSplitState.ratio || DEFAULT_BROWSER_SPLIT_RATIO;
        setBrowserSplitRatio(initialRatio);
        // Highlight the closest ratio button
        document.querySelectorAll('.split-ratio-btn').forEach(b => {
            const btnRatio = parseFloat(b.dataset.ratio);
            b.classList.toggle('active', Math.abs(btnRatio - initialRatio) < 0.05);
        });
        playerShell?.classList.add('browser-split');
        applyActiveFitModes();
        await new Promise((resolve) => requestAnimationFrame(() => resolve()));
        lockBrowserSplitViewports(app);
        await initSecondaryDecoder();
        if (secondaryTouchHandler) {
            secondaryTouchHandler.destroy();
        }
        secondaryTouchHandler = new TouchHandler(secondaryCanvas, getActiveSecondaryRenderer(), controlSocket, 'secondary');
        applyActiveFitModes();
        connectSecondaryVideo();
        requestAnimationFrame(() => sendViewportSize());
        setTimeout(() => sendSecondaryLaunchRequest(), 120);
    }

    function disableBrowserSplit(options = {}) {
        const { notifyServer = true } = options;
        const wasActive = browserSplitState.active;
        browserSplitState.active = false;
        browserSplitState.resizing = false;
        browserSplitState.app = null;
        browserSplitState.url = null;
        browserSplitState.fitMode = 'contain';
        browserSplitState.lockedPrimaryViewport = null;
        browserSplitState.lockedSecondaryViewport = null;
        browserSplitState.preset = null;
        streamPolicy.layoutMode = 'single';
        document.body.dataset.layoutMode = streamPolicy.layoutMode;
        playerShell?.classList.remove('browser-split');
        playerShell?.classList.remove('freeform-split');
        playerShell?.style.removeProperty('--split-left-width');

        if (SPLIT_STRATEGY === 'freeform') {
            // Tell server to close split and restore primary fullscreen
            if (notifyServer && wasActive && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'closeSplit' }));
            }
        } else {
            destroySecondaryTransport();
            if (notifyServer && wasActive && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'closeSecondary' }));
            }
        }

        applyActiveFitModes();
        // Force send full viewport immediately (don't rely on CSS transition timing)
        if (wasActive && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            const fullWidth = Math.round(window.innerWidth || 1920);
            const fullHeight = Math.round(window.innerHeight || 1080);
            console.log(`[Main] Split closed — forcing full viewport ${fullWidth}x${fullHeight}`);
            controlSocket.send(JSON.stringify({
                type: 'viewport',
                pane: 'primary',
                width: fullWidth,
                height: fullHeight,
                fitMode: getEffectivePrimaryFitMode(),
                layoutMode: 'single'
            }));
        }
    }

    function applyStreamPolicy(config = {}) {
        streamPolicy = {
            ...streamPolicy,
            ...config
        };

        document.body.dataset.layoutMode = streamPolicy.layoutMode;

        if (adBanner) {
            adBanner.style.display = streamPolicy.showAdBanner ? 'block' : 'none';
        }

        applyActiveFitModes();
        requestAnimationFrame(() => sendViewportSize());
    }

    function requestPurchase(source = 'web') {
        console.log(`[Main] Purchase requested from ${source}`);
        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(JSON.stringify({ type: 'requestPurchase' }));
        }
        showLauncherNotice('Premium feature — upgrade to unlock.');
    }

    function focusKeyboardProxy() {
        const kbInput = document.getElementById('keyboard-input');
        if (!kbInput) return;

        kbInput.style.pointerEvents = 'auto';
        kbInput.focus({ preventScroll: true });
        if (typeof kbInput.setSelectionRange === 'function') {
            const len = kbInput.value.length;
            kbInput.setSelectionRange(len, len);
        }
    }

    function blurKeyboardProxy() {
        const kbInput = document.getElementById('keyboard-input');
        if (!kbInput) return;

        kbInput.blur();
        kbInput.style.pointerEvents = 'none';
        kbInput.value = '';
    }

    let firstFrameReceived = false;
    let launchTimeout = null;
    let composing = false;
    let skipNextInput = false;

    // ── Bubble Composer state ──
    const useBubbleInput = true;
    let bubbleVisible = false;
    const inputBubble = document.getElementById('input-bubble');
    const bubbleText = document.getElementById('bubble-text');
    const bubbleSubmit = document.getElementById('bubble-submit');
    const bubbleBackspace = document.getElementById('bubble-backspace');
    const bubbleCancel = document.getElementById('bubble-cancel');

    // Prevent bubble touch/pointer events from propagating to canvas
    if (inputBubble) {
        for (const evt of ['pointerdown', 'pointerup', 'pointermove', 'touchstart', 'touchend', 'touchmove', 'mousedown', 'mouseup']) {
            inputBubble.addEventListener(evt, (e) => e.stopPropagation());
        }
    }

    function clearLaunchTimeout() {
        if (launchTimeout) {
            clearTimeout(launchTimeout);
            launchTimeout = null;
        }
    }

    function showLauncherNotice(message) {
        if (!launcherLoading) return;
        launcherLoading.innerHTML = `<div class="loading-text">${message}</div>`;
        launcherLoading.style.display = 'flex';
    }

    function hideLauncherNotice() {
        if (!launcherLoading) return;
        launcherLoading.style.display = 'none';
    }

    // ── Bubble Composer functions ──
    function positionInputBubble(anchor) {
        if (!inputBubble) return;
        const bh = 56;
        const margin = 12;
        const bw = inputBubble.offsetWidth || 360;

        let cx, top;
        if (anchor) {
            cx = anchor.clientX;
            top = anchor.clientY - bh - margin;
            if (top < margin) top = anchor.clientY + margin;
        } else {
            cx = window.innerWidth / 2;
            top = window.innerHeight - bh - 60;
        }

        let left = cx - bw / 2;
        if (left < margin) left = margin;
        if (left + bw > window.innerWidth - margin) left = window.innerWidth - margin - bw;
        if (top < margin) top = margin;
        if (top + bh > window.innerHeight - margin) top = window.innerHeight - margin - bh;

        inputBubble.style.left = `${left}px`;
        inputBubble.style.top = `${top}px`;
    }

    function openInputBubble(anchor) {
        if (!inputBubble || bubbleVisible) return;
        bubbleVisible = true;
        positionInputBubble(anchor);
        inputBubble.classList.add('visible');
        bubbleText.value = '';
        setTimeout(() => bubbleText.focus({ preventScroll: true }), 80);
    }

    function closeInputBubble(clear = true) {
        if (!inputBubble) return;
        bubbleVisible = false;
        inputBubble.classList.remove('visible');
        if (clear && bubbleText) bubbleText.value = '';
        bubbleText?.blur();
    }

    function submitBubbleInput() {
        if (!bubbleText) return;
        // Force-finish any ongoing IME composition (e.g. Korean)
        bubbleText.blur();
        const text = bubbleText.value;
        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            if (text) {
                controlSocket.send(JSON.stringify({ type: 'textInput', text }));
            }
            controlSocket.send(JSON.stringify({ type: 'keyEvent', keyCode: 66 }));
        }
        bubbleText.value = '';
    }

    if (bubbleSubmit) {
        bubbleSubmit.addEventListener('click', (e) => {
            e.preventDefault();
            submitBubbleInput();
        });
    }
    if (bubbleBackspace) {
        bubbleBackspace.addEventListener('click', (e) => {
            e.preventDefault();
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'keyEvent', keyCode: 67 }));
            }
            bubbleText?.focus({ preventScroll: true });
        });
    }
    if (bubbleCancel) {
        bubbleCancel.addEventListener('click', (e) => {
            e.preventDefault();
            closeInputBubble(true);
        });
    }
    if (bubbleText) {
        bubbleText.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                submitBubbleInput();
            }
        });
    }

    // ── Browser-Only Split Pane Functions ──
    const browserPaneUi = document.getElementById('browser-pane-ui');
    const browserUrlInput = document.getElementById('browser-url-input');
    const browserGoBtn = document.getElementById('browser-go-btn');
    const browserRefreshBtn = document.getElementById('browser-refresh-btn');
    const browserPresetsContainer = document.getElementById('browser-presets');
    const browserIframe = document.getElementById('browser-iframe');
    const browserLoading = document.getElementById('browser-loading');
    const browserError = document.getElementById('browser-error');
    const browserErrorClose = document.getElementById('browser-error-close');
    const browserHomeState = document.getElementById('browser-home-state');

    // Map from package name to browser URL (mirrors server-side OTT_WEB_URLS)
    const OTT_WEB_URLS = {
        'com.google.android.youtube': 'https://m.youtube.com',
        'com.netflix.mediaclient': 'https://www.netflix.com',
        'com.disney.disneyplus': 'https://www.disneyplus.com',
        'com.disney.disneyplus.kr': 'https://www.disneyplus.com',
        'com.wavve.player': 'https://m.wavve.com',
        'net.cj.cjhv.gs.tving': 'https://www.tving.com',
        'com.coupang.play': 'https://www.coupangplay.com',
        'com.frograms.watcha': 'https://watcha.com'
    };

    function getPresetUrlForApp(app) {
        if (!app) return null;
        // 1. Server already provides webUrl for OTT apps
        if (app.webUrl) return app.webUrl;
        // 2. Fallback: check client-side OTT map
        const url = OTT_WEB_URLS[app.packageName];
        if (url) return url;
        return null;
    }

    function loadBrowserUrl(url) {
        if (!url) return;
        browserSplitState.url = url;
        if (browserHomeState) browserHomeState.classList.add('hidden');
        if (browserError) browserError.classList.remove('visible');
        if (browserLoading) browserLoading.classList.remove('hidden');
        if (browserIframe) {
            browserIframe.src = url;
            browserIframe.style.display = 'block';
        }
        if (browserUrlInput) browserUrlInput.value = url;
        updatePresetButtons(url);
        console.log(`[Main] Browser pane loading: ${url}`);

        // Detect load or timeout
        let loaded = false;
        const onLoad = () => {
            loaded = true;
            if (browserLoading) browserLoading.classList.add('hidden');
        };
        if (browserIframe) {
            browserIframe.onload = onLoad;
        }
        setTimeout(() => {
            if (!loaded && browserLoading) browserLoading.classList.add('hidden');
        }, 8000);
    }

    function showBrowserHome() {
        browserSplitState.url = null;
        if (browserIframe) { browserIframe.src = 'about:blank'; browserIframe.style.display = 'none'; }
        if (browserHomeState) browserHomeState.classList.remove('hidden');
        if (browserError) browserError.classList.remove('visible');
        if (browserLoading) browserLoading.classList.add('hidden');
        if (browserUrlInput) browserUrlInput.value = '';
        updatePresetButtons(null);
    }

    function clearBrowserPane() {
        if (browserIframe) { browserIframe.src = 'about:blank'; browserIframe.style.display = 'none'; }
        if (browserLoading) browserLoading.classList.add('hidden');
        if (browserError) browserError.classList.remove('visible');
        if (browserHomeState) browserHomeState.classList.remove('hidden');
        if (browserUrlInput) browserUrlInput.value = '';
    }

    function updatePresetButtons(activeUrl) {
        if (!browserPresetsContainer) return;
        const buttons = browserPresetsContainer.querySelectorAll('.browser-preset-btn');
        buttons.forEach(btn => {
            btn.classList.toggle('active', activeUrl && btn.dataset.url === activeUrl);
        });
    }

    // Render preset buttons
    if (browserPresetsContainer) {
        BROWSER_PRESETS.forEach(preset => {
            const btn = document.createElement('button');
            btn.className = 'browser-preset-btn';
            btn.textContent = preset.label;
            btn.dataset.url = preset.url;
            btn.addEventListener('click', () => loadBrowserUrl(preset.url));
            browserPresetsContainer.appendChild(btn);
        });
    }

    if (browserGoBtn && browserUrlInput) {
        const navigateBrowserUrl = () => {
            let url = (browserUrlInput.value || '').trim();
            if (!url) return;
            if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
            loadBrowserUrl(url);
        };
        browserGoBtn.addEventListener('click', navigateBrowserUrl);
        browserUrlInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); navigateBrowserUrl(); }
        });
    }

    if (browserRefreshBtn && browserIframe) {
        browserRefreshBtn.addEventListener('click', () => {
            if (browserSplitState.url) loadBrowserUrl(browserSplitState.url);
        });
    }

    if (browserErrorClose) {
        browserErrorClose.addEventListener('click', () => showBrowserHome());
    }

    setBrowserSplitRatio(DEFAULT_BROWSER_SPLIT_RATIO);
    updateSplitFitButton();
    hideOverlay();

    async function initDecoder() {
        console.log('[Main] Initializing decoders...');

        if (typeof WebCodecs !== 'undefined' || window.VideoDecoder) {
            console.log('[Main] Using WebCodecs Decoder');
            const renderer = new CanvasRenderer(canvas);
            renderer.setFitMode(getEffectivePrimaryFitMode());
            decoder = new H264Decoder(
                (frame) => renderer.render(frame),
                (error) => console.error('[Main] Decoder error:', error)
            );
            decoder.renderer = renderer;
            await decoder.init(canvas);
            codecMode = 'h264';
            applyActiveFitModes();
        } else if (typeof createImageBitmap !== 'undefined') {
            console.log('[Main] Using MJPEG fallback');
            decoder = new FallbackDecoder(
                () => {
                    if (!firstFrameReceived) {
                        firstFrameReceived = true;
                        checkReady();
                    }
                },
                (error) => console.error('[Main] Fallback error:', error)
            );
            await decoder.init(canvas);
            decoder.renderer?.setFitMode?.(getEffectivePrimaryFitMode());
            codecMode = 'mjpeg';
            applyActiveFitModes();

            canvas.style.display = 'block';
            const mseVideo = document.getElementById('mse-video');
            if (mseVideo) mseVideo.style.display = 'none';

            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }
        } else {
            throw new Error('No supported decoder available.');
        }
    }

    function connectVideo() {
        const wsUrl = `ws://${host}/ws/video`;
        if (!isLauncherMode) setStatus('Connecting...', '');

        videoSocket = new WebSocket(wsUrl);
        videoSocket.binaryType = 'arraybuffer';

        videoSocket.onopen = () => {
            if (!isLauncherMode) setStatus('Loading...', '');
            if (codecMode === 'mjpeg' && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }
        };

        videoSocket.onmessage = async (event) => {
            if (event.data instanceof ArrayBuffer) {
                if (!decoder) return;
                if (codecMode === 'h264') {
                    const v = new Uint8Array(event.data);
                    if (v.length > 0 && v[0] === 0x01 && !firstFrameReceived) {
                        firstFrameReceived = true;
                        checkReady();
                    }
                }
                decoder.decode(event.data);
            }
        };

        videoSocket.onclose = () => {
            if (!isLauncherMode) {
                setStatus('Disconnected', 'error');
                showOverlay();
            }
            scheduleReconnect();
        };

        videoSocket.onerror = (error) => console.error('[Main] Video WebSocket error:', error);
    }

    function checkReady() {
        if (firstFrameReceived) {
            clearLaunchTimeout();
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
            isReconnecting = false;
            if (videoSocket && videoSocket.readyState === WebSocket.CLOSED) connectVideo();
            if (SPLIT_STRATEGY === 'dual_stream' && browserSplitState.active && (!secondaryVideoSocket || secondaryVideoSocket.readyState === WebSocket.CLOSED)) connectSecondaryVideo();
            if (controlSocket && controlSocket.readyState === WebSocket.CLOSED) connectControl();
            if (audioPlayer && (!audioPlayer.socket || audioPlayer.socket.readyState === WebSocket.CLOSED)) {
                audioPlayer.startFromUserGesture(`ws://${host}/ws/audio`);
            }
        }, 3000);
    }

    let resizeTimer = null;
    function describeViewport(viewport) {
        return viewport && viewport.width > 0 && viewport.height > 0
            ? `${viewport.width}x${viewport.height}`
            : 'none';
    }

    function sendViewportSize() {
        if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
        if (codecMode === 'mjpeg' && !streamPolicy.autoFit) return;

        const livePrimaryWidth = Math.round(streamPane?.clientWidth || canvas.clientWidth || window.innerWidth);
        const livePrimaryHeight = Math.round(streamPane?.clientHeight || canvas.clientHeight || window.innerHeight);
        if (livePrimaryWidth <= 0 || livePrimaryHeight <= 0) return;

        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => {
            const primaryViewport = browserSplitState.active && browserSplitState.lockedPrimaryViewport
                ? browserSplitState.lockedPrimaryViewport
                : { width: livePrimaryWidth, height: livePrimaryHeight };

            // Only send secondary viewport in legacy dual-stream mode
            if (SPLIT_STRATEGY === 'dual_stream' && browserSplitState.active && browserSplitPane) {
                const secondaryViewport = browserSplitState.lockedSecondaryViewport;
                if (secondaryViewport && secondaryViewport.width > 0 && secondaryViewport.height > 0) {
                    console.log(`[Main] Sending viewport pane=secondary requested=${secondaryViewport.width}x${secondaryViewport.height} fitMode=${getEffectiveSecondaryFitMode()} locked=${describeViewport(secondaryViewport)} split=${browserSplitState.active}`);
                    controlSocket.send(JSON.stringify({
                        type: 'viewport',
                        pane: 'secondary',
                        width: secondaryViewport.width,
                        height: secondaryViewport.height,
                        fitMode: getEffectiveSecondaryFitMode(),
                        layoutMode: streamPolicy.layoutMode
                    }));
                }
            }

            console.log(`[Main] Sending viewport pane=primary requested=${primaryViewport.width}x${primaryViewport.height} fitMode=${getEffectivePrimaryFitMode()} locked=${describeViewport(browserSplitState.lockedPrimaryViewport)} split=${browserSplitState.active}`);

            controlSocket.send(JSON.stringify({
                type: 'viewport',
                pane: 'primary',
                width: primaryViewport.width,
                height: primaryViewport.height,
                fitMode: getEffectivePrimaryFitMode(),
                layoutMode: streamPolicy.layoutMode
            }));
        }, 500);
    }

    function connectControl() {
        const wsUrl = `ws://${host}/ws/control`;
        controlSocket = new WebSocket(wsUrl);

        controlSocket.onopen = () => {
            closeInputBubble(true);
            if (touchHandler) touchHandler.destroy();
            const renderer = (decoder && decoder.renderer) ? decoder.renderer : null;
            touchHandler = new TouchHandler(canvas, renderer, controlSocket, 'primary');
            if (SPLIT_STRATEGY === 'dual_stream' && browserSplitState.active && secondaryCanvas) {
                if (secondaryTouchHandler) secondaryTouchHandler.destroy();
                secondaryTouchHandler = new TouchHandler(secondaryCanvas, getActiveSecondaryRenderer(), controlSocket, 'secondary');
            }

            sendViewportSize();

            if (SPLIT_STRATEGY === 'dual_stream' && browserSplitState.active) {
                if (!secondaryVideoSocket || secondaryVideoSocket.readyState === WebSocket.CLOSED) {
                    connectSecondaryVideo();
                }
                setTimeout(() => sendSecondaryLaunchRequest(), 150);
            }

            if (codecMode === 'mjpeg') {
                controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
            }

            if (isLauncherMode) {
                loadLauncherApps();
            }
        };

        controlSocket.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'resolutionChanged') {
                    const pane = msg.pane || 'primary';
                    const lockedViewport = pane === 'secondary'
                        ? browserSplitState.lockedSecondaryViewport
                        : browserSplitState.lockedPrimaryViewport;
                    const fitMode = pane === 'secondary'
                        ? getEffectiveSecondaryFitMode()
                        : getEffectivePrimaryFitMode();
                    console.log(`[Main] Server resolution changed pane=${pane} server=${msg.width}x${msg.height} fitMode=${fitMode} locked=${describeViewport(lockedViewport)} split=${browserSplitState.active}`);
                } else if (msg.type === 'showKeyboard') {
                    if (useBubbleInput) {
                        const anchor = secondaryTouchHandler?.lastTap || touchHandler?.lastTap || null;
                        openInputBubble(anchor);
                    } else focusKeyboardProxy();
                } else if (msg.type === 'hideKeyboard') {
                    if (useBubbleInput) closeInputBubble(true);
                    else blurKeyboardProxy();
                }
            } catch (e) {}
        };

        controlSocket.onclose = () => scheduleReconnect();
    }

    // --- Web Launcher & Split Launcher Code ---
    async function loadLauncherApps() {
        try {
            const response = await fetch('/api/apps');
            if (!response.ok) throw new Error('Network error');
            const data = await response.json();

            const isPremium = data.isPremium || false;
            const apps = data.apps || [];

            applyStreamPolicy({
                isPremium,
                fitMode: data.fitMode || 'contain',
                autoFit: data.autoFit === true,
                layoutMode: data.layoutMode || 'single',
                showAdBanner: data.showAdBanner === true
            });

            renderLauncherApps(apps, isPremium);
            renderSplitLauncherApps(apps, isPremium);
        } catch (err) {
            console.error('[Launcher]', err);
            showLauncherNotice('Failed to load apps. Try refreshing.');
        }
    }

    function renderSplitLauncherApps(apps, isPremium) {
        if (!splitAppList) return;
        splitAppList.innerHTML = '';

        apps.forEach(app => {
            const cell = document.createElement('div');
            cell.className = 'split-app-item';

            const icon = document.createElement('img');
            icon.className = 'split-app-icon';
            icon.src = `/api/icon?pkg=${app.packageName}`;
            cell.appendChild(icon);

            const label = document.createElement('div');
            label.textContent = SPLIT_STRATEGY === 'freeform' ? `${app.label} (Split)` : `${app.label} (Dual Stream)`;
            label.style.color = '#FFD700';
            cell.appendChild(label);

            cell.addEventListener('click', () => {
                if ((app.category !== 'NAVIGATION') && !isPremium) {
                    requestPurchase(`locked:${app.packageName}`);
                    return;
                }

                launchApp(app, true);
                splitDrawer.classList.remove('open');
            });

            splitAppList.appendChild(cell);
        });
    }

    function renderLauncherApps(apps, isPremium) {
        launcherContent.innerHTML = '';
        const grouped = {
            'NAVIGATION': { title: 'Navigation', color: '#4CAF50', items: [] },
            'VIDEO': { title: 'Video', color: '#FF5722', items: [], locked: !isPremium },
            'MUSIC': { title: 'Music', color: '#9C27B0', items: [], locked: !isPremium },
            'OTHER': { title: 'Apps', color: '#9E9E9E', items: [], locked: !isPremium }
        };

        apps.forEach(app => {
            if (grouped[app.category]) grouped[app.category].items.push(app);
            else grouped['OTHER'].items.push(app);
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
                    launchApp(app, false);
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
        window.dispatchEvent(new Event('launcher-ready'));
    }

    function launchApp(app, isSplit = false) {
        const pkgName = app.packageName;
        const componentName = app.componentName || null;
        console.log(`[Launcher] Launching app: ${pkgName} (split=${isSplit})`);

        if (isSplit) {
            if (isLauncherMode) {
                showLauncherNotice('먼저 왼쪽에 실행할 앱을 선택하세요.');
                return;
            }
            if (!isDualStreamCapable(app)) {
                showLauncherNotice('이 앱은 듀얼 스트림을 지원하지 않습니다.');
                return;
            }
            enableBrowserSplit(app);
            return;
        }

        disableBrowserSplit();
        currentPrimaryApp = app;
        isLauncherMode = false;
        webLauncher.classList.add('hidden');
        splitDrawer.style.display = 'flex';
        homeBtn.style.display = 'block';
        firstFrameReceived = false;
        clearLaunchTimeout();

        // Clear the previous app's last frame immediately so it doesn't
        // flash during the transition to the new app
        clearCanvas();

        setTimeout(() => {
            if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                const message = {
                    type: 'launchApp',
                    pkg: pkgName,
                    splitMode: false
                };
                if (componentName) message.componentName = componentName;

                controlSocket.send(JSON.stringify(message));

                if (codecMode === 'mjpeg') {
                    controlSocket.send(JSON.stringify({ type: 'codec', mode: 'mjpeg' }));
                }

                sendViewportSize();
                setStatus('Loading...', '');
                showOverlay();
                launchTimeout = setTimeout(() => {
                    if (firstFrameReceived) return;
                    closeInputBubble(true);
                    isLauncherMode = true;
                    webLauncher.classList.remove('hidden');
                    splitDrawer.style.display = 'none';
                    homeBtn.style.display = 'none';
                    hideOverlay();
                    showLauncherNotice('Launch timed out. Try again.');
                }, 5000);
            }
        }, 50);
    }

    function clearCanvas() {
        try {
            const ctx = canvas.getContext('2d');
            if (ctx) {
                ctx.clearRect(0, 0, canvas.width, canvas.height);
            }
        } catch (e) { /* canvas may be using webgl */ }
        const mseVideo = document.getElementById('mse-video');
        if (mseVideo) mseVideo.style.opacity = '0';
        canvas.style.opacity = '0';
    }

    function goHome() {
        isLauncherMode = true;
        clearLaunchTimeout();
        closeInputBubble(true);
        blurKeyboardProxy();
        disableBrowserSplit();

        // Immediately clear the canvas to prevent previous app's screen
        // from being visible when the launcher is shown
        clearCanvas();

        webLauncher.classList.remove('hidden');
        splitDrawer.style.display = 'none';
        splitDrawer.classList.remove('open');
        homeBtn.style.display = 'none';

        hideOverlay();
        firstFrameReceived = false;

        currentPrimaryApp = null;
        if (controlSocket && controlSocket.readyState === WebSocket.OPEN) {
            controlSocket.send(JSON.stringify({ type: 'goHome' }));
        }
    }

    homeBtn.addEventListener('click', goHome);

    // ── Edge Swipe Handlers for Split Drawer ──
    if (splitHandle) {
        splitHandle.addEventListener('click', () => {
            splitDrawer.classList.toggle('open');
        });

        // Swipe on handle
        let startX = 0;
        splitHandle.addEventListener('touchstart', (e) => {
            startX = e.touches[0].clientX;
        }, {passive: true});

        splitHandle.addEventListener('touchend', (e) => {
            let endX = e.changedTouches[0].clientX;
            if (startX - endX > 15) { // Swiped left
                splitDrawer.classList.add('open');
            } else if (endX - startX > 15) { // Swiped right
                splitDrawer.classList.remove('open');
            }
        }, {passive: true});
    }

    if (splitDrawer) {
        // Swipe on the drawer itself to close it
        let drawerStartX = 0;
        splitDrawer.addEventListener('touchstart', (e) => {
            drawerStartX = e.touches[0].clientX;
        }, {passive: true});

        splitDrawer.addEventListener('touchend', (e) => {
            let endX = e.changedTouches[0].clientX;
            if (endX - drawerStartX > 30) { // Swiped right
                splitDrawer.classList.remove('open');
            }
        }, {passive: true});
    }

    // Split toolbar: show on tap, auto-hide after 3s
    const splitToolbar = document.getElementById('split-pane-toolbar');
    let splitToolbarTimer = null;
    function showSplitToolbar() {
        if (!splitToolbar || !browserSplitState.active) return;
        splitToolbar.classList.add('visible');
        clearTimeout(splitToolbarTimer);
        splitToolbarTimer = setTimeout(() => {
            splitToolbar.classList.remove('visible');
        }, 3000);
    }
    function hideSplitToolbar() {
        splitToolbar?.classList.remove('visible');
        clearTimeout(splitToolbarTimer);
    }
    // Show toolbar on double-tap in the top zone — touch passes through to app
    let lastToolbarTapTime = 0;
    let lastToolbarTapY = 0;
    const TOOLBAR_TAP_ZONE_HEIGHT = 48;
    playerShell?.addEventListener('pointerup', (e) => {
        if (!browserSplitState.active) return;
        if (e.target.closest('#split-pane-toolbar')) return;
        // Only respond to taps in the top zone
        const rect = playerShell.getBoundingClientRect();
        const relY = e.clientY - rect.top;
        if (relY > TOOLBAR_TAP_ZONE_HEIGHT) return;
        const now = Date.now();
        if (now - lastToolbarTapTime < 400 && Math.abs(relY - lastToolbarTapY) < 30) {
            if (splitToolbar?.classList.contains('visible')) {
                hideSplitToolbar();
            } else {
                showSplitToolbar();
            }
            lastToolbarTapTime = 0;
        } else {
            lastToolbarTapTime = now;
            lastToolbarTapY = relY;
        }
    });

    // Split ratio buttons
    document.querySelectorAll('.split-ratio-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const ratio = parseFloat(btn.dataset.ratio);
            if (!ratio || !browserSplitState.active) return;
            document.querySelectorAll('.split-ratio-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            setBrowserSplitRatio(ratio);
            lockBrowserSplitViewports(browserSplitState.app);
            requestAnimationFrame(() => sendViewportSize());
            showSplitToolbar(); // reset auto-hide timer
        });
    });

    if (splitCloseBtn) {
        splitCloseBtn.addEventListener('click', () => {
            hideSplitToolbar();
            disableBrowserSplit();
        });
    }

    // ── Keyboard handling ──
    if (canvas) {
        const maybeFocusKeyboard = () => {
            if (!isLauncherMode && !useBubbleInput) focusKeyboardProxy();
        };
        canvas.addEventListener('pointerup', maybeFocusKeyboard);
        canvas.addEventListener('mouseup', maybeFocusKeyboard);
        canvas.addEventListener('touchend', maybeFocusKeyboard, { passive: true });
    }

    if (adBanner) adBanner.addEventListener('click', () => requestPurchase('banner'));

    const kbInput = document.getElementById('keyboard-input');
    if (kbInput) {
        kbInput.addEventListener('compositionstart', () => {
            if (useBubbleInput) return;
            composing = true;
            skipNextInput = false;
        });
        kbInput.addEventListener('compositionupdate', () => {});
        kbInput.addEventListener('compositionend', (e) => {
            if (useBubbleInput) return;
            const finalText = e.data || kbInput.value || '';
            if (finalText && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'textInput', text: finalText }));
            }
            composing = false;
            skipNextInput = true;
            kbInput.value = '';
        });
        kbInput.addEventListener('input', (e) => {
            if (useBubbleInput || composing) return;
            if (skipNextInput) {
                skipNextInput = false;
                kbInput.value = '';
                return;
            }
            const text = e.data || e.target.value;
            if (text && controlSocket && controlSocket.readyState === WebSocket.OPEN) {
                controlSocket.send(JSON.stringify({ type: 'textInput', text }));
            }
            kbInput.value = '';
        });
        kbInput.addEventListener('keydown', (e) => {
            if (!controlSocket || controlSocket.readyState !== WebSocket.OPEN) return;
            if (e.key === 'Backspace' && !composing) {
                controlSocket.send(JSON.stringify({ type: 'keyEvent', keyCode: 67 }));
                e.preventDefault();
                return;
            }
            if (useBubbleInput) return;
            if (e.key === 'Enter') {
                controlSocket.send(JSON.stringify({ type: 'textInput', text: '\n' }));
                e.preventDefault();
            }
        });
        kbInput.addEventListener('blur', () => {
            kbInput.style.pointerEvents = 'none';
            composing = false;
            skipNextInput = false;
        });
    }

    window.addEventListener('resize', () => {
        if (browserSplitState.active) {
            requestAnimationFrame(() => {
                setBrowserSplitRatio(browserSplitState.ratio);
                lockBrowserSplitViewports(browserSplitState.app);
                sendViewportSize();
            });
            return;
        }
        sendViewportSize();
    });

    try {
        await initDecoder();
        connectVideo();
        connectControl();
    } catch (e) {
        setStatus(e.message, 'error');
        showOverlay();
    }

    audioPlayer = new AudioPlayer();
    const splashScreen = document.getElementById('splash-screen');
    const splashUnmute = document.getElementById('splash-unmute');
    let splashReady = false;

    // Show "Tap to Start" once launcher apps are loaded
    const splashLoading = document.getElementById('splash-loading');
    window.addEventListener('launcher-ready', () => {
        splashReady = true;
        if (splashLoading) splashLoading.classList.add('hidden');
        if (splashUnmute) splashUnmute.classList.add('visible');
    });

    const dismissSplash = async () => {
        if (!splashReady) return; // ignore taps before loading finishes
        if (!audioPlayer.socket || audioPlayer.socket.readyState === WebSocket.CLOSED) {
            await audioPlayer.startFromUserGesture(`ws://${host}/ws/audio`);
        }
        document.removeEventListener('click', dismissSplash);
        document.removeEventListener('touchstart', dismissSplash);
        if (splashScreen) {
            splashScreen.classList.add('hidden');
            setTimeout(() => splashScreen.classList.add('removed'), 500);
        }
    };
    if (splashScreen) {
        splashScreen.addEventListener('click', dismissSplash);
        splashScreen.addEventListener('touchstart', dismissSplash);
    }
    document.addEventListener('click', dismissSplash);
    document.addEventListener('touchstart', dismissSplash);

    const mseVideo = document.getElementById('mse-video');
    if (mseVideo) mseVideo.style.pointerEvents = 'none';
    if (canvas) canvas.style.pointerEvents = 'auto';
    if (secondaryCanvas) secondaryCanvas.style.pointerEvents = 'auto';
});
