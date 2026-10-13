package mcopt.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The frame caps: hidden window first, then the power cap near the refresh, else none. */
class OcclusionTest {
	@Test
	void visibleAndCoolIsUncapped() {
		assertEquals(0, Occlusion.capFps(false, 15, false, false, 120));
	}

	@Test
	void hiddenWinsOverPowerCap() {
		assertEquals(15, Occlusion.capFps(true, 15, true, false, 120));
	}

	@Test
	void hiddenLimitOffLeavesPowerCap() {
		assertEquals(144, Occlusion.capFps(true, 0, true, false, 120));
	}

	@Test
	void powerCapSitsAFifthAboveRefresh() {
		assertEquals(144, Occlusion.capFps(false, 15, true, false, 120));
		assertEquals(72, Occlusion.capFps(false, 15, true, false, 60));
		assertEquals(72, Occlusion.capFps(false, 15, true, false, 59.94));
	}

	@Test
	void unknownRefreshIsUncapped() {
		assertEquals(0, Occlusion.capFps(false, 15, true, false, 0));
	}

	@Test
	void vsyncCapSitsJustAboveRefresh() {
		// alpha.9 rendered 119 fps on a 75 Hz monitor with vsync on: capped at 79 now
		assertEquals(79, Occlusion.capFps(false, 15, false, true, 75));
		assertEquals(126, Occlusion.capFps(false, 15, false, true, 120));
		assertEquals(79, Occlusion.capFps(false, 15, true, true, 75)); // with the power cap too: the lower one
		assertEquals(15, Occlusion.capFps(true, 15, false, true, 75)); // hidden still wins
		assertEquals(0, Occlusion.capFps(false, 15, false, true, 0)); // refresh unknown: no cap
	}
}
