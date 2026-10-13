package mcopt.metal.shade.taa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The hold controller against a steady GPU load: it must spend the frame budget the target gives it, no more. */
class DynamicResolutionTest {
	/** Runs frames of a fixed GPU time (cost model: half fixed, half with the scale's pixels) and returns the final scale. */
	private static float run(DynamicResolution drs, double msAtFullScale, int frames) {
		double t = 1;
		for (int i = 0; i < frames; i++) {
			float s = drs.update();
			double ms = msAtFullScale * (0.5 + 0.5 * s * s);
			drs.frame(t, t + ms / 1e3);
			t += 0.05; // frames never overlap: each one's busy time is its whole GPU time
		}
		return drs.scale();
	}

	@Test
	void retargetKeepsBaseFps() {
		DynamicResolution drs = new DynamicResolution(0.8f, 0.5f, 1f, 120);
		drs.setFps(60);
		assertEquals(60, drs.fps());
		assertEquals(120, drs.baseFps());
		drs.setFps(0); // ignored
		assertEquals(60, drs.fps());
	}

	@Test
	void overBudgetDropsScale() {
		// 12 ms at full scale is over 120 fps's 8.3 ms budget: the scale must come down
		float s = run(new DynamicResolution(0.8f, 0.5f, 1f, 120), 12, 600);
		assertTrue(s < 0.8f, "scale " + s);
	}

	@Test
	void sixtyHertzKeepsResolution() {
		// the same load fits a 60 Hz display's 16.7 ms: the scale holds or climbs instead of dropping for unseen frames
		DynamicResolution drs = new DynamicResolution(0.8f, 0.5f, 1f, 120);
		drs.setFps(60);
		float s = run(drs, 12, 600);
		assertTrue(s >= 0.8f, "scale " + s);
	}

	@Test
	void settlesUnderBudget() {
		// whatever it settles at, the modelled frame fits under the drop threshold of the budget
		DynamicResolution drs = new DynamicResolution(1f, 0.4f, 1f, 120);
		float s = run(drs, 14, 2000);
		double ms = 14 * (0.5 + 0.5 * s * s);
		assertTrue(ms <= 0.965 * 1000.0 / 120 || s <= 0.55f + 1e-6, "scale " + s + " frame " + ms + " ms");
	}
}
