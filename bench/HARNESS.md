# mcbench — in-game benchmark + screenshot harness (M0)

Everything here lives under `work/` (= this file's directory):

| Path | What |
|---|---|
| `bin/mcbench-run` | **The only way to launch Minecraft.** One automated run of a dev instance → a result dir. |
| `bin/mcbench-summary` | Markdown table of result dirs (`--label X`, `--last N`, or dirs). |
| `bin/mcbench-baselines` | Re-runs the three M0 baselines twice each. |
| `bin/mcbench-setup-instances` | (Re)creates the Prism instances `mcopt DEV` and `mcopt DEV-IRIS` (idempotent). |
| `bin/mcbench-wrap` | Prism `WrapperCommand` of the dev instances (injects the per-run JVM args). |
| `mcbench/` | The Fabric mod (own git repo, Loom, Java 25, MC 26.3, loader 0.19.5, **no Fabric API**, mixins only). |
| `bench-results/<YYYYmmdd-HHMMSS>-<label>/` | One dir per run. |
| `.gamelock/` | Global lock (pid + info). `.mcbench/` per-instance args/env/pid/cmdline files. |

## Quick start

```bash
W=".../work"   # this directory
M="/Users/gaelquintanilla/Documents/Minecraft Metal Shaders/work/release"
# your mcopt build + Sodium on the Metal dev instance, native Retina fullscreen (3024x1964):
"$W/bin/mcbench-run" --instance "mcopt DEV" \
    --mods "$W/wt/<branch>/metal/build/libs/metal-0.2.0-alpha.3.jar" \
    --mods "$M/sodium-fabric-0.9.2+mc26.3.jar" \
    --jvm "-Dmcopt.shade=true" --label m1-shadows --seconds 20
# stdout: the result dir, then one line, e.g.
# OK m1-shadows: fb 3024x1964 Metal | spin 568.1 fps (1% low 235.7, p99 3.187 ms) gpu 1.752 ms/f | flight ... | 5 shots
"$W/bin/mcbench-summary" --label m1-shadows --label baseline-mcopt-metal
```

Then **look at the screenshots** (`<result>/screenshots/*.png`, 3024x1964; `sips -Z 1200 in.png --out x.jpg -s format jpeg`
makes a quick-to-read copy).

## `mcbench-run` interface

```
mcbench-run --instance "mcopt DEV"|"mcopt DEV-IRIS" --mods <jar> [--mods <jar> ...] [--jvm "<extra jvm args>"]
            [--label <name>] [--scenes all|perf|shots] [--seconds N] [--shaderpack <zip>|none] [--timeout-min N]
            [--display builtin|main|keep|<name>] [--fullscreen|--windowed] [--window WxH]
            [--env KEY=VAL] [--no-gpu-times] [--fresh-world] [--wait-user-min N] [--direct]
```

| Option | Default | Meaning |
|---|---|---|
| `--instance` | required | Only `mcopt DEV` / `mcopt DEV-IRIS` / `mcopt DEV-DH` are accepted (the script refuses anything else). |
| `--mods` | keep current mods | Replaces `mods/` with exactly these jars, plus the newest `mcbench/build/libs/mcbench-*.jar` automatically. |
| `--jvm` | | Extra JVM args, whitespace-split (values cannot contain spaces). E.g. `"-Dmcopt.shade=true -Dmcopt.metal.trace=400"`. |
| `--label` | `run` | Result dir suffix and `mcbench.label`. |
| `--scenes` | `all` | `perf` (spin + flight), `shots` (5 screenshots), `all` (perf first, then shots). |
| `--seconds` | 20 | Length of **each** perf segment (spin, then flight). |
| `--shaderpack` | untouched | Copies the zip into `shaderpacks/` and writes `config/iris.properties` (enabled). `none` disables shaders. |
| `--timeout-min` | 15 | Kill the game after this; the in-game watchdog fires 30 s earlier and writes `error.txt`. |
| `--display` | `builtin` | Where the game ends up: the built-in Retina panel, `main` (macOS main display), `keep`, or a display-name substring (e.g. `XZ3020`). |
| `--fullscreen` / `--windowed` | fullscreen | Fullscreen on the target display (exclusive mode; built-in = 1512x982@120 = **3024x1964** framebuffer). |
| `--window WxH` | 1512x982 | Window size in **points** (options.txt `overrideWidth/Height`); framebuffer = 2x on Retina. Used with `--windowed`. |
| `--env` | | Environment for the game (e.g. `MTL_HUD_ENABLED=1`). `MCBENCH_STDERR=<file>` makes mcbench-wrap append the game's stderr there (Metal debug-layer messages: `MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_ERROR_MODE=nslog`; the unified log redacts them). |
| `--no-gpu-times` | gpu times on | Don't pass `-Dmcopt.metal.gpuTimes=true`. |
| `--fresh-world` | | Delete `saves/mcbench-world` first (it is recreated; same seed ⇒ same terrain and spot). |
| `--wait-user-min` | 30 | How long to wait for a non-dev Minecraft to exit before giving up (exit 3). |
| `--direct` | off (`MCBENCH_DIRECT=1` = on) | Start the game **without Prism and offline** (`bin/mcbench-direct`): Prism's own java command line (JVM args, classpath) from a template, Fabric's `KnotClient` as main class, an offline session (access token `0`) for the profile that owns the bench world's player. No Microsoft login at all. **Prefer it for every run**: Prism logs into Microsoft (`api.minecraftservices.com/launcher/login`) on each launch, and ~25 launches in 2 h got the user's real account rate-limited (HTTP 429/403, 2026-10-07; Prism launches then fail with "Prism did not start the game within 240 s"). Template: `work/.mcbench/<instance>.direct-template`, made from a Prism-launched result's `cmdline.txt` with `bin/mcbench-direct --template "<instance>" <result>/cmdline.txt` (redo it after Prism changes the instance's libraries; made for `mcopt DEV` only so far). The game logs harmless 401/Realms errors (no session). |

