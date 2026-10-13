package mcopt.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps an exception thrown by -[NSWindow setStyleMask:] from aborting the game (mcmetal.m, "window style guard"). SDL
 * calls it when the window leaves exclusive fullscreen, and on macOS 27.2 it threw there twice on one rig (at quit, and on
 * a fullscreen change in Video Settings). Caught exceptions are logged here with AppKit's reason.
 * -Dmcopt.metal.styleGuard=false leaves NSWindow alone.
 */
final class StyleGuard {
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.metal.styleGuard", "true"));
	private static boolean installed;
	private static int reported;

	private StyleGuard() {
	}

	/** Render thread (the main thread on macOS), before SDL can change the window's style. */
	static void install() {
		if (!ENABLED || installed) return;
		try {
			installed = Native.styleGuard();
		} catch (Throwable t) {
			Log.warnOnce("style guard", "[mcopt] can't guard the window's style changes; leaving exclusive fullscreen may crash on macOS 27", t);
		}
	}

	/** Logs a setStyleMask: exception caught since the last call. */
	static void report() {
		if (!installed) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long buf = stack.nmalloc(1, 512);
			int n = Native.styleGuardError(buf, 512);
			if (n == reported) return;
			reported = n;
			Log.warn("[mcopt] macOS refused a window style change (leaving exclusive fullscreen?); the game kept running with the window as it was: "
				+ MemoryUtil.memUTF8(buf) + ". If the window looks wrong, toggle fullscreen again or use borderless fullscreen.");
		} catch (Throwable t) {
			installed = false;
		}
	}
}
