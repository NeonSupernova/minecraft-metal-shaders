/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.mixin.shade;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * M3.3: vanilla's "Improved Transparency" (26.3: order-independent transparency, also part of the Fabulous graphics preset)
 * stays off while the native shading runs, as Iris does with Fabulous: every one of the game's "use it?" checks goes through
 * GameRenderer.useImprovedTransparency(), and with it on the translucent terrain, rain and cutout leaves take the game's OIT
 * pipelines, which the shading doesn't own: unshaded water without reflections, opaque dark-blue rain streaks, greyed trees
 * (M3.3 robustness run rb1, rb_transp_on_*). The shading draws water, glass and rain itself. The option keeps its value and
 * comes back with mcopt.shade=false. {@code mcopt.shade.oitOff} (default true, read every call): false = M3.2 (the option
 * as set).
 */
@Mixin(GameRenderer.class)
abstract class ShadeOitMixin {
	@Unique private static boolean mcopt$logged;

	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void mcopt$noOit(CallbackInfoReturnable<Boolean> cir) {
		if ("false".equals(System.getProperty("mcopt.shade.oitOff"))) return;
		if (!mcopt$logged && net.minecraft.client.Minecraft.getInstance().options.improvedTransparency().get()) {
			mcopt$logged = true;
			mcopt.metal.Log.warn("[mcopt] shade: Improved Transparency is on in the video settings: kept off while the shaders run (they draw water, glass and rain"
				+ " themselves; -Dmcopt.shade.oitOff=false uses it anyway)");
		}
		cir.setReturnValue(false);
	}
}
