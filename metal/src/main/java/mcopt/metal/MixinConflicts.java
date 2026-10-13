package mcopt.metal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

/**
 * Which other mod hooks a Sodium method one of our mixins replaces. An @Overwrite (or a @Redirect) takes the method or
 * call away from every other mod's injector into it, and Mixin turns that into a startup crash ("cannot inject into ...
 * merged by mcopt..."): Polytone and Iris both hit it on BlockRenderer.renderModel. Our replacements are speed-ups with
 * identical output, so the right move is to step aside, and this finds when.
 * <p>
 * Every other mod's mixin configs (fabric.mod.json "mixins") are read once, and each listed mixin class's bytes kept if
 * they mention Sodium at all. A hit is a class that names both the target class (as an internal name, a descriptor or a
 * dotted @Mixin(targets) string) and the method: a mixin that only uses the class elsewhere also counts, which costs at
 * worst one of our speed-ups, never a crash. Anything unreadable is skipped (the old behaviour: ours applies).
 */
public final class MixinConflicts {
	private static final String OWN_ID = "mcopt-metal";
	private static @Nullable List<Entry> index;

	private record Entry(String modId, byte[] bytes) {
	}

	private MixinConflicts() {
	}

	/**
	 * The id of a mod with a mixin that hooks {@code owner}'s method (any of {@code methods}), or null. {@code owner} is
	 * the dotted class name, nested classes with '$'.
	 */
	public static synchronized @Nullable String hookedBy(String owner, String... methods) {
		if (index == null) index = scan();
		byte[] internal = owner.replace('.', '/').getBytes(StandardCharsets.UTF_8);
		byte[] dotted = owner.getBytes(StandardCharsets.UTF_8);
		for (Entry e : index) {
			if (indexOf(e.bytes, internal) < 0 && indexOf(e.bytes, dotted) < 0) continue;
			for (String m : methods) {
				if (indexOf(e.bytes, m.getBytes(StandardCharsets.UTF_8)) >= 0) return e.modId;
			}
		}
		return null;
	}

	private static List<Entry> scan() {
		long start = System.nanoTime();
		List<Entry> out = new ArrayList<>();
		byte[] sodium = "caffeinemc".getBytes(StandardCharsets.UTF_8);
		int classes = 0;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (id.equals(OWN_ID) || mod.getMetadata().getType().equals("builtin")) continue;
			try {
				for (String config : configs(mod)) {
					JsonObject cfg = json(mod, config);
					if (cfg == null || !cfg.has("package")) continue;
					String pkg = cfg.get("package").getAsString().replace('.', '/');
					for (String side : new String[] {"mixins", "client", "server"}) {
						if (!(cfg.get(side) instanceof JsonArray names)) continue;
						for (JsonElement name : names) {
							byte[] bytes = read(mod, pkg + "/" + name.getAsString().replace('.', '/') + ".class");
							classes++;
							if (bytes != null && indexOf(bytes, sodium) >= 0) out.add(new Entry(id, bytes));
						}
					}
				}
			} catch (RuntimeException | IOException e) {
				System.out.println("[mcopt] mixin conflicts: skipped " + id + " (" + e + ")");
			}
		}
		System.out.printf("[mcopt] mixin conflicts: %d mixin classes read, %d touch Sodium (%.0f ms)%n", classes, out.size(),
			(System.nanoTime() - start) / 1e6);
		return out;
	}

	/** The mixin config paths a mod declares: "mixins" entries are a path, or an object with "config". */
	private static List<String> configs(ModContainer mod) throws IOException {
		List<String> out = new ArrayList<>();
		JsonObject fmj = json(mod, "fabric.mod.json");
		if (fmj == null || !(fmj.get("mixins") instanceof JsonArray entries)) return out;
		for (JsonElement e : entries) {
			if (e.isJsonPrimitive()) out.add(e.getAsString());
			else if (e instanceof JsonObject o && o.has("config")) out.add(o.get("config").getAsString());
		}
		return out;
	}

	private static @Nullable JsonObject json(ModContainer mod, String path) throws IOException {
		byte[] bytes = read(mod, path);
		if (bytes == null) return null;
		JsonElement e = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
		return e instanceof JsonObject o ? o : null;
	}

	private static byte @Nullable [] read(ModContainer mod, String path) throws IOException {
		Optional<Path> p = mod.findPath(path);
		return p.isPresent() && Files.isRegularFile(p.get()) ? Files.readAllBytes(p.get()) : null;
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0, last = hay.length - needle.length; i <= last; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}
}