Exit codes: **0** results.json written, no error.txt, no crash report · **1** run failed · **2** usage ·
**3** the user is playing (a Minecraft JVM outside the dev instances ran for the whole wait) · **4** lock wait timed out (90 min).

Always added JVM args: `-Dmcbench.out=<result dir> -Dmcbench.scenes -Dmcbench.seconds -Dmcbench.label -Dmcbench.timeoutSec
-Dmcbench.display -Dmcbench.fullscreen -Dmcopt.edgeCap=false -Dmcopt.metal.gpuTimes=true`, and for `mcopt DEV-IRIS`
also `-Dmcopt.metal=false -Dmcopt.chunk.mesh=air,bounds,vis,biome` (see gotchas).

What it does, in order: take the global lock (FIFO: a ticket in `.gamelock-queue/<epoch>-<pid>`, only the oldest live
ticket may `mkdir .gamelock`; pid file; stale = dead pid; polls every 10 s, gives up after 90 min) → refuse to start while
any Minecraft JVM whose cwd is not a dev instance is running (poll 30 s, up to 30 min; leftover dev-instance games are
killed) → wait (≤5 min, `MCBENCH_QUIET_MAX_S`) until the summed CPU of all processes is under 350 %
(`MCBENCH_QUIET_PCT`; an idle desktop with the Claude app is ~200 %, a gradle build 400–1000 %) → result dir,
`power.txt` (AC/battery), a background `cpu-load.txt` sampler (top 5 processes every 5 s) → mods / shaderpack / options.txt window settings → JVM args into
`.mcbench/<instance>.args` (+ mirrored, minus space-containing args, into instance.cfg `JvmArgs`) →
`open -n -a "Prism Launcher.app" --args --launch "<instance>"` → wait for the game pid (written by `mcbench-wrap`) →
wait for `results.json` / `error.txt` / a new crash report / the process exiting / the timeout → let the game quit by
itself (up to 90 s; it saves the world) or TERM/KILL it → copy `latest.log`, crash reports, `hs_err_pid*.log`, the
java command line → release the lock → print the result dir and a one-line summary.

## Result dir contents

| File | |
|---|---|
| `results.json` | Everything below in one file (see fields). Only written on success. |
| `frametimes.csv` | `phase,frame,t_ms,frame_ms` — every CPU frame of the spin and flight segments. |
| `gputimes.csv` | mcopt Metal only: `phase,submitIndex,gpuStartNs,gpuEndNs,busyUs,presenting` per command buffer. |
| `screenshots/*.png` | `noon_overview`, `forest_closeup`, `sunset_water`, `night_torches`, `rain` at full framebuffer size (GUI hidden). |
| `error.txt` | On any failure (exception with stack, watchdog, early exit, crash, timeout). `results-partial.json` next to it. |
| `cpu-load.txt` | Top-5 CPU processes every 5 s during the run. **Check it when numbers look off** (other agents' gradle builds). |
| `latest.log`, `crash-*.txt`, `cmdline.txt`, `jvm-args.txt`, `mods.txt`, `options-bench.txt`, `iris.properties`, `power.txt` | Context. |

`results.json` fields: `display` (framebuffer and window size, pixel density, display name/mode, fullscreen,
`gpuBackend` = Metal|OpenGL, GPU name/driver), `options` (render/simulation distance, maxFps, vsync, clouds…),
`environment` (mods + versions, Java, filtered JVM args, Iris pack), `world`, `spot` (bench location + poses), `settle`
(per scene: seconds to settle, timedOut, client chunks), `perf.{spin,flight,all}` (`frames, seconds, avgFps, low1Fps,
low01Fps, p50Ms, p95Ms, p99Ms, maxMs, meanMs`), `gpu.{spin,flight}` (mcopt only), `screenshots`, `warnings`.

Metric definitions:
- **frame time** = `System.nanoTime()` between consecutive `Minecraft.runTick` heads (one main-loop iteration = one frame).
- **avgFps** = frames / sum(frame times). **low1Fps** = 1000 / mean of the slowest 1% frame times (low01Fps: 0.1%).
- **gpu.gpuMsPerFrame** = union of all command-buffer GPU [start,end] intervals in the segment / CPU frames
  (command buffers overlap with 2 in flight, so the plain sum — `submitSpanSumMsPerFrame` — over-counts).
  **gpuBusyFraction** ≈ 1.0 means GPU-bound. `gpuAvgFps` is mcopt's *presented* rate (its pacer presents at most one
  frame per display refresh while vsync is off, so ~117 on 120 Hz even when 600 frames/s are rendered).

## The bench (mcbench mod)

