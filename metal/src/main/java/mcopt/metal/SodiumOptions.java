package mcopt.metal;

import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The "Metal Shaders" page in Video Settings (Sodium's config API, entrypoint sodium:config_api_user): the settings that
 * used to need config/mcopt.properties, where the Iris shader-pack button sits in a pack like Fabulously Optimized.
 * Every value is saved to config/mcopt.properties (ConfigFile), so the file and the screen always agree. Settings the
 * game only reads at startup are marked "requires restart" (Sodium asks for it); the rest apply from the next frame.
 */
public final class SodiumOptions implements ConfigEntryPoint {
	enum Preset { PERFORMANCE, BALANCED, QUALITY, ULTRA }
	enum Look { EPIC, STORYBOOK, NATURAL }
	enum Resolution { AUTO, NATIVE, P1800, P1600, P1440, P1200, P1080 }
	enum FrameGeneration { OFF, PACED, ON }
	enum Profile { ALPHA, RECOMMENDED, NONE }

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("mcopt-metal", path);
	}

	private static Component text(String s) {
		return Component.literal(s);
	}

	private static boolean bool(String key, boolean fallback) {
		return Boolean.parseBoolean(ConfigFile.get(key, Boolean.toString(fallback)));
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String key, E fallback) {
		String v = ConfigFile.get(key, fallback.name()).trim().toUpperCase(Locale.ROOT);
		try {
			return Enum.valueOf(type, v);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static String lower(Enum<?> e) {
		return e.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public void registerConfigLate(ConfigBuilder b) {
		boolean iris = DhCompat.irisLoaded();

		BooleanOptionBuilder metal = b.createBooleanOption(id("metal"))
			.setName(text("Metal Renderer"))
			.setTooltip(text("Renders the game with Apple Metal instead of OpenGL: the shaders below need it."
				+ (iris ? " Iris is installed, and Iris only runs on OpenGL: disable Iris first, or the game starts on OpenGL anyway." : "")))
			.setImpact(OptionImpact.HIGH)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(true)
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("mcopt.metal", Boolean.toString(v)), () -> bool("mcopt.metal", !iris));

		BooleanOptionBuilder shaders = b.createBooleanOption(id("shaders"))
			.setName(text("Shaders"))
			.setTooltip(text("Metal Shaders: sun and moon shadows, volumetric clouds and light, reflective water, HDR, dynamic lights. Off = Metal without shaders."))
			.setImpact(OptionImpact.HIGH)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(true)
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("mcopt.shade", Boolean.toString(v)), () -> bool("mcopt.shade", true));

		EnumOptionBuilder<Preset> preset = b.createEnumOption(id("preset"), Preset.class)
			.setName(text("Shader Quality"))
			.setTooltip(text("Performance and Balanced hold the display's refresh rate with dynamic resolution; Quality and Ultra trade frame rate for sharpness and effects."))
			.setImpact(OptionImpact.HIGH)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(Preset.BALANCED)
			.setElementNameProvider(e -> text(new String[] {"Performance", "Balanced", "Quality", "Ultra"}[e.ordinal()]))
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("mcopt.shade.preset", lower(v)), () -> enumValue(Preset.class, "mcopt.shade.preset", Preset.BALANCED));

		EnumOptionBuilder<Look> look = b.createEnumOption(id("look"), Look.class)
			.setName(text("Look"))
			.setTooltip(text("Art style: Epic (vivid, cinematic), Storybook (soft, warm), Natural (close to vanilla colours)."))
			.setImpact(OptionImpact.LOW)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(Look.EPIC)
			.setElementNameProvider(e -> text(new String[] {"Epic", "Storybook", "Natural"}[e.ordinal()]))
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("mcopt.shade.look", lower(v)), () -> enumValue(Look.class, "mcopt.shade.look", Look.EPIC));

		EnumOptionBuilder<Resolution> resolution = b.createEnumOption(id("resolution"), Resolution.class)
			.setName(text("Render Resolution"))
			.setTooltip(text("The world's render height; the GUI stays sharp at the screen's resolution. Auto: dynamic resolution holds the preset's frame rate (capped at the display's refresh). Applies right away; also in the pause menu."))
			.setImpact(OptionImpact.HIGH)
			.setDefaultValue(Resolution.AUTO)
			.setElementNameProvider(e -> text(new String[] {"Auto", "Native", "1800p", "1600p", "1440p", "1200p", "1080p"}[e.ordinal()]))
			.setStorageHandler(() -> {})
			.setBinding(v -> mcopt.metal.shade.ShadeResolution.choose(v.ordinal(), ConfigFile.path().getParent().getParent()),
				() -> Resolution.values()[mcopt.metal.shade.ShadeResolution.choice()]);

		EnumOptionBuilder<FrameGeneration> frameGen = b.createEnumOption(id("framegen"), FrameGeneration.class)
			.setName(text("Frame Generation"))
			.setTooltip(text("MetalFX frame interpolation: renders every other frame and generates the ones between, about twice the frames on screen at a little more latency. Paced: the same pacing without generated frames. Needs VSync."))
			.setImpact(OptionImpact.VARIES)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(FrameGeneration.OFF)
			.setElementNameProvider(e -> text(new String[] {"Off", "Paced", "On"}[e.ordinal()]))
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("mcopt.metal.framegen", v == FrameGeneration.ON ? "true" : v == FrameGeneration.PACED ? "paced" : "false"),
				() -> switch (ConfigFile.get("mcopt.metal.framegen", "false").trim()) {
					case "true" -> FrameGeneration.ON;
					case "paced" -> FrameGeneration.PACED;
					default -> FrameGeneration.OFF;
				});

		BooleanOptionBuilder vsyncCap = b.createBooleanOption(id("vsync_cap"))
			.setName(text("Cap to Display Refresh"))
			.setTooltip(text("With VSync on, stops rendering frames faster than the display the window is on can show (a 75 Hz monitor next to a 120 Hz MacBook screen). Applies right away."))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(true)
			.setStorageHandler(Occlusion::reload)
			.setBinding(v -> live("mcopt.vsyncCap", Boolean.toString(v)), () -> bool("mcopt.vsyncCap", true));

		BooleanOptionBuilder powerCap = b.createBooleanOption(id("power_cap"))
			.setName(text("Save Power When Hot"))
			.setTooltip(text("While macOS reports thermal throttling or Low Power Mode, caps frames just above the display's refresh: same picture, cooler chip, steadier clocks. Applies right away."))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(true)
			.setStorageHandler(Occlusion::reload)
			.setBinding(v -> live("mcopt.powerCap", Boolean.toString(v)), () -> bool("mcopt.powerCap", true));

		IntegerOptionBuilder hiddenFps = b.createIntegerOption(id("hidden_fps"))
			.setName(text("Hidden Window FPS"))
			.setTooltip(text("Frame rate while the game window is completely hidden (covered, another Space). 0 = no limit. Applies right away."))
			.setImpact(OptionImpact.LOW)
			.setRange(0, 60, 5)
			.setValueFormatter(v -> text(v == 0 ? "No limit" : v + " fps"))
			.setDefaultValue(15)
			.setStorageHandler(Occlusion::reload)
			.setBinding(v -> live("mcopt.occludedFps", Integer.toString(v)), () -> {
				try {
					return Math.clamp(Integer.parseInt(ConfigFile.get("mcopt.occludedFps", "15").trim()), 0, 60);
				} catch (NumberFormatException e) {
					return 15;
				}
			});

		BooleanOptionBuilder f3 = b.createBooleanOption(id("f3"))
			.setName(text("Metal Line on F3"))
			.setTooltip(text("A line on the F3 screen: GPU time per frame, time waiting on the GPU, GPU memory, display refresh, thermal state. Applies right away."))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(true)
			.setStorageHandler(() -> {})
			.setBinding(v -> live("mcopt.f3", Boolean.toString(v)), () -> bool("mcopt.f3", true));

		EnumOptionBuilder<Profile> profile = b.createEnumOption(id("profile"), Profile.class)
			.setName(text("Optimization Profile"))
			.setTooltip(text("Alpha: all of mcopt's measured speed-ups (chunk meshing, render lists, startup). Recommended: the safest subset. None: the Metal renderer and shaders only."))
			.setImpact(OptionImpact.MEDIUM)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setDefaultValue(Profile.ALPHA)
			.setElementNameProvider(e -> text(new String[] {"Alpha", "Recommended", "None"}[e.ordinal()]))
			.setStorageHandler(() -> {})
			.setBinding(v -> ConfigFile.set("profile", lower(v)), () -> enumValue(Profile.class, "profile", Profile.ALPHA));

		b.registerOwnModOptions()
			.setName("Metal Shaders")
			.addPage(b.createOptionPage()
				.setName(text("Metal Shaders"))
				.addOptionGroup(b.createOptionGroup().setName(text("Renderer")).addOption(metal).addOption(shaders))
				.addOptionGroup(b.createOptionGroup().setName(text("Shaders")).addOption(preset).addOption(look).addOption(resolution))
				.addOptionGroup(b.createOptionGroup().setName(text("Frame Rate")).addOption(frameGen).addOption(vsyncCap).addOption(powerCap).addOption(hiddenFps))
				.addOptionGroup(b.createOptionGroup().setName(text("Advanced")).addOption(f3).addOption(profile)));
	}

	/** A setting the game reads while it runs: the running value now, and the file for the next launch. */
	private static void live(String key, String value) {
		System.setProperty(key, value);
		ConfigFile.set(key, value);
	}
}
