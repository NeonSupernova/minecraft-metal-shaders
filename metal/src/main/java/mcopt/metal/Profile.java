package mcopt.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Flag profiles: one place that turns on a set of -Dmcopt.* switches without editing the launcher's JVM arguments.
 * <ul>
 * <li>{@code -Dmcopt.profile=NAME}, or {@code profile=NAME} in {@code <game dir>/config/mcopt.properties}, applies
 * the jar's {@code /mcopt/profiles/NAME.properties} (e.g. {@code recommended}: the measured, accepted wins).</li>
 * <li>Any {@code mcopt.*} key in config/mcopt.properties is applied too, and wins over the profile's value.</li>
 * <li>A flag given on the command line wins over both: a key is only set when System.getProperty(key) is still null.</li>
 * </ul>
 * With no profile named anywhere, {@code alpha} applies (the perf-only set); {@code profile=none} applies no profile,
 * exactly the old no-profile behaviour. If config/mcopt.properties is absent it is written with the effective profile ({@code alpha}, or what -Dmcopt.profile names)
 * and comment lines on how to turn it off and how to try far terrain. For {@code alpha} on the small tier (GPU under
 * 10 cores, or 8 GB of RAM or less, or either unreadable) the keys in {@link #SMALL_OUT} are left out. It runs first in every mixin config plugin
 * and in the preLaunch entrypoint, before any of our classes read a flag; the first call does the work, later calls
 * return at once.
 */
public final class Profile {
	private static boolean applied;
	/** Alpha keys left out on the small tier: the 4096-entry clone cache costs memory an 8 GB / small-GPU Mac lacks. */
	static final java.util.List<String> SMALL_OUT = java.util.List.of("mcopt.chunk.clones", "mcopt.chunk.clonesCleanup");
	static final String DEFAULT = "alpha";

	private Profile() {
	}

	public static synchronized void apply() {
		if (applied) return;
		applied = true;
		applyFlags();
		distantHorizons();
	}

	/**
	 * Distant Horizons. DH 3.3.x on 26.3 draws its LODs through the game's renderpearl API, which mcopt runs on Metal, so
	 * with a tested DH version ({@link DhCompat#range()}) the Metal backend stays on and the compat mixin makes DH treat it
	 * like Vulkan (mcopt PR #4 by CazzaCoder). It falls back to OpenGL (exactly as -Dmcopt.metal=false) when:
	 * <ul>
	 * <li>Iris is loaded too: DH then forces its raw-OpenGL engine, which can't run on Metal;</li>
	 * <li>DH's own config asks for that engine ({@code renderingEngine = "OPEN_GL"} in config/DistantHorizons.toml);</li>
	 * <li>DH's version is outside the tested range: the compat mixin isn't applied, and DH would cast the game's textures
	 * to OpenGL's (ClassCastException MetalTexture -> GlTexture in its Lightmap mixin).</li>
	 * </ul>
	 * An explicit mcopt.metal (command line or config/mcopt.properties, already applied above) wins. mcopt's own far
	 * terrain (mcopt.lod) is always turned off with DH: both would draw far terrain and both hook the fog.
	 */
	private static void distantHorizons() {
		if (!DhCompat.loaded()) return;
		String version = DhCompat.version();
		if (Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null) {
			System.out.println("[mcopt] Distant Horizons " + version + " detected: mcopt.lod (far terrain) turned off, DH draws the far terrain");
		}
		System.setProperty("mcopt.lod", "false");
		System.clearProperty("mcopt.lod.radius");
		String gl = null;
		if (DhCompat.irisLoaded()) gl = "Iris is loaded (DH then uses its OpenGL engine)";
		else if ("OPEN_GL".equals(DhCompat.renderingEngine(gameDir()))) gl = "its renderingEngine is OPEN_GL in config/DistantHorizons.toml";
		else if (!DhCompat.versionSupported()) gl = "only DH " + DhCompat.range() + " is tested on Metal (-Dmcopt.dh.anyVersion=true tries it anyway)";
		String explicit = System.getProperty("mcopt.metal");
		if (explicit != null) {
			// In each of the gl cases DH crashes on the Metal backend (GL engine, or the compat mixin left out), so say so.
			System.out.println("[mcopt] Distant Horizons " + version + " detected: mcopt.metal=" + explicit + " (set explicitly)"
				+ (gl != null && Boolean.parseBoolean(explicit) ? "; WARNING: DH will likely crash on Metal here: " + gl : ""));
			if (gl == null && Boolean.parseBoolean(explicit)) vanillaFade();
			return;
		}
		if (gl != null) {
			System.setProperty("mcopt.metal", "false");
			// No "-Dmcopt.metal=true overrides" hint: in every one of these cases DH would crash on Metal.
			System.out.println("[mcopt] Distant Horizons " + version + " detected, using OpenGL: " + gl + "; mcopt's other optimizations stay on");
		} else {
			System.out.println("[mcopt] Distant Horizons " + version + " detected: staying on Metal (DH's Blaze3D renderer runs on mcopt's backend; -Dmcopt.metal=false for OpenGL)");
			vanillaFade();
		}
	}

	/**
	 * With the shaders on (mcopt.shade) and DH on Metal: DH's vanilla fade DOUBLE_PASS (its default) out of its config file
	 * before DH reads it ({@link DhCompat#fixVanillaFade}). -Dmcopt.shade.dh.fixFade=false, or an explicit mcopt.shade.dh.fade
	 * (the bench's A/B of the fade modes), leaves the file alone.
	 */
	private static void vanillaFade() {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.shade")) || "false".equals(System.getProperty("mcopt.shade.dh.fixFade"))
			|| System.getProperty("mcopt.shade.dh.fade") != null) return;
		String done = DhCompat.fixVanillaFade(gameDir());
		if (done != null) System.out.println("[mcopt] Distant Horizons: " + done);
	}

	private static void applyFlags() {
		Properties file = new Properties();
		Path cfg = gameDir().resolve("config").resolve("mcopt.properties");
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				file.load(r);
			} catch (IOException e) {
				Log.warn("[mcopt] profile: can't read " + cfg, e);
			}
		}
		String name = System.getProperty("mcopt.profile", file.getProperty("profile", "")).trim();
		if (name.isEmpty()) name = DEFAULT;
		if (!Files.exists(cfg)) { // first launch: record the effective profile (none stays none) and the shaders' defaults, applied now too
			writeDefault(cfg, name);
			if (Files.isRegularFile(cfg)) {
				try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
					file.load(r);
				} catch (IOException e) {
					Log.warn("[mcopt] profile: can't read " + cfg, e);
				}
			}
		}
		Map<String, String> flags = new TreeMap<>();
		if (!name.isEmpty() && !name.equals("none")) {
			Properties p = new Properties();
			try (InputStream in = Profile.class.getResourceAsStream("/mcopt/profiles/" + name + ".properties")) {
				if (in == null) {
					Log.warn("[mcopt] profile: no profile named '" + name + "' (from profile= in config/mcopt.properties or -Dmcopt.profile); use alpha, recommended or none. No profile applied");
				} else {
					p.load(in);
				}
			} catch (IOException e) {
				Log.warn("[mcopt] profile: can't read profile '" + name + "' from the mod jar; no profile applied", e);
			}
			p.stringPropertyNames().forEach(k -> flags.put(k, p.getProperty(k).trim()));
			if (name.equals("alpha")) tier(flags);
		}
		file.stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).forEach(k -> flags.put(k, file.getProperty(k).trim()));
		if (flags.isEmpty()) return;
		StringBuilder set = new StringBuilder(), kept = new StringBuilder();
		flags.forEach((k, v) -> {
			if (System.getProperty(k) == null) {
				System.setProperty(k, v);
				set.append(' ').append(k).append('=').append(v);
			} else {
				kept.append(' ').append(k).append('=').append(System.getProperty(k));
			}
		});
		System.out.println("[mcopt] profile " + (name.isEmpty() ? "(config only)" : name) + ": set" + set
			+ (kept.isEmpty() ? "" : "; kept from the command line:" + kept));
	}

	/** Small tier: GPU cores < 10 or RAM <= 8 GB, or either unreadable. Logs the tier and drops {@link #SMALL_OUT}. */
	private static void tier(Map<String, String> flags) {
		int cores = gpuCores();
		long mem = memBytes();
		boolean small = cores < 10 || mem <= 8L << 30;
		StringBuilder out = new StringBuilder();
		if (small) for (String k : SMALL_OUT) if (flags.remove(k) != null) out.append(' ').append(k);
		System.out.println("[mcopt] profile alpha: tier " + (small ? "small" : "full") + " (gpu cores "
			+ (cores < 0 ? "unknown" : cores) + ", ram " + (mem < 0 ? "unknown" : String.format("%.1f GB", mem / (double) (1L << 30))) + ")"
			+ (out.isEmpty() ? "" : "; left out:" + out));
	}

	private static int gpuCores() {
		String s = run("/usr/sbin/ioreg", "-rd1", "-c", "AGXAccelerator");
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(s);
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	private static long memBytes() {
		try {
			return Long.parseLong(run("/usr/sbin/sysctl", "-n", "hw.memsize").trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
			byte[] b = p.getInputStream().readAllBytes();
			p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static void writeDefault(Path cfg, String name) {
		String text = """
			# mcopt settings. Written on first launch; edit freely.
			#
			# profile=alpha: measured vanilla performance options (chunk meshing, render lists, startup), the default.
			# To turn it off:  profile=none
			profile=%s
			#
			# Metal Shaders (mcopt-shade): lighting, shadows, sky, clouds, water, god rays. false = mcopt's plain Metal renderer.
			mcopt.shade=true
			# Quality preset: performance, balanced, quality or ultra.
			mcopt.shade.preset=balanced
			# Art style: epic, storybook or natural.
			mcopt.shade.look=epic
			#
			# Far terrain (EXPERIMENTAL, off by default; for Macs with 10 or more GPU cores): remove the # below.
			#mcopt.lod=true
			""".formatted(name);
		try {
			Files.createDirectories(cfg.getParent());
			Files.writeString(cfg, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			System.out.println("[mcopt] profile: wrote " + cfg + " (profile=" + name + ")");
		} catch (IOException e) {
			Log.warn("[mcopt] profile: can't write " + cfg, e);
		}
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}
}
