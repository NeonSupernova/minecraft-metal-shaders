package mcopt.metal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settings screen writes config/mcopt.properties in place: comments, order and look-alike keys untouched. */
class ConfigFileTest {
	@TempDir
	Path dir;

	@Test
	void replacesInPlaceAndUncomments() throws Exception {
		Path file = this.dir.resolve("mcopt.properties");
		Files.write(file, List.of("# shaders", "mcopt.shade=true", "mcopt.shade.preset=balanced", "#mcopt.log.tick=5"));
		ConfigFile.set(file, "mcopt.shade.preset", "ultra");
		ConfigFile.set(file, "mcopt.log.tick", "10");
		ConfigFile.set(file, "mcopt.vsyncCap", "false");
		assertEquals(List.of("# shaders", "mcopt.shade=true", "mcopt.shade.preset=ultra", "mcopt.log.tick=10", "mcopt.vsyncCap=false"),
			Files.readAllLines(file));
	}

	@Test
	void doesNotTouchALongerKey() throws Exception {
		Path file = this.dir.resolve("mcopt.properties");
		Files.write(file, List.of("mcopt.shade.preset=balanced", "mcopt.shade=true"));
		ConfigFile.set(file, "mcopt.shade", "false");
		assertEquals(List.of("mcopt.shade.preset=balanced", "mcopt.shade=false"), Files.readAllLines(file));
	}
}
