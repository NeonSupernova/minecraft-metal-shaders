package mcopt.metal.mixin.chunk;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mcopt.metal.chunk.ChunkOpt;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Each chunk mixin applies only when its -Dmcopt.chunk.* switch is set (ChunkOpt.mixinEnabled), so by default nothing changes.
 * Mixins that rewrite methods other optimization mods also rewrite step aside when such a mod is loaded (that mod's version of
 * the optimization then applies): Lithium (POI scans, random ticks, chunk access), ScalableLux (the light engine), C2ME (chunk
 * IO, serialization, chunk access). The ones that @Overwrite a Sodium method also step aside when any other mod has a
 * mixin into that method (MixinConflicts): an overwrite would make that mod's injector fail at startup (Polytone and Iris
 * on BlockRenderer.renderModel).
 */
public final class ChunkMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}
	private static final Map<String, List<String>> YIELDS_TO = Map.of(
		"AcquirePoiMixin", List.of("lithium"),
		"ServerLevelTickMixin", List.of("lithium"),
		"ChunkAccessSectionMixin", List.of("lithium", "c2me"),
		"DataLayerStorageMapMixin", List.of("scalablelux"),
		"BlockLightMapMixin", List.of("scalablelux"),
		"SkyLightMapMixin", List.of("scalablelux"),
		"PalettedContainerFactoryMixin", List.of("c2me"));
	/** The Sodium method each @Overwrite mixin replaces: owner, then method names. */
	private static final Map<String, List<String>> OVERWRITES = Map.of(
		"BlockRendererMixin", List.of("net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", "renderModel"),
		"LevelSliceAirMixin", List.of("net.caffeinemc.mods.sodium.client.world.LevelSlice", "copySectionData"),
		"LevelSliceBoundsMixin", List.of("net.caffeinemc.mods.sodium.client.world.LevelSlice", "getBlockState"),
		"DirectionalVisGraphMixin", List.of("net.caffeinemc.mods.sodium.client.render.chunk.occlusion.DirectionalVisGraph", "resolveWithDirections"),
		"LevelBiomeSliceMixin", List.of("net.caffeinemc.mods.sodium.client.world.biome.LevelBiomeSlice", "calculateUniform"));

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (!ChunkOpt.mixinEnabled(name)) return false;
		for (String mod : YIELDS_TO.getOrDefault(name, List.of())) {
			if (FabricLoader.getInstance().isModLoaded(mod)) {
				mcopt.metal.Log.info("mcopt-chunk: " + name + " not applied: " + mod + " is loaded");
				return false;
			}
		}
		List<String> target = OVERWRITES.get(name);
		if (target != null) {
			String mod = mcopt.metal.MixinConflicts.hookedBy(target.get(0), target.subList(1, target.size()).toArray(String[]::new));
			if (mod != null) {
				mcopt.metal.Log.info("mcopt-chunk: " + name + " not applied: " + mod + " has a mixin into " + target.get(0) + "." + target.get(1));
				return false;
			}
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
