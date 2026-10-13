// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mcopt.metal.MetalBridge;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The sun (or moon) shadow map: two orthographic cascades in one Depth32Float (or Depth16Unorm) 2D array texture, rendered natively before
 * the frame's first world pass (-Dmcopt.shade=true, mcopt.shade.shadows on by default; see ShadowConfig).
 * <p>
 * Frame lifecycle (ShadowLevelRendererMixin): {@link #beginFrame} at LevelRenderer.render HEAD fits the cascades and fixes
 * this frame's matrices (idempotent within a frame, so the infrastructure's ShadeFrame packer may call it first);
 * {@link #renderFrame} right before the level's frame graph executes lists the casters and encodes the depth passes in
 * their own render encoders; {@link #endFrame} at RETURN draws the debug view (mcopt.shade.debug=1) and closes the frame.
 * <p>
 * Near cascade (slice 0): half-width {@link ShadowConfig#NEAR}, redrawn every frame. Far cascade (slice 1): half-width
 * {@link ShadowConfig#DIST} centered on the camera, redrawn every {@link ShadowConfig#FAR_EVERY} frames, or when the
 * light direction (quantized) changed or the camera left an eighth of it; in between its map and its world-anchored
 * matrix are kept, re-expressed against the moving camera every frame.
 * <p>
 * Conventions (shade_shadow.metal has the receiver code): matrices take CAMERA-RELATIVE world positions (Sodium's origin
 * this frame) to light clip x, y in [-1, 1], z in [0, 1] REVERSED (1 nearest the light, cleared to 0); texture uv =
 * (0.5 + 0.5 x, 0.5 - 0.5 y); the compare sampler is GreaterEqual (lit when receiver depth >= stored).
 */
public final class ShadowPass {
	/** Binding slots of the shared shade contract: texture(16) = map, sampler(12) = compare sampler. */
	public static final int TEXTURE_SLOT = 16, SAMPLER_SLOT = 12;
	public static final int CASCADES = 2;
	private static final int CASCADE_BYTES = 80, DRAW_BYTES = 24, DEBUG_BYTES = 16;
	/** Light below this elevation (sin) is raised to it for the shadow projection, so maps never degenerate at the horizon. */
	private static final double MIN_ELEVATION = Math.sin(Math.toRadians(2.0));
	/**
	 * Light-space depth kept beyond each cascade's half-width: behind its center (away from the light; receivers deeper than
	 * this fall back to the far cascade, or are lit past it) and in front of it, plus how far past that casters are still
	 * listed (they're pancaked onto depth 1 by the clamped depth clip).
	 */
	private static final double NEAR_BACK = 96, FAR_BACK = 64, DEPTH_FRONT = 32, CASTER_REACH = 192;

	private static @Nullable ShadowPass instance;
	private static boolean failed;

	private long shadow;
	private long texture, compareSampler;
	/** M3 coloured shadows: the tint map (0 = off), the water sprites for its pass. */
	private long tintMap;
	private final float[] waterRects = new float[8];
	private static final int TINT_PARAM_BYTES = 48;
	private final ShadowCascade[] cascades = {new ShadowCascade(), new ShadowCascade()};
	private final ShadowCasters casters = new ShadowCasters();
	/** This frame's camera-relative matrices (2 x 16, column-major) and their camera. */
	private final float[] matrices = new float[32];
	private double camX, camY, camZ;
	/** The quantized direction to the light that casts (sun, or moon at night), world space. */
	private final double[] light = new double[3];
	private boolean lightIsMoon;
	private long lightKey = Long.MIN_VALUE, farLightKey = Long.MIN_VALUE;
	private final boolean[] draw = new boolean[2];
	private int framesSinceFar = Integer.MAX_VALUE / 2;
	/** beginFrame ran for this frame token and found shadows possible. renderOk: the last renderFrame drew the maps. */
	private boolean prepared, renderOk = true;
	private long frameToken, preparedToken = -1;
	private long frames;
	private @Nullable String lastProblem;

	// ---- M3.3 far cascade spread (ShadowConfig.farSpread, read every frame) ----
	/** The fit the back map is being drawn with, part by part; it becomes cascades[1] when the last part is drawn. */
	private final ShadowCascade farBack = new ShadowCascade();
	private final ShadowCascade[] castCascades = new ShadowCascade[2];
	private final float[] backMatrix = new float[16];
	/** The part of the back map drawn next (0..FAR_EVERY-1), -1: no back map under way. */
	private int backPart = -1;
	private long backLightKey;
	/** This frame's far cascade: 0 not drawn, 1 whole into the bound map (M3.2), 2 one part into the back map, 3 the last part (the maps swap first). */
	private int farMode;
	private int farPartNow;
	private boolean spreadReady, spreadFailed;
	/** Light more than this far (cos) from the bound far map's: redraw it whole at once (a /time jump; the day's 0.1 degree steps never get there). */
	private static final double SPREAD_LIGHT_COS = Math.cos(Math.toRadians(1.0));
	private long statSwaps, statWhole;
	// ---- M3.3 GPU timers (ShadowConfig.times) ----
	private boolean timing;
	private long timesPrinted;
	private final long timesOut = MemoryUtil.nmemAlloc(9 * 4);
	/** M3.3 coloured-shadow pricing / lite map (ShadowConfig.tintDev 8). */
	private long tintLite;

	private ShadowPass() {
	}

	// ---- public API (render thread only) ----

	/**
	 * Whether this frame has a shadow map (ShadeFrame.shadowsEnabled; receivers treat everything as lit otherwise). Valid
	 * from beginFrame on: true when the cascades are fitted for this frame and the last render succeeded.
	 */
	public static boolean enabled() {
		ShadowPass p = instance;
		return p != null && p.prepared && p.renderOk && !failed;
	}

	/** The map: an id<MTLTexture> (depth2d_array, Depth32Float or Depth16Unorm, CASCADES slices), 0 before the first frame or when off. */
	public static long texture() {
		ShadowPass p = instance;
		return p == null ? 0 : p.texture;
	}

	/** The compare sampler for sampler(12): an id<MTLSamplerState>, 0 before the first frame or when off. */
	public static long sampler() {
		ShadowPass p = instance;
		return p == null ? 0 : p.compareSampler;
	}

	/**
	 * This frame's camera-relative world -> light clip matrices, column-major, cascade 0 at out[offset..+16), cascade 1 at
	 * out[offset + 16..+32). Identity-free zeros when shadows are off this frame.
	 */
	public static void matrices(float[] out, int offset) {
		ShadowPass p = instance;
		if (p == null || !p.prepared) {
			java.util.Arrays.fill(out, offset, offset + 32, 0f);
			return;
		}
		System.arraycopy(p.matrices, 0, out, offset, 32);
	}

	public static void matrices(float[] out) {
		matrices(out, 0);
	}

	public static void matrices(Matrix4f cascade0, Matrix4f cascade1) {
		float[] m = new float[32];
		matrices(m, 0);
		cascade0.set(m, 0);
		cascade1.set(m, 16);
	}

	/**
	 * ShadeFrame.cascadeSplits: {d0, d1, texel0, texel1}. d0 / d1: distance in blocks from the camera within which cascade 0
	 * / 1 covers every direction inside its 85% blend margin (the near box is pushed ahead of the camera by 40% of its
	 * half-width; the far box may lag the camera by an eighth of its half-width), for receivers that pick a cascade by
	 * distance. shade_shadow.metal picks by the box itself, which covers more. texel0 / texel1: blocks per texel.
	 */
	public static void splits(float[] out) {
		out[0] = (float) ((0.85 - 0.4) * ShadowConfig.NEAR);
		out[1] = (float) ((0.85 - 0.125) * ShadowConfig.DIST);
		out[2] = texelSize(0);
		out[3] = texelSize(1);
	}

	/** Receiver depth bias in clip z to use with this map: casters are already biased at raster time, so none is needed. */
	public static float receiverDepthBias() {
		return 0f;
	}

	/** World blocks per shadow texel of cascade i (for the receiver's normal offset). */
	public static float texelSize(int cascade) {
		return (float) (2.0 * (cascade == 0 ? ShadowConfig.NEAR : ShadowConfig.DIST) / ShadowConfig.RES);
	}

	/** 1 / resolution: one texel in texture coordinates (ShadeFrame.shadowTexelSize). */
	public static float texelUv() {
		return 1f / ShadowConfig.RES;
	}

	public static int resolution() {
		return ShadowConfig.RES;
	}

	/** Unit world direction toward the light the map was rendered from (sun, or the moon at night). */
	public static void lightDir(float[] out) {
		ShadowPass p = instance;
		if (p == null) {
			out[0] = 0;
			out[1] = 1;
			out[2] = 0;
			return;
		}
		out[0] = (float) p.light[0];
		out[1] = (float) p.light[1];
		out[2] = (float) p.light[2];
	}

	/** Whether the casting light is the moon (the sun is below the horizon). */
	public static boolean lightIsMoon() {
		ShadowPass p = instance;
		return p != null && p.lightIsMoon;
	}

	/**
	 * Binds the map at texture(16) and the compare sampler at sampler(12), vertex and fragment, on the open render encoder
	 * of native encoder enc (MetalBridge.enc). For the infrastructure's global rebinds; a no-op before the first frame.
	 */
	public static void bind(long enc) {
		ShadowPass p = instance;
		if (p == null || p.shadow == 0) return;
		ShadowNative.bind(enc, p.shadow, TEXTURE_SLOT, SAMPLER_SLOT);
	}

	// ---- frame hooks (ShadowLevelRendererMixin) ----

	/**
	 * Fits this frame's cascades and fixes their matrices for camera cam. Idempotent within a frame. Callable by the shade
	 * infrastructure before it packs ShadeFrame (any order relative to the shadow pass's own HEAD hook).
	 */
	public static void beginFrame(CameraRenderState cam) {
		if (!ShadowConfig.SHADOWS || failed || mcopt.metal.shade.ShadeBindings.fallback()) return;
		ShadowPass p = instance;
		if (p == null) p = instance = new ShadowPass();
		if (p.preparedToken == p.frameToken) return;
		p.preparedToken = p.frameToken;
		try {
			p.prepare(cam);
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	/** Encodes the depth passes; called right before the level's frame graph runs (no render pass open). */
	public static void renderFrame() {
		ShadowPass p = instance;
		if (p != null && EntityCasters.ON) EntityCasters.beginFrame(p);
		if (p == null || !p.prepared || failed) return;
		try {
			p.render();
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	/** End of the level's frame: the debug view, then the next frame token. */
	public static void endFrame() {
		ShadowPass p = instance;
		if (p == null) return;
		try {
			if (ShadowConfig.DEBUG == 1 && p.prepared && p.shadow != 0 && !failed) p.debugView();
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
		p.frameToken++;
		p.frames++;
	}

	private static void fail(Throwable e) {
		failed = true;
		ShadowPass p = instance;
		if (p != null) p.prepared = false;
		mcopt.metal.Log.warn("[mcopt] shade: shadow pass disabled after an error", e);
	}

	// ---- implementation ----

	private void prepare(CameraRenderState cam) {
		this.prepared = false;
		this.draw[0] = this.draw[1] = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !cam.initialized) return;
		SkyRenderState sky = mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
		if (sky.skybox == DimensionType.Skybox.NONE) return; // no sun: Nether
		if (sky.skybox == DimensionType.Skybox.END) {
			// M3 (mcopt.shade.endShadows): the End's fixed key light (ShadeDims), so the far cascade stays cached
			if (!mcopt.metal.shade.dims.DimsConfig.END_SHADOWS || mcopt.metal.shade.dims.ShadeDims.paused()) return;
			float[] e = mcopt.metal.shade.dims.ShadeDims.endLight();
			this.light[0] = e[0];
			this.light[1] = e[1];
			this.light[2] = e[2];
			this.lightIsMoon = false;
			this.lightKey = 0x456e64L; // constant: never forces a far redraw
		} else {
			this.chooseLight(sky.sunAngle, sky.moonAngle);
		}
		double x = cam.pos.x, y = cam.pos.y, z = cam.pos.z;
		this.camX = x;
		this.camY = y;
		this.camZ = z;
		int res = ShadowConfig.RES;
		// near: every frame, pushed ahead of the view so more of it covers what's on screen
		Vector3f f = cam.orientation.transform(new Vector3f(0, 0, -1));
		double near = ShadowConfig.NEAR, shift = 0.4 * near;
		this.cascades[0].fit(this.light, x + f.x * shift, y + f.y * shift, z + f.z * shift, near, res, NEAR_BACK, DEPTH_FRONT, CASTER_REACH);
		this.draw[0] = true;
		// far: kept between redraws, anchored in the world
		ShadowCascade far = this.cascades[1];
		double dist = ShadowConfig.DIST;
		double dx = x - far.cx, dy = y - far.cy, dz = z - far.cz;
		boolean moved = dx * dx + dy * dy + dz * dz > (dist / 8) * (dist / 8);
		// M3.3: spread the far redraw over FAR_EVERY frames (one part of the casters per frame into the back map, all parts with
		// one fit, then the maps swap); off = M3.2. A camera jump or a big light jump still redraws it whole in this frame.
		boolean spread = ShadowConfig.FAR_EVERY > 1 && !this.spreadFailed && ShadowConfig.farSpread();
		if (!spread) this.backPart = -1;
		this.farMode = 0;
		boolean lightJump = spread ? far.z[0] * this.light[0] + far.z[1] * this.light[1] + far.z[2] * this.light[2] < SPREAD_LIGHT_COS
			: this.farLightKey != this.lightKey;
		if (!far.valid || moved || lightJump || !spread && this.framesSinceFar >= ShadowConfig.FAR_EVERY - 1) {
			far.fit(this.light, x, y, z, dist, res, FAR_BACK, DEPTH_FRONT, CASTER_REACH);
			this.farMode = 1;
			this.backPart = -1;
		} else if (spread) {
			if (this.backPart < 0) { // a new back map: fitted once, here, for all its parts
				this.farBack.fit(this.light, x, y, z, dist, res, FAR_BACK, DEPTH_FRONT, CASTER_REACH);
				this.backPart = 0;
				this.backLightKey = this.lightKey;
			}
			this.farPartNow = this.backPart;
			if (this.backPart >= ShadowConfig.FAR_EVERY - 1) {
				far.copyFrom(this.farBack); // receivers read the back map (complete after this frame's part) from this frame on
				this.farMode = 3;
			} else {
				this.farMode = 2;
				this.farBack.matrix(x, y, z, this.backMatrix, 0);
			}
		}
		this.draw[1] = this.farMode != 0;
		this.cascades[0].matrix(x, y, z, this.matrices, 0);
		far.matrix(x, y, z, this.matrices, 16);
		this.prepared = true;
	}

	/** The light that casts this frame: the sun, or the moon while the sun is below the horizon, quantized to the sun step. */
	private void chooseLight(float sunAngle, float moonAngle) {
		double step = Math.toRadians(ShadowConfig.SUN_STEP_DEGREES);
		double sun = quantize(sunAngle, step), moon = quantize(moonAngle, step);
		// vanilla's celestial transform: RotY(-90) * RotX(angle) * (0, 1, 0) = (-sin a, cos a, 0)
		// the same switch as the shade sky (ShadeSky.compute: the moon once the sun is 0.06 below the horizon), so the light
		// the terrain is lit by and the one the map is drawn from are always the same
		boolean useMoon = Math.cos(sunAngle) * Math.cos(Math.toRadians(mcopt.metal.shade.Shade.SUN_TILT)) < -0.06;
		double a = useMoon ? moon : sun;
		double[] d = new double[3];
		mcopt.metal.shade.Shade.celestial(a, d); // vanilla's direction, tilted by the shared sun path tilt
		double lx = d[0], ly = d[1], lz = d[2];
		if (ly < MIN_ELEVATION) { // keep the projection sane at the horizon (direct light is ~0 there anyway)
			double h = Math.sqrt(1 - MIN_ELEVATION * MIN_ELEVATION), n = Math.sqrt(lx * lx + lz * lz);
			if (n < 1e-9) {
				lx = h;
				lz = 0;
			} else {
				lx = lx / n * h;
				lz = lz / n * h;
			}
			ly = MIN_ELEVATION;
		}
		this.light[0] = lx;
		this.light[1] = ly;
		this.light[2] = lz;
		this.lightIsMoon = useMoon;
		this.lightKey = Double.doubleToLongBits(a) ^ (useMoon ? 1L : 0L);
	}

	/** This frame's maps weren't drawn: say so to receivers, and refit (redraw) the far cascade next frame. */
	private void unrendered() {
		this.prepared = false;
		this.renderOk = false;
		this.cascades[1].valid = false;
		this.backPart = -1;
		this.draw[0] = this.draw[1] = false;
	}

	private static double quantize(double angle, double step) {
		return step <= 0 ? angle : Math.round(angle / step) * step;
	}

	/** The tint map's resolution this frame (the receivers' rectangle pad is 8 of its texels). */
	private int tintRes = mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOW_RES;

	/**
	 * M3 coloured shadows: the listed translucent casters' rectangle in cascade 0's light clip space (its matrix takes
	 * camera-relative positions; orthographic, so a box of half extents h spans its centre +- h.|row|), widened by
	 * 4 texels for the receivers' normal offset and bilinear taps; into this frame's ShadeLook (ssr.w, tintRect).
	 */
	private void publishTint(boolean any) {
		boolean on = any && this.tintRect();
		float[] r = this.tintRectNow;
		mcopt.metal.shade.ShadeBindings.setShadowTint(on ? this.tintSubNow ? 2 : 1 : 0, r[0], r[1], r[2], r[3]);
	}

	/** This frame's tint rectangle (x0, y0, x1, y1 in cascade 0's light clip space, padded; clipped to [-1, 1] with tintSub). */
	private final float[] tintRectNow = new float[4];
	/** M3.3 mcopt.shade.tintSub: this frame's tint pass draws only the rectangle (tintSubMatrix), ssr.w = 2. */
	private boolean tintSubNow;

	/** Fills tintRectNow from the listed casters' boxes; false when none of them reaches the near cascade. */
	private boolean tintRect() {
		float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
		float[] m = this.matrices;
		int[] b = this.casters.tintBoxes;
		for (int i = 0; i < this.casters.tintCount; i++) {
			int o = i * 6; // world block box, max exclusive (a section's 16^3, or M3.3's tight box of its translucent blocks)
			float hx = (b[o + 3] - b[o]) * 0.5f, hy = (b[o + 4] - b[o + 1]) * 0.5f, hz = (b[o + 5] - b[o + 2]) * 0.5f;
			float px = (float) (b[o] + hx - this.camX), py = (float) (b[o + 1] + hy - this.camY), pz = (float) (b[o + 2] + hz - this.camZ);
			float cx = m[0] * px + m[4] * py + m[8] * pz + m[12], cy = m[1] * px + m[5] * py + m[9] * pz + m[13];
			float bx = hx * Math.abs(m[0]) + hy * Math.abs(m[4]) + hz * Math.abs(m[8]), by = hx * Math.abs(m[1]) + hy * Math.abs(m[5]) + hz * Math.abs(m[9]);
			x0 = Math.min(x0, cx - bx);
			x1 = Math.max(x1, cx + bx);
			y0 = Math.min(y0, cy - by);
			y1 = Math.max(y1, cy + by);
		}
		float pad = 8f / Math.max(this.tintRes, 1);
		float[] r = this.tintRectNow;
		r[0] = x0 - pad;
		r[1] = y0 - pad;
		r[2] = x1 + pad;
		r[3] = y1 + pad;
		if (this.tintSubNow) { // receivers only look up inside the cascade's square: the drawn rectangle needs no more
			r[0] = Math.max(r[0], -1f);
			r[1] = Math.max(r[1], -1f);
			r[2] = Math.min(r[2], 1f);
			r[3] = Math.min(r[3], 1f);
		}
		return this.casters.tintCount > 0 && r[0] < 1f && r[2] > -1f && r[1] < 1f && r[3] > -1f && r[0] < r[2] && r[1] < r[3];
	}

	/**
	 * M3.3 mcopt.shade.tintSub: cascade 0's matrix (at cascadeData) made to draw only the tint rectangle into the top-left
	 * areaW x areaH texels of the map, at the same texel size as the whole map (texel column (c.x - x0) res / 2, row
	 * (y1 - c.y) res / 2; weather_surface.metal reads it back that way when ssr.w = 2). Returns {areaW, areaH}.
	 */
	private int[] tintSubMatrix(long cascadeData, int res) {
		float[] r = this.tintRectNow;
		int w = Math.min(res, (int) Math.ceil(0.5 * (r[2] - r[0]) * res) + 2), h = Math.min(res, (int) Math.ceil(0.5 * (r[3] - r[1]) * res) + 2);
		float sx = (float) res / w, sy = (float) res / h;
		// clip x'' = sx (c.x - x0) - 1, y'' = 1 - sy (y1 - c.y): row x and row y of the column-major matrix scaled and shifted
		for (int col = 0; col < 4; col++) {
			long ax = cascadeData + (col * 4L) * 4L, ay = ax + 4;
			MemoryUtil.memPutFloat(ax, sx * MemoryUtil.memGetFloat(ax));
			MemoryUtil.memPutFloat(ay, sy * MemoryUtil.memGetFloat(ay));
		}
		long tx = cascadeData + 12 * 4L, ty = cascadeData + 13 * 4L;
		MemoryUtil.memPutFloat(tx, MemoryUtil.memGetFloat(tx) - sx * r[0] - 1f);
		MemoryUtil.memPutFloat(ty, MemoryUtil.memGetFloat(ty) + 1f - sy * r[3]);
		return new int[] {w, h};
	}

	private void render() {
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (encoder == null || MetalBridge.inRenderPass(encoder)) {
			this.problem("not on the Metal backend, or a render pass is open");
			this.unrendered();
			return;
		}
		if (this.shadow == 0) this.create(MetalBridge.ctx(encoder));
		if (this.farMode >= 2 && !this.spreadReady) { // M3.3: the back map, made on first use
			if (ShadowNative.spreadInit(this.shadow) == 0) {
				this.spreadReady = true;
				System.out.println(String.format("[mcopt] shade: far cascade spread over %d frames (second map %.0f MB)", ShadowConfig.FAR_EVERY,
					CASCADES * (ShadowConfig.DEPTH_BITS / 8.0) * ShadowConfig.RES * ShadowConfig.RES / (1 << 20)));
			} else {
				this.spreadFailed = true; // keep M3.2's way: the bound far map stays as it is this frame
				mcopt.metal.Log.warn("[mcopt] shade: far cascade spread off: no memory for the second map");
				this.farMode = 0;
				this.draw[1] = false;
				this.backPart = -1;
			}
		}
		this.timing = ShadowConfig.times() && ShadowNative.timesFrame(this.shadow, this.frames) == 0;
		if (this.draw[0]) EntityCasters.prepareCast(this.frames, this.camX, this.camY, this.camZ); // M3: last frame's entities, onto this camera
		int faceMask = ShadowCasters.faceMask(this.light, ShadowConfig.ALL_FACES);
		long t0 = ShadowConfig.STATS ? System.nanoTime() : 0;
		// SHADE_DEV_WX_NO_TINT (16384, mcopt.shade.dev, read per frame): skip the tint pass too, to price coloured shadows in one session
		// in heavy rain on performance/balanced (ShadeWeather.tintHidden) the receivers' tint has faded out: no pass either
		boolean tint = this.tintMap != 0 && (Integer.getInteger("mcopt.shade.dev", 0) & 16384) == 0 && !mcopt.metal.shade.weather.ShadeWeather.tintHidden();
		int tintDev = ShadowConfig.tintDev();
		this.casters.tintTight = ShadowConfig.tintTight();
		// M3.3 far spread: this frame's far casters are one part of them, cast into the back map's fit (mode 2) or the bound one's
		this.casters.farPart = this.farMode >= 2 ? this.farPartNow : -1;
		this.casters.farParts = ShadowConfig.FAR_EVERY;
		this.castCascades[0] = this.cascades[0];
		this.castCascades[1] = this.farMode == 2 ? this.farBack : this.cascades[1];
		boolean built = this.casters.build(this.castCascades, this.draw, faceMask, this.camX, this.camY, this.camZ, ShadowConfig.CULL_LIGHT, tint,
			mcopt.metal.shade.water.ShadeWater.ON);
		if (ShadowConfig.STATS) this.statBuildNanos += System.nanoTime() - t0;
		if (!built) {
			this.problem(this.casters.problem);
			this.unrendered();
			return;
		}
		if (this.farMode == 3) { // the back map gets its last part this frame and is what receivers read from now on
			ShadowNative.swap(this.shadow);
			this.texture = ShadowNative.texture(this.shadow);
		}
		// Sodium geometry copies MetalTerrain holds back for its split must land before these passes read the arenas.
		MetalBridge.flushHeldTerrainCopies(encoder);
		long enc = MetalBridge.enc(encoder);
		var memory = ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).transientMemory();
		// regions, then every cascade's chunks back to back; one upload each
		int regionBytes = Math.max(16, this.casters.regionCount * ShadowCasters.regionBytes());
		GpuBufferSlice.MappedView regions = memory.allocateGpuMapped(regionBytes, 16L, GpuBuffer.USAGE_UNIFORM);
		MemoryUtil.memCopy(this.casters.regions, MemoryUtil.memAddress(regions.data()), (long) this.casters.regionCount * ShadowCasters.regionBytes());
		int totalChunks = 0;
		for (int c = 0; c < CASCADES; c++) for (ShadowCasters.DrawList l : this.casters.lists[c]) totalChunks += l.count;
		GpuBufferSlice.MappedView chunks = memory.allocateGpuMapped(Math.max(16, (long) totalChunks * ShadowCasters.chunkBytes()), 16L, GpuBuffer.USAGE_UNIFORM);
		long chunkAt = MemoryUtil.memAddress(chunks.data());
		long regionsHandle = MetalBridge.useBuffer(encoder, regions.slice().buffer()), chunksHandle = MetalBridge.useBuffer(encoder, chunks.slice().buffer());
		long atlas = MetalBridge.viewHandle(DefaultTerrainRenderPasses.CUTOUT.getAtlas());
		int first = 0;
		boolean tracing = MetalBridge.tracing(encoder);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int c = 0; c < CASCADES; c++) {
				if (!this.draw[c]) continue;
				List<ShadowCasters.DrawList> lists = this.casters.lists[c];
				long draws = stack.ncalloc(8, Math.max(1, lists.size()) * DRAW_BYTES, 1);
				long tintDraws = stack.ncalloc(8, Math.max(1, lists.size()) * DRAW_BYTES, 1);
				int n = 0, nTint = 0;
				// (FT2.8, audit idx 84) the near cascade's cutout lists top-down when the light is high: Sodium meshes a section from
				// its lowest y up and the lists walk the sections bottom-up, so every lower leaf layer was alpha-tested, written and then
				// overwritten by the one above (depth Greater); drawn from the top, the lower layers fail the depth test before shading.
				// The map is the per-texel maximum either way (mcopt.shade.shadowTopDown, read every frame; false = bottom-up)
				boolean topDown = c == 0 && this.light[1] > 0.5 && !"false".equals(System.getProperty("mcopt.shade.shadowTopDown"));
				for (ShadowCasters.DrawList l : lists) {
					if (l.count == 0 || l.arena.isClosed()) continue;
					boolean rev = topDown && l.kind == ShadowCasters.KIND_CUTOUT;
					if (rev) {
						java.nio.IntBuffer ib = MemoryUtil.memIntBuffer(chunkAt + (long) first * ShadowCasters.chunkBytes(), l.count * 2);
						for (int k = l.count - 1; k >= 0; k--) ib.put(l.chunks[k * 2]).put(l.chunks[k * 2 + 1]);
					} else {
						MemoryUtil.memIntBuffer(chunkAt + (long) first * ShadowCasters.chunkBytes(), l.count * 2).put(l.chunks, 0, l.count * 2);
					}
					boolean translucent = l.kind == ShadowCasters.KIND_TRANSLUCENT; // M3 coloured shadows: the tint pass, not the depth pass
					long d = (translucent ? tintDraws : draws) + (long) (translucent ? nTint : n) * DRAW_BYTES;
					MemoryUtil.memPutLong(d, MetalBridge.useBuffer(encoder, l.arena));
					MemoryUtil.memPutInt(d + 8, first);
					MemoryUtil.memPutInt(d + 12, l.count);
					MemoryUtil.memPutInt(d + 16, l.kind | (rev ? 256 : 0));
					first += l.count;
					if (translucent) nTint++; else n++;
				}
				ShadowCascade cascade = this.castCascades[c];
				long cascadeData = stack.ncalloc(16, CASCADE_BYTES, 1);
				// M3.3: a part of the back map is drawn with the back map's fit (receivers still read the bound one this frame)
				boolean back = c == 1 && this.farMode == 2;
				for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(cascadeData + i * 4L, back ? this.backMatrix[i] : this.matrices[c * 16 + i]);
				float depthPerTexel = (float) (cascade.texelWorld() / cascade.depthRange());
				MemoryUtil.memPutFloat(cascadeData + 64, ShadowConfig.BIAS_TEXELS * depthPerTexel);
				// slope-scaled raster bias, negative: reversed Z pushes casters away from the light by lowering depth
				// M3.3: far parts go into the back map (mode 2) or, the last one, into the just-swapped bound map (mode 3); every part after the
				// first keeps what the slice holds
				int load = c == 1 && this.farMode >= 2 && this.farPartNow > 0 ? 1 : 0;
				int timer = !this.timing ? -1 : c == 0 ? 0 : this.farMode == 1 ? 1 : 2;
				int gpuEncoder = ShadowNative.renderEx(enc, this.shadow, c, cascadeData, CASCADE_BYTES, chunksHandle, chunks.slice().offset(), regionsHandle,
					regions.slice().offset(), draws, n, atlas, -ShadowConfig.BIAS_SLOPE, -8f * depthPerTexel, back ? 1 : 0, load, timer);
				if (c == 0 && tint) {
					// M3 coloured shadows: the near cascade's translucent casters into the tint map (unbiased matrix: receivers compare their own depth)
					MemoryUtil.memPutFloat(cascadeData + 64, 0f);
					long params = stack.ncalloc(16, TINT_PARAM_BYTES, 1);
					mcopt.metal.shade.water.WaterFrame.rects(this.waterRects);
					for (int i = 0; i < 8; i++) MemoryUtil.memPutFloat(params + i * 4L, this.waterRects[i]);
					// mode.x = 1: leave water out (the water module absorbs the light on its way down to the floor itself)
					MemoryUtil.memPutFloat(params + 32, mcopt.metal.shade.water.ShadeWater.ON ? 1f : 0f);
					long translucentAtlas = MetalBridge.viewHandle(DefaultTerrainRenderPasses.TRANSLUCENT.getAtlas());
					// M3.3 pricing (mcopt.shade.tintDev, per frame): 8 = a 512^2 map, 2 = no pass (the last map is looked up), 1 = no lookup
					boolean lite = (tintDev & 8) != 0;
					if (lite && this.tintLite == 0) this.tintLite = ShadowNative.tintLiteInit(this.shadow, 512);
					lite &= this.tintLite != 0;
					this.tintRes = lite ? 512 : mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOW_RES;
					// M3.3 mcopt.shade.tintSub: draw (clear, store) only the casters' rectangle, at the map's texel size, in its top-left corner
					this.tintSubNow = ShadowConfig.tintSub() && nTint > 0 && (tintDev & 2) == 0;
					int[] area = this.tintSubNow && this.tintRect() ? this.tintSubMatrix(cascadeData, this.tintRes) : new int[2];
					if ((tintDev & 2) == 0) {
						ShadowNative.tintRenderEx(enc, this.shadow, cascadeData, CASCADE_BYTES, chunksHandle, chunks.slice().offset(), regionsHandle,
							regions.slice().offset(), tintDraws, nTint, translucentAtlas, params, TINT_PARAM_BYTES, lite ? 1 : 0, this.timing ? 3 : -1, area[0], area[1]);
					}
					this.publishTint(nTint > 0 && (tintDev & 1) == 0);
					mcopt.metal.shade.ShadeRender.resource(mcopt.metal.shade.weather.ShadeWeather.RES_SHADOW_TINT, lite ? this.tintLite : this.tintMap);
				}
				if (tracing) {
					System.out.println(String.format("[mcopt] shade trace: shadow cascade %d: %d sections, %d quads, %d chunks in %d draws%s", c, this.casters.sections[c],
						this.casters.quads[c], lists.stream().mapToInt(l -> l.count).sum(), n,
						gpuEncoder >= 0 ? " (gpu encoder #" + gpuEncoder + " in the profile below; later game encoders shift by one)" : ""));
				}
				if (ShadowConfig.STATS) {
					this.statQuads[c] += this.casters.quads[c];
					this.statSections[c] += this.casters.sections[c];
					this.statDraws[c]++;
				}
			}
		}
		switch (this.farMode) {
			case 1 -> { // whole (M3.2's redraw, or a jump in spread mode)
				this.framesSinceFar = 0;
				this.farLightKey = this.lightKey;
				this.statWhole++;
			}
			case 2 -> { // one part of the back map
				this.backPart++;
				this.framesSinceFar++;
			}
			case 3 -> { // the back map is complete and bound: the next frame starts the next one
				this.backPart = -1;
				this.framesSinceFar = 0;
				this.farLightKey = this.backLightKey;
				this.statSwaps++;
			}
			default -> this.framesSinceFar++;
		}
		this.renderOk = true;
		this.draw[0] = this.draw[1] = false;
		if (ShadowConfig.STATS) this.stats();
		if (this.timing || this.timesPrinted != 0) this.times();
		if (this.lastProblem != null) {
			System.out.println("[mcopt] shade: shadow pass running again");
			this.lastProblem = null;
		}
	}

	private final long[] statQuads = new long[2], statSections = new long[2];
	private final int[] statDraws = new int[2];
	private long statBuildNanos, statStart, statFrames;

	/** -Dmcopt.shade.shadowStats: averages every ~5 s (stdout; println, which the game's log captures). */
	private void stats() {
		long now = System.nanoTime();
		if (this.statStart == 0) this.statStart = now;
		this.statFrames++;
		if (now - this.statStart < 5_000_000_000L) return;
		System.out.println(String.format("[mcopt] shade stats: %d frames; near drawn %d x, avg %d sections / %d quads; far drawn %d x, avg %d sections / %d quads; "
			+ "caster listing %.3f ms/frame CPU; casters: %s; far spread %s (%d swaps, %d whole); tint boxes %d (tight %s, %d scans)", this.statFrames,
			this.statDraws[0], this.statSections[0] / Math.max(1, this.statDraws[0]),
			this.statQuads[0] / Math.max(1, this.statDraws[0]), this.statDraws[1], this.statSections[1] / Math.max(1, this.statDraws[1]),
			this.statQuads[1] / Math.max(1, this.statDraws[1]), this.statBuildNanos / 1e6 / this.statFrames, ShadowConfig.CULL_LIGHT ? "light" : "camera",
			ShadowConfig.farSpread(), this.statSwaps, this.statWhole, this.casters.tintCount, this.casters.tintTight, this.casters.tightScans));
		this.statSwaps = this.statWhole = 0;
		java.util.Arrays.fill(this.statQuads, 0);
		java.util.Arrays.fill(this.statSections, 0);
		java.util.Arrays.fill(this.statDraws, 0);
		this.statBuildNanos = 0;
		this.statFrames = 0;
		this.statStart = now;
	}

	/** M3.3 mcopt.shade.shadowTimes: the shadow passes' GPU timers every ~2 s (ms per run, runs). */
	private void times() {
		long now = System.nanoTime();
		if (this.timesPrinted == 0) {
			this.timesPrinted = now;
			return;
		}
		if (now - this.timesPrinted < 2_000_000_000L) return;
		this.timesPrinted = this.timing ? now : 0;
		ShadowNative.times(this.shadow, this.timesOut);
		float[] t = new float[9];
		for (int i = 0; i < 9; i++) t[i] = MemoryUtil.memGetFloat(this.timesOut + i * 4L);
		System.out.println(String.format(java.util.Locale.ROOT,
			"[mcopt] shade: shadow gpu ms near %.3f (%d) far %.3f (%d) farPart %.3f (%d) tint %.3f (%d), %d frames; spread %s, tintTight %s, tintSub %s, tintDev %d",
			t[0], (int) t[4], t[1], (int) t[5], t[2], (int) t[6], t[3], (int) t[7], (int) t[8], ShadowConfig.farSpread(), ShadowConfig.tintTight(),
			ShadowConfig.tintSub(), ShadowConfig.tintDev()));
	}

	private void problem(@Nullable String why) {
		if (why == null || why.equals(this.lastProblem)) return;
		this.lastProblem = why;
		System.out.println("[mcopt] shade: no shadow map this frame: " + why);
	}

	static @Nullable ShadowPass instance() {
		return instance;
	}

	double camX() {
		return this.camX;
	}

	double camY() {
		return this.camY;
	}

	double camZ() {
		return this.camZ;
	}

	/** Whether this frame draws the near cascade (fitted in beginFrame; false in the Nether / End, after a failure). */
	boolean nearThisFrame() {
		return this.prepared && this.draw[0] && !failed;
	}

	/** Frame counter (endFrame advances it). */
	long frame() {
		return this.frames;
	}

	private void create(long ctx) {
		String source;
		try (InputStream in = ShadowPass.class.getResourceAsStream("/mcopt/shade/shadow.metal")) {
			if (in == null) throw new IllegalStateException("missing /mcopt/shade/shadow.metal");
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 2048, 1);
			mcopt.metal.shade.ShadeRobust.maybeFail("shadow"); // M3.3: dev-only injected failure; inert unset
			long s = ShadowNative.create(ctx, MemoryUtil.memAddress(stack.UTF8(source)), ShadowConfig.RES, CASCADES, ShadowConfig.DEPTH_BITS, err, 2048);
			if (s == 0) throw new IllegalStateException("shadow pass setup: " + MemoryUtil.memUTF8(err));
			this.shadow = s;
		}
		EntityCasters.attach(this.shadow); // M3: entity draws cast into the near cascade
		this.texture = ShadowNative.texture(this.shadow);
		this.compareSampler = ShadowNative.compareSampler(this.shadow);
		if (mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOWS) { // M3 coloured shadows: the near cascade's tint map
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (ShadowNative.tintInit(this.shadow, mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOW_RES, err, 1024) == 0) {
					this.tintMap = ShadowNative.tintTexture(this.shadow);
					System.out.println("[mcopt] shade: coloured shadows " + mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOW_RES + "^2 (near cascade)");
				} else {
					mcopt.metal.Log.warn("[mcopt] shade: no coloured shadows: " + MemoryUtil.memUTF8(err));
				}
			}
		}
		System.out.println(String.format("[mcopt] shade: shadow map %dx%d x %d cascades (%s, %.0f MB), near %.0f / far %.0f blocks, casters: %s, faces: %s, far cutout: %s",
			ShadowConfig.RES, ShadowConfig.RES, CASCADES, ShadowConfig.DEPTH_BITS == 16 ? "Depth16Unorm" : "Depth32Float",
			CASCADES * (ShadowConfig.DEPTH_BITS / 8.0) * ShadowConfig.RES * ShadowConfig.RES / (1 << 20), ShadowConfig.NEAR, ShadowConfig.DIST,
			ShadowConfig.CULL_LIGHT ? "light" : "camera", ShadowConfig.ALL_FACES ? "all" : "sun-facing", ShadowConfig.FAR_CUTOUT_OPAQUE ? "opaque" : "alpha test"));
	}

	/** mcopt.shade.debug=1: both cascades as tiles in the top-right corner of the main target (near left, far right). */
	private void debugView() {
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		if (encoder == null || MetalBridge.inRenderPass(encoder)) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var color = main.getColorTexture();
		if (color == null) return;
		MetalBridge.flushClear(encoder, color);
		long enc = MetalBridge.enc(encoder);
		int size = Math.max(64, main.height / 4), margin = Math.max(4, main.height / 100);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long params = stack.ncalloc(16, CASCADES * DEBUG_BYTES, 1);
			for (int c = 0; c < CASCADES; c++) {
				ShadowCascade cascade = this.cascades[c];
				long at = params + (long) c * DEBUG_BYTES;
				MemoryUtil.memPutInt(at, c);
				MemoryUtil.memPutFloat(at + 4, (float) cascade.depthOf(this.camX, this.camY, this.camZ));
				MemoryUtil.memPutFloat(at + 8, (float) cascade.depthRange());
			}
			int x = main.width - CASCADES * (size + margin);
			int y = main.height - size - margin; // GL row order: high rows are the top of the screen
			ShadowNative.debug(enc, this.shadow, MetalBridge.textureHandle(color), x, y, size, margin, params, DEBUG_BYTES);
		}
	}
}
