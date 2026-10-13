package mcopt.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The frame caps: hidden window first, then the power cap near the refresh, else none. */
class OcclusionTest {
	@Test
	void visibleAndCoolIsUncapped() {
		assertEquals(0, Occlusion.capFps(false, 15, false, 120));
	}

	@Test
	void hiddenWinsOverPowerCap() {
		assertEquals(15, Occlusion.capFps(true, 15, true, 120));
	}

	@Test
	void hiddenLimitOffLeavesPowerCap() {
		assertEquals(144, Occlusion.capFps(true, 0, true, 120));
	}

	@Test
	void powerCapSitsAFifthAboveRefresh() {
		assertEquals(144, Occlusion.capFps(false, 15, true, 120));
		assertEquals(72, Occlusion.capFps(false, 15, true, 60));
		assertEquals(72, Occlusion.capFps(false, 15, true, 59.94));
	}

	@Test
	void unknownRefreshIsUncapped() {
		assertEquals(0, Occlusion.capFps(false, 15, true, 0));
	}
}
