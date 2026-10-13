package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * A pipeline is the frontend's SPIR-V run through SPIRV-Cross into Metal Shading Language, compiled by Metal.
 * Resource slots are fixed: uniform i lives in buffer/texture/sampler slot i, push constants in slot 26, vertex
 * buffers from slot 27. Vertex Y is flipped so render targets keep the same row order as OpenGL and Vulkan.
 */
final class MetalPipeline implements BackendRenderPipeline {
	/** Metal bakes the depth attachment format into the pipeline, so like Vulkan there's one for passes with depth and one without. */
	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	/** Vanilla's cloud pipelines: their draws may be culled (MetalTerrain.cullClouds) or replaced (ShadeClouds). Set once, not compared per draw. */
	final boolean clouds;
	/** MTLCompareFunction of depthState, for MetalProbe. */
	final int depthCompare;
	/** Sodium solid/cutout terrain only, with MetalTerrain on: the same pipeline fetching its own vertices; 0 otherwise. */
	final long pulled;
	private final MetalEncoder encoder;
	private boolean closed;
	/**
	 * What the pipeline was built from, kept (only when RETAIN) so variant() can build the same shaders for other attachment
	 * formats (an HDR target, a G-buffer) without the frontend: the create info, both stages' final MSL and entry points,
	 * and the pulled twin's vertex MSL (null without one). Upstream drops all of these after compiling.
	 */
	private final @Nullable CreateInfo info;
	private final @Nullable String vertexMsl, fragmentMsl, pulledMsl, vertexEntry, fragmentEntry;
	/** Whether the registry replaced the vertex / fragment stage (told to MetalShaderRegistry's format adapter). */
	private final boolean registryVertex, registryFragment;
	/** The RGBA8 color target count was 1: what format redirects (one color) can draw with. */
	private final int colorTargets;
	/** variant() PSOs by key, released with the pipeline. */
	private final Map<String, Long> variants = new java.util.HashMap<>();

