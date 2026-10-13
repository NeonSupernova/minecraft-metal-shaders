/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M3.3: frame generation (upstream's MetalFX frame interpolation, mcopt.metal.FrameGen) chosen from the pause menu
 * (ShadePauseScreenMixin, next to "Resolución") instead of a launch flag. It can't switch live (the window's present mode and
 * the frame pacing are set when the game starts), so the button saves the choice as {@code mcopt.metal.framegen} in
 * config/mcopt.properties (read at startup like every mcopt key; a -Dmcopt.metal.framegen on the command line still wins) and
 * says "(al reiniciar)" while the choice differs from what runs. Off by default, and the button itself only shows with
 * {@code mcopt.shade.framegenButton=true}: measured with the native shading (M33_ROBUST.md section 3) frame generation halves
 * the real frame rate to 30 at 3024x1964 (MetalFX interpolation ~12 ms of GPU per frame) for 60 shown fps and ~53 ms of
 * latency, worse than the ~90 real fps without it. Without the button nothing changes: the key absent = M3.2.
 */
public final class ShadeFrameGenOption {
	/** The choice for the next start (initially what this one runs with). */
	private static volatile boolean chosen = "true".equals(System.getProperty("mcopt.metal.framegen"));

	private ShadeFrameGenOption() {
	}

	/** Whether this session runs frame generation (fixed at startup). */
	public static boolean running() {
		return mcopt.metal.FrameGen.ENABLED && "true".equals(System.getProperty("mcopt.metal.framegen"));
	}

	/** The menu label (short: the pause menu's buttons are 204 pixels wide). */
	public static String label() {
		return ShadeLang.t("Gen. de cuadros: ", "Frame gen: ") + (chosen ? ShadeLang.t("Sí", "On") : ShadeLang.t("No", "Off"))
			+ (chosen != running() ? ShadeLang.t(" (al reiniciar)", " (on restart)") : "");
	}

	/** The tooltip: what it does and its price. */
	public static String tooltip() {
		return ShadeLang.t("Generación de cuadros (MetalFX): el Mac dibuja hasta la mitad de los Hz de la pantalla y crea un cuadro intermedio"
			+ " entre cada par. Se ve más fluido, pero responde un poco más tarde. Se aplica al reiniciar el juego.",
			"Frame generation (MetalFX): the Mac draws up to half the display's Hz and creates an in-between frame for each pair."
			+ " Smoother, but responds a little later. Applies after restarting the game.");
	}

	/** Flips the choice and saves it to config/mcopt.properties. */
	public static void toggle(Path gameDir) {
		chosen = !chosen;
		save(gameDir.resolve("config").resolve("mcopt.properties"), chosen ? "true" : "false");
	}

	private static void save(Path file, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith("mcopt.metal.framegen=")) {
					lines.set(i, "mcopt.metal.framegen=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Generación de cuadros (MetalFX), también desde el menú de pausa; se aplica al reiniciar: true / false");
				lines.add("mcopt.metal.framegen=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
			System.out.println("[mcopt] shade: frame generation " + (value.equals("true") ? "on" : "off") + " from the next start (" + file + ")");
		} catch (IOException e) {
			mcopt.metal.Log.warn("[mcopt] shade: could not save the frame generation choice", e);
		}
	}
}
