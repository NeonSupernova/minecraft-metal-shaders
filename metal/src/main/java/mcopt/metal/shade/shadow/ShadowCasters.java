// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Which Sodium geometry casts into each cascade this frame, as lists of 64-quad chunks per (cascade, pass kind, arena),
 * read straight from Sodium's section render data (SectionRenderDataStorage / SectionRenderDataUnsafe): the same base
 * vertex and per-facing vertex counts its own renderer turns into draws. Read-only: nothing in Sodium is changed, so this
 * is indifferent to mcopt's list/culling mixins (mcopt.cpu.lists, cullRecover, cullReuse).
 * <ul>
 * <li>{@code camera} mode: the sections in Sodium's render lists for this frame (frustum + occlusion culled for the camera).</li>
 * <li>{@code light} mode: every section of every loaded region whose box can cast into the cascade (the light frustum),
 * whether or not the camera sees it: a mountain behind the player, a roof overhead.</li>
 * </ul>
 * Facing: only the face directions that face the light (Sodium's per-facing ranges), plus UNASSIGNED quads (non-axis
 * aligned: plants), unless mcopt.shade.shadowFaces=all.
 */
final class ShadowCasters {
	/** KIND_TRANSLUCENT (M3 coloured shadows): water, stained glass, ice..., near cascade only, drawn into the tint map. */
	static final int KIND_SOLID = 0, KIND_CUTOUT = 1, KIND_TRANSLUCENT = 2;
	private static final int CHUNK_QUADS = 32; // mcshadow.m QUAD_INDICES: 32-quad chunks halve the padding launches of 64
	private static final int REGION_BYTES = 16, CHUNK_BYTES = 8;
	private static final int UNASSIGNED = 6;
	/** Sodium's RenderSectionManager, private in SodiumWorldRenderer (mcopt's Lod reads it the same way). */
	private static final @Nullable Field RSM = rsmField();

	/** A list of chunks drawn with one instanced draw: one arena, one kind, one cascade. */
	static final class DrawList {
		GpuBuffer arena; // reused from a pool (M3.3): set by listFor
		int kind;
		/** firstVertex, packed pairs. */
		int[] chunks = new int[256];
		int count;

		DrawList(GpuBuffer arena, int kind) {
			this.arena = arena;
			this.kind = kind;
		}

		void add(int firstVertex, int packed) {
			if (this.count * 2 == this.chunks.length) this.chunks = java.util.Arrays.copyOf(this.chunks, this.chunks.length * 2);
			this.chunks[this.count * 2] = firstVertex;
			this.chunks[this.count * 2 + 1] = packed;
			this.count++;
		}
	}

	/** Per cascade: its draw lists this frame (empty when the cascade isn't redrawn). */
	@SuppressWarnings("unchecked")
	final List<DrawList>[] lists = new List[] {new ArrayList<DrawList>(), new ArrayList<DrawList>()};
	/** ShadowRegion records (camera-relative origin + pad), regionCount of them. */
	long regions = MemoryUtil.nmemAlloc((long) REGION_BYTES * 256);
	private int regionCapacity = 256;
	int regionCount;
	/** Sections and quads listed, per cascade, for stats. */
	final int[] sections = new int[2];
	final long[] quads = new long[2];
	/** Why the last build found nothing to do, or null. */
	@Nullable String problem;
	/**
	 * M3 coloured shadows: the near cascade's translucent casters listed for the tint pass this build, as world block boxes
	 * (x0 y0 z0 x1 y1 z1, max exclusive: a section's 16^3 box, or with tintTight the box of its translucent blocks), for the
	 * casters' light-space rectangle (ShadowPass).
	 */
	int[] tintBoxes = new int[6 * 64];
	int tintCount;
	/**
	 * M3.3 far cascade spread: when farPart >= 0, cascade 1 lists only the sections of that part (farParts parts: section x, z
	 * on a lattice, so every part is spread evenly over the map); -1 lists them all (M3.2).
	 */
	int farPart = -1, farParts = 1;
	/**
	 * M3.3 (mcopt.shade.tintDev 4 / mcopt.shade.tintTight): the tint rectangle from the translucent blocks themselves, not their
	 * sections' 16^3 boxes (sections holding none are not drawn either). Only while water is left out of the tint pass (the
	 * blocks are read from the client level; water is a fluid, not a block model).
	 */
	boolean tintTight;
	/** Sections scanned for their translucent blocks: section key -> {signature, frame scanned, x0, y0, z0, x1, y1, z1} (empty: x0 > x1). */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> tightCache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(); // (no boxed keys per lookup)
	/** tightBox's result (world block coordinates, max exclusive). */
	private final int[] tightOut = new int[6];
	private final java.util.IdentityHashMap<BlockState, Boolean> translucentStates = new java.util.IdentityHashMap<>();
	private @Nullable BlockStateModelSet translucentStatesFor;
	private int buildFrame;
	/** Sections scanned this build (stats). */
	int tightScans;

