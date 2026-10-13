package mcopt.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * What the game is doing, in the log. At startup: the machine (macOS version and build, chip, performance and
 * efficiency cores, GPU cores, RAM), Java, the Metal device's memory budget, the Sodium version and whether it is a
 * checked one, and the settings that shape the frame. Then a tick line every few seconds (-Dmcopt.log.tick=SECONDS,
 * default 5, 0 turns it off): frame rate and its 1% low, CPU frame time and GPU time per frame as percentiles, how long
 * the render thread sat waiting on the GPU, GPU memory against macOS's budget, Java heap, Sodium's chunk counts, and the
 * thermal state. Thermal and Low Power Mode changes are logged when they happen, not only at the next tick.
 * <p>
 * Render thread only, no allocation per frame: frame and GPU times go into fixed rings, sorted once per tick.
 */
public final class Telemetry {
	/** Seconds between tick lines; 0 = off. */
	public static final int TICK_S = Integer.getInteger("mcopt.log.tick", 5);
	public static final boolean ON = TICK_S > 0;
	private static final int RING = 1 << 13;
	private static final float[] frameMs = new float[RING], gpuMs = new float[RING], scratch = new float[RING];
	private static int frames, gpus;
	private static long lastSubmitNs, tickStartNs, waitNs;
	private static long ctx;
	private static long workingSet;
	private static int power = -1;
	/** GPU memory was over the budget at the last tick: the warning is logged on crossing it, not every tick. */
	private static boolean overBudget;
	/** For the crash report (any thread): the device line and the last tick line. */
	private static volatile String device = "not created", lastTick = "none yet";

	private Telemetry() {
	}

	/** Once, when the Metal device exists: the startup report. */
	static void start(long context, String deviceName) {
		ctx = context;
		device = deviceName;
		try {
			workingSet = Native.workingSet(context);
			Runtime rt = Runtime.getRuntime();
			int p = (int) sysctlLong("hw.perflevel0.physicalcpu"), e = (int) sysctlLong("hw.perflevel1.physicalcpu"), gpu = Profile.gpuCores();
			Log.info(String.format("[mcopt] system: macOS %s (%s), %s (%s%s), %.1f GB RAM; Java %s (%s), heap up to %.1f GB",
				sysctlString("kern.osproductversion"), sysctlString("kern.osversion"), sysctlString("machdep.cpu.brand_string"),
				p > 0 ? p + "P + " + e + "E cores" : Runtime.getRuntime().availableProcessors() + " cores", gpu > 0 ? ", " + gpu + "-core GPU" : "",
				gb(sysctlLong("hw.memsize")), System.getProperty("java.version"), System.getProperty("java.vendor"), gb(rt.maxMemory())));
			String sodium = SodiumCompat.version();
			Log.info(String.format("[mcopt] metal: %s, GPU memory budget %.1f GB (macOS pages beyond it), max buffer %.1f GB; Sodium %s%s; profile %s; "
					+ "shaders %s (preset %s, look %s); tick every %d s (-Dmcopt.log.tick=0 turns it off)",
				deviceName, gb(workingSet), gb(Native.maxBufferLength(context)), sodium.isEmpty() ? "absent" : sodium,
				sodium.isEmpty() || SodiumCompat.CHECKED.contains(sodium) ? "" : " (not a checked version, see the Sodium lines above)",
				Profile.active, Boolean.getBoolean("mcopt.shade") ? "on" : "off",
				System.getProperty("mcopt.shade.preset", "balanced"), System.getProperty("mcopt.shade.look", "epic"), TICK_S));
			power(Native.powerState());
		} catch (Throwable t) {
			Log.warn("[mcopt] telemetry: the startup report is incomplete", t);
		}
	}

	/** A frame was submitted (render thread). */
	static void frame() {
		long now = System.nanoTime();
		if (lastSubmitNs != 0 && frames < RING) frameMs[frames++] = (now - lastSubmitNs) / 1e6f;
		lastSubmitNs = now;
		if (tickStartNs == 0) tickStartNs = now;
		if (now - tickStartNs >= TICK_S * 1_000_000_000L) tick(now);
	}

	/** A frame's command buffer completed: its GPU start and end (seconds, 0 when unknown), and how long the render thread waited for it. */
	static void retired(double gpuStart, double gpuEnd, long waitedNs) {
		if (gpuStart > 0 && gpuEnd > gpuStart && gpus < RING) gpuMs[gpus++] = (float) ((gpuEnd - gpuStart) * 1e3);
		waitNs += waitedNs;
	}

	private static void tick(long now) {
		double seconds = (now - tickStartNs) / 1e9;
		int n = frames;
		try {
			power(Native.powerState());
			if (n > 0) {
				float p50 = pct(frameMs, n, 0.50f), p99 = pct(frameMs, n, 0.99f), worst = pct(frameMs, n, 1f);
				String gpu = gpus > 0 ? String.format("gpu p50 %.1f ms p99 %.1f", pct(gpuMs, gpus, 0.50f), pct(gpuMs, gpus, 0.99f)) : "gpu n/a";
				Runtime rt = Runtime.getRuntime();
				long allocated = Native.deviceAllocated(ctx);
				double hz = Native.refreshHz();
				String line;
				Log.info(line = String.format("[mcopt tick] %.0f fps%s (1%% low %.0f, worst frame %.1f ms) | frame p50 %.1f ms p99 %.1f | %s | render thread waited %.2f ms/frame on the GPU"
						+ " | GPU memory %.2f GB of %.1f GB | heap %.1f/%.1f GB | %s | %s",
					n / seconds, hz > 0 ? String.format(" on a %.0f Hz display", hz) : "", 1000 / Math.max(p99, 0.001f), worst, p50, p99, gpu, waitNs / 1e6 / n,
					gb(allocated), gb(workingSet), gb(rt.totalMemory() - rt.freeMemory()), gb(rt.maxMemory()), chunks(), thermal(power)));
				lastTick = line.substring("[mcopt tick] ".length());
				boolean over = workingSet > 0 && allocated > workingSet;
				if (over && !overBudget) {
					Log.warn(String.format("[mcopt] GPU memory %.2f GB is over macOS's %.1f GB budget: expect stutter from paging; lower the shader preset or render distance",
						gb(allocated), gb(workingSet)));
				} else if (!over && overBudget) {
					Log.info(String.format("[mcopt] GPU memory back under the budget (%.2f GB of %.1f GB)", gb(allocated), gb(workingSet)));
				}
				overBudget = over;
			}
		} catch (Throwable t) {
			Log.warnOnce("telemetry tick", "[mcopt] telemetry: a tick line failed", t);
		}
		frames = gpus = 0;
		waitNs = 0;
		tickStartNs = now;
	}

