package mcopt.metal.mixin.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons 3.3.3-3.3.4 on 26.3 asks which API the game renders with ({@code MinecraftRenderWrapper.getMcRenderingApi})
 * and answers VULKAN only for a backend named "Vulkan", OPEN_GL for anything else. On mcopt's Metal backend that sends it
 * down its OpenGL branches (a GlTexture cast of the lightmap: ClassCastException on the first frame). Its Blaze3D renderer
 * only uses renderpearl objects, which mcopt implements, and Metal shares Vulkan's conventions that DH cares about (0..1
 * clip depth, top-left origin), so for the "Metal" backend the answer is VULKAN. Any other backend gets DH's own answer.
 * <p>
 * Re-implementation of the idea in mcopt PR #4 by CazzaCoder. DH is not a compile dependency (it is LGPL-3.0 and shipped
 * unmodified): the target is named by string, the enum constant is looked up at run time, and the injector has
 * require = 0, so a DH that renames the method leaves the game unchanged instead of crashing. Applied only by
 * {@link CompatMixinPlugin} (DH loaded, tested version, Metal on).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
abstract class DhRenderApiMixin {
	@Unique
	private static Object mcopt$vulkan;

	@Inject(method = "getMcRenderingApi", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcopt$metalLikeVulkan(CallbackInfoReturnable<Object> cir) {
		Object api = mcopt$vulkan;
		if (api == null) {
			try {
				if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) return;
				Class<?> e = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi", false, this.getClass().getClassLoader());
				for (Object c : e.getEnumConstants()) if (((Enum<?>) c).name().equals("VULKAN")) api = c;
			} catch (Throwable t) {
				mcopt.metal.Log.warn("[mcopt] compat: Distant Horizons render-API check left unchanged", t);
				return;
			}
			if (api == null) return;
			mcopt$vulkan = api;
			System.out.println("[mcopt] compat: Distant Horizons sees the Metal backend as VULKAN (renderpearl, 0..1 depth)");
		}
		cir.setReturnValue(api);
	}
}
