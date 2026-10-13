package mcopt.metal;

/**
 * Frames nobody will see aren't rendered.
 * <p>Hidden window: a game window nobody can see renders at a few frames a second. The mod holds a latency-critical activity
 * (mc_latency_activity), which also keeps macOS's App Nap from slowing a hidden app, so without this a window covered by
 * other windows or left on another Space rendered at full speed: a laptop's fans and battery for frames never shown.
 * Checked every 15 frames (or each frame while hidden); the first visible pixel brings full speed back at the next frame.
 * -Dmcopt.occludedFps=N (default 15; 0 = off).
 * <p>Hot or saving power: with VSync off the pacer presents one frame per refresh and drops the rest, but every one of them
 * was rendered; at 1000+ fps on a 120 Hz panel that is most of the chip's heat. While macOS reports thermal pressure
 * (serious or critical) or Low Power Mode, frames are capped near the display's refresh: the same frames reach the screen,
 * and the power goes to keeping the clocks up instead (the cap sits a fifth above the refresh, so every refresh still gets a new frame). -Dmcopt.powerCap=false turns it off. Render thread only.
 */
final class Occlusion {
	private static final int FPS = Integer.getInteger("mcopt.occludedFps", 15);
	private static final boolean POWER_CAP = Boolean.parseBoolean(System.getProperty("mcopt.powerCap", "true"));
	private static boolean hidden, failed, capped;
	private static int frames;
	private static long last;

	private Occlusion() {
	}

	/** Before a frame is presented: waits out the rest of the frame interval when the window is hidden or the cap is on. */
	static void throttle() {
		if (failed) return;
		boolean check = frames++ % 15 == 0;
		if (POWER_CAP && check) {
			try {
				int state = Native.powerState();
				boolean now = (state & 0xff) >= 2 || (state & 0x100) != 0;
				if (now != capped) Log.info(now ? "[mcopt] " + ((state & 0x100) != 0 ? "Low Power Mode" : "thermal pressure")
					+ ": frames capped just above the display's refresh until it passes (-Dmcopt.powerCap=false)" : "[mcopt] power: frame cap lifted");
				capped = now;
			} catch (Throwable t) {
				failed = true;
				Log.warn("[mcopt] can't read the power state; frames are not capped when the machine is hot", t);
				return;
			}
		}
		if (FPS > 0 && (hidden || check)) {
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
		double hz = capped && !hidden ? Display.refreshHz() : 0;
		// a fifth above the refresh: an unsynchronised cap at exactly the refresh drifts against it and some refreshes would
		// get no new frame; the pacer still presents one per refresh
		int fps = hidden ? FPS : hz > 0 ? (int) Math.round(hz * 1.2) : 0;
		if (fps > 0) {
			long remaining = last + 1_000_000_000L / fps - t;
			if (remaining > 0) FrameWait.sleep(remaining);
			t = System.nanoTime();
		}
		last = t;
	}
}
