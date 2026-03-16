package com.castla.mirror.shizuku;

import android.view.Surface;

interface IPrivilegedService {
    void destroy() = 16777114;

    /**
     * Create a virtual display with the given parameters.
     * Returns the display ID, or -1 on failure.
     */
    int createVirtualDisplay(int width, int height, int dpi, String name) = 1;

    /**
     * Attach a Surface to the virtual display so content renders onto it.
     * Must be called after createVirtualDisplay with the encoder's input surface.
     */
    void setSurface(int displayId, in Surface surface) = 5;

    /**
     * Release the virtual display with the given ID.
     */
    void releaseVirtualDisplay(int displayId) = 2;

    /**
     * Inject an input event at the given coordinates on the specified display.
     * action: MotionEvent action constant (0=DOWN, 1=UP, 2=MOVE)
     */
    void injectInput(int displayId, int action, float x, float y, int pointerId) = 3;

    /**
     * Check if the service is alive.
     */
    boolean isAlive() = 4;

    /**
     * Execute a shell command with elevated (ADB) privileges.
     * Returns the command output (stdout), or empty string on failure.
     */
    String execCommand(String command) = 6;

    /**
     * Add an IP address to a network interface using Android's INetd binder.
     * This goes through netd (runs as root) and is allowed from ADB shell uid.
     * Returns true on success.
     */
    boolean addInterfaceAddress(String ifName, String address, int prefixLength) = 7;

    /**
     * Remove an IP address from a network interface using INetd binder.
     */
    boolean removeInterfaceAddress(String ifName, String address, int prefixLength) = 8;

    /**
     * Run full Tesla network setup: try all methods to add IP alias.
     * Returns a diagnostic log string with results of each method tried.
     */
    String setupTeslaNetworking(String ifName, String virtualIp) = 9;

    /**
     * Restart WiFi tethering with a CGNAT IP (100.64.0.1/24) so Tesla browser
     * can reach the phone. Uses TetheringManager via reflection from Shizuku's
     * elevated process (uid 2000).
     * Returns a diagnostic log string.
     */
    String restartTetheringWithCgnat() = 10;

    /**
     * Launch an app on a specific virtual display.
     * Uses am start --display to place the app on the given display.
     */
    void launchAppOnDisplay(int displayId, String packageName) = 11;

    /**
     * Launch the home screen on a specific virtual display.
     * Uses am start with HOME category.
     */
    void launchHomeOnDisplay(int displayId) = 12;
}
