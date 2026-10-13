package mcopt.metal.mixin.qos;

import java.util.List;
import java.util.Set;
import mcopt.metal.Qos;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** The QoS mixins apply only for the roles a -Dmcopt.qos.ROLE property names, so by default the game is unchanged. */
public final class QosMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String role = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1).replace("QosMixin", "").toLowerCase(java.util.Locale.ROOT);
		return Qos.has(role) && mcopt.metal.SodiumCompat.ok(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
