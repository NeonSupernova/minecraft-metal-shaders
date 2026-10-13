package mcopt.metal.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mcopt.metal.Log;
import mcopt.metal.MetalBridge;
import mcopt.metal.Profile;

/**
 * The shader pack runtime: Iris shader packs (Photon, BSL, Complementary, ...) on the Metal renderer, with Iris installed
 * for its front end and mcopt drawing. Experimental, -Dmcopt.pack=true (docs/pack-runtime.md has the plan).
 *
 * <p>M1, this build: the pack check. At startup the pack Iris has selected is read and every program is patched by
 * Iris, translated to MSL and compiled by Metal, off the render thread; the log gets one line with the counts and one
 * per failure, and logs/mcopt-pack/ the GLSL, MSL and error of each failing stage. Nothing is drawn with the pack yet:
 * the game renders as without it (mcopt's own shaders if on).
 */
public final class PackRuntime {
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.pack", "false"));
	private static boolean started;

	private PackRuntime() {
	}

	/** Render thread, once the Metal device and window surface exist (MetalSurface). */
	public static void start(long ctx) {
		if (!ENABLED || started) return;
		started = true;
		if (!Profile.irisInert()) {
			Log.info("[mcopt] pack: mcopt.pack is on but Iris isn't installed (or couldn't be switched to its inert mode); nothing to load");
			return;
		}
		Path gameDir = gameDir();
		IrisPack.Loaded pack;
		try {
			Optional<IrisPack.Loaded> loaded = IrisPack.load(gameDir);
			if (loaded.isEmpty()) {
				Log.info("[mcopt] pack: no shader pack selected (shaderPack= in config/iris.properties, or enableShaders=false)");
				return;
			}
			pack = loaded.get();
		} catch (Throwable t) {
			Log.warn("[mcopt] pack: can't read the selected shader pack", t);
			return;
		}
		Thread worker = new Thread(() -> check(ctx, pack, gameDir.resolve("logs").resolve("mcopt-pack")), "mcopt pack check");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}

	/** Patches, translates and compiles every stage of every program; logs the counts and the failures. */
	private static void check(long ctx, IrisPack.Loaded pack, Path dumpDir) {
		long t0 = System.nanoTime();
		int programs = 0, stages = 0, compiled = 0, unsupported = 0;
		List<String> failures = new ArrayList<>();
		dumpDir = clean(dumpDir);
		try (MslTranslator translator = new MslTranslator()) {
			for (PackProgram program : pack.programs()) {
				programs++;
				Map<String, String> glsl;
				try {
					glsl = program.patch();
				} catch (Throwable t) {
					failures.add(program.name() + ": Iris patch: " + message(t));
					continue;
				}
				for (Map.Entry<String, String> stage : glsl.entrySet()) {
					stages++;
					String key = stage.getKey();
					if (key.equals(PackProgram.GEOMETRY) || key.equals(PackProgram.TESSELLATION)) {
						unsupported++;
						failures.add(program.name() + "." + key + ": Metal has no " + key + " shaders (not supported yet)");
						continue;
					}
					MslTranslator.Msl msl = null;
					try {
						msl = translator.translate(program.name(), key, stage.getValue());
						MetalBridge.release(MetalBridge.compileLibrary(ctx, msl.source()));
						compiled++;
					} catch (Throwable t) {
						String why = msl == null ? message(t) : "Metal compile: " + PackText.firstLines(message(t), 6);
						failures.add(program.name() + "." + key + ": " + why);
						dump(dumpDir, program.name() + "." + key, stage.getValue(), msl == null ? null : msl.source(), why);
					}
				}
			}
		} catch (Throwable t) {
			Log.warn("[mcopt] pack: the translator couldn't start (shaderc or SPIRV-Cross missing?)", t);
			return;
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		Log.info(String.format("[mcopt] pack: \"%s\": %d programs, %d of %d shader stages compiled for Metal (%d need geometry/tessellation), %d failed, %.1f s%s",
			pack.name(), programs, compiled, stages, unsupported, stages - compiled - unsupported, ms / 1000.0,
			failures.isEmpty() ? "" : "; details in " + dumpDir));
		int shown = 0;
		for (String f : failures) {
			if (shown++ == 40) {
				Log.warn("[mcopt] pack:   ... " + (failures.size() - 40) + " more");
				break;
			}
			Log.warn("[mcopt] pack:   " + f);
		}
	}

	private static String message(Throwable t) {
		String m = t.getMessage();
		return t.getClass().getSimpleName() + (m == null ? "" : ": " + m.strip());
	}

	/** An empty dump folder (the last check's files removed); null if it can't be made. */
	private static Path clean(Path dir) {
		try {
			if (Files.isDirectory(dir)) {
				try (var files = Files.list(dir)) {
					for (Path f : (Iterable<Path>) files::iterator) Files.deleteIfExists(f);
				}
			}
			Files.createDirectories(dir);
			return dir;
		} catch (IOException e) {
			return null;
		}
	}

	private static void dump(Path dir, String name, String glsl, String msl, String error) {
		if (dir == null) return;
		String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
		try {
			Files.writeString(dir.resolve(safe + ".glsl"), glsl);
			if (msl != null) Files.writeString(dir.resolve(safe + ".metal"), msl);
			Files.writeString(dir.resolve(safe + ".txt"), error + "\n");
		} catch (IOException e) {
			// a missing dump only costs detail
		}
	}
}
