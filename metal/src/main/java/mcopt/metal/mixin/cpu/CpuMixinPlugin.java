package mcopt.metal.mixin.cpu;

import java.util.List;
import java.util.Set;
import mcopt.metal.cpu.Cpu;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** These mixins apply only when a -Dmcopt.cpu.* property turns their lever on, so by default the game is unchanged. */
public final class CpuMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (name.endsWith("Access")) return Cpu.cullHooks();
		if (name.startsWith("Cull")) return Cpu.cullHooks();
		if (name.startsWith("AbFrame")) return Cpu.AB_SHOTS || Cpu.PASS_AB || Cpu.LEASH_AB || Cpu.LISTS_AB || Cpu.MODEL_AB || Cpu.MERGE_AB || mcopt.metal.cpu.EntityBox.AB;
		if (name.equals("ListRegionCacheMixin") && Cpu.LISTS) return !yields(name, "net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector", "getForChunk");
		if (name.startsWith("List")) return Cpu.LISTS;
		if (name.startsWith("Leash")) return Cpu.LEASH;
		if (name.startsWith("DrawMerge")) return Cpu.MERGE_ON;
		if (name.startsWith("Model")) return Cpu.MODEL;
		if (name.startsWith("EntityBox") || name.startsWith("EntityRendererBox")) return mcopt.metal.cpu.EntityBox.ON;
		if (name.startsWith("Loading")) return Cpu.LOADING_FPS > 0;
		return false;
	}

	/** A @Redirect fails at startup when another mod redirects the same call: step aside then (MixinConflicts). */
	private static boolean yields(String name, String owner, String call) {
		String mod = mcopt.metal.MixinConflicts.hookedBy(owner, call);
		if (mod != null) mcopt.metal.Log.info("mcopt-cpu: " + name + " not applied: " + mod + " has a mixin into " + owner + " that names " + call);
		return mod != null;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
