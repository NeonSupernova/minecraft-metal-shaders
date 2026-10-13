package mcopt.metal.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackTextTest {
	@Test
	void replacesTheFirstVersionLine() {
		assertEquals("#version 460 core\nvoid main() {}\n", PackText.forceVersion("#version 330 core\nvoid main() {}\n"));
		assertEquals("#version 460 core\n#extension GL_ARB_x : enable\n",
			PackText.forceVersion("#version 430 compatibility\n#extension GL_ARB_x : enable\n"));
	}

	@Test
	void keepsWhatComesBeforeTheVersion() {
		assertEquals("// header\n#version 460 core\nx\n", PackText.forceVersion("// header\n#version 150\nx\n"));
	}

	@Test
	void addsAVersionWhenThereIsNone() {
		assertEquals("#version 460 core\nvoid main() {}", PackText.forceVersion("void main() {}"));
	}

	@Test
	void shortensLongMessages() {
		assertEquals("a | b", PackText.firstLines("a\nb\n", 3));
		assertEquals("a | b | (2 more lines)", PackText.firstLines("a\nb\nc\nd", 2));
		assertEquals("", PackText.firstLines(null, 2));
	}
}
