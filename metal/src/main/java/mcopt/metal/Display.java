package mcopt.metal;

/**
 * The refresh rate the game's frames are shown at: the display the window is on (mcmetal.m mc_refresh_hz), so a window
 * moved from a 120 Hz panel to a 60 Hz monitor is seen within a second. Render thread only (the native side asks AppKit,
 * and the render thread is the main thread on macOS).
 */
public final class Display {
	private static double hz;
	private static int frames;

	private Display() {
	}

	/** Hz, 0 while unknown (no window yet). Re-read every 60 calls: one call per frame is enough. */
	public static double refreshHz() {
		if (frames++ % 60 == 0) {
			try {
				double now = Native.refreshHz();
				if (Math.abs(now - hz) >= 1 && now > 0) {
					if (hz > 0) Log.info(String.format("[mcopt] display: refresh %.0f Hz -> %.0f Hz", hz, now));
					hz = now;
				}
			} catch (Throwable t) {
				Log.warnOnce("refresh", "[mcopt] display: can't read the refresh rate; dynamic resolution keeps the preset's target", t);
			}
		}
		return hz;
	}
}
