package mcopt.metal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Sodium hook table (SodiumCompat.HOOKS) against the Sodium jar this build compiles against: every target class must
 * exist and name everything listed, and every entry must be a real mixin. Catches the table drifting from the mixins or
 * from Sodium, which would wave a broken hook through on an unchecked Sodium (or turn a working one off).
 */
class SodiumCompatTest {
	@Test
	void everyHookFitsTheBuildsSodium() throws IOException {
		for (Map.Entry<String, List<String>> e : SodiumCompat.hooks().entrySet()) {
			String owner = e.getValue().get(0);
			try (InputStream in = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
				assertNotNull(in, e.getKey() + ": Sodium has no " + owner);
				String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
				for (String name : e.getValue().subList(1, e.getValue().size())) {
					assertTrue(text.contains(name), e.getKey() + ": " + owner + " has no " + name);
				}
			}
		}
	}

	@Test
	void everyHookIsAMixin() throws IOException {
		Path root = Path.of("src/main/java/mcopt/metal/mixin");
		for (String mixin : SodiumCompat.hooks().keySet()) {
			try (Stream<Path> files = Files.walk(root)) {
				assertTrue(files.anyMatch(p -> p.getFileName().toString().equals(mixin + ".java")), mixin + " is in the hook table but not a mixin");
			}
		}
	}
}
