package mcopt.metal;

/**
 * A game window nobody can see renders at a few frames a second. The mod holds a latency-critical activity
 * (mc_latency_activity), which also keeps macOS's App Nap from slowing a hidden app, so without this a window covered by
 * other windows or left on another Space rendered at full speed: a laptop's fans and battery for frames never shown.
 * Checked every 15 frames (or each frame while hidden); the first visible pixel brings full speed back at the next frame.
 * -Dmcopt.occludedFps=N (default 15; 0 = off). Render thread only.
 */
final class Occlusion {
	private static final int FPS = Integer.getInteger("mcopt.occludedFps", 15);
	private static boolean hidden, failed;
	private static int frames;
	private static long last;

	private Occlusion() {
	}

	/** Before a frame is presented: waits out the rest of the hidden frame interval when the window can't be seen. */
	static void throttle() {
		if (FPS <= 0 || failed) return;
		if (hidden || frames++ % 15 == 0) {
			try {
				boolean now = Native.windowOccluded();
				if (now != hidden) Log.info(now ? "[mcopt] window hidden: rendering at " + FPS + " fps until it is visible again (-Dmcopt.occludedFps)"
					: "[mcopt] window visible: full frame rate");
				hidden = now;
			} catch (Throwable t) {
				failed = true;
				Log.warn("[mcopt] can't tell whether the window is visible; a hidden window keeps rendering at full speed", t);
				return;
			}
		}
		long t = System.nanoTime();
		if (hidden) {
			long remaining = last + 1_000_000_000L / FPS - t;
			if (remaining > 0) FrameWait.sleep(remaining);
			t = System.nanoTime();
		}
		last = t;
	}
}
