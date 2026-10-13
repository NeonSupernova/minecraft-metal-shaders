package mcopt.metal.pack;

import java.util.Map;
import java.util.function.Supplier;

/**
 * One program of a shader pack (gbuffers_terrain, composite3, ...), patched by Iris's front end into GLSL per stage. The
 * patching is deferred (patch()) so it runs on the translator's thread, not the render thread that read the pack.
 */
record PackProgram(String name, String kind, Supplier<Map<String, String>> patcher) {
	static final String VERTEX = "vertex";
	static final String FRAGMENT = "fragment";
	static final String GEOMETRY = "geometry";
	static final String TESSELLATION = "tessellation";
	static final String COMPUTE = "compute";

	static PackProgram lazy(String name, String kind, Supplier<Map<String, String>> patcher) {
		return new PackProgram(name, kind, patcher);
	}

	/** stage -> patched GLSL (VERTEX, FRAGMENT, ... keys). */
	Map<String, String> patch() {
		return this.patcher.get();
	}
}
