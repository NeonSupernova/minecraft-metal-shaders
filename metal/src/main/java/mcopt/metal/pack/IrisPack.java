package mcopt.metal.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

/**
 * The shader pack Iris has selected (config/iris.properties), read and patched with Iris's own front end: pack parsing,
 * options, includes and its GLSL transformer, so the GLSL that reaches the Metal translator is exactly what Iris would hand
 * OpenGL. The only class of the pack runtime that names Iris types: loaded only when Iris is installed (PackRuntime).
 * Render thread: Iris's standard defines read the GL version strings, which the GL shim's hidden context answers.
 */
final class IrisPack {
	/** A selected pack: its name and the patched programs of the overworld's program set. */
	record Loaded(String name, List<PackProgram> programs) {
	}

	private IrisPack() {
	}

	/** The selected pack, or empty when Iris has none selected or shaders are off in iris.properties. */
	static Optional<Loaded> load(Path gameDir) throws IOException, ReflectiveOperationException {
		irisConfig(gameDir);
		Properties iris = properties(gameDir.resolve("config").resolve("iris.properties"));
		String name = iris.getProperty("shaderPack", "").trim();
		if (name.isEmpty() || "false".equals(iris.getProperty("enableShaders"))) return Optional.empty();
		Path packs = gameDir.resolve("shaderpacks");
		Path root = packs.resolve(name);
		Map<String, String> changed = new HashMap<>();
		properties(packs.resolve(name + ".txt")).forEach((k, v) -> changed.put(k.toString(), v.toString()));

		FileSystem zip = null;
		try {
			Path shaders;
			boolean isZip = !Files.isDirectory(root) && name.endsWith(".zip");
			if (isZip) {
				zip = FileSystems.newFileSystem(root, IrisPack.class.getClassLoader());
				shaders = shadersDir(zip);
			} else {
				shaders = root.resolve("shaders");
			}
			if (shaders == null || !Files.isDirectory(shaders)) throw new IOException("\"" + name + "\" has no shaders folder");
			ShaderPack pack = new ShaderPack(shaders, changed, StandardMacros.createStandardEnvironmentDefines(), isZip);
			return Optional.of(new Loaded(name, programs(pack.getProgramSet(DimensionId.OVERWORLD))));
		} finally {
			if (zip != null) zip.close();
		}
	}

	/**
	 * Iris's config, which its pack reader consults (debug options in IdMap, ShaderProperties, IncludeGraph). Iris creates it
	 * in onEarlyInitialize, which one of its mixins calls: inert, it never runs, so it is created here the same way.
	 */
	private static void irisConfig(Path gameDir) throws IOException, ReflectiveOperationException {
		if (Iris.getIrisConfig() != null) return;
		Path dir = gameDir.resolve("config");
		IrisConfig config = new IrisConfig(dir.resolve("iris.properties"), dir.resolve("iris-excluded.json"));
		config.initialize();
		java.lang.reflect.Field field = Iris.class.getDeclaredField("irisConfig");
		field.setAccessible(true);
		field.set(null, config);
	}

	/** Iris's search: "shaders" at the zip's root, else the first folder named shaders (Pack-master/shaders). */
	private static Path shadersDir(FileSystem zip) throws IOException {
		Path direct = zip.getPath("shaders");
		if (Files.isDirectory(direct)) return direct;
		try (Stream<Path> walk = Files.walk(zip.getRootDirectories().iterator().next())) {
			return walk.filter(Files::isDirectory).filter(p -> p.endsWith("shaders")).findFirst().orElse(null);
		}
	}

