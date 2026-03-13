package com.jakarta.mirror.shizuku;

interface IPrivilegedService {
    void destroy() = 16777114;

    /**
     * Create a virtual display with the given parameters.
     * Returns the display ID, or -1 on failure.
     */
    int createVirtualDisplay(int width, int height, int dpi, String name) = 1;

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
}
