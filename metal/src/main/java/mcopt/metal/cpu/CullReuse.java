/*
 * Portions of this file are copied or adapted from Sodium (https://github.com/CaffeineMC/sodium), Copyright
 * JellySquid (jellysquid3) and contributors. Those portions are licensed under the PolyForm Shield License 1.0.0
 * (LICENSES/PolyForm-Shield-1.0.0.md, https://polyformproject.org/licenses/shield/1.0.0); the rest of the
 * file is Apache-2.0 like the rest of mcopt. See NOTICE.
 */
package mcopt.metal.cpu;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import mcopt.metal.mixin.cpu.CullBiForestAccess;
import mcopt.metal.mixin.cpu.CullMultiForestAccess;
import mcopt.metal.mixin.cpu.CullTaskTreeAccess;
import mcopt.metal.mixin.cpu.CullTreeAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.Tree;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;

/**
 * Skips Sodium cull passes that could only rebuild the trees already in use (-Dmcopt.cpu.cullReuse).
 *
 * <p>Sodium starts an async cull on every frame the camera changed. A pass builds three trees: LOCAL (frustum-tested, ray
 * occlusion; used only once the camera holds still: Sodium drops it on every frame the camera changed), REGULAR (what the
 * render lists are read from while the camera moves; the render thread frustum-tests it as it walks it) and WIDE (fallback
 * for a neighbouring chunk, plus the chunk-build task list). REGULAR and WIDE depend on the camera only through
 * <ol>
 * <li>its section (the search origin, the angle masks, the slope test, the outward directions, the tree offsets);</li>
 * <li>which sections pass the search-distance test, which reads the exact position: separable into one test per section column
 * (xz) and one per section row (y), both evaluated here with Sodium's own float arithmetic for both positions;</li>
 * <li>the camera's 26 neighbour sections the search didn't reach, which addNearbySections adds when they pass a looser frustum
 * test (recorded on the cull thread, re-tested here for both views);</li>
 * <li>the task list's priorities (a frustum bonus): only when the list isn't empty, so a non-empty list never skips;</li>
 * </ol>
 * and otherwise only on the section graph (every change of which goes through markGraphDirty, counted here), the search
 * distances and the occlusion switch. A frame where the camera changed but none of these did gets the trees a pass would
 * rebuild, so the pass is skipped. When the camera then holds still, one pass runs for the final view so LOCAL exists, as it
 * would have had the last moving frame's pass started.
 *
 * <p>verify: Sodium culls every time; each pass the rule would have skipped is compared bit for bit with the trees of the
 * last pass the rule would have run.
 */
public final class CullReuse {
	static final int OK = 0, NO_REF = 1, DIRTY = 2, PARAMS = 3, CHUNK = 4, PENDING = 5, DIST = 6, NEARBY = 7, STILL = 8;
	private static final String[] REASONS = {"ok", "noRef", "dirty", "params", "chunk", "pending", "dist", "nearby", "still"};

	/** Inputs of one cull pass. */
	private static final class Inputs {
		Viewport viewport;
		long chunk;
		float distRegular, distLocal;
		boolean occlusion;
		long graphGen;
	}

	private long graphGen;
	private Inputs ref, pendingIn;
	private boolean pendingSkippable;
	private CullTask pendingTask;
	private final RenderSection[] refNearby = new RenderSection[26];
	private int refNearbyCount;
	private SectionTree refRegular, refWide;
	private boolean skippedSinceRef;
	private Viewport lastViewport;
	private float[] ax = new float[0], bx = ax, az = ax, bz = ax;
	private boolean broken;

	// stats, per bench phase
	private String phase = "";
	private final Map<String, long[]> byPhase = new LinkedHashMap<>();
	private long[] s = new long[S_N];
	private static final int S_FRAMES = 0, S_CULLS = 1, S_SKIPS = 2, S_SETTLE = 3, S_COALESCED = 4, S_CULL_NS = 5, S_RULE_NS = 6,
		S_RULES = 7, S_MOVED = 8, S_VERIFY_EQ = 9, S_VERIFY_NE = 10, S_GRAPH = 11, S_VERIFY_RACY = 12, S_DIRTY_EQUAL = 13, S_STALE_N = 14, S_STALE_NS = 15, S_STALE_MAX_NS = 16, S_STALE_FRAMES = 17, S_STALE_HIST = 18, STALE_BUCKETS = 2001,
		S_REASON = S_STALE_HIST + STALE_BUCKETS, S_CANCELLED = S_REASON + REASONS.length, S_LOST = S_CANCELLED + 1, S_LOST_NS = S_CANCELLED + 2,
		S_LOST_MAX_NS = S_CANCELLED + 3, S_N = S_CANCELLED + 4;
	private int mismatchLogs;