	private static @Nullable Field rsmField() {
		try {
			Field f = SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			mcopt.metal.Log.warn("[mcopt] shade: no Sodium render section manager, no shadow casters", e);
			return null;
		}
	}

	/**
	 * Builds this frame's lists for the cascades with draw[c] set. Camera at world (camX, camY, camZ), as this frame's
	 * terrain uses it. faceMask[c]: Sodium ModelQuadFacing bits to draw. Returns false when Sodium has nothing to give.
	 */
	boolean build(ShadowCascade[] cascades, boolean[] draw, int faceMask, double camX, double camY, double camZ, boolean lightMode, boolean tint,
		boolean tintNoWater) {
		this.tint = tint && draw[0];
		this.tintCount = 0;
		this.tightScans = 0;
		this.buildFrame++;
		this.tintLevel = null;
		this.tintModels = null;
		if (this.tint && tintNoWater) {
			// sections whose block palette has no translucent model (stained glass, ice, slime, honey...) hold only water there
			Minecraft mc = Minecraft.getInstance();
			this.tintLevel = mc.level;
			try {
				this.tintModels = mc.getModelManager().getBlockStateModelSet();
			} catch (RuntimeException e) {
				this.tintModels = null; // models not loaded yet: list every translucent section
			}
		}
		for (int c = 0; c < 2; c++) {
			this.pool.addAll(this.lists[c]); // M3.3: lists are reused frame to frame (the far parts list every frame now)
			this.lists[c].clear();
			this.sections[c] = 0;
			this.quads[c] = 0;
		}
		this.regionCount = 0;
		this.problem = null;
		SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
		if (sodium == null || RSM == null) {
			this.problem = "no Sodium world renderer";
			return false;
		}
		RenderSectionManager rsm;
		try {
			rsm = (RenderSectionManager) RSM.get(sodium);
		} catch (ReflectiveOperationException | RuntimeException e) {
			this.problem = "Sodium render section manager: " + e;
			return false;
		}
		if (rsm == null) {
			this.problem = "no Sodium render section manager";
			return false;
		}
		CameraTransform camera = new CameraTransform(camX, camY, camZ);
		if (lightMode) {
			for (RenderRegion region : rsm.regions.getLoadedRegions()) this.region(region, null, cascades, draw, faceMask, camera);
		} else {
			Iterator<ChunkRenderList> it = rsm.getRenderLists().iterator(false);
			while (it.hasNext()) {
				ChunkRenderList list = it.next();
				this.region(list.getRegion(), list, cascades, draw, faceMask, camera);
			}
		}
		// solid (no fragment stage) before cutout (discard): what's drawn first lets hidden surface removal skip the rest
		for (int c = 0; c < 2; c++) this.lists[c].sort(java.util.Comparator.comparingInt(l -> l.kind));
		return true;
	}