	private MetalPipeline(MetalEncoder encoder, long withDepth, long withoutDepth, long pulled, long depthState, int depthCompare, CreateInfo info,
		@Nullable Retained retained) {
		this.encoder = encoder;
		this.info = retained != null ? info : null;
		this.vertexMsl = retained != null ? retained.vertex.msl : null;
		this.fragmentMsl = retained != null ? retained.fragment.msl : null;
		this.vertexEntry = retained != null ? retained.vertex.entry : null;
		this.fragmentEntry = retained != null ? retained.fragment.entry : null;
		this.pulledMsl = retained != null ? retained.pulled : null;
		this.registryVertex = retained != null && retained.registryVertex;
		this.registryFragment = retained != null && retained.registryFragment;
		this.colorTargets = info.colorTargetStates().size();
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.pulled = pulled;
		this.depthState = depthState;
		this.depthCompare = depthCompare;
		this.name = info.name();
		this.clouds = this.name.equals("minecraft:pipeline/clouds") || this.name.equals("minecraft:pipeline/flat_clouds");
		this.uniforms = info.uniforms();
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.primitiveTopology());
		// MetalDrawTap (native shading's entity shadow casters): quads with a position at 0 and a uv at location 2, sampled from "Sampler0"
		int stride = 0, uv = -1, sampler = -1;
		if (info.primitiveTopology() == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS && !info.vertexBuffers().isEmpty()) {
			stride = info.vertexBuffers().get(0).stride();
			for (CreateInfo.AttribBinding a : info.attribBindings()) {
				if (a.location() == 2 && a.bufferSlot() == info.vertexBuffers().get(0).bufferSlot()) uv = a.offset();
			}
			for (int i = 0; i < info.uniforms().size(); i++) if (info.uniforms().get(i).name().equals("Sampler0")) sampler = i;
		}
		this.tapStride = stride;
		this.tapUv = sampler >= 0 ? uv : -1;
		this.tapSampler = sampler;
	}

	/** MetalDrawTap: vertex stride, uv offset (-1: not tappable) and the "Sampler0" uniform index of a quad pipeline. */
	final int tapStride, tapUv, tapSampler;

	/** Time spent creating pipelines so far, by step, for the bench's startup breakdown (read reflectively). */
	private static long compiled, translateNs, libraryNs, stateNs;

	static synchronized Map<String, Object> compileStats() {
		return Map.of("count", compiled, "translateMs", translateNs / 1e6, "libraryMs", libraryNs / 1e6, "stateMs", stateNs / 1e6, "mslCacheHits", mslHits, "mslCacheMismatches", mslMismatches);
	}

	static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info) {
		if (MetalShaderRegistry.get(info.name()) == null) return compile(encoder, info, false);
		try {
			MetalPipeline built = compile(encoder, info, true);
			mcopt.metal.shade.ShadeRobust.registryBuilt(info.name(), true); // mcopt-shade M3.3 (no-op unless it failed before)
			return built;
		} catch (RuntimeException e) {
			// A registry (native shading) variant that doesn't build, e.g. against a resource pack's own core shader: the
			// game's shaders instead of a crash.
			Log.warn("mcopt-metal: registry MSL for " + info.name() + " failed to build, using the game's shaders: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
			mcopt.metal.shade.ShadeRobust.registryBuilt(info.name(), false); // mcopt-shade M3.3: what depends on it steps back too
			return compile(encoder, info, false);
		}
	}

	private static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info, boolean useRegistry) {
		long ctx = encoder.ctx;
		long t0 = System.nanoTime(), t1 = t0, t2 = t0;
		CreateInfo.Shader vertex = shader(info, ShaderType.VERTEX), fragment = shader(info, ShaderType.FRAGMENT);
		long vlib = 0, flib = 0, plib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			Translated v = translate(vertex.module(), info.uniforms().size());
			Translated f = translate(fragment.module(), info.uniforms().size());
			if (DUMP_DIR != null) dump(info.name(), v, f);
			String defines = BUILTIN_MSL ? SODIUM_TERRAIN.get(info.name()) : null;
			if (defines != null) {
				v = new Translated(resource("sodium_terrain.vs.metal"), v.entry);
				f = new Translated(defines + resource("sodium_terrain.fs.metal"), f.entry);
			}
			MetalShaderRegistry.Variant registered = useRegistry ? registered(info) : null;
			if (registered != null) {
				// mcopt-shade M3.3: dev-only injected failure (-Dmcopt.shade.dev.failCompile), tests the fallback below; inert unset
				if (mcopt.metal.shade.ShadeRobust.failCompile(info.name())) throw new IllegalStateException(info.name() + ": injected shader build failure (-Dmcopt.shade.dev.failCompile)");
				v = apply(registered.vertex(), registered.vertexEntry(), v, info);
				f = apply(registered.fragment(), registered.fragmentEntry(), f, info);
			}
			if (OVERRIDE_DIR != null) {
				v = override(info.name() + ".vs.metal", v);
				f = override(info.name() + ".fs.metal", f);
			}
			if (UNCACHED) {
				v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl, v.entry);
				f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl, f.entry);
			}
			t1 = System.nanoTime();
			vlib = libraryNew(ctx, stack, v.msl, err);
			if (vlib == 0) throw new IllegalStateException(info.name() + " vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl);
			flib = libraryNew(ctx, stack, f.msl, err);
			if (flib == 0) throw new IllegalStateException(info.name() + " fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl);
			t2 = System.nanoTime();
			long vname = MemoryUtil.memAddress(stack.UTF8(v.entry)), fname = MemoryUtil.memAddress(stack.UTF8(f.entry));
			DepthStencilState depth = info.depthStencilState();
			long withDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, info, true, true), err, 4096);
			if (withDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			long withoutDepth = 0;
			if (depth == null) {
				withoutDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, info, false, true), err, 4096);
				if (withoutDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			}
			long pulled = 0;
			String pulledSource = null;
			if (defines != null && MetalTerrain.OCC && !info.name().contains("translucent")) {
				// Translucent stays on Sodium's own draws: its quads are drawn in sorted index-buffer order.
				String twin = "#define PULLED\n" + resource("sodium_terrain.vs.metal");
				if (registered != null && registered.vertex() != null) twin = apply(registered.vertex(), registered.vertexEntry(), new Translated(twin, v.entry), info).msl;
				pulledSource = twin;
				plib = libraryNew(ctx, stack, twin, err);
				if (plib == 0) throw new IllegalStateException(info.name() + " pulled vertex: " + MemoryUtil.memUTF8(err));
				pulled = Native.pipelineNew(ctx, plib, vname, flib, fname, describe(stack, info, true, false), err, 4096);
				if (pulled == 0) throw new IllegalStateException(info.name() + " pulled: " + MemoryUtil.memUTF8(err));
			}
			int compare = depth == null ? 7 : MetalConst.compare(depth.depthTest());
			long depthState = Native.depthStateNew(ctx, compare, depth != null && depth.writeDepth() ? 1 : 0);
			MetalPipeline pipeline = new MetalPipeline(encoder, withDepth, withoutDepth, pulled, depthState, compare, info,
				RETAIN ? new Retained(v, f, pulledSource, registered != null && registered.vertex() != null, registered != null && registered.fragment() != null) : null);
			pipeline.prewarm();
			return pipeline;
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			if (plib != 0) Native.release(plib);
			long t3 = System.nanoTime();
			synchronized (MetalPipeline.class) {
				compiled++;
				translateNs += t1 - t0;
				libraryNs += t2 - t1;
				stateNs += t3 - t2;
			}
		}
	}

	/** -Dmcopt.metal.dumpMsl=DIR writes every pipeline's generated Metal source there, for reading what the GPU actually runs. */
	private static final @Nullable String DUMP_DIR = System.getProperty("mcopt.metal.dumpMsl");
	/** -Dmcopt.metal.overrideMsl=DIR uses DIR/NAME.{vs,fs}.metal instead of the generated source when present: shader experiments without a rebuild. */
	private static final @Nullable String OVERRIDE_DIR = System.getProperty("mcopt.metal.overrideMsl");

	/**
	 * Keep each pipeline's create info and final MSL for variant(): on with the native shading pipeline (-Dmcopt.shade=true)
	 * or -Dmcopt.metal.retainMsl=true; off, pipelines are exactly upstream's.
	 */
	static final boolean RETAIN = Boolean.getBoolean("mcopt.shade") || Boolean.getBoolean("mcopt.metal.retainMsl");

	/**
	 * mc_library_new on msl. Sources over 16 KB go through a malloc'd copy instead of the thread's MemoryStack (64 KB by
	 * default): native shading prepends ~25 KB of shared MSL, and a pipeline's stages are encoded on the same stack.
	 */
	private static long libraryNew(long ctx, MemoryStack stack, String msl, long err) {
		if (msl.length() < 16384) return Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(msl)), err, 4096);
		java.nio.ByteBuffer source = MemoryUtil.memUTF8(msl);
		try {
			return Native.libraryNew(ctx, MemoryUtil.memAddress(source), err, 4096);
		} finally {
			MemoryUtil.memFree(source);
		}
	}

	private record Retained(Translated vertex, Translated fragment, @Nullable String pulled, boolean registryVertex, boolean registryFragment) {
	}

	/** Pipelines whose registry variant was skipped (missing uniforms), logged once each. */
	private static final java.util.Set<String> SKIPPED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static MetalShaderRegistry.@Nullable Variant registered(CreateInfo info) {
		MetalShaderRegistry.Variant variant = MetalShaderRegistry.get(info.name());
		if (variant == null) return null;
		for (String required : variant.required()) {
			if (info.uniforms().stream().noneMatch(u -> u.name().equals(required))) {
				if (SKIPPED.add(info.name())) Log.info("mcopt-metal: registry MSL for " + info.name() + " skipped, the pipeline has no uniform " + required);
				return null;
			}
		}
		return variant;
	}

	private static Translated apply(java.util.function.@Nullable UnaryOperator<String> stage, String entry, Translated current, CreateInfo info) {
		if (stage == null) return current;
		List<String> names = info.uniforms().stream().map(BindGroupLayout.UniformDescription::name).toList();
		return new Translated(MetalShaderRegistry.slotDefines(names) + stage.apply(current.msl), entry);
	}

	/**
	 * -Dmcopt.metal.uncachedMsl=true gives every source a per-launch prefix so Metal's on-disk shader cache misses: what the
	 * first launch after install or a macOS update pays (about +0.7 s of boot on the M4 mini).
	 */
	private static final boolean UNCACHED = Boolean.getBoolean("mcopt.metal.uncachedMsl");
	private static final long UNCACHED_NONCE = System.nanoTime();

	/** -Dmcopt.metal.builtinMsl=false compiles Sodium's terrain from its own SPIR-V again, for A/B runs. */
	private static final boolean BUILTIN_MSL = Boolean.parseBoolean(System.getProperty("mcopt.metal.builtinMsl", "true"));
	/**
	 * Pipelines that use the hand-written MSL in resources/mcopt/metal/msl, with the ALPHA_CUTOUT define Sodium's
	 * ShaderChunkRenderer gives each. Only these exact names: Sodium's OIT variants are named differently and keep their SPIR-V.
	 */
	private static final Map<String, String> SODIUM_TERRAIN = Map.of(
		"sodium:pipeline/solid_terrain", "",
		"sodium:pipeline/cutout_terrain", "#define ALPHA_CUTOUT 0.5\n",
		"sodium:pipeline/translucent_terrain", "#define ALPHA_CUTOUT 0.01\n");

	private static String resource(String name) {
		try (InputStream in = MetalPipeline.class.getResourceAsStream("/mcopt/metal/msl/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String fileName(String pipelineName) {
		return pipelineName.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/** dump(): the sources already written per pipeline name (several pipelines can share one). */
	private static final Map<String, java.util.List<Integer>> DUMPED = new java.util.HashMap<>();

	/**
	 * Writes NAME.{vs,fs}.metal; another pipeline with the same name but different sources (MC 26.3 registers three
	 * "item_cutout": plain, glint, glint special) gets NAME~1, NAME~2... (a reload's identical sources write nothing new).
	 */
	private static synchronized void dump(String name, Translated v, Translated f) {
		java.util.List<Integer> seen = DUMPED.computeIfAbsent(name, k -> new java.util.ArrayList<>());
		int hash = (v.msl + "\u0000" + f.msl).hashCode(), k = seen.indexOf(hash);
		if (k < 0) {
			seen.add(hash);
			k = seen.size() - 1;
		}
		String file = fileName(name) + (k == 0 ? "" : "~" + k);
		try {
			Files.createDirectories(Path.of(DUMP_DIR));
			Files.writeString(Path.of(DUMP_DIR, file + ".vs.metal"), v.msl);
			Files.writeString(Path.of(DUMP_DIR, file + ".fs.metal"), f.msl);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Translated override(String name, Translated generated) {
		Path file = Path.of(OVERRIDE_DIR, fileName(name));
		if (!Files.exists(file)) return generated;
		try {
			System.out.println("mcopt-metal: using " + file);
			return new Translated(Files.readString(file), generated.entry);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static CreateInfo.Shader shader(CreateInfo info, ShaderType type) {
		return info.shaders().stream().filter(s -> s.module().type() == type).findFirst()
			.orElseThrow(() -> new IllegalStateException(info.name() + " has no " + type + " shader"));
	}

	/** Packs the pipeline layout into the int array mc_pipeline_new reads (see mcmetal.m); no vertex input for shaders that pull their own. */
	private static long describe(MemoryStack stack, CreateInfo info, boolean hasDepth, boolean vertexInput) {
		return describe(stack, info, hasDepth, vertexInput, null);
	}

	/** colorFormats: MTLPixelFormat per color target in place of the pipeline's own (same count), or null to keep them. */
	private static long describe(MemoryStack stack, CreateInfo info, boolean hasDepth, boolean vertexInput, int @Nullable [] colorFormats) {
		IntBuffer d = stack.mallocInt(5 + info.vertexBuffers().size() * 3 + info.attribBindings().size() * 4 + info.colorTargetStates().size() * 9);
		List<CreateInfo.VertexBuffer> buffers = vertexInput ? info.vertexBuffers() : List.of();
		List<CreateInfo.AttribBinding> attributes = vertexInput ? info.attribBindings() : List.of();
		d.put(buffers.size());
		for (CreateInfo.VertexBuffer vb : buffers) d.put(MetalConst.VERTEX_BUFFER_BASE + vb.bufferSlot()).put(vb.stride()).put(vb.stepRate());
		d.put(attributes.size());
		for (CreateInfo.AttribBinding a : attributes) {
			d.put(a.location()).put(MetalConst.VERTEX_BUFFER_BASE + a.bufferSlot()).put(a.offset()).put(MetalConst.vertexFormat(a.format()));
		}
		d.put(info.colorTargetStates().size());
		int target = 0;
		for (@Nullable ColorTargetState c : info.colorTargetStates()) {
			int index = target++;
			if (c == null) {
				d.put(new int[9]);
				continue;
			}
			// MTLColorWriteMask is ABGR-ordered (red = 8), renderpearl's is RGBA-ordered (red = 1).
			int mask = (c.writeRed() ? 8 : 0) | (c.writeGreen() ? 4 : 0) | (c.writeBlue() ? 2 : 0) | (c.writeAlpha() ? 1 : 0);
			d.put(colorFormats != null ? colorFormats[index] : MetalConst.pixelFormat(c.format())).put(mask);
			if (c.blendFunction().isPresent()) {
				var b = c.blendFunction().get();
				d.put(1).put(MetalConst.blendFactor(b.color().sourceFactor())).put(MetalConst.blendFactor(b.color().destFactor())).put(MetalConst.blendOp(b.color().op()))
					.put(MetalConst.blendFactor(b.alpha().sourceFactor())).put(MetalConst.blendFactor(b.alpha().destFactor())).put(MetalConst.blendOp(b.alpha().op()));
			} else {
				d.put(new int[] {0, 1, 0, 0, 1, 0, 0});
			}
		}
		d.put(hasDepth ? MetalConst.pixelFormat(com.mojang.renderpearl.api.GpuFormat.D32_FLOAT) : 0);
		d.put(MetalConst.topologyClass(info.primitiveTopology()));
		return MemoryUtil.memAddress(d.flip());
	}

	private record Translated(String msl, String entry) {
	}

	/**
	 * -Dmcopt.startup.mslCache=true: SPIRV-Cross's output for a module is a pure function of its SPIR-V, stage and uniform count
	 * (and of this mod's and SPIRV-Cross's versions), so it is kept on disk under <game dir>/mcopt-cache/msl/ and read back on the
	 * next launch instead of translated again (the translation is ~0.15-0.3 s of every startup on the main thread).
	 */
	private static final String MSL_MODE = System.getProperty("mcopt.startup.mslCache", "false");
	/** true: use the cache; verify: use it and also translate every hit, counting any difference (mslCacheMismatches). */
	private static final boolean MSL_CACHE = MSL_MODE.equals("true") || MSL_MODE.equals("verify");
	static int mslMismatches;
	private static final java.nio.file.Path MSL_DIR = MSL_CACHE ? (System.getProperty("mcopt.startup.mslCacheDir") != null
		? java.nio.file.Path.of(System.getProperty("mcopt.startup.mslCacheDir"))
		: net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mcopt-cache").resolve("msl")) : null;
	/** What the translation depends on besides its inputs: this class's code (options, bindings) and the SPIRV-Cross build. */
	private static final String MSL_KEY_SALT = MSL_CACHE ? mslSalt() : null;

	private static String mslSalt() {
		try (java.io.InputStream in = MetalPipeline.class.getResourceAsStream("MetalPipeline.class")) {
			byte[] code = in.readAllBytes();
			return "mcopt-msl-1|" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code))
				+ "|" + org.lwjgl.Version.getVersion();
		} catch (Exception e) {
			throw new IllegalStateException("mslCache salt", e);
		}
	}
	static int mslHits, mslMisses;

	private static Translated translate(SpvModule module, int uniformCount) {
		if (!MSL_CACHE) return translateNow(module, uniformCount);
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(MSL_KEY_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update((byte) module.type().ordinal());
			md.update(java.nio.ByteBuffer.allocate(4).putInt(uniformCount).array());
			md.update(module.spv().duplicate());
			key = java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			return translateNow(module, uniformCount);
		}
		java.nio.file.Path file = MSL_DIR.resolve(key);
		try {
			String text = java.nio.file.Files.readString(file);
			int nl = text.indexOf('\n');
			if (nl > 0) {
				mslHits++;
				Translated hit = new Translated(text.substring(nl + 1), text.substring(0, nl));
				if (MSL_MODE.equals("verify") && !hit.equals(translateNow(module, uniformCount))) mslMismatches++;
				return hit;
			}
		} catch (java.io.IOException missing) {
			// not cached yet
		}
		Translated t = translateNow(module, uniformCount);
		mslMisses++;
		try {
			java.nio.file.Files.createDirectories(MSL_DIR);
			java.nio.file.Path tmp = java.nio.file.Files.createTempFile(MSL_DIR, key, ".tmp");
			java.nio.file.Files.writeString(tmp, t.entry + "\n" + t.msl);
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			// a cache that can't be written is just a slower launch
		}
		return t;
	}

	private static Translated translateNow(SpvModule module, int uniformCount) {
		boolean vertex = module.type() == ShaderType.VERTEX;
		int model = vertex ? Spv.SpvExecutionModelVertex : Spv.SpvExecutionModelFragment;
		IntBuffer spirv = module.spv().asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				if (vertex) spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (int i = 0; i < uniformCount; i++) bind(stack, compiler, model, 0, i, i);
				bind(stack, compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, MetalConst.PUSH_CONSTANTS_INDEX);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				return new Translated(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	private static void bind(MemoryStack stack, long compiler, int model, int set, int binding, int slot) {
		SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(b);
		b.stage(model).desc_set(set).binding(binding).msl_buffer(slot).msl_texture(slot).msl_sampler(slot);
		spvc_compiler_msl_add_resource_binding(compiler, b);
	}

	private static void check(long context, int result, String step) {
		if (result != 0) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	/**
	 * This pipeline's shaders and state for other color attachment formats: colorFormats holds an MTLPixelFormat per color
	 * target (same count as the pipeline's), depth whether the pass has a (Depth32Float) depth attachment, pulled the
	 * MetalTerrain twin (0 when there is none). Built on first use from the retained MSL (Metal's shader cache makes the
	 * recompile cheap) and cached until the pipeline closes. Needs RETAIN (-Dmcopt.shade=true or -Dmcopt.metal.retainMsl=true).
	 */
	synchronized long variant(int[] colorFormats, boolean depth, boolean pulled) {
		if (this.closed) throw new IllegalStateException(this.name + " is closed");
		if (this.info == null) throw new IllegalStateException("MetalPipeline.variant needs -Dmcopt.shade=true or -Dmcopt.metal.retainMsl=true");
		if (colorFormats.length != this.info.colorTargetStates().size()) {
			throw new IllegalArgumentException(this.name + " has " + this.info.colorTargetStates().size() + " color targets, not " + colorFormats.length);
		}
		if (pulled && this.pulledMsl == null) return 0;
		MetalShaderRegistry.FormatAdapter canon = MetalShaderRegistry.formatAdapter();
		if (canon != null) colorFormats = canon.canonical(this.name, colorFormats);
		String key = java.util.Arrays.toString(colorFormats) + (depth ? "+d" : "") + (pulled ? "+p" : "");
		Long cached = this.variants.get(key);
		if (cached != null) return cached;
		long ctx = this.encoder.ctx, vlib = 0, flib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			String vsrc = pulled ? this.pulledMsl : this.vertexMsl, fsrc = this.fragmentMsl;
			MetalShaderRegistry.FormatAdapter adapter = MetalShaderRegistry.formatAdapter();
			if (adapter != null) {
				vsrc = adapter.vertex(this.name, vsrc, this.registryVertex || pulled, colorFormats);
				fsrc = adapter.fragment(this.name, fsrc, this.registryFragment, colorFormats);
			}
			vlib = libraryNew(ctx, stack, vsrc, err);
			if (vlib == 0) throw new IllegalStateException(this.name + " variant vertex: " + MemoryUtil.memUTF8(err));
			flib = libraryNew(ctx, stack, fsrc, err);
			if (flib == 0) throw new IllegalStateException(this.name + " variant fragment: " + MemoryUtil.memUTF8(err));
			long vname = MemoryUtil.memAddress(stack.UTF8(this.vertexEntry)), fname = MemoryUtil.memAddress(stack.UTF8(this.fragmentEntry));
			long pso = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, this.info, depth, !pulled, MetalHooks.pixelFormats(colorFormats)), err, 4096);
			if (pso == 0) throw new IllegalStateException(this.name + " variant " + key + ": " + MemoryUtil.memUTF8(err));
			this.variants.put(key, pso);
			return pso;
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
		}
	}

	/**
	 * The PSO to draw with in a pass whose color attachments have formats (null: this pipeline's own), with or without
	 * depth. A variant that can't be built (logged once) falls back to the pipeline's own state.
	 */
	long state(int @Nullable [] formats, boolean depth) {
		long own = depth ? this.withDepth : this.withoutDepth;
		if (formats == null || this.info == null || formats.length != this.colorTargets) return own;
		try {
			return this.variant(formats, depth, false);
		} catch (RuntimeException e) {
			if (FAILED_VARIANTS.add(this.name)) Log.warn("mcopt-metal: no " + java.util.Arrays.toString(formats) + " variant of " + this.name + ": " + firstLine(e));
			return own;
		}
	}

	/** state() for the MetalTerrain pulled twin. */
	long pulledState(int @Nullable [] formats) {
		if (formats == null || this.info == null || formats.length != this.colorTargets) return this.pulled;
		try {
			long v = this.variant(formats, true, true);
			return v != 0 ? v : this.pulled;
		} catch (RuntimeException e) {
			if (FAILED_VARIANTS.add(this.name + "+p")) Log.warn("mcopt-metal: no pulled variant of " + this.name + ": " + firstLine(e));
			return this.pulled;
		}
	}

	private static final java.util.Set<String> FAILED_VARIANTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static String firstLine(RuntimeException e) {
		return String.valueOf(e.getMessage()).lines().findFirst().orElse("");
	}

	/**
	 * Builds the variants MetalShaderRegistry asks to have ready (prewarmFormats), on the compile thread, so the first
	 * frame that needs one doesn't stall the render thread compiling it.
	 */
	private void prewarm() {
		int[][] sets = MetalShaderRegistry.prewarmFormats(this.name);
		if (sets == null || this.info == null) return;
		for (ColorTargetState c : this.info.colorTargetStates()) {
			if (c == null || MetalConst.pixelFormat(c.format()) != 70) return; // RGBA8Unorm targets only (what the game's main target is)
		}
		for (int[] formats : sets) {
			if (formats == null || formats.length != this.colorTargets) continue;
			this.state(formats, true);
			if (this.withoutDepth != 0) this.state(formats, false);
			if (this.pulled != 0) this.pulledState(formats);
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.withDepth);
			if (this.withoutDepth != 0) this.encoder.releaseLater(this.withoutDepth);
			if (this.pulled != 0) this.encoder.releaseLater(this.pulled);
			this.encoder.releaseLater(this.depthState);
			synchronized (this) {
				for (long pso : this.variants.values()) this.encoder.releaseLater(pso);
				this.variants.clear();
			}
		}
	}
}