	public void frame() {
		if (!Cpu.CULL_STATS) return;
		String p = Cpu.phase();
		if (!p.equals(phase)) {
			if (!phase.isEmpty()) print(phase, s);
			phase = p;
			s = new long[S_N];
			byPhase.put(byPhase.size() + ":" + p, s);
		}
		s[S_FRAMES]++;
		frameNo++;
	}

	// staleness: per frame with a graph change, the time and frames until a pass scheduled after it is in use
	private long frameNo;
	private final long[] staleGen = new long[4096], staleTime = new long[4096], staleFrame = new long[4096];
	private int staleHead, staleTail;

	private void staleMark() {
		if (staleTail != staleHead && staleFrame[(staleTail - 1) & 4095] == frameNo) {
			staleGen[(staleTail - 1) & 4095] = graphGen;
			return;
		}
		if (((staleTail + 1) & 4095) == staleHead) staleHead = (staleHead + 1) & 4095; // full: drop the oldest
		staleGen[staleTail] = graphGen;
		staleTime[staleTail] = System.nanoTime();
		staleFrame[staleTail] = frameNo;
		staleTail = (staleTail + 1) & 4095;
	}

	private void staleResolve(long passGen) {
		long now = System.nanoTime();
		while (staleHead != staleTail && staleGen[staleHead] <= passGen) {
			long ns = now - staleTime[staleHead];
			s[S_STALE_N]++;
			s[S_STALE_NS] += ns;
			s[S_STALE_MAX_NS] = Math.max(s[S_STALE_MAX_NS], ns);
			s[S_STALE_FRAMES] += frameNo - staleFrame[staleHead];
			s[S_STALE_HIST + (int) Math.min(STALE_BUCKETS - 1, ns / 500_000)]++; // 0.5 ms buckets to 1 s
			staleHead = (staleHead + 1) & 4095;
		}
	}

	private static double staleP99(long[] c) {
		long n = c[S_STALE_N], acc = 0;
		for (int i = 0; i < STALE_BUCKETS; i++) {
			acc += c[S_STALE_HIST + i];
			if (acc >= n * 0.99) return (i + 1) * 0.5;
		}
		return STALE_BUCKETS * 0.5;
	}

	public void graphDirty() {
		s[S_GRAPH]++;
		// verify: a dirty mark from chunk builds whose every graph change was content-equal (see sectionChanged) doesn't count,
		// so the passes after it get compared
		if (inBuilds && realChanges == 0 && equalChanges > 0) {
			s[S_DIRTY_EQUAL]++;
			return;
		}
		graphGen++;
		if (Cpu.CULL_STATS) staleMark();
	}

	private static boolean inBuilds;
	private static int realChanges, equalChanges;
	private static long equalTotal, realTotal;

	public static void beginBuilds() {
		inBuilds = true;
		realChanges = equalChanges = 0;
	}

	public static void endBuilds() {
		inBuilds = false;
	}

	/**
	 * A rebuilt section reported a graph change; equal = its flags and visibility data are the same as before. Returns whether
	 * Sodium should still see it as a change: with skipping on, content-equal rebuilds don't dirty the graph.
	 */
	public static boolean sectionChanged(boolean equal) {
		if (equal) {
			equalChanges++;
			equalTotal++;
			return !Cpu.CULL_SKIP;
		}
		realChanges++;
		realTotal++;
		return true;
	}

	/** A cull was due but the previous one is still running (Sodium then doesn't start one). */
	public void coalesced() {
		s[S_COALESCED]++;
	}