	private static Properties properties(Path file) throws IOException {
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
				p.load(r);
			}
		}
		return p;
	}

	/**
	 * Every program of the set, patched the way Iris's pipeline patches it: terrain, water and the shadow pass through
	 * Sodium's interface, the other gbuffers through the vanilla one, composite-style passes per texture stage, compute
	 * programs on their own. Programs a pack doesn't have are skipped (Iris falls back to a parent program at draw time).
	 */
	private static List<PackProgram> programs(ProgramSet set) {
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textures = set.getPackDirectives().getTextureMap();
		Set<String> overrides = Set.of(); // custom texture names: only needed once custom textures are bound (M4)
		ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, true, true, true);
		List<PackProgram> out = new ArrayList<>();
		for (ProgramId id : ProgramId.values()) {
			if (id.getGroup() == ProgramGroup.Dh) continue; // Distant Horizons programs: not with Metal
			Optional<ProgramSource> found = set.get(id);
			if (found.isEmpty() || !found.get().getName().equals(id.getSourceName())) continue; // absent, or another program's fallback
			ProgramSource s = found.get();
			out.add(PackProgram.lazy(s.getName(), id == ProgramId.Final ? "final" : "gbuffers", () -> {
				if (id == ProgramId.Final) {
					return stages(TransformPatcher.patchComposite(s.getName(), s.getVertexSource().orElse(null), s.getGeometrySource().orElse(null),
						s.getFragmentSource().orElse(null), TextureStage.COMPOSITE_AND_FINAL, textures, overrides));
				}
				AlphaTest alpha = s.getDirectives().getAlphaTestOverride().orElse(AlphaTests.ONE_TENTH_ALPHA);
				if (sodium(id)) {
					return stages(TransformPatcher.patchSodium(s.getName(), s.getVertexSource().orElse(null), s.getGeometrySource().orElse(null),
						s.getTessControlSource().orElse(null), s.getTessEvalSource().orElse(null), s.getFragmentSource().orElse(null),
						alpha, textures, overrides, id.getGroup() == ProgramGroup.Shadow));
				}
				return stages(TransformPatcher.patchVanilla(s.getName(), s.getVertexSource().orElse(null), s.getGeometrySource().orElse(null),
					s.getTessControlSource().orElse(null), s.getTessEvalSource().orElse(null), s.getFragmentSource().orElse(null),
					alpha, id == ProgramId.Line, id == ProgramId.Clouds, true, inputs, textures, overrides));
			}));
		}
		composites(set, ProgramArrayId.Begin, TextureStage.BEGIN, textures, overrides, out);
		composites(set, ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP, textures, overrides, out);
		composites(set, ProgramArrayId.Prepare, TextureStage.PREPARE, textures, overrides, out);
		composites(set, ProgramArrayId.Deferred, TextureStage.DEFERRED, textures, overrides, out);
		composites(set, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, textures, overrides, out);
		compute(set.getSetup(), TextureStage.SETUP, textures, overrides, out);
		for (ProgramArrayId array : ProgramArrayId.values()) {
			for (ComputeSource[] pass : set.getCompute(array)) compute(pass, stage(array), textures, overrides, out);
		}
		compute(set.getShadowCompute(), TextureStage.SHADOWCOMP, textures, overrides, out);
		compute(set.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, textures, overrides, out);
		return out;
	}

	/** The programs Iris draws through Sodium's chunk renderer (ShaderKey's Sodium patch). */
	private static boolean sodium(ProgramId id) {
		return switch (id) {
			case Terrain, TerrainSolid, TerrainCutout, Water, Shadow, ShadowSolid, ShadowCutout, ShadowWater -> true;
			default -> false;
		};
	}

	private static TextureStage stage(ProgramArrayId array) {
		return switch (array) {
			case Setup -> TextureStage.SETUP;
			case Begin -> TextureStage.BEGIN;
			case ShadowComposite -> TextureStage.SHADOWCOMP;
			case Prepare -> TextureStage.PREPARE;
			case Deferred -> TextureStage.DEFERRED;
			case Composite -> TextureStage.COMPOSITE_AND_FINAL;
		};
	}

	private static void composites(ProgramSet set, ProgramArrayId array, TextureStage stage,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textures, Set<String> overrides, List<PackProgram> out) {
		for (ProgramSource s : set.getComposite(array)) {
			if (s == null || !s.isValid()) continue;
			out.add(PackProgram.lazy(s.getName(), "composite", () -> stages(TransformPatcher.patchComposite(s.getName(),
				s.getVertexSource().orElse(null), s.getGeometrySource().orElse(null), s.getFragmentSource().orElse(null), stage, textures, overrides))));
		}
	}

	private static void compute(ComputeSource[] sources, TextureStage stage,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textures, Set<String> overrides, List<PackProgram> out) {
		if (sources == null) return;
		for (ComputeSource s : sources) {
			if (s == null || !s.isValid()) continue;
			out.add(PackProgram.lazy(s.getName(), "compute",
				() -> Map.of(PackProgram.COMPUTE, TransformPatcher.patchCompute(s.getName(), s.getSource().orElse(null), stage, textures, overrides))));
		}
	}

	private static Map<String, String> stages(Map<PatchShaderType, String> patched) {
		Map<String, String> out = new HashMap<>();
		patched.forEach((type, source) -> {
			if (source == null) return;
			String key = switch (type) {
				case VERTEX -> PackProgram.VERTEX;
				case FRAGMENT -> PackProgram.FRAGMENT;
				case GEOMETRY -> PackProgram.GEOMETRY;
				case COMPUTE -> PackProgram.COMPUTE;
				default -> PackProgram.TESSELLATION;
			};
			out.put(key, source);
		});
		return out;
	}
}
