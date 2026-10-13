package mcopt.metal;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * config/mcopt.properties, the file every mcopt setting lives in (Profile reads it at startup), for the settings screen:
 * a value as the file has it, and a value written back in place. Writing keeps the file's comments and order: the key's
 * line (or its commented-out "#key=" line) is replaced, else the key is appended. Render thread (the settings screen).
 */
public final class ConfigFile {
	private ConfigFile() {
	}

	public static Path path() {
		Path dir;
		try {
			dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			dir = Path.of("").toAbsolutePath();
		}
		return dir.resolve("config").resolve("mcopt.properties");
	}

	/** The key's value in the file, else what the game runs with (command line, profile), else the fallback. */
	public static String get(String key, String fallback) {
		Path file = path();
		if (Files.isRegularFile(file)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
				String v = p.getProperty(key);
				if (v != null) return v.trim();
			} catch (IOException e) {
				Log.warnOnce("config read", "[mcopt] settings: can't read " + file, e);
			}
		}
		return System.getProperty(key, fallback);
	}

	/** Writes key=value into the file, in place. */
	public static void set(String key, String value) {
		set(path(), key, value);
	}

	static void set(Path file, String key, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			Pattern line = Pattern.compile("^#?\\s*" + Pattern.quote(key) + "\\s*[=:].*");
			boolean done = false;
			for (int i = 0; i < lines.size() && !done; i++) {
				if (line.matcher(lines.get(i).trim()).matches() && isKey(lines.get(i), key)) {
					lines.set(i, key + "=" + value);
					done = true;
				}
			}
			if (!done) lines.add(key + "=" + value);
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Log.warn("[mcopt] settings: can't save " + key + " to " + file, e);
		}
	}

	/** The line names exactly this key (Properties' own parsing, so "mcopt.shade" doesn't match "mcopt.shade.preset"). */
	private static boolean isKey(String raw, String key) {
		String s = raw.trim();
		if (s.startsWith("#")) s = s.substring(1).trim();
		Properties p = new Properties();
		try {
			p.load(new StringReader(s));
		} catch (IOException e) {
			return false;
		}
		return p.containsKey(key);
	}
}