	/**
	 * scheduleAsyncWork is about to start a pass (no pass pending). Returns true to skip it. Records the pass's inputs otherwise.
	 */
	public boolean beforeSchedule(Viewport viewport, float distRegular, float distLocal, boolean occlusion, boolean cameraChanged,
								  boolean needsGraphUpdate, Map<CullType, SectionTree> cullResults, int minSectionY, int maxSectionY) {
		boolean skippable = false;
		if (!broken && (Cpu.CULL_SKIP || Cpu.CULL_VERIFY || Cpu.CULL_STATS)) {
			try {
				long t0 = System.nanoTime();
				int reason = cameraChanged ? evaluate(viewport, distRegular, distLocal, occlusion, needsGraphUpdate, cullResults, minSectionY, maxSectionY) : STILL;
				s[S_RULE_NS] += System.nanoTime() - t0;
				s[S_RULES]++;
				s[S_REASON + reason]++;
				if (lastViewport != null && !sameTransform(lastViewport.getTransform(), viewport.getTransform())) s[S_MOVED]++;
				lastViewport = viewport;
				skippable = reason == OK;
			} catch (RuntimeException e) {
				broken = true;
				mcopt.metal.Log.warn("mcopt-cpu: cull reuse off after an error; Sodium culls every frame as without it", e);
			}
		}
		if (skippable && Cpu.CULL_SKIP) {
			skippedSinceRef = true;
			s[S_SKIPS]++;
			return true;
		}
		Inputs in = new Inputs();
		in.viewport = viewport;
		in.chunk = viewport.getChunkCoord().asLong();
		in.distRegular = distRegular;
		in.distLocal = distLocal;
		in.occlusion = occlusion;
		in.graphGen = graphGen;
		pendingIn = in;
		pendingSkippable = skippable && Cpu.CULL_VERIFY;
		return false;
	}

	private long lostAt;

	/** Sodium cancelled a scheduled pass that hadn't started; lost = nothing (camera, dirty flag) will schedule another. */
	public void cancelled(boolean lost) {
		s[S_CANCELLED]++;
		if (lost) {
			s[S_LOST]++;
			if (lostAt == 0) lostAt = System.nanoTime();
		}
	}

	public void scheduled(CullTask task) {
		pendingTask = task;
		s[S_CULLS]++;
		if (lostAt != 0) {
			long ns = System.nanoTime() - lostAt;
			lostAt = 0;
			s[S_LOST_NS] += ns;
			s[S_LOST_MAX_NS] = Math.max(s[S_LOST_MAX_NS], ns);
		}
	}

	/** Sodium took a finished pass's results into cullResults. */
	public void consumed(CullTask task, Map<CullType, SectionTree> cullResults, OcclusionCuller culler) {
		s[S_CULL_NS] += Math.max(0, task.getElapsedNanos());
		if (task != pendingTask || pendingIn == null) {
			ref = null; // not a pass we saw start: no reference until the next one
			return;
		}
		SectionTree regular = cullResults.get(CullType.REGULAR), wide = cullResults.get(CullType.WIDE);
		if (pendingSkippable && ref != null) {
			// a graph change while the pass ran may or may not have been seen by it (Sodium reads the graph unlocked); with
			// skipping, the next frame re-culls for it (dirty), so only passes that ran on an unchanged graph are comparable
			if (graphGen != pendingIn.graphGen) s[S_VERIFY_RACY]++;
			else verify(regular, wide);
		} else {
			ref = pendingIn;
			refRegular = regular;
			refWide = wide;
			CullerNearby nearby = (CullerNearby) culler;
			refNearbyCount = nearby.mcopt$nearbyCount();
			System.arraycopy(nearby.mcopt$nearby(), 0, refNearby, 0, refNearbyCount);
			skippedSinceRef = false;
		}
		if (Cpu.CULL_STATS && pendingIn != null) staleResolve(pendingIn.graphGen);
		pendingTask = null;
		pendingIn = null;
		pendingSkippable = false;
	}

	/** After prepareRenderTrees: the camera holds still after skipped passes, so run one for the final view (LOCAL). */
	public boolean wantsSettle(boolean cameraChanged, boolean needsGraphUpdate, boolean nothingPending) {
		if (Cpu.CULL_SKIP && skippedSinceRef && !cameraChanged && !needsGraphUpdate && nothingPending) {
			s[S_SETTLE]++;
			return true;
		}
		return false;
	}

