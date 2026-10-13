package mcopt.metal.mixin;

import mcopt.metal.Telemetry;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A "Metal Shaders" section in every crash report and F3 system report: the device, profile, Sodium compatibility, the
 * hooks yielded to other mods, the power state and the last telemetry tick (Telemetry.report). Optional: a changed
 * SystemReport only loses the section.
 */
@Mixin(SystemReport.class)
abstract class SystemReportMixin {
	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void mcopt$section(CallbackInfo ci) {
		((SystemReport) (Object) this).setDetail("Metal Shaders", () -> {
			try {
				return Telemetry.report();
			} catch (Throwable t) { // a report is being written because something broke: never add to it
				return "unavailable (" + t + ")";
			}
		});
	}
}
