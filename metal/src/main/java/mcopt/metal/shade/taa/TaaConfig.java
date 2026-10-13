/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.taa;

import java.util.Locale;

/**
 * Temporal anti-aliasing / upscaling settings (shader bible E18, §5), read once at startup from system properties (or
 * config/mcopt.properties, which mcopt's Profile turns into the same properties first). Free of game types.
 *
 * <p>Flags:
 * <ul>
 * <li>{@code mcopt.shade.taa} (true): TAA/TAAU on while native shading is on. false = M1's native, unjittered render.</li>
 * <li>{@code mcopt.shade.preset} (Shade.PRESET_DEFAULT; Shade.TIER, which every module reads): performance | balanced | quality | ultra, see {@link Preset}.</li>
 * <li>{@code mcopt.shade.scale}: start scale (overrides the preset's); {@code mcopt.shade.scaleMin} / {@code scaleMax}: the
 * dynamic resolution range. The world targets are allocated at scaleMax, so a narrower range also saves memory.</li>
 * <li>{@code mcopt.shade.drs} (true): dynamic resolution; {@code mcopt.shade.drsFps}: its target frame rate (preset's).</li>
 * <li>{@code mcopt.shade.taaMode} (preset's): responsive | stable (history weight and clip box).</li>
 * <li>{@code mcopt.shade.sharpen}: sharpening strength 0..1 (default by scale: 0.25 native .. 0.4 at 0.67x).</li>
 * <li>{@code mcopt.shade.mipBias} (true): sharper texture mips at reduced scale (log2 scale), like native.</li>
 * <li>{@code mcopt.shade.taaDebug} (0): 1 history age, 2 rejection (red clip, green disocclusion, blue off-screen), 3 no history, 4 reprojection check (unclipped history 50/50 over the current frame).</li>
 * <li>{@code mcopt.shade.drsLog} (false): one log line per resolution change.</li>
 * <li>{@code mcopt.shade.taaLossy} (false): lossy-compressed colour history (Apple8+).</li>
 * </ul>
 * Never below 0.6x, and no preset starts below 0.67x (critique C2): fewer pixels than that is below what the user ran at
 * 1080p before.
 */
public final class TaaConfig {
	/**
	 * The presets: start scale, dynamic resolution range, target fps, history mode. performance and balanced aim at the 120 Hz
	 * panel; quality at 80 (native resolution, the 120 Hz panel's VRR range), ultra at 60. The ranges and targets are the bible's §5 table.
	 */
	public enum Preset {
		PERFORMANCE(0.67f, 0.60f, 0.75f, 120, false),
		BALANCED(0.80f, 0.60f, 0.90f, 120, true), // floor 0.60 under load (bible §5 fallback: water-heavy views pinned it at 0.67)
		QUALITY(1.00f, 0.85f, 1.00f, 80, true),
		ULTRA(1.00f, 0.90f, 1.00f, 60, true);

		public final float scale, min, max;
		public final int fps;
		public final boolean stable;

		Preset(float scale, float min, float max, int fps, boolean stable) {
			this.scale = scale;
			this.min = min;
			this.max = max;
			this.fps = fps;
			this.stable = stable;
		}
	}

	/** The absolute floor of any scale (a transient dip; presets never start this low). */
	public static final float FLOOR = 0.6f;

	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.shade.taa", "true"));
	public static final Preset PRESET = Preset.values()[mcopt.metal.shade.Shade.TIER];
	public static final float SCALE_MAX = clamp(floatProp("mcopt.shade.scaleMax", PRESET.max), FLOOR, 1f);
	public static final float SCALE_MIN = clamp(floatProp("mcopt.shade.scaleMin", PRESET.min), FLOOR, SCALE_MAX);
	public static final float SCALE = clamp(floatProp("mcopt.shade.scale", PRESET.scale), SCALE_MIN, SCALE_MAX);
	public static final boolean DRS = Boolean.parseBoolean(System.getProperty("mcopt.shade.drs", "true")) && SCALE_MIN < SCALE_MAX;
	/** The player set mcopt.shade.drsFps: kept as given instead of following the display. */
	public static final boolean DRS_FPS_SET = System.getProperty("mcopt.shade.drsFps") != null;
	public static final int DRS_FPS = Math.max(20, Integer.getInteger("mcopt.shade.drsFps", PRESET.fps));
	public static final boolean STABLE = switch (System.getProperty("mcopt.shade.taaMode", "").toLowerCase(Locale.ROOT)) {
		case "stable" -> true;
		case "responsive" -> false;
		default -> PRESET.stable;
	};
	/** Negative: by scale (sharpen(scale)). */
	private static final float SHARPEN = floatProp("mcopt.shade.sharpen", -1f);
	public static final boolean MIP_BIAS = Boolean.parseBoolean(System.getProperty("mcopt.shade.mipBias", "true"));
	/** Lossy framebuffer compression for the colour history (Apple8+): less bandwidth, tiny error. Off by default (TAAU.md). */
	public static final boolean LOSSY = Boolean.getBoolean("mcopt.shade.taaLossy");
	public static final int DEBUG = Integer.getInteger("mcopt.shade.taaDebug", 0);
	public static final boolean DRS_LOG = Boolean.getBoolean("mcopt.shade.drsLog");

	private TaaConfig() {
	}

	private static float floatProp(String key, float fallback) {
		String v = System.getProperty(key);
		if (v == null || v.isBlank()) return fallback;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			mcopt.metal.Log.warn("[mcopt] shade: " + key + "=" + v + " (config/mcopt.properties or a -D flag) is not valid, using " + fallback);
			return fallback;
		}
	}

	static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Sharpening strength at a scale: the bible's 0.25 at native rising to 0.4 at 0.67x, unless mcopt.shade.sharpen sets it. */
	public static float sharpen(float scale) {
		if (SHARPEN >= 0f) return Math.min(SHARPEN, 1f);
		return 0.25f + 0.15f * clamp((1f - scale) / 0.33f, 0f, 1f);
	}

	/** Minimum weight of the current frame (Responsive 0.12, Stable 0.08). */
	public static float alphaMin() {
		return STABLE ? 0.08f : 0.12f;
	}

	/** Floor of the current frame's weight on moving pixels (full from 0.5 px/frame; Responsive 0.25, Stable 0.18): keeps texture detail in motion. */
	public static float motionAlpha() {
		return STABLE ? 0.18f : 0.25f;
	}

	/** Variance clip box size in standard deviations (Responsive 1.0, Stable 1.25), +0.5 while the camera is still. */
	public static float clipGamma(boolean still) {
		return (STABLE ? 1.25f : 1.0f) + (still ? 0.5f : 0f);
	}

	public static String describe() {
		return String.format(Locale.ROOT, "preset %s, scale %.2f (%.2f-%.2f%s), %s, sharpen %.2f%s", PRESET.name().toLowerCase(Locale.ROOT), SCALE, SCALE_MIN, SCALE_MAX,
			DRS ? ", dynamic to hold " + DRS_FPS + " fps" : ", fixed", STABLE ? "stable" : "responsive", sharpen(SCALE), MIP_BIAS ? "" : ", no mip bias");
	}
}