	private int evaluate(Viewport viewport, float distRegular, float distLocal, boolean occlusion, boolean needsGraphUpdate,
						 Map<CullType, SectionTree> cullResults, int minSectionY, int maxSectionY) {
		Inputs r = ref;
		if (r == null) return NO_REF;
		// skipping keeps the reference pass's trees in use; verify holds them aside while Sodium replaces them
		if (Cpu.CULL_SKIP && (cullResults.get(CullType.REGULAR) != refRegular || cullResults.get(CullType.WIDE) != refWide)) return NO_REF;
		if (needsGraphUpdate || graphGen != r.graphGen) return DIRTY;
		if (occlusion != r.occlusion || Float.floatToRawIntBits(distRegular) != Float.floatToRawIntBits(r.distRegular)
			|| Float.floatToRawIntBits(distLocal) != Float.floatToRawIntBits(r.distLocal)) return PARAMS;
		SectionPos origin = viewport.getChunkCoord();
		if (origin.asLong() != r.chunk) return CHUNK;
		if (!(refWide instanceof CullTaskTreeAccess wide) || !wide.mcopt$pending().isEmpty()) return PENDING;
		if (!sameDistanceOutcomes(r.viewport.getTransform(), viewport.getTransform(), origin, distRegular, minSectionY, maxSectionY)) return DIST;
		for (int i = 0; i < refNearbyCount; i++) {
			RenderSection n = refNearby[i];
			if (OcclusionCuller.isWithinNearbySectionFrustum(r.viewport, n) != OcclusionCuller.isWithinNearbySectionFrustum(viewport, n)) return NEARBY;
		}
		return OK;
	}

	private static boolean sameTransform(CameraTransform a, CameraTransform b) {
		return a.intX == b.intX && a.intY == b.intY && a.intZ == b.intZ
			&& Float.floatToRawIntBits(a.fracX) == Float.floatToRawIntBits(b.fracX)
			&& Float.floatToRawIntBits(a.fracY) == Float.floatToRawIntBits(b.fracY)
			&& Float.floatToRawIntBits(a.fracZ) == Float.floatToRawIntBits(b.fracZ);
	}

	/**
	 * Whether every section passes OcclusionCuller.visitNode's search-distance test at both camera positions alike. The test is
	 * xz-part && y-part, each a function of one coordinate's section index, so comparing every column and every row covers every
	 * section. Same float expressions as Sodium's.
	 */
	boolean sameDistanceOutcomes(CameraTransform a, CameraTransform b, SectionPos origin, float maxDistance, int minSectionY, int maxSectionY) {
		if (sameTransform(a, b)) return true;
		int k = (int) Math.ceil(maxDistance / 16.0F) + 2, n = 2 * k + 1;
		if (ax.length < n) {
			ax = new float[n];
			bx = new float[n];
			az = new float[n];
			bz = new float[n];
		}
		for (int i = 0; i < n; i++) {
			float dxa = axis(origin.getX() - k + i, a.intX, a.fracX), dxb = axis(origin.getX() - k + i, b.intX, b.fracX);
			float dza = axis(origin.getZ() - k + i, a.intZ, a.fracZ), dzb = axis(origin.getZ() - k + i, b.intZ, b.fracZ);
			ax[i] = dxa * dxa;
			bx[i] = dxb * dxb;
			az[i] = dza * dza;
			bz[i] = dzb * dzb;
		}
		float max2 = maxDistance * maxDistance;
		for (int i = 0; i < n; i++) {
			float xa = ax[i], xb = bx[i];
			for (int j = 0; j < n; j++) {
				if ((xa + az[j] < max2) != (xb + bz[j] < max2)) return false;
			}
		}
		for (int y = minSectionY; y <= maxSectionY; y++) {
			if ((Math.abs(axis(y, a.intY, a.fracY)) < maxDistance) != (Math.abs(axis(y, b.intY, b.fracY)) < maxDistance)) return false;
		}
		return true;
	}

	/** visitNode's per-axis distance: nearestToZero(o - 1, o + 17) - frac with o the section's origin minus the camera's integer part. */
	private static float axis(int section, int cameraInt, float cameraFrac) {
		int o = (section << 4) - cameraInt;
		int min = o - 1, max = o + 17, clamped = 0;
		if (min > 0) clamped = min;
		if (max < 0) clamped = max;
		return clamped - cameraFrac;
	}

	private void verify(SectionTree regular, SectionTree wide) {
		long dr = diffBits(refRegular, regular), dw = diffBits(refWide, wide);
		boolean pendingSame = wide instanceof CullTaskTreeAccess w && w.mcopt$pending().isEmpty();
		if (dr == 0 && dw == 0 && pendingSame) {
			s[S_VERIFY_EQ]++;
			return;
		}
		s[S_VERIFY_NE]++;
		if (mismatchLogs++ < 20) {
			CameraTransform a = ref.viewport.getTransform(), b = pendingIn.viewport.getTransform();
			System.out.printf("mcopt-cpu: cullReuse verify MISMATCH regular %d bits, wide %d bits, pending %s; ref (%.5f %.5f %.5f) now (%.5f %.5f %.5f) chunk %s%n",
				dr, dw, pendingSame ? "same" : "differs", a.x, a.y, a.z, b.x, b.y, b.z, pendingIn.viewport.getChunkCoord());
		}
	}

