package mcopt.metal.pack;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text helpers of the pack runtime, free of native libraries (unit-tested). */
final class PackText {
	private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#version[ \\t]+\\d+.*$");

	private PackText() {
	}

	/** Iris emits 330 core (or 4xx with compatibility); Vulkan GLSL wants core, and 460 for explicit SSBO bindings. */
	static String forceVersion(String glsl) {
		Matcher m = VERSION.matcher(glsl);
		return m.find() ? glsl.substring(0, m.start()) + "#version 460 core" + glsl.substring(m.end()) : "#version 460 core\n" + glsl;
	}

	/** The first n lines of a compiler message on one log line. */
	static String firstLines(String s, int n) {
		if (s == null) return "";
		String[] lines = s.strip().split("\n");
		if (lines.length <= n) return String.join(" | ", lines);
		return String.join(" | ", Arrays.copyOf(lines, n)) + " | (" + (lines.length - n) + " more lines)";
	}
}
