# Metal Shaders

**Native Metal shaders for Minecraft: Java Edition on Apple Silicon Macs.** Real-time shadows, volumetric clouds, god rays,
reflective water, HDR and more, written directly in Metal instead of going through OpenGL, at the full resolution of a
Retina display.

![Sunset over the lake](media/hero.jpg)

<p align="center">
  <a href="https://github.com/GaelQuinta/minecraft-metal-shaders/releases/latest"><b>Download</b></a> ·
  <a href="#install">Install</a> ·
  <a href="#performance">Performance</a> ·
  <a href="#features">Features</a> ·
  <a href="#español">Español</a>
</p>

> **Alpha.** It runs well on the Mac it was built on (MacBook Pro M4), but expect bugs. Back up your worlds.

## Why

Every shader pack for Java Edition runs through Iris on OpenGL, and OpenGL on macOS has been frozen since 2011. On a
MacBook Pro M4 at its native 3024×1964, Iris + Complementary Reimagined runs at about **22 FPS**.

Metal Shaders is a fork of [mcopt](https://github.com/noahdunnagan/mcopt), a mod that gives Minecraft 26.3 a Metal
renderer. On top of it, every effect is written from scratch in Metal Shading Language and runs inside the game's own
frame, with Apple-GPU-specific tricks (tile memory, compressed render targets, a custom temporal upscaler). Same Mac,
same resolution: **90–120 FPS** with the balanced preset, and **135–150 FPS** at 1440p.

## Videos

Recorded in-game on a MacBook Pro M4 (the camera paths were scripted with the test harness in `bench/`).

| [![Showcase video](media/video-showcase.jpg)](https://github.com/GaelQuinta/minecraft-metal-shaders/releases/download/v1.0-alpha/metal-shaders-showcase.mp4) | [![Presets video](media/video-presets.jpg)](https://github.com/GaelQuinta/minecraft-metal-shaders/releases/download/v1.0-alpha/metal-shaders-presets.mp4) |
|---|---|
| **Showcase** (85 s): sunsets, god rays, water, survival, auroras | **Every preset × every resolution** with F3 (49 s) |

### Gallery

| | |
|---|---|
| ![Lake at sunset](media/lake_sunset.jpg) | ![Cherry grove at sunset](media/cherry_high_sunset.jpg) |
| ![God rays in the jungle](media/jungle_sunset.jpg) | ![Above the clouds](media/clouds_above_sunset.jpg) |
| ![Red sunset over a snowy taiga](media/snowy_red_sunset.jpg) | ![Red sunset on the lake](media/lake_red_sunset.jpg) |
| ![Aurora over the snowy slopes](media/aurora_slopes.jpg) | ![Aurora and stars over the lake](media/night_lake_stars.jpg) |
| ![Morning in the cherry grove](media/cherry_walk_morning.jpg) | ![Badlands at sunset](media/badlands_sunset.jpg) |

Full-resolution photos (3024×1964) are in the [release](https://github.com/GaelQuinta/minecraft-metal-shaders/releases/latest).

## Performance

MacBook Pro 14" M4 (10-core GPU), macOS 26, Minecraft 26.3, measured in-game while recording the videos above
(the recorder costs a few FPS). F3 shows the numbers in each clip.

Average FPS over each 10-second clip, 16 chunks, while recording the video (F3 on):

| Preset | Output | Lake sunset | Cherry forest | Mountain sunrise |
|---|---|---|---|---|
| Performance | 3024×1964 (native) | 94 | 128 | 128 |
| Performance | 2218×1440 | 136 | 165 | 150 |
| Performance | 1662×1080 | 159 | 249 | 223 |
| Balanced | 3024×1964 (native) | 92 | 118 | 118 |
| Balanced | 2218×1440 | 137 | 148 | 142 |
| Balanced | 1662×1080 | 142 | 220 | 194 |
| Quality | 3024×1964 (native) | 55 | 85 | 78 |
| Quality | 2218×1440 | 86 | 119 | 107 |
| Quality | 1662×1080 | 113 | 179 | 155 |
| Ultra | 3024×1964 (native) | 42 | 70 | 63 |
| Ultra | 2218×1440 | 66 | 105 | 89 |
| Ultra | 1662×1080 | 92 | 155 | 126 |

For reference, on the same Mac: Iris + Complementary Reimagined (OpenGL) at 3024×1964 ≈ 22 FPS, at 1080p ≈ 43 FPS.

## Features

- **Shadows** from the sun and the moon (cascaded shadow maps, soft contact-hardening shadows), coloured shadows through
  stained glass, entity shadows.
- **Lighting**: sky light, block light and bounce, held lights (a torch in your hand lights the world around you, around
  corners), emissive blocks.
- **Sky**: physically based atmosphere, sun and moon discs, stars and the Milky Way, **aurora** in cold biomes.
- **Volumetric clouds** with cloud shadows, lit by the sunset.
- **God rays** (volumetric light) through forests, cave mouths and clouds; morning mist and forest air.
- **Water**: waves, screen-space reflections, refraction, absorption, caustics, foam, **waterfalls with white water**,
  underwater fog and light shafts.
- **Weather**: wet surfaces and puddles, rain ripples, lightning.
- **HDR** pipeline with bloom, auto-exposure, a filmic tonemap, and **XDR/EDR** output on Liquid Retina XDR displays.
- **TAAU** (temporal anti-aliasing and upscaling) and dynamic resolution; an **output resolution** button in the pause menu.
- Nether and End looks.
- **Distant Horizons** support: the far LODs are lit by the same shaders (lighting, fog, water reflections).
- Presets: `performance`, `balanced`, `quality`, `ultra`; looks: `epic`, `storybook`, `natural`.

## Install

You need:

- An **Apple Silicon Mac** (M1 or newer) on **macOS 26** or later
- **Minecraft: Java Edition 26.3** with **Fabric Loader 0.19.5** or newer, on **Java 25**
- **Sodium 0.9.2 or 0.9.3** for 26.3 ([Modrinth](https://modrinth.com/mod/sodium))

Steps:

1. Install Fabric for 26.3 ([installer](https://fabricmc.net/use/installer/), or in Prism Launcher: new instance → 26.3 → Fabric).
2. Put `metal-shaders-<version>.jar` (from [Releases](https://github.com/GaelQuinta/minecraft-metal-shaders/releases/latest))
   and the Sodium jar in your `mods` folder.
3. Start the game. The shaders are on from the first launch (balanced preset).

Settings live in `config/mcopt.properties` in your game folder (written on the first launch):

| Key | Values | Default |
|---|---|---|
| `mcopt.shade` | `true` / `false` (false = mcopt's plain Metal renderer) | `true` |
| `mcopt.shade.preset` | `performance`, `balanced`, `quality`, `ultra` | `balanced` |
| `mcopt.shade.look` | `epic`, `storybook`, `natural` | `epic` |
| `mcopt.shade.output` | `native`, `1440`, `1200`, `1080`, `900` (also the **Output** button in the pause menu) | `native` |

For the most FPS: Video Settings → VSync off, Max Framerate unlimited. To see your FPS, press **F3**.

**Distant Horizons** (optional): add Distant Horizons 3.3.4 for 26.3 and Fabric API to the mods folder. It runs on Metal
too, and its far terrain is lit by the same shaders (DH's own SSAO, anti-aliasing and fog are turned off while they run).

- **Bottom half of the screen dark with DH?** DH's *Vanilla Fade Mode* is on `DOUBLE_PASS` (DH's default), which clashes
  with the shaders. Since **1.0-alpha.2** the mod sets it to `NONE` by itself at startup. On 1.0-alpha: open
  `config/DistantHorizons.toml`, set `vanillaFadeMode = "NONE"` (or `SINGLE_PASS`), and restart the game (DH reads it only
  at startup).

## How it was made

- `metal/` — the mod (fork of mcopt 0.2.0-alpha.3). The shading code is in `metal/src/main/resources/mcopt/shade/*.metal`
  and `metal/src/main/java/mcopt/metal/shade/`. Everything new sits behind `mcopt.shade` flags: with `mcopt.shade=false`
  the mod behaves like upstream mcopt.
- `docs/shader-bible.md` — the design of every effect (E01–E27), frame budgets and licensing. `docs/research/` — studies
  of Apple GPUs, the 26.3 renderer, Iris, Distant Horizons and other packs' techniques. `docs/releases/` — notes of every
  version (in Spanish).
- `bench/` — the test harness: `mcbench` (an in-game benchmark mod: a fixed world, camera routes, screenshots, frame
  times, and the "cine" mode that recorded the videos above) and the scripts that launch the game and measure it.

Build: `./gradlew :metal:build` with JDK 25 → `metal/build/libs/`.

## Credits and licences

- **mcopt** by Noah Dunnagan — Apache-2.0. Files derived from **Sodium** (CaffeineMC) keep the PolyForm Shield 1.0.0
  licence (listed in `NOTICE`).
- Portions of the clouds and volumetric light are adapted from **Photon Shaders** by sixthsurge, from its MIT-licensed
  snapshot `26c2906e` (`LICENSES/MIT-Photon.txt`). Other techniques credited in `NOTICE` (SE Natural Bloom, MJP's
  Catmull-Rom, public papers). No code from Complementary, BSL or SEUS: those were only studied.
- Distant Horizons is LGPL and is not included; install it separately.
- Shader code and the harness: Apache-2.0. Not affiliated with Mojang or Microsoft.

Made by Gael Quintanilla, with Claude (Anthropic) writing most of the code.

---

## Español

**Shaders nativos en Metal para Minecraft Java en Macs con Apple Silicon.** Sombras, nubes volumétricas, rayos de sol
(godrays), agua con reflejos, HDR y más, escritos directamente en Metal en lugar de pasar por OpenGL.

En una MacBook Pro M4 a resolución completa (3024×1964), Iris + Complementary corre a ~22 FPS. Con Metal Shaders,
la misma Mac a la misma resolución da **90–120 FPS** (preset balanceado), y **135–150 FPS** a 1440p.

**Instalación:** Mac con Apple Silicon y macOS 26, Minecraft 26.3 con Fabric 0.19.5 y Java 25, y Sodium 0.9.2 o 0.9.3.
Pon el jar de [Releases](https://github.com/GaelQuinta/minecraft-metal-shaders/releases/latest) y el de Sodium en la
carpeta `mods` y abre el juego: los shaders se activan solos (preset balanceado). Para cambiar el preset, edita
`config/mcopt.properties` (`mcopt.shade.preset=performance|balanced|quality|ultra`); la resolución de salida se cambia con
el botón **"Salida"** del menú de pausa. Compatible con Distant Horizons 3.3.4 + Fabric API (opcional). Si con DH la mitad de
abajo de la pantalla se ve oscura (versión 1.0-alpha): en `config/DistantHorizons.toml` pon `vanillaFadeMode = "NONE"` y
reinicia el juego; desde la 1.0-alpha.2 el mod lo hace solo.

Es una versión alfa: haz respaldo de tus mundos.
