package mcopt.metal.mixin;

import mcopt.metal.Telemetry;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3 shows a Metal line next to the fps: GPU time per frame, how long the render thread waited on the GPU, GPU memory
 * against macOS's budget, the display's refresh and the thermal state (Telemetry, refreshed every mcopt.log.tick
 * seconds). It tells at a glance whether a low frame rate is the GPU, the CPU, memory or a hot machine. -Dmcopt.f3=false
 * hides it; optional (require 0): a changed F3 entry only loses the line.
 */
@Mixin(DebugEntryFps.class)
abstract class MetalDebugMixin {
	@Inject(method = "display", at = @At("TAIL"), require = 0)
	private void mcopt$metalLine(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk, CallbackInfo ci) {
		String line = Telemetry.f3Line();
		if (line != null && !"false".equals(System.getProperty("mcopt.f3"))) displayer.addPriorityLine(line);
	}
}