	/** Differing present bits between two trees' forests (-1 when their layouts differ). */
	static long diffBits(SectionTree a, SectionTree b) {
		if (a == null || b == null) return a == b ? 0 : -1;
		long[][] la = bits(a), lb = bits(b);
		if (la.length != lb.length) return -1;
		long d = 0;
		for (int i = 0; i < la.length; i++) {
			long[] x = la[i], y = lb[i];
			if (x == null && y == null) continue;
			for (int j = 0; j < 4096; j++) d += Long.bitCount((x == null ? 0 : x[j]) ^ (y == null ? 0 : y[j]));
		}
		return d;
	}

	/** Differing present bits between two forests of the same kind (-1 when their layouts differ). */
	public static long diffForests(Object a, Object b) {
		long[][] la = forestBits(a), lb = forestBits(b);
		if (la.length != lb.length) return -1;
		long d = 0;
		for (int i = 0; i < la.length; i++) {
			long[] x = la[i], y = lb[i];
			if (x == null && y == null) continue;
			for (int j = 0; j < 4096; j++) d += Long.bitCount((x == null ? 0 : x[j]) ^ (y == null ? 0 : y[j]));
		}
		return d;
	}

	private static long[][] bits(SectionTree t) {
		return forestBits(t.tree);
	}

	private static long[][] forestBits(Object forest) {
		Tree[] trees;
		if (forest instanceof CullBiForestAccess bi) trees = new Tree[] {bi.mcopt$main(), bi.mcopt$secondary()};
		else trees = ((CullMultiForestAccess) forest).mcopt$trees();
		long[][] out = new long[trees.length][];
		for (int i = 0; i < trees.length; i++) out[i] = trees[i] == null ? null : ((CullTreeAccess) trees[i]).mcopt$bits();
		return out;
	}

	public void print(String p, long[] c) {
		StringBuilder b = new StringBuilder("mcopt-cpu: cull phase=").append(p);
		b.append(" frames=").append(c[S_FRAMES]).append(" culls=").append(c[S_CULLS]).append(" skips=").append(c[S_SKIPS])
			.append(" settle=").append(c[S_SETTLE]).append(" coalesced=").append(c[S_COALESCED]).append(" moved=").append(c[S_MOVED])
			.append(" graphDirty=").append(c[S_GRAPH]).append(" dirtyEqualOnly=").append(c[S_DIRTY_EQUAL])
			.append(String.format(" cullMs=%.3f", c[S_CULLS] == 0 ? 0 : c[S_CULL_NS] / 1e6 / Math.max(1, c[S_CULLS])))
			.append(String.format(" ruleUs=%.2f", c[S_RULES] == 0 ? 0 : c[S_RULE_NS] / 1e3 / c[S_RULES]));
		for (int i = 0; i < REASONS.length; i++) b.append(' ').append(REASONS[i]).append('=').append(c[S_REASON + i]);
		b.append(" cancelled=").append(c[S_CANCELLED]).append(" lostUpdates=").append(c[S_LOST]);
		if (c[S_LOST] > 0) b.append(String.format(" lostMeanMs=%.1f lostMaxMs=%.1f", c[S_LOST_NS] / 1e6 / Math.max(1, c[S_LOST]), c[S_LOST_MAX_NS] / 1e6));
		if (c[S_STALE_N] > 0) b.append(String.format(" staleN=%d staleMeanMs=%.2f staleP99Ms<=%.2f staleMaxMs=%.1f staleMeanFrames=%.2f", c[S_STALE_N],
			c[S_STALE_NS] / 1e6 / c[S_STALE_N], staleP99(c), c[S_STALE_MAX_NS] / 1e6, (double) c[S_STALE_FRAMES] / c[S_STALE_N]));
		if (Cpu.CULL_VERIFY) b.append(" verifyEq=").append(c[S_VERIFY_EQ]).append(" verifyNe=").append(c[S_VERIFY_NE]).append(" verifyRacy=").append(c[S_VERIFY_RACY]);
		System.out.println(b);
	}

	public void printAll() {
		if (!Cpu.CULL_STATS) return;
		for (Map.Entry<String, long[]> e : byPhase.entrySet()) print(e.getKey(), e.getValue());
		System.out.println("mcopt-cpu: section graph changes from builds: real " + realTotal + ", content-equal " + equalTotal);
	}

	static String reasons() {
		return Arrays.toString(REASONS);
	}
}