Inert unless `-Dmcbench.out` is set (the mixin plugin doesn't even apply the mixins). Driven per frame from
`Minecraft.runTick` HEAD; the camera pose is re-applied at `renderFrame` HEAD (after client ticks). Steps:

1. **boot**: wait until resources are loaded and no overlay for 2 s (any first screen, e.g. title/onboarding).
2. **place-window**: move the window to `-Dmcbench.display` (default: the built-in panel) with SDL, then fullscreen there.
3. **open world** `mcbench-world` (`WorldOpenFlows.openWorld`), or create it the first time
   (`createFreshLevel`: seed **20261006**, creative, cheats, peaceful, normal world preset).
4. **setup** via integrated-server commands: `advance_time/advance_weather false`, `random_tick_speed 0`, mob spawning
   off, `player_movement_check false`, command feedback off, `gamemode spectator` (no hand, no collision), HUD hidden
   (F1 state), `pauseOnLostFocus=false`. Vanilla fps throttles (AFK 30 fps after 60 s, unfocused exclusive fullscreen
   10 fps, menus 60 fps) are disabled by a mixin while the bench runs.
5. **spot**: on first use the overworld biome noise is sampled (no chunk generation) in a square spiral from spawn for
   a 192x192 area with ocean/river + forest + plains/meadow and ≥12 blocks of relief; viewpoints are derived from it
   and stored in `saves/mcbench-world/mcbench-spot.json`. With seed 20261006: centre (-1088, 128), water 48 %,
   forest 17 %, plains 20 %, heights 63..80, a small village on the shore.
6. **torches** (first run per world only): after loading the overview area, the flattest dry patch near the centre
   (from real terrain) gets a 7x7 torch grid 5 blocks apart; the night camera aims at it. Done before the first perf
   run so every perf run sees the same world.
7. **perf** (noon, clear): settle → 2 s warm-up → **spin**: 360° yaw at the overview point (y≈96, pitch 10° down)
   over N s → **flight**: straight line along the overview direction at constant altitude, 10 blocks/s, N s
   (`-Dmcbench.flightSpeed`). Route position is a function of wall time, so it is identical at any fps.
7b. **stress** (opt-in, `--jvm "-Dmcbench.stress=reload,resize,nether"`; any subset, in the order given; default none):
   `reload` = a full resource reload (what F3+T does), `resize` = fullscreen -> windowed -> fullscreen (two framebuffer
   resizes), `nether` = tp to the Nether for 8 s and back (two dimension changes). Each is logged and recorded in
   results.json `stress` (seconds, notes); an exception fails the run. The shots that follow show the state afterwards.
8. **shots**: for each scene set time/weather + pose → settle → hold `-Dmcbench.shotWait` (2 s; rain 4 s) →
   `Screenshot.takeScreenshot(mainRenderTarget)` → PNG. Times: noon 6000, sunset 12400 (facing west over water),
   night 18000 (torch field), rain = noon overview with `weather rain`.
8b. **water shots** (opt-in, `--jvm "-Dmcbench.water=true"`; owned by the m2-water work, `mcbench/WaterShots.java`): after
   the standard shots (skip those with `-Dmcbench.standardShots=false` for quicker look-dev runs) poses are found in the
   loaded world around the spot (read only, nothing built) and shot like extraShots: `water_sunset` (low over open water
   facing the setting sun, far shore on the horizon: glitter road, sky and shore reflections), `water_shore_noon` and
   `water_shore_evening` (3 blocks above water looking at a shore with trees 10-24 blocks away: SSR, refraction,
   absorption), `water_shallows` (looking steeply down into 2-4 blocks of water: refraction, caustics, foam),
   `water_underwater_up` (4 blocks under the deepest water nearby looking up toward the sun: Snell's window, total
   internal reflection). Chosen poses go to results.json `waterShots`. Example:
   `--jvm "-Dmcopt.shade=true -Dmcbench.water=true -Dmcbench.standardShots=false" --scenes shots`. The Iris / Complementary
   reference of the same poses: `--instance "mcopt DEV-IRIS" --jvm "-Dmcbench.water=true -Dmcbench.standardShots=false" --scenes
   shots` (bench-results/20261006-220207-wref-iris).
8c. **motion shots** (opt-in, `--jvm "-Dmcbench.motionShots=forest;overview;sunset"`; owned by the m2-taau work,
   `mcbench/MotionShots.java`): screenshots taken *while the camera moves*, for temporal AA ghosting / smearing / shimmer.
   Each entry settles at its start pose, holds still `-Dmcbench.shotWait`, then moves at constant velocity for
   `-Dmcbench.motionSeconds` (1.5 s) and shoots mid-motion: `forest` → `motion_forest_pan` (forest close-up turning
   30°/s: leaves against the sky, shadows), `overview` → `motion_overview_strafe` (overview strafing right at 8 blocks/s:
   parallax, thin edges), `sunset` → `motion_sunset_pan` (sunset over water turning 15°/s: glint, horizon). Custom:
   `name@time@x,y,z,yaw,pitch@vx,vy,vz,yawPerSecond`. Poses and motion go to results.json `motionShots`.
8d. **god-ray shots** (opt-in, `--jvm "-Dmcbench.godRays=true"`; owned by the m2-vl work, `mcbench/GodRayShots.java`):
   after the standard shots, poses found in the loaded world around the spot (read only), golden-hour low sun:
   `vl_forest_morning` (under a 65-85% leaf canopy looking east and up, time 250), `vl_forest_side` (same spot looking
   south, 600: shafts crossing the view), `vl_forest_evening` (5+ blocks out of the forest edge looking back west into
   the sun behind the trees, 11750), `vl_overhang` (standing room under a solid non-leaf roof, cave mouth / overhang,
   looking out of its opening east (250) or west (11750)), `vl_shelter` (inside a village house looking out of an
   east/west door; skipped when the area has none, as with the default world), `vl_underwater` (7 blocks under the
   deepest water nearby looking up toward the sun, 2600). Chosen poses go to results.json `godRays`. The Iris /
   Complementary reference of the same poses: `--instance "mcopt DEV-IRIS" --jvm "-Dmcbench.godRays=true
   -Dmcbench.standardShots=false" --scenes shots` (e.g. bench-results/20261006-181431-vlref-iris, older times).
8e. **stray keys**: the game has the keyboard while it is fullscreen on the built-in panel, so a key pressed then (E,
   Esc...) opens a screen that would end up in the next shot. The hold before every shot closes any open screen,
   re-hides the HUD and holds again; results.json `warnings` lists `closed screen <Screen> before shot <name>`.
8g. **sky / dimension shots** (opt-in, `--jvm "-Dmcbench.dims=true"` or a comma list of `nether,end,aurora,lava,snow`;
   owned by the M3 sky/dimensions/XDR work, `mcbench/DimShots.java`): poses found by the integrated server (the Nether and
   End chunks they need are generated on the way; nothing is built except one powder snow block for `snow`, removed after
   its shot), each reached with `execute in <dimension> run tp`: `nether_wastes`, `nether_crimson`, `nether_warped`,
   `nether_soul`, `nether_basalt` (an open view near the nearest such biome, skipped if none within 2000 blocks),
   `nether_lava_sea` (low over the lava sea), `end_island` (above the main island's edge looking at the centre),
   `end_void` (from its south edge out over the void), `aurora_snow` / `aurora_snow_low` (midnight in the nearest snowy biome
   looking north, up and toward the horizon; add `-Dmcopt.shade.aurora=always` to force the aurora), `media_lava` (the camera
   inside the Nether's lava sea), `media_powder_snow` (inside a powder snow block, noon). The player is sent back to the
   overworld spot afterwards. Chosen poses go to results.json `dimShots`. Example:
   `--jvm "-Dmcopt.shade=true -Dmcopt.shade.aurora=always -Dmcbench.dims=true -Dmcbench.standardShots=false" --scenes shots`.
8f. **entity shots** (opt-in, `--jvm "-Dmcbench.entities=true"` or a comma list of `perf,noon,evening,night,hand,motion`; owned
   by the M3 base/entities work, `mcbench/EntityShots.java`): a tagged "zoo" (villager, iron golem, cow, sheep, pig, horse,
   chicken, an armour stand in iron armour, a chest, a sign, a fixed item frame with a compass, floating dropped items) is
   summoned on the torch-grid site (NoAI, facing the camera) and removed afterwards (`kill @e[tag=mcbench_ent]`, items
   around the site, the chest and sign set back to air; also at the start, for leftovers of an interrupted run).
   `perf`: a recorded perf phase **`zoo`** (results.json `perf.zoo` / `gpu.zoo`, like spin and flight): noon, camera 9.5
   blocks from the line panning +-20 degrees for `-Dmcbench.zooSeconds` (10) s, then **`zoo_noshadow`** (system property
   `mcopt.shade.entityShadows.pause=true`, read by mcopt-shade every frame: no entity shadows) and **`zoo2`** (as `zoo`): the
   entity shadows' cost A/B inside one session, bracketed (the first `zoo` runs ~0.15 ms high: warm-up; compare
   `zoo_noshadow` with `zoo2`). Pass `-Dmcopt.shade.drs=false` for any perf A/B (dynamic resolution holds the GPU near
   7.2 ms at balanced and hides costs). Shots: `ent_noon` (6000), `ent_evening`
   (11500, long shadows), `ent_night` (18000, torches), `ent_hand` (night, creative, torch in the main hand and a lantern in
   the off hand, HUD shown so the hand renders: the hotbar is in the shot), `ent_motion` (a villager, a horse and a pig
   teleported along +x at 4-6 blocks/s every server tick, camera still, shot mid-motion: TAAU ghosting). Poses go to
   results.json `entityShots`. Example: `--jvm "-Dmcopt.shade=true -Dmcbench.entities=true -Dmcbench.standardShots=false"
   --scenes shots` (the `zoo` perf phase runs with `--scenes shots` too when `perf` is in the list or `true`).
8h. **weather shots** (opt-in, `--jvm "-Dmcbench.weather=true"` or a comma list of `perf,rain,sunset,night,water,storm,glass,caustics`;
   owned by the M3 weather work, `mcbench/WeatherShots.java`): poses found around the torch grid and the sunset spot.
   `perf`: two recorded perf phases **`wxdry`** and **`wxrain`** (results.json `perf.wxdry/wxrain`, `gpu.*`): 2.6 blocks above
   the ground diagonally outside the torch grid, panning +-25 degrees for `-Dmcbench.wxSeconds` (10) s, clear noon then rain
   (the weather's cost). Shots: `wx_glass_noon` / `wx_glass_evening` (6000 / 11000: a 6x6 roof of six colours of stained glass
   4 blocks above a flat patch beside the torch grid, for coloured shadows; it is the only thing built, set back to air after
   the shots and before them, replacing only stained glass), `wx_rain_ground` (noon rain, same pose as perf), `wx_wet_sunset`
   (rain for 4 s, then clear, shot at 12000: the ground still wet), `wx_night_rain` (18000, torch light in the puddles),
   `wx_rain_water` (2.5 blocks above the water at the sunset spot, looking down: raindrop rings), `wx_storm` (rain over the
   water looking west, a `lightning_bolt` summoned on the water 45-90 blocks ahead, shot 0.12 s later). `wx_caustics` (noon, clear: 5 blocks above
   shallow water 1-3 deep found within 48 blocks of the sunset spot, looking down at its floor: water caustics). Glass first, so the
   ground is dry there. Pair with `-Dmcopt.shade.weatherTime=10` (wetness builds and dries 10x faster). Poses go to
   results.json `weatherShots`. Example: `--jvm "-Dmcopt.shade=true -Dmcopt.shade.weatherTime=10 -Dmcbench.weather=true
   -Dmcbench.standardShots=false" --scenes all --seconds 15`.
8i. **cloud / sky shots** (opt-in, `--jvm "-Dmcbench.clouds=all"` or a comma list of `noon_south,noon_north,up,morning_east,
   sunset_west,sunset_east,dusk,night,rain,thunder,above,inside,water`; owned by the M3 volumetric clouds work, branch
   `m3-clouds`, `mcbench/CloudShots.java`): fixed poses at the overview point (no world search), each shot `clouds_<name>`:
   noon toward / away from the tilted sun (15 degrees up), 70 degrees up, morning toward the sun (1000), sunset toward /
   away from the sun (12400), dusk (13000), night (18000, 25 degrees up), rain and a thunderstorm (`weather thunder`), 380
   high over the cloud tops, 250 high inside the cumulus layer, and the sunset spot 6 blocks higher looking down at the
   reflected clouds (11800). Times/weather go to results.json `cloudShots`. Example:
   `--jvm "-Dmcopt.shade=true -Dmcopt.shade.clouds.log=true -Dmcbench.clouds=all -Dmcbench.standardShots=false" --scenes shots`.
   **`perf`** (only by name, not part of `all`; runs before the shots): three recorded phases **`clouds`, `clouds_off`,
   `clouds2`** (results.json `perf.*` / `gpu.*`): noon, clear, the overview point turning a full circle 8 degrees up in
   `-Dmcbench.cloudsSeconds` (12) s, with the volumetric clouds paused in the middle one (system property
   `mcopt.shade.clouds.pause`, read by mcopt-shade every frame: vanilla's clouds, as `mcopt.shade.clouds=false`): the
   clouds' whole cost A/B in one session, bracketed. E.g. `-Dmcbench.clouds=perf,noon_south,sunset_east`.
8j. **dynamic-light shots** (opt-in, `--jvm "-Dmcbench.dynLights=true"` or a comma list of
   `field,mobs,perf,cave,walk,water,nether`; owned by the M3 dynamic lights work, branch `m3-lights`,
   `mcbench/LightShots.java`): creative mode, a **torch in the main hand and a soul lantern in the off hand**, HUD shown
   (the first-person hand renders; the hotbar shows in these shots), difficulty easy while it runs (NoAI hostile mobs),
   everything tagged `mcbench_dl` and killed afterwards (spectator, empty hands, peaceful and the hidden HUD come back).
   Night (18000): `dl_field_hand` (a flat dry patch 30-60 blocks from the torch grid, looking 30 degrees down),
   `dl_field_mobs` (the patch from 9 blocks: a zombie holding a torch, a skeleton holding a soul lantern, a villager holding
   a lantern, a burning zombie, a magma cube, a blaze, dropped torch / glowstone / sea lantern), `dl_cave_hand` (the
   roomiest dark cave under the area, along its longest free direction), `dl_cave_walk` (walking that way at 3.5
   blocks/s, shot after 1.5 s), `dl_water` (a shore 2 blocks from open water, looking out), `dl_nether` (blazes and a
   magma cube near 0,0 in the Nether, then back). `perf` = three recorded phases on the mobs view panning +-15 degrees,
   `-Dmcbench.lightsSeconds` (10) s each: **`lights`, `lights_off`, `lights2`** (results.json `perf.*` / `gpu.*`); in
   `lights_off` mcbench sets the system property `mcopt.shade.dynLights.pause=true`, which mcopt-shade reads every frame
   (no lights), so the module's whole cost is an A/B inside one session. Pass `-Dmcopt.shade.drs=false` for it (dynamic
   resolution would otherwise move the scale between the phases). Poses go to results.json `dynLights`.
8k. **view bobbing** (opt-in, `--jvm "-Dmcbench.bob=0.1"`; `mcbench/BobSim.java`, mixins `CameraBobMixin`,
   `GameRendererBobMixin`; added 2026-10-08): vanilla's walking bob (GameRenderer.bobView, which goes into the projection
   and moves the eye by up to 0.1 block) as if the player walked on the ground. The spectator camera never bobs (the bob
   decays to 0 off the ground) and the dev instances have `bobView:false`, while the user's instances have it on: the sky
   turning while walking (mcopt-shade FT1, view rays taken from the camera's origin) never showed in a bench run. `<amplitude>`
   animates the walk cycle at walking pace (2.6 walk distance per second, wall time, also with ticks frozen); `<amplitude>@<walk>`
   holds one phase (`@0` = 0.1 block down, the strongest tilt; `@0.5` = 0.05 block to the side). Read every frame, so a
   variantShots / ab phase can set it: `-Dmcbench.variantShots=bsun@12400@sunset_west@clear@still:mcbench.bob=-;low:mcbench.bob=0.1@0`.
   While it is set the bobView option counts as on. Example runs: `notes/bob-2026-10-08/bob1.sh` (stills), `bob2.sh` (recordings).
9. **cleanup + finish**: `weather clear`, `time set 6000` (so the saved world never starts the next run in rain —
   shader packs smooth wetness/fog over tens of seconds), write results, `Minecraft.stop()` (clean quit; world saved).

**Settle** = client loaded-chunk count unchanged for 2 s AND `LevelRenderer.hasRenderedAllSections()` (Sodium:
`isTerrainRenderComplete`) true for 1.5 s AND rain level at its target AND ≥3 s; timeout 120–180 s (a warning, not a
failure). First run in a fresh world: ~15 s to generate the area; later runs ~3 s per scene. A full `--scenes all
--seconds 20` run is ~2.5 min wall time including launch.

Robustness: any exception → `error.txt` + quit; in-game watchdog (`-Dmcbench.timeoutSec`) → `error.txt` + quit
(halt after 60 s more); a shutdown hook writes `error.txt` if the game exits early; the runner adds its own timeout.

mcopt integration: mcbench also ships `mcopt.bench.Bench` with the static `phase` (BOOT, LOAD, SETUP, SETTLE, WARMUP,
SPIN, FLIGHT, SHOT, DONE) and `settledAt` fields that mcopt already reflects on (`Cpu.phase()`, `AbShots`, `Recorder`
phase start/stop). GPU times come from `mcopt.metal.GpuTimes.snapshot(phases)` (reflective, with
`-Dmcopt.metal.gpuTimes=true`, which the runner passes by default).

## Instances

Created by `bin/mcbench-setup-instances` in `~/Library/Application Support/PrismLauncher/instances/` (folder name =
Prism instance ID = name). Same `mmc-pack.json` as "mcopt Metal 26.3" (MC 26.3, Fabric 0.19.5, intermediary 26.3,
LWJGL 3.4.3), same Java (Prism's java-runtime-epsilon, 25.0.1), `AutomaticJava=true`, 6 GB heap, Prism console off.

- **mcopt DEV** — Metal. Default mods: upstream mcopt 0.2.0-alpha.3 + Sodium 0.9.2 (runner usually replaces them).
- **mcopt DEV-DH** (FT0, opt-in: `bin/mcbench-setup-instances --only "mcopt DEV-DH"`) — Metal + Distant Horizons
  3.3.4-26.3 + Fabric API 0.161.0+26.3 (unmodified Modrinth jars, sha512-checked, in `work/packs/dh/`). Its
  `config/DistantHorizons.toml` starts from `work/packs/dh/DistantHorizons.toml` (the user's Pollones DH settings plus the
  Mac changes of far-terrain-plan §5; `--reset-dh-config` rewrites it). mcbench-run adds `-XX:+UseZGC` for it. Pass the DH
  and Fabric API jars with `--mods` along with mcopt and Sodium. Its world is its own `saves/mcbench-world` (same seed/spot);
  DH's LOD database lives in it, so later runs start with LODs already generated.
- **mcopt DEV-IRIS** — OpenGL + Iris 1.11.7 + Complementary Reimagined r5.9.3 (default settings), upstream mcopt jar
  with `mcopt.metal=false`. `config/iris.properties`: `shaderPack=ComplementaryReimagined_r5.9.3.zip`, `enableShaders=true`.
  Its `saves/mcbench-world` is a copy of DEV's (same seed would give the same world anyway).

Bench options (options.txt, both): vsync off, maxFps 260 (= unlimited), render distance 16, simulation 8, fancy-equivalent
custom preset (AO on, fancy clouds 64, biome blend 2, mipmaps 4, RGSS filtering, cutout leaves, entity shadows,
particles all, no improved transparency), chunk fade-in 0, FOV 70, bobbing off, `pauseOnLostFocus:false`,
`inactivityFpsLimit:minimized`, sound master 0, onboarding/tutorial/realms notifications off, GUI hidden by the bench.
`fullscreen:false` on disk (the bench switches to fullscreen after moving the window), `exclusiveFullscreen:true`,
`overrideWidth/Height 1512x982` (points).

**Framebuffer size:** exclusive fullscreen on the built-in 14" panel picks its 1512x982@120 HiDPI mode and gives the
full **3024x1964** framebuffer (incl. the notch rows). Windowed the best is **3024x1680** (window 1512x840 pt: the
menu bar + Dock take the rest), and windowed runs were also much slower/hitchier on Metal (303 vs 618 fps on the
same route, first runs). So all Retina numbers use fullscreen. "1080p" = `--windowed --window 960x540` on the built-in
panel = 1920x1080 framebuffer (the user's external XZ3020 1920x1080@144 monitor can be used instead with
`--display XZ3020`; not used for the baselines so as not to take over the user's main screen).

## Gotchas (read these)

- **Jars (2026-10-10):** Gael removed the old "mcopt Metal 26.3" instance (and every shader version but the newest): Sodium
  (`sodium-fabric-0.9.2+mc26.3.jar`) and the original mcopt (`mcopt-0.2.0-alpha.3.jar`) now come from `work/release/`
  (same SHA-256); the scripts were repointed there.

- **`--jvm` is split on whitespace: no spaces inside a value.** The project path has spaces ("Minecraft Metal Shaders"),
  so e.g. `-Dmcopt.rec=<project>/notes/x.mp4` breaks the launch ("game exited without results.json", `jvm-args.txt` shows
  the path cut at the spaces). Write such files to a path without spaces (the session scratchpad, `/tmp`) and copy them
  into the project afterwards (`notes/bob-2026-10-08/bob2.sh`).
- **Never launch Minecraft any other way** than `mcbench-run`; never touch the user's instances. The script refuses
  other instance names and waits while the user plays.
- **Prism caches instance.cfg while it runs** and splits `JvmArgs` on spaces. So per-run args go through
  `bin/mcbench-wrap` (Prism `WrapperCommand`, set once in instance.cfg): it reads `.mcbench/<instance>.args`, drops
  every `-Dmcbench.*`, `-Dmcopt.*`, `-Diris.*` and stray non-`-` fragments that Prism put before `-cp`, and execs java.
  Consequence: **instance.cfg's `-Dmcopt.*` flags are ignored** — pass mcopt flags with `--jvm` (or put them in the
  instance's `config/mcopt.properties`, which mcopt's Profile reads).
- `open -n -a Prism --args --launch <id>` starts a Prism that quits after the game exits (if Prism was already open, the
  new process hands the launch to it via IPC). Prism logs: `~/Library/Application Support/PrismLauncher/logs/`.
- **mcopt's default `alpha` profile breaks Iris**: `mcopt.chunk.mesh=all` includes `lambda`
  (`mcopt.metal.mixin.chunk.BlockRendererMixin`), which makes Iris's `MixinBlockRenderer` fail
  (`InvalidInjectionException` on Sodium `BlockRenderer.renderModel`) → crash at startup. DEV-IRIS therefore runs with
  `-Dmcopt.chunk.mesh=air,bounds,vis,biome`. **The user's own "mcopt Shaders 26.3" instance (never launched yet) will hit
  this crash** unless the same flag (or `profile=none`) is set.
- The external monitor (XZ3020, 1080p 144 Hz) is the macOS *main* display when connected; without `--display` logic the
  game would open there. The bench moves it to the built-in panel by default.
- **Windowed runs and the external monitor (2026-10-10):** plugging the XZ3020 in during a `--windowed` run moved the game window onto
  it (scale 1.0: a 960x540 framebuffer instead of 1920x1080; comparisons against earlier 1080p runs then fail on size). Check `fb` in
  the one-line summary; repeat such runs once the display setup is stable.
- Power: `power.txt` records AC/battery. Compare runs on the same power source (first test runs were on battery).
- `-Dmcopt.edgeCap=false` is always passed (the alpha profile turns edgeCap on, which caps fps while terrain generates).
- mcopt's pacer: with vsync off mcopt presents at most one frame per refresh (`gpuAvgFps` ≈ 117) but renders every frame;
  CPU fps (frame times) is the throughput number, `gpu.gpuMsPerFrame` the GPU cost.
- The world is shared by all runs of an instance. Don't build in it. `--fresh-world` recreates it (same result).
- Screenshots are read back from `mainRenderTarget` (post-everything, before the GUI, GUI hidden anyway).
- A run takes the built-in panel (exclusive fullscreen) for ~2–3 min; the user may be working on the external monitor.
- **Noise**: other agents build (gradle) while a game runs, which makes hitches (one baseline run had 1% low 96 fps vs
  203 in the next). Averages were within ±5 %; 1% lows need ≥2 runs. Compare medians of ≥2 runs, same power source.
- **Hung Prism**: a Prism the runner started for a dev instance (`prismlauncher --launch mcopt DEV...`) once hung after
  an account refresh without starting the game (2026-10-06 20:06): every later `open -n ... --launch` was handed to it
  through IPC and failed with "Prism did not start the game within 240 s". On that timeout the runner now ends such a
  Prism (only one whose arguments are `--launch` of a dev instance), so the next run launches normally.
- Another agent added a third instance, `mcopt DEV-DH` (Distant Horizons; `mcbench-setup-instances --only "mcopt DEV-DH"`).

## Baselines (M0)

Measured 2026-10-06 on AC power, route `--seconds 20` (20 s spin + 20 s flight), RD 16, vsync off, mcbench world
seed 20261006. The machine was in use at the time (external XZ3020 monitor as main display, Zoom, browsers, the
Claude app, other agents building), so treat the 1% lows as noisy. Values: **median of 3 runs** (individual runs in
brackets). Re-run with `bin/mcbench-baselines` (`ROUNDS=N`), table with `bin/mcbench-summary --label <label>`.

| Config | Framebuffer | Spin avg fps | Spin 1% low | Flight avg fps | Flight 1% low | GPU ms/frame (spin / flight) |
|---|---|---|---|---|---|---|
| **baseline-mcopt-metal** — fork `shade` (= upstream 0.2.0-alpha.3) + Sodium, Metal, alpha profile | 3024x1964 fullscreen | **528** (508, 536, 528) | 109 (96, 203, 109) | **407** (407, 440, 405) | 87 (87, 175, 87) | **1.85 / 2.36** (GPU busy ≈100 %: GPU-bound) |
| **baseline-iris-gl-retina** — OpenGL, Iris 1.11.7, Complementary Reimagined r5.9.3 defaults | 3024x1964 fullscreen | **22.4** (22.4, 21.8, 23.2) | 14.0 | **21.4** (20.6, 21.4, 21.9) | 10.5 (4.0, 10.5, 11.0) | n/a (GL) |
| **baseline-iris-gl-1080p** — same, window 960x540 pt on the built-in panel | 1920x1080 windowed | **43.3** (42.1, 43.3, 45.5) | 20.1 | **41.9** (41.9, 41.4, 42.4) | 21.3 | n/a |
| (extra, 1 run, 10 s segments) Iris+Complementary fullscreen on the user's external XZ3020 1080p@144 | 1920x1080 fullscreen | 50.2 | 25.6 | 47.0 | 8.6 | n/a |

Result dirs: `bench-results/20261006-130746-baseline-mcopt-metal`, `…-131314-baseline-mcopt-metal`,
`…-133512-baseline-mcopt-metal`, `…-130931-baseline-iris-gl-retina`, `…-131520-…`, `…-133754-…`,
`…-131123-baseline-iris-gl-1080p`, `…-131757-…`, `…-134126-…`, `…-130056-test5-iris` (external monitor).

Reading: vanilla mcopt Metal costs ~1.9–2.4 ms of GPU per frame at full Retina on this route (CPU frame rate 400–530
fps), so M1 has a budget of ~6 ms for shadows + lighting + sky before dropping under 120 fps. Iris+Complementary is
~2.0x slower at Retina than at 1080p (2.87x the pixels) and runs ~22 fps at native Retina; the user's present setup
(~1080p) gives 42–50 fps. Complementary also looks hazier in the overview (its distance fog), see the screenshots.

Screenshots (all 5 scenes each): `<result dir>/screenshots/{noon_overview,forest_closeup,sunset_water,night_torches,rain}.png`.

## Rebuilding

```bash
cd work/mcbench && JAVA_HOME=".../work/tools/jdk-25.0.4.1+1/Contents/Home" ./gradlew --no-daemon build   # ~5 s
python3 work/bin/mcbench-setup-instances            # recreate/refresh the dev instances (keeps saves)
```

## Lessons (2026-10-07)

- **Perf runs must run alone.** Video analysis (ffmpeg decode + numpy over GBs of frames) running at the same time as a
  perf run made the GPU times 0.5-0.8 ms too high: on Apple silicon CPU memory traffic steals GPU bandwidth. Queue
  recordings and their analysis separately from perf batches, and alternate builds (ABBA) within one batch.
- Flicker measurement with static holds and the recorder: `work/notes/vl-stable-2026-10-07/README.md`.
- **Power source and other apps change whole-frame numbers non-uniformly** (2026-10-07 evening, batches O/P in
  `work/notes/vl-stable-2026-10-07/results/RESULTS_cost.md`): on battery with Safari/audio active, M2.2 measured ~+1 ms over
  M2.1 in a clean ABBA, while on AC with nothing else open the same jars differ by +0.05 ms. The extra was not in the passes'
  own GPU timers. Compare only runs with the same `power.txt`, keep other apps closed, and prefer in-session A/B switches.
- 2026-10-08: with the Mac screen **locked** (it locked at midnight while tests ran unattended) the game never starts: runs
  fail with "Prism did not start the game within 240 s" even with `--direct`. Check `ioreg -n Root -d1 -a | grep -A1 ScreenIsLocked`
  before long unattended batches; nothing can be measured until Gael unlocks the Mac.
- 2026-10-08 (correction): the runs after midnight failed mainly because `bin/mcbench-direct` read the player name from
  `logs/latest.log` with `set -euo pipefail`: after the daily log rotation the grep found nothing and the script ended
  silently (no game, "did not start within 240 s"). Fixed: it also reads the rotated `*.log.gz` and never fails there.
  A locked screen can still stop a fullscreen launch; both were true that night.
- 2026-10-10: to reproduce a player's scene without his world, build it with `-Dmcbench.variantSetup=cmd;;cmd` ('+' = space; runs at the
  first variant group's pose, so its chunks are loaded) and undo it with `-Dmcbench.variantTeardown=cmd;;cmd` (after the last group);
  examples in `notes/ft210-2026-10-10/t2.sh` (a sealed cave room with water), `t3.sh` (snow platform), `t6.sh` (walls at the render distance).
- 2026-10-11 (FT2.11): the bench world has natural flowing water (a spring) within ~96 blocks of the perf route, so `WaterFlowScan`
  reports moving water near and the translucent terrain draws with its falls variant in every perf run (log line
  `[mcopt] shade: moving water near the camera: true`). To price water code in one session use `notes/ft211-2026-10-11/price.sh`
  (LEAN_DEV defines, 4 pairs) and run a control define first: the method's noise is ±0.04 ms, sessions apart differ by ±0.3 ms.
