package mcopt.metal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

/**
 * Sodium versions beyond the ones this build was checked against. On a checked version ({@link #CHECKED}, see
 * tools/sodium-compat) every hook applies as before. On any other, each mixin that hooks Sodium is checked against the
 * installed Sodium's own class files before it applies: its target class must exist and name every method, field and
 * call the mixin needs ({@link #HOOKS}, generated from the mixins' sources). A hook whose target is gone is left out with
 * a warning, so a newer Sodium costs that speed-up instead of failing at startup. The plugins ask {@link #ok} first.
 */
public final class SodiumCompat {
	/** Sodium releases for MC 26.3 whose classes this build was compared with (tools/sodium-compat): all hooks apply unchecked. */
	public static final Set<String> CHECKED = Set.of("0.9.2-beta.1", "0.9.2-beta.2", "0.9.2", "0.9.3-alpha.1");
	/** Mixin -> Sodium class (internal name) and the names its bytes must contain. */
	private static final Map<String, List<String>> HOOKS = Map.ofEntries(
		Map.entry("BitArrayAccessor", List.of("net/caffeinemc/mods/sodium/client/util/collections/BitArray", "words")),
		Map.entry("BlockRendererMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer", "colorProvider", "colorProviderRegistry", "cutoutLeaves", "posOffset", "renderModel")),
		Map.entry("ClonedChunkSectionCacheCleanupMixin", List.of("net/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSectionCache", "MAX_CACHE_DURATION", "cleanup", "getMonotonicTimeSource", "positionToEntry", "time")),
		Map.entry("ClonedChunkSectionCacheMixin", List.of("net/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSectionCache", "acquire", "clone")),
		Map.entry("DirectionalVisGraphMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/occlusion/DirectionalVisGraph", "blocks", "resolveWithDirections")),
		Map.entry("LevelBiomeSliceMixin", List.of("net/caffeinemc/mods/sodium/client/world/biome/LevelBiomeSlice", "biomes", "calculateUniform", "dataArrayIndex", "hasUniformNeighbors", "uniform")),
		Map.entry("LevelSliceAirMixin", List.of("net/caffeinemc/mods/sodium/client/world/LevelSlice", "auxLightManager", "blockArrays", "blockEntityArrays", "blockEntityRenderDataArrays", "copySectionData", "lightArrays", "modelMapArrays", "unpackBlockData")),
		Map.entry("LevelSliceBoundsMixin", List.of("net/caffeinemc/mods/sodium/client/world/LevelSlice", "blockArrays", "copyData", "getBlockState", "originBlockX", "originBlockY", "originBlockZ", "volume")),
		Map.entry("CullBiForestAccess", List.of("net/caffeinemc/mods/sodium/client/render/chunk/tree/BaseBiForest", "mainTree", "secondaryTree")),
		Map.entry("CullMultiForestAccess", List.of("net/caffeinemc/mods/sodium/client/render/chunk/tree/BaseMultiForest", "trees")),
		Map.entry("CullNearbyMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller", "addNearbySections", "sections", "token")),
		Map.entry("CullReuseManagerMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager", "cameraChanged", "consumeCullTaskResults", "cullResults", "destroy", "getSearchDistanceForCullType", "isOutOfGraph", "level", "markGraphDirty", "needsGraphUpdate", "occlusionCuller", "pendingTask", "prepareRender", "prepareRenderTrees", "processChunkBuilds", "scheduleAsyncWork")),
		Map.entry("CullSectionInfoMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/RenderSection", "region", "sectionIndex", "setRenderState", "visibilityData")),
		Map.entry("CullTaskTreeAccess", List.of("net/caffeinemc/mods/sodium/client/render/chunk/lists/TaskCollectingTree", "creationTime", "pendingTasks")),
		Map.entry("CullTreeAccess", List.of("net/caffeinemc/mods/sodium/client/render/chunk/tree/Tree", "offsetX", "offsetY", "offsetZ", "tree")),
		Map.entry("DrawMergeAbMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer", "render")),
		Map.entry("DrawMergeMixin", List.of("net/caffeinemc/mods/sodium/client/gpu/device/batch/GLDrawBatch", "draw", "pBaseVertex", "pElementCount", "pElementPointer")),
		Map.entry("EntityBoxSodiumMixin", List.of("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer", "isEntityVisible")),
		Map.entry("ListAbMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager", "readRenderListFromTree")),
		Map.entry("ListRegionCacheMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector", "getForChunk", "visit")),
		Map.entry("LodSodiumFadeMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager", "writeMeshTimes")),
		Map.entry("CullQosMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager", "makeAsyncCullThread")),
		Map.entry("MeshQosMixin", List.of("net/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder$WorkerRunnable", "run")),
		Map.entry("SodiumFlatFaceShadeMixin", List.of("net/caffeinemc/mods/sodium/client/model/light/flat/FlatLightPipeline", "byFace", "getShade")),
		Map.entry("SodiumSmoothFaceShadeMixin", List.of("net/caffeinemc/mods/sodium/client/model/light/smooth/SmoothLightPipeline", "byFace", "getAmbientBrightness")));
	/** The hook table, for the tests (they check it against the Sodium jar the build compiles against). */
	static Map<String, List<String>> hooks() {
		return HOOKS;
	}

	/** Mixins that work only together (an accessor and the mixin that casts to it): one missing target turns off the group. */
	private static final List<List<String>> GROUPS = List.of(
		List.of("CullBiForestAccess", "CullMultiForestAccess", "CullNearbyMixin", "CullReuseManagerMixin", "CullSectionInfoMixin", "CullTaskTreeAccess", "CullTreeAccess"),
		List.of("DirectionalVisGraphMixin", "BitArrayAccessor"));
	private static final Map<String, Boolean> FITS = new HashMap<>();
	private static final java.util.Set<String> OFF = new java.util.TreeSet<>();
	private static @Nullable String version;
	private static boolean checked;
	private static final Map<String, byte @Nullable []> CLASSES = new HashMap<>();

	private SodiumCompat() {
	}

	/** The installed Sodium's version without build metadata ("0.9.2"), or "" without Sodium. */
	public static synchronized String version() {
		if (version == null) {
			version = FabricLoader.getInstance().getModContainer("sodium")
				.map(m -> m.getMetadata().getVersion().getFriendlyString().split("\\+")[0]).orElse("");
			checked = version.isEmpty() || CHECKED.contains(version);
			if (!checked) {
				Log.warn("[mcopt] Sodium " + version + " is newer than the versions this build was checked against " + CHECKED
					+ "; each Sodium hook is checked against it and only the ones that still fit are applied (tools/sodium-compat compares a new tag)");
			}
		}
		return version;
	}

	/** True when the mixin may apply: a checked Sodium, a mixin that doesn't hook Sodium, or one whose targets are all present. */
	public static synchronized boolean ok(String mixin) {
		version();
		if (checked) return true;
		for (List<String> group : GROUPS) {
			if (!group.contains(mixin)) continue;
			for (String member : group) {
				if (!fits(member)) {
					OFF.add(mixin);
					if (!member.equals(mixin)) Log.warn("[mcopt] Sodium " + version + ": " + mixin + " off with " + member + " (they only work together)");
					return false;
				}
			}
			return true;
		}
		boolean fits = fits(mixin);
		if (!fits) OFF.add(mixin);
		return fits;
	}

	/** For the crash report: the Sodium version and whether every hook applied. */
	public static synchronized String status() {
		String v = version();
		if (v.isEmpty()) return "absent";
		if (checked) return v + " (checked, all hooks)";
		return v + " (not checked; " + (OFF.isEmpty() ? "all hooks fit" : "hooks off: " + String.join(", ", OFF)) + ")";
	}

	private static boolean fits(String mixin) {
		return FITS.computeIfAbsent(mixin, SodiumCompat::check);
	}

	private static boolean check(String mixin) {
		List<String> hook = HOOKS.get(mixin);
		if (hook == null) return true;
		byte[] bytes = classBytes(hook.get(0));
		String owner = hook.get(0).substring(hook.get(0).lastIndexOf('/') + 1);
		if (bytes == null) {
			Log.warn("[mcopt] Sodium " + version + ": " + mixin + " off, Sodium has no class " + owner);
			return false;
		}
		String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		for (String name : hook.subList(1, hook.size())) {
			if (!text.contains(name)) {
				Log.warn("[mcopt] Sodium " + version + ": " + mixin + " off, " + owner + " has no " + name);
				return false;
			}
		}
		Log.info("[mcopt] Sodium " + version + ": " + mixin + " fits (" + owner + ")");
		return true;
	}

	private static byte @Nullable [] classBytes(String internalName) {
		return CLASSES.computeIfAbsent(internalName, n -> {
			Optional<ModContainer> sodium = FabricLoader.getInstance().getModContainer("sodium");
			if (sodium.isEmpty()) return null;
			Optional<Path> p = sodium.get().findPath(n + ".class");
			try {
				return p.isPresent() && Files.isRegularFile(p.get()) ? Files.readAllBytes(p.get()) : null;
			} catch (IOException e) {
				Log.warn("[mcopt] can't read Sodium's " + n + ".class", e);
				return null;
			}
		});
	}
}
