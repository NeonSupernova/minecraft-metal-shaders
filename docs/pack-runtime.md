# Shader pack runtime: Iris shader packs on Metal

Goal: Iris shader packs (Photon, BSL, Complementary, ...) running on the Metal renderer. Iris stays installed and picks
and configures the pack. mcopt draws it.

Background: `docs/research/iris-internals.md`. That research found Iris's own renderer is OpenGL throughout, so porting
it is out. Its front end is not: pack parsing, options, `#include`s, ID maps and the GLSL transformer. The runtime reuses
that front end and draws with its own Metal code.

## How it fits together

```
Iris (installed, inert)                 mcopt
  pack selection, options UI   --->  IrisPack: ShaderPack + TransformPatcher (Iris's own patched GLSL)
  iris.properties, <pack>.txt         MslTranslator: GLSL -> SPIR-V (shaderc, relaxed Vulkan rules)
                                                      -> MSL (SPIRV-Cross, argument buffers)
                                      Metal: compile, pipelines, passes (MetalHooks.Redirect / PassDelegate)
```

- **Iris inert.** Iris turns its OpenGL renderer off by itself when the game runs on Vulkan
  (`IrisMixinPlugin.usingVulkan`). With `mcopt.pack` on, mcopt sets that flag before Iris's mixins are applied, so the
  game keeps Metal. Without `mcopt.pack`, Iris gets OpenGL as before.
- **Not bundled.** mcopt only builds against Iris (`compileOnly`). It ships none of Iris, glsl-transformer, or any
  pack. Translation happens on the player's Mac at load time, as Iris itself does.

## Milestones

Every milestone ships behind `mcopt.pack` (off by default) and is tested in-game before the next one starts.

| | Milestone | What the player sees |
|---|---|---|
| **M1** | Coexist and translate. Iris inert, Metal kept. The selected pack is read, patched, translated and compiled at startup. | One log line: `N of M shader stages compiled for Metal`. Failing stages are dumped to `logs/mcopt-pack/`. The game looks as without the pack. |
| M2 | Post passes. Render targets (`colortexN`, flipping, clears, mips), uniforms (camera, time, sun...) and the `composite*` / `final` passes over the game's own world image. | The pack's tone, bloom and post effects, over vanilla-lit geometry. |
| M3 | Geometry passes. `gbuffers_*` take over every draw: terrain through Sodium in Iris's vertex format, entities, hand, sky, weather. `deferred*` passes. mcopt's own terrain fast path and native shaders step aside. | The pack's lighting and materials. |
| M4 | Shadows and the rest of the spec. `shadow*` pass from the light, `shadowcomp`, custom textures (noisetex, LUTs), compare samplers, `depthtex1/2`. | Full packs without compute: Complementary's default macOS profile, BSL. |
| M5 | Compute, images and SSBOs. `.csh` passes, `image.*`, `bufferObject.*`. | Photon's and Complementary's compute-only features (colored lighting, ...). |
| M6 | Speed. MetalFX upscaling of the pack's frame, per-pass GPU timing, caching translated shaders. | Frame rate. |

Geometry and tessellation shaders have no Metal equivalent. Packs that need them are reported in M1 and handled later
(compute rewrite) or not at all.

## Testing M1

1. Install Iris (the version for 26.3) and the pack, and select the pack in Iris as usual. Iris writes
   `config/iris.properties` and `shaderpacks/<pack>.txt`.
2. Turn on Options → Video Settings → Metal Shaders → **Iris Shader Packs on Metal**, or add `mcopt.pack=true` to
   `config/mcopt.properties`. Then restart.
3. The log should show `[mcopt] pack: shader packs run on Metal`, and then after a few seconds:
   `[mcopt] pack: "<pack>": P programs, C of S shader stages compiled for Metal ...`
4. Send that line, the `[mcopt] pack:` lines after it, and the `logs/mcopt-pack/` folder if it isn't empty.

Iris's own shader screen (the I key) will say Iris can't run on Vulkan, and its button switches the game to OpenGL. That
screen belongs to Iris's inert mode. An mcopt pack screen replaces it in a later milestone. Until then, pick packs with
Metal off (or edit `config/iris.properties`).