	private final boolean[] sectionIn = new boolean[2];
	/** (FT2.8, audit idx 87) sectionCasts' result per section of the region being listed (castStamp == castNow: valid), so the
	 * solid / cutout / translucent passes of a section don't repeat its two cascade box tests. */
	private final int[] castStamp = new int[RenderRegion.REGION_SIZE];
	private final byte[] castBits = new byte[RenderRegion.REGION_SIZE];
	private int castNow;
	/** M3.3 far spread: the section indices of this frame's part in the region being walked (light mode). */
	private final int[] partIndex = new int[RenderRegion.REGION_SIZE];
	/** This build also lists the near cascade's translucent casters (KIND_TRANSLUCENT). */
	private boolean tint;
	/**
	 * With the water module on (tintNoWater) the tint pass leaves water out (its light under water is the water module's: M2.1
	 * absorbs it on the way down to the floor), so sections with nothing but water in the translucent pass are not listed:
	 * the client level and its block models, to read a section's palette (null: list every translucent section).
	 */
	private @Nullable ClientLevel tintLevel;
	private @Nullable BlockStateModelSet tintModels;
	private final java.util.function.Predicate<BlockState> translucentModel = st -> !st.isAir() && this.tintModels != null
		&& this.tintModels.get(st).hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);

	/**
	 * Whether section (sx, sy, sz) (section coordinates) may hold a block with a translucent model: its palette's states (a
	 * palette keeps states that were replaced until it is rebuilt, and the global palette answers yes: never a false no).
	 */
	private boolean maybeTranslucentBlocks(int sx, int sy, int sz) {
		ClientLevel level = this.tintLevel;
		if (level == null || this.tintModels == null) return true;
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) return true;
		int i = chunk.getSectionIndexFromSectionY(sy);
		if (i < 0 || i >= chunk.getSectionsCount()) return true;
		return chunk.getSection(i).maybeHas(this.translucentModel);
	}

	private void addTintBox(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (this.tintCount * 6 == this.tintBoxes.length) this.tintBoxes = java.util.Arrays.copyOf(this.tintBoxes, this.tintBoxes.length * 2);
		int i = this.tintCount * 6;
		this.tintBoxes[i] = x0;
		this.tintBoxes[i + 1] = y0;
		this.tintBoxes[i + 2] = z0;
		this.tintBoxes[i + 3] = x1;
		this.tintBoxes[i + 4] = y1;
		this.tintBoxes[i + 5] = z1;
		this.tintCount++;
	}

	private boolean translucentState(BlockState st) {
		Boolean b = this.translucentStates.get(st);
		if (b == null) {
			b = this.translucentModel.test(st);
			this.translucentStates.put(st, b);
		}
		return b;
	}

	/**
	 * M3.3 tintTight: the box of section (sx, sy, sz)'s blocks with a translucent model (stained glass and panes, ice, slime,
	 * honey...), world block coordinates, max exclusive; null when it holds none. Cached per section, scanned again when its
	 * translucent geometry (signature: base vertex, vertex counts) changes, and at least every 2 s (an edit that keeps the
	 * counts). Falls back to the whole section when the level or the models are not there. The box goes into {@link #tightOut}
	 * (no allocation per section and frame); false when the section holds none.
	 */
	private boolean tightBox(int sx, int sy, int sz, long ptr) {
		ClientLevel level = this.tintLevel;
		if (level == null || this.tintModels == null) return this.wholeBox(sx, sy, sz);
		if (this.translucentStatesFor != this.tintModels) {
			this.translucentStates.clear();
			this.tightCache.clear();
			this.translucentStatesFor = this.tintModels;
		}
		long sig = SectionRenderDataUnsafe.getBaseVertex(ptr) * 31L + SectionRenderDataUnsafe.getSliceMask(ptr);
		for (int i = 0; i < 7; i++) sig = sig * 131L + SectionRenderDataUnsafe.getVertexCount(ptr, i);
		long key = net.minecraft.core.SectionPos.asLong(sx, sy, sz);
		int[] c = this.tightCache.get(key);
		if (c != null && c[0] == (int) (sig ^ (sig >>> 32)) && this.buildFrame - c[1] < 240) {
			if (c[2] > c[5]) return false;
			System.arraycopy(c, 2, this.tightOut, 0, 6);
			return true;
		}
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) return this.wholeBox(sx, sy, sz);
		int si = chunk.getSectionIndexFromSectionY(sy);
		if (si < 0 || si >= chunk.getSectionsCount()) return this.wholeBox(sx, sy, sz);
		net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(si);
		int x0 = 16, y0 = 16, z0 = 16, x1 = -1, y1 = -1, z1 = -1;
		if (section.maybeHas(this.translucentModel)) {
			for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
				if (!this.translucentState(section.getBlockState(x, y, z))) continue;
				x0 = Math.min(x0, x);
				y0 = Math.min(y0, y);
				z0 = Math.min(z0, z);
				x1 = Math.max(x1, x);
				y1 = Math.max(y1, y);
				z1 = Math.max(z1, z);
			}
		}
		this.tightScans++;
		if (this.tightCache.size() > 4096) this.tightCache.clear();
		int[] entry = {(int) (sig ^ (sig >>> 32)), this.buildFrame, sx * 16 + x0, sy * 16 + y0, sz * 16 + z0, sx * 16 + x1 + 1, sy * 16 + y1 + 1, sz * 16 + z1 + 1};
		this.tightCache.put(key, entry);
		if (x0 > x1) return false;
		System.arraycopy(entry, 2, this.tightOut, 0, 6);
		return true;
	}

	/** tightBox's fallback: section (sx, sy, sz)'s whole 16^3 box into tightOut. */
	private boolean wholeBox(int sx, int sy, int sz) {
		int[] b = this.tightOut;
		b[0] = sx * 16; b[1] = sy * 16; b[2] = sz * 16;
		b[3] = b[0] + 16; b[4] = b[1] + 16; b[5] = b[2] + 16;
		return true;
	}

	/** M3.3 far spread: the part (0..parts-1) of section column (sx, sz): a lattice, so every 2 x 2 columns hold one of each of 4. */
	static int part(int sx, int sz, int parts) {
		return Math.floorMod(sx + sz * ((parts + 1) / 2), parts);
	}

	/** One region: its sections that cast into a cascade being drawn, from list (camera mode) or all of them (light mode). */
	private void region(RenderRegion region, @Nullable ChunkRenderList list, ShadowCascade[] cascades, boolean[] draw, int faceMask, CameraTransform camera) {
		RenderRegion.DeviceResources resources = region.getResources();
		if (resources == null) return;
		GpuBuffer arena = resources.getGeometryBuffer();
		if (arena == null || arena.isClosed()) return;
		double ox = region.getOriginX(), oy = region.getOriginY(), oz = region.getOriginZ();
		boolean any = false, near = false;
		for (int c = 0; c < 2; c++) {
			if (draw[c] && cascades[c].casts(ox, oy, oz, ox + RenderRegion.REGION_WIDTH * 16, oy + RenderRegion.REGION_HEIGHT * 16, oz + RenderRegion.REGION_LENGTH * 16)) {
				any = true;
				if (c == 0) near = true;
			}
		}
		if (!any) return;
		this.listStart[0] = this.lists[0].size();
		this.listStart[1] = this.lists[1].size();
		// M3.3 far spread: a region only the far cascade wants this frame skips the other parts' sections before reading them
		boolean partOnly = !near && this.farPart >= 0;
		// light mode, far part only: walk just that part's sections (a quarter of the region's 256), not all of them
		int partSections = 0;
		if (partOnly && list == null) {
			for (int lx = 0; lx < 8; lx++) for (int lz = 0; lz < 8; lz++) {
				if (part(region.getChunkX() + lx, region.getChunkZ() + lz, this.farParts) != this.farPart) continue;
				for (int ly = 0; ly < RenderRegion.REGION_HEIGHT; ly++) this.partIndex[partSections++] = lx << 5 | lz << 2 | ly;
			}
		}
		int regionIndex = -1;
		this.castNow++;
		for (int kind = 0; kind < (this.tint ? 3 : 2); kind++) {
			TerrainRenderPass pass = kind == KIND_SOLID ? DefaultTerrainRenderPasses.SOLID : kind == KIND_CUTOUT ? DefaultTerrainRenderPasses.CUTOUT
				: DefaultTerrainRenderPasses.TRANSLUCENT;
			SectionRenderDataStorage storage = region.getStorage(pass);
			if (storage == null) continue;
			// Far cascade, cutout: at 1/8 block per texel a leaf's 1/16-block holes are below the map's resolution, so axis-aligned
			// cutout faces (leaves, glass, vines) are drawn as opaque (no fragment stage, no discard: hidden surface removal keeps
			// working) and unassigned cutout quads (grass, flowers, crops) are left out, whose distant shadows would only be noise.
			boolean farOpaque = kind == KIND_CUTOUT && ShadowConfig.FAR_CUTOUT_OPAQUE;
			int[] kinds = {kind, farOpaque ? KIND_SOLID : kind};
			int[] masks = {faceMask, farOpaque ? faceMask & ~(1 << UNASSIGNED) : faceMask};
			DrawList[] lists = new DrawList[2];
			var it = list != null ? list.sectionsWithGeometryIterator(false) : null;
			if (list != null && it == null) return;
			for (int i = 0; ; i++) {
				int section;
				if (it != null) {
					if (!it.hasNext()) break;
					section = it.nextByteAsInt();
				} else if (partOnly) {
					if (i == partSections) break;
					section = this.partIndex[i];
				} else {
					if (i == RenderRegion.REGION_SIZE) break;
					section = i;
				}
				if (partOnly && part(region.getChunkX() + (section >> 5 & 7), region.getChunkZ() + (section >> 2 & 7), this.farParts) != this.farPart) continue;
				long ptr = storage.getDataPointer(section);
				if ((SectionRenderDataUnsafe.getSliceMask(ptr) & faceMask) == 0) continue;
				if (!this.sectionCasts(region, section, cascades, draw)) continue;
				if (kind == KIND_TRANSLUCENT) {
					// the tint pass draws the near cascade only; water-only sections are skipped when the water module is on
					if (!this.sectionIn[0]) continue;
					int sx = region.getChunkX() + (section >> 5 & 7), sy = region.getChunkY() + (section & 3), sz = region.getChunkZ() + (section >> 2 & 7);
					if (this.tintLevel != null && !this.maybeTranslucentBlocks(sx, sy, sz)) continue;
					if (this.tintTight && this.tintLevel != null) {
						if (!this.tightBox(sx, sy, sz, ptr)) continue; // only water (or stale palette entries): nothing tints here
						int[] b = this.tightOut;
						this.addTintBox(b[0], b[1], b[2], b[3], b[4], b[5]);
					} else {
						this.addTintBox(sx * 16, sy * 16, sz * 16, sx * 16 + 16, sy * 16 + 16, sz * 16 + 16);
					}
				}
				if (regionIndex < 0) regionIndex = this.addRegion(region, camera);
				for (int c = 0; c < 2; c++) {
					if (!this.sectionIn[c] || (kind == KIND_TRANSLUCENT && c != 0)) continue;
					if (lists[c] == null) lists[c] = this.listFor(c, arena, kinds[c]);
					this.emit(ptr, masks[c], regionIndex, lists[c], c);
				}
			}
		}
	}

	/** Sets sectionIn[c] for the section's 16^3 box; true if it casts into any cascade being drawn. */
	private boolean sectionCasts(RenderRegion region, int section, ShadowCascade[] cascades, boolean[] draw) {
		if (this.castStamp[section] == this.castNow) {
			int b = this.castBits[section];
			this.sectionIn[0] = (b & 1) != 0;
			this.sectionIn[1] = (b & 2) != 0;
			return b != 0;
		}
		// Sodium's LocalSectionIndex: x bits 5-7, y bits 0-1, z bits 2-4
		double x = (region.getChunkX() + (section >> 5 & 7)) * 16.0, y = (region.getChunkY() + (section & 3)) * 16.0, z = (region.getChunkZ() + (section >> 2 & 7)) * 16.0;
		boolean any = false;
		for (int c = 0; c < 2; c++) {
			this.sectionIn[c] = draw[c] && (c == 0 || this.farPart < 0 || part((int) x >> 4, (int) z >> 4, this.farParts) == this.farPart)
				&& cascades[c].casts(x, y, z, x + 16, y + 16, z + 16);
			any |= this.sectionIn[c];
		}
		this.castStamp[section] = this.castNow;
		this.castBits[section] = (byte) ((this.sectionIn[0] ? 1 : 0) | (this.sectionIn[1] ? 2 : 0));
		return any;
	}

	private DrawList listFor(int cascade, GpuBuffer arena, int kind) {
		// every region has its own arena, so only the lists this region made can match (M3.3: was a scan of all of them)
		List<DrawList> ls = this.lists[cascade];
		for (int i = this.listStart[cascade]; i < ls.size(); i++) {
			DrawList l = ls.get(i);
			if (l.arena == arena && l.kind == kind) return l;
		}
		DrawList l = this.pool.isEmpty() ? new DrawList(arena, kind) : this.pool.remove(this.pool.size() - 1);
		l.arena = arena;
		l.kind = kind;
		l.count = 0;
		ls.add(l);
		return l;
	}

	/** M3.3: draw lists of earlier frames, for reuse; where this region's lists start in lists[c]. */
	private final List<DrawList> pool = new ArrayList<>();
	private final int[] listStart = new int[2];

	/** The region's ShadowRegion record: its origin relative to the camera, computed exactly as Sodium's renderer does. */
	private int addRegion(RenderRegion region, CameraTransform camera) {
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		long at = this.regions + (long) this.regionCount * REGION_BYTES;
		MemoryUtil.memPutFloat(at, (region.getOriginX() - camera.intX) - camera.fracX);
		MemoryUtil.memPutFloat(at + 4, (region.getOriginY() - camera.intY) - camera.fracY);
		MemoryUtil.memPutFloat(at + 8, (region.getOriginZ() - camera.intZ) - camera.fracZ);
		MemoryUtil.memPutFloat(at + 12, 0f);
		return this.regionCount++;
	}

	/**
	 * A section's ranges whose facing is in faceMask, merged where consecutive, cut into 64-quad chunks, into list (cascade c).
	 * Layout as Sodium's renderer reads it: the section's vertices start at its base vertex, one range per storage slot i
	 * (vertex count i), slot i holding facing (facingList >> 8 i) & 0xFF.
	 */
	private void emit(long ptr, int faceMask, int regionIndex, DrawList list, int c) {
		if ((SectionRenderDataUnsafe.getSliceMask(ptr) & faceMask) == 0) return;
		long facingList = SectionRenderDataUnsafe.getFacingList(ptr);
		long vertex = SectionRenderDataUnsafe.getBaseVertex(ptr);
		long start = -1, end = -1;
		for (int i = 0; i < 7; i++) {
			long count = SectionRenderDataUnsafe.getVertexCount(ptr, i);
			if (count == 0) continue;
			int facing = (int) (facingList >>> (i * 8) & 0xFF);
			if ((faceMask >> facing & 1) != 0) {
				if (start >= 0 && end == vertex) {
					end = vertex + count;
				} else {
					if (start >= 0) this.range(start, end, regionIndex, list, c);
					start = vertex;
					end = vertex + count;
				}
			}
			vertex += count;
		}
		if (start >= 0) this.range(start, end, regionIndex, list, c);
		this.sections[c]++;
	}

	private void range(long start, long end, int regionIndex, DrawList list, int c) {
		long quads = (end - start) >> 2;
		this.quads[c] += quads;
		for (long q = 0; q < quads; q += CHUNK_QUADS) list.add((int) (start + q * 4), (int) (Math.min(CHUNK_QUADS, quads - q) - 1) | regionIndex << 6);
	}

	/** The faces (ModelQuadFacing bits) that face direction l, plus UNASSIGNED; or all of them. */
	static int faceMask(double[] l, boolean all) {
		if (all) return (1 << 7) - 1;
		final double eps = 1e-4;
		int mask = 1 << UNASSIGNED;
		// POS_X, POS_Y, POS_Z, NEG_X, NEG_Y, NEG_Z = 0..5
		if (l[0] > eps) mask |= 1;
		if (l[1] > eps) mask |= 1 << 1;
		if (l[2] > eps) mask |= 1 << 2;
		if (l[0] < -eps) mask |= 1 << 3;
		if (l[1] < -eps) mask |= 1 << 4;
		if (l[2] < -eps) mask |= 1 << 5;
		return mask;
	}

	static int chunkBytes() {
		return CHUNK_BYTES;
	}

	static int regionBytes() {
		return REGION_BYTES;
	}

	void close() {
		MemoryUtil.nmemFree(this.regions);
		this.regions = 0;
	}
}
