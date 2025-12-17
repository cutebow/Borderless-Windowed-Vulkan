package me.cutebow.borderless_windowed_vulkan;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.util.Window;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

public final class BorderlessWindowedVulkanWindowController {
    private enum Mode {
        NORMAL,
        BORDERLESS_FULLSCREEN
    }

    private static boolean saved;
    private static int savedX;
    private static int savedY;
    private static int savedW;
    private static int savedH;

    private static Mode appliedMode = Mode.NORMAL;
    private static Mode pendingMode = Mode.NORMAL;

    private static boolean lastEnabled;
    private static boolean lastFullscreenOption;

    private static long transitionUntilNanos;
    private static long pendingSinceNanos;

    private static boolean lastFocused = true;

    private BorderlessWindowedVulkanWindowController() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null) return;

        Window window = client.getWindow();
        if (window == null) return;

        long handle = window.getHandle();
        if (handle == 0L) return;

        BorderlessWindowedVulkanConfig cfg = BorderlessWindowedVulkanConfig.get();
        GameOptions options = client.options;

        boolean fullscreenOption = options != null && options.getFullscreen().getValue();
        Mode desired = computeMode(cfg, fullscreenOption);

        long now = System.nanoTime();

        boolean focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (!focused) {
            if (appliedMode == Mode.BORDERLESS_FULLSCREEN) {
                setAttrib(handle, GLFW.GLFW_FLOATING, false);
            }
            lastFocused = false;
            lastEnabled = cfg.enabled;
            lastFullscreenOption = fullscreenOption;
            return;
        } else if (!lastFocused) {
            pendingSinceNanos = now;
            transitionUntilNanos = 0L;
            lastFocused = true;
        }

        boolean enabledChanged = cfg.enabled != lastEnabled;
        boolean fullscreenChanged = fullscreenOption != lastFullscreenOption;

        lastEnabled = cfg.enabled;
        lastFullscreenOption = fullscreenOption;

        if (enabledChanged || fullscreenChanged) {
            pendingSinceNanos = now;
            transitionUntilNanos = 0L;
        }

        if (desired != pendingMode) {
            pendingMode = desired;
            pendingSinceNanos = now;
        }

        if (pendingMode != appliedMode) {
            long stableFor = now - pendingSinceNanos;
            if (stableFor >= 120_000_000L && now >= transitionUntilNanos) {
                applyMode(handle, pendingMode, true);
                appliedMode = pendingMode;
                transitionUntilNanos = now + 350_000_000L;
            } else if (stableFor >= 900_000_000L) {
                transitionUntilNanos = 0L;
                applyMode(handle, pendingMode, true);
                appliedMode = pendingMode;
                transitionUntilNanos = now + 350_000_000L;
            }
        }

        if (appliedMode == Mode.BORDERLESS_FULLSCREEN) {
            enforceBorderlessFullscreen(handle, true);
        } else {
            enforceNormalSanity(handle, cfg.enabled, fullscreenOption, true);
        }

        if (cfg.enabled && appliedMode == Mode.NORMAL && !fullscreenOption) {
            updateSavedBounds(handle);
        }
    }

    private static Mode computeMode(BorderlessWindowedVulkanConfig cfg, boolean fullscreenOption) {
        if (!cfg.enabled) return Mode.NORMAL;
        return fullscreenOption ? Mode.BORDERLESS_FULLSCREEN : Mode.NORMAL;
    }

    private static void applyMode(long handle, Mode mode, boolean allowFocusSteal) {
        GLFW.glfwRestoreWindow(handle);

        if (mode == Mode.BORDERLESS_FULLSCREEN) {
            if (!saved) saveBoundsIfSane(handle);

            long monitor = findBestMonitor(handle);
            if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

            int mx;
            int my;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                GLFW.glfwGetMonitorPos(monitor, x, y);
                mx = x.get(0);
                my = y.get(0);
            }

            GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
            if (vm == null) return;

            int mw = vm.width();
            int mh = vm.height();

            setAttrib(handle, GLFW.GLFW_DECORATED, false);
            setAttrib(handle, GLFW.GLFW_FLOATING, true);
            setAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, false);

            GLFW.glfwSetWindowMonitor(handle, 0L, mx, my, mw, mh, GLFW.GLFW_DONT_CARE);

            if (allowFocusSteal) {
                GLFW.glfwFocusWindow(handle);
            }
            return;
        }

        setAttrib(handle, GLFW.GLFW_FLOATING, false);
        setAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, true);
        setAttrib(handle, GLFW.GLFW_DECORATED, true);

        restoreToSavedOrDefault(handle);

        setAttrib(handle, GLFW.GLFW_DECORATED, true);
        setAttrib(handle, GLFW.GLFW_FLOATING, false);
        setAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, true);

        clampToMonitor(handle);

        if (allowFocusSteal) {
            GLFW.glfwFocusWindow(handle);
        }
    }

    private static boolean enforceBorderlessFullscreen(long handle, boolean allowFloating) {
        long monitor = findBestMonitor(handle);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

        int mx;
        int my;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return false;

        int mw = vm.width();
        int mh = vm.height();

        int wx;
        int wy;
        int ww;
        int wh;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = w.get(0);
            wh = h.get(0);
        }

        boolean fixed = false;

        if (wx != mx || wy != my || ww != mw || wh != mh) {
            GLFW.glfwSetWindowMonitor(handle, 0L, mx, my, mw, mh, GLFW.GLFW_DONT_CARE);
            fixed = true;
        }

        fixed |= setAttribChanged(handle, GLFW.GLFW_DECORATED, false);
        fixed |= setAttribChanged(handle, GLFW.GLFW_FLOATING, allowFloating);
        fixed |= setAttribChanged(handle, GLFW.GLFW_AUTO_ICONIFY, false);

        return fixed;
    }

    private static boolean enforceNormalSanity(long handle, boolean enabled, boolean fullscreenOption, boolean allowClamp) {
        boolean fixed = false;

        if (!enabled) {
            fixed |= setAttribChanged(handle, GLFW.GLFW_FLOATING, false);
            fixed |= setAttribChanged(handle, GLFW.GLFW_DECORATED, true);
            fixed |= setAttribChanged(handle, GLFW.GLFW_AUTO_ICONIFY, true);
            if (allowClamp) clampToMonitor(handle);
            return fixed;
        }

        if (fullscreenOption) return false;

        fixed |= setAttribChanged(handle, GLFW.GLFW_FLOATING, false);
        fixed |= setAttribChanged(handle, GLFW.GLFW_DECORATED, true);
        fixed |= setAttribChanged(handle, GLFW.GLFW_AUTO_ICONIFY, true);

        if (looksLikeStuckFullscreenOrBorderless(handle)) {
            restoreToSavedOrDefault(handle);
            fixed = true;
        }

        if (allowClamp) clampToMonitor(handle);
        return fixed;
    }

    private static boolean looksLikeStuckFullscreenOrBorderless(long handle) {
        long monitor = findBestMonitor(handle);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

        int mx;
        int my;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return false;

        int mw = vm.width();
        int mh = vm.height();

        int wx;
        int wy;
        int ww;
        int wh;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = w.get(0);
            wh = h.get(0);
        }

        boolean decorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE;
        boolean floating = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FLOATING) == GLFW.GLFW_TRUE;

        boolean looksFullscreenRect = wx == mx && wy == my && ww == mw && wh == mh;
        if (looksFullscreenRect) return true;
        return (!decorated || floating) && ww >= mw - 2 && wh >= mh - 2 && wx == mx && wy == my;
    }

    private static void restoreToSavedOrDefault(long handle) {
        long monitor = findBestMonitor(handle);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

        int mx;
        int my;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return;

        int mw = vm.width();
        int mh = vm.height();

        int rx;
        int ry;
        int rw;
        int rh;

        if (saved && isSaneSaved(mw, mh)) {
            rx = savedX;
            ry = savedY;
            rw = savedW;
            rh = savedH;
        } else {
            rw = Math.min(1280, Math.max(640, mw - 200));
            rh = Math.min(720, Math.max(480, mh - 200));
            rx = mx + (mw - rw) / 2;
            ry = my + (mh - rh) / 2;
        }

        GLFW.glfwSetWindowMonitor(handle, 0L, rx, ry, rw, rh, GLFW.GLFW_DONT_CARE);
        GLFW.glfwSetWindowPos(handle, rx, ry);
        GLFW.glfwSetWindowSize(handle, rw, rh);
        GLFW.glfwRestoreWindow(handle);
    }

    private static boolean isSaneSaved(int mw, int mh) {
        if (savedW <= 1 || savedH <= 1) return false;
        if (savedW > mw || savedH > mh) return false;
        if (savedW >= mw - 1 && savedH >= mh - 1) return false;
        return true;
    }

    private static void clampToMonitor(long handle) {
        long monitor = findBestMonitor(handle);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

        int mx;
        int my;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return;

        int aw = vm.width();
        int ah = vm.height();

        int wx;
        int wy;
        int ww;
        int wh;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = Math.max(1, w.get(0));
            wh = Math.max(1, h.get(0));
        }

        int newW = Math.min(ww, Math.max(1, aw));
        int newH = Math.min(wh, Math.max(1, ah));

        int minX = mx;
        int minY = my;
        int maxX = mx + aw - newW;
        int maxY = my + ah - newH;

        int newX = clamp(wx, minX, maxX);
        int newY = clamp(wy, minY, maxY);

        if (newW != ww || newH != wh) {
            GLFW.glfwSetWindowSize(handle, newW, newH);
        }
        if (newX != wx || newY != wy) {
            GLFW.glfwSetWindowPos(handle, newX, newY);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static void setAttrib(long handle, int attrib, boolean enabled) {
        int want = enabled ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE;
        int cur = GLFW.glfwGetWindowAttrib(handle, attrib);
        if (cur != want) {
            GLFW.glfwSetWindowAttrib(handle, attrib, want);
        }
    }

    private static boolean setAttribChanged(long handle, int attrib, boolean enabled) {
        int want = enabled ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE;
        int cur = GLFW.glfwGetWindowAttrib(handle, attrib);
        if (cur != want) {
            GLFW.glfwSetWindowAttrib(handle, attrib, want);
            return true;
        }
        return false;
    }

    private static void saveBoundsIfSane(long handle) {
        long monitor = findBestMonitor(handle);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();

        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) return;

        int mw = vm.width();
        int mh = vm.height();

        int wx;
        int wy;
        int ww;
        int wh;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = Math.max(1, w.get(0));
            wh = Math.max(1, h.get(0));
        }

        boolean decorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE;
        if (!decorated) return;

        if (ww >= mw - 1 && wh >= mh - 1) return;

        savedX = wx;
        savedY = wy;
        savedW = ww;
        savedH = wh;
        saved = true;
    }

    private static void updateSavedBounds(long handle) {
        int wx;
        int wy;
        int ww;
        int wh;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = Math.max(1, w.get(0));
            wh = Math.max(1, h.get(0));
        }

        boolean decorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE;
        if (!decorated) return;

        savedX = wx;
        savedY = wy;
        savedW = ww;
        savedH = wh;
        saved = true;
    }

    private static long findBestMonitor(long handle) {
        int wx;
        int wy;
        int ww;
        int wh;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, w, h);
            wx = x.get(0);
            wy = y.get(0);
            ww = w.get(0);
            wh = h.get(0);
        }

        int cx = wx + Math.max(0, ww) / 2;
        int cy = wy + Math.max(0, wh) / 2;

        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) return 0L;

        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode == null) continue;

            int mx;
            int my;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                GLFW.glfwGetMonitorPos(monitor, x, y);
                mx = x.get(0);
                my = y.get(0);
            }

            int mw = mode.width();
            int mh = mode.height();

            if (cx >= mx && cx < mx + mw && cy >= my && cy < my + mh) {
                return monitor;
            }
        }

        return 0L;
    }
}