	/**
	 * The crash report's "Metal Shaders" section: what was rendering and how it was doing, so a report alone is enough to
	 * tell a Metal problem from a mod conflict, a Sodium mismatch, memory pressure or a throttled machine. Any thread; never throws.
	 */
	public static String report() {
		StringBuilder b = new StringBuilder();
		try {
			b.append("\n\t\tDevice: ").append(device).append(String.format(", GPU memory budget %.1f GB", gb(workingSet)));
			b.append("\n\t\tProfile: ").append(Profile.active).append("; shaders ").append(Boolean.getBoolean("mcopt.shade") ? "on" : "off")
				.append(" (preset ").append(System.getProperty("mcopt.shade.preset", "balanced")).append("); frame generation ")
				.append(FrameGen.ENABLED ? "on" : "off");
			b.append("\n\t\tSodium: ").append(SodiumCompat.status());
			String conflicts = MixinConflicts.found();
			b.append("\n\t\tYielded to other mods: ").append(conflicts.isEmpty() ? "none" : conflicts);
			b.append("\n\t\tPower: ").append(power < 0 ? "unknown" : thermal(power) + ((power & 0x100) != 0 ? ", Low Power Mode" : ""));
			b.append("\n\t\tLast tick: ").append(lastTick);
		} catch (Throwable t) {
			b.append("\n\t\t(incomplete: ").append(t).append(')');
		}
		return b.toString();
	}

	/** Logs a thermal or Low Power Mode change as it happens. */
	private static void power(int state) {
		if (state == power) return;
		int was = power;
		power = state;
		int thermal = state & 0xff;
		boolean low = (state & 0x100) != 0;
		if (was < 0) {
			Log.info("[mcopt] power: " + thermal(state) + (low ? ", Low Power Mode on: macOS caps the clocks, expect lower fps" : ""));
			return;
		}
		if (thermal != (was & 0xff)) {
			String line = "[mcopt] power: thermal state " + thermal(was) + " -> " + thermal(state);
			if (thermal >= 2) Log.warn(line + ": macOS is throttling the chip, so fps drops are the machine, not the game");
			else Log.info(line);
		}
		if (low != ((was & 0x100) != 0)) Log.info("[mcopt] power: Low Power Mode " + (low ? "on: macOS caps the clocks, expect lower fps" : "off"));
	}

	private static String thermal(int state) {
		return switch (state & 0xff) {
			case 0 -> "thermal nominal";
			case 1 -> "thermal fair";
			case 2 -> "thermal serious (throttling)";
			case 3 -> "thermal critical (throttling hard)";
			default -> "thermal unknown";
		};
	}

	/** Sodium's own F3 chunk line ("C: visible/total (cull) D: distance"), if a world is loaded. */
	private static String chunks() {
		try {
			var sodium = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
			String s = sodium != null ? sodium.getChunksDebugString() : null;
			return s != null ? "chunks " + s : "no world";
		} catch (LinkageError | RuntimeException e) {
			return "chunks n/a";
		}
	}

	private static float pct(float[] values, int n, float q) {
		System.arraycopy(values, 0, scratch, 0, n);
		Arrays.sort(scratch, 0, n);
		return scratch[Math.clamp((long) Math.ceil(q * n) - 1, 0, n - 1)];
	}

	private static double gb(long bytes) {
		return bytes / (double) (1L << 30);
	}

	// sysctlbyname through FFM: no process spawned.
	private static final MethodHandle SYSCTL = Linker.nativeLinker().downcallHandle(Linker.nativeLinker().defaultLookup().find("sysctlbyname").orElseThrow(),
		FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

	private static String sysctlString(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment len = arena.allocate(ValueLayout.JAVA_LONG);
			MemorySegment buf = arena.allocate(256);
			len.set(ValueLayout.JAVA_LONG, 0, 256);
			int rc = (int) SYSCTL.invokeExact(arena.allocateFrom(name), buf, len, MemorySegment.NULL, 0L);
			return rc == 0 ? buf.getString(0) : "?";
		} catch (Throwable t) {
			return "?";
		}
	}

	private static long sysctlLong(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment len = arena.allocate(ValueLayout.JAVA_LONG);
			MemorySegment buf = arena.allocate(8);
			len.set(ValueLayout.JAVA_LONG, 0, 8);
			int rc = (int) SYSCTL.invokeExact(arena.allocateFrom(name), buf, len, MemorySegment.NULL, 0L);
			if (rc != 0) return -1;
			long size = len.get(ValueLayout.JAVA_LONG, 0);
			return size == 4 ? buf.get(ValueLayout.JAVA_INT, 0) : buf.get(ValueLayout.JAVA_LONG, 0);
		} catch (Throwable t) {
			return -1;
		}
	}
}
