# Volumetric light / god rays (M2 module, branch `m2-vl`)

Shader bible E07: sun shafts through the M1 sun shadow map, crepuscular streaks toward the sun, caustic light shafts
under water, faint moon shafts at night. Self-contained module on top of `m1`, integration-ready for the HDR base (`m2`).
All code is original (techniques from public papers, see the header of `vl.metal`); no shader pack code, so no NOTICE
entry is needed. New files are Apache-2.0.

## 1. Files

| File | What |
|---|---|
| `metal/src/main/resources/mcopt/shade/vl.metal` | All MSL: `VlParams` (256 B), canopy kernel, march, temporal, composite (LDR and HDR variants via function constant 0 `VL_HDR`) |
| `metal/src/main/native/mcvl.m` | Pipelines, VL-resolution targets (private RenderTarget\|ShaderRead RGBA16F, compressible), per-frame encoding |
| `metal/src/main/native/mcshade.m` | + `mc_shade_bind_render` / `mc_shade_bind_compute`: M1 globals (ShadeFrame 16, shadow map 16 + compare sampler 12, LUT 17) on our own encoders |
| `metal/src/main/java/mcopt/metal/shade/vl/ShadeVl.java` | CPU side: medium, light colour, strength, canopy smoothing factors, water surface; packs `VlParams`; calls native |
| `.../vl/VlConfig.java` | Flags (below) |
| `.../vl/VlNative.java` | JNI-free FFM bindings to `mc_vl_*` |
| `.../vl/mixin/VlGameRendererMixin.java`, `VlMixinPlugin.java`, `resources/mcopt-metal-shade-vl.mixins.json` | Hook H7 on m1: `GameRenderer.renderLevel` INVOKE `render3dHud` (level drawn, main depth intact, hand not yet drawn). Applies only when `VlConfig.ENABLED` |
| `tools/shade/vlcheck.m` (+ `validate.sh`, `ShadeMslDump.java`) | Compiles `vl.metal` like mcvl.m and builds every pipeline: march, temporal, LDR composite (RGBA8), HDR composite (RGBA16F, One/SourceAlpha), canopy kernel |

## 2. Flags (system properties or `config/mcopt.properties`; read once)

| Flag | Default | |
|---|---|---|
| `mcopt.shade.vl` | true (with `mcopt.shade=true`) | god rays on. With `mcopt.shade=false` nothing of this module loads (mixin plugin) |
| `mcopt.shade.preset` | performance (balanced on m1) | performance / balanced / quality / ultra: VL scale 0.25 / 0.33 / 0.5 / 0.5, steps 10 / 12 / 16 / 24, screen taps 6 / 8 / 10 / 12, distance 160 / 160 / 224 / 224 (m2's base defaults the same property to performance) |
| `mcopt.shade.vl.scale` | preset | march resolution relative to the main target |
| `mcopt.shade.vl.steps`, `.ssTaps`, `.dist` | preset | override the preset |
| `mcopt.shade.vl.strength` | 1 | overall brightness |
| `mcopt.shade.vl.mist` | 1 | density of the canopy mist (what shows the shafts under trees / overhangs) |
| `mcopt.shade.vl.extinction` | true | the canopy mist also dims what is behind it (false: light only added) |
| `mcopt.shade.vl.moon` | 1 | moon shafts relative to sun shafts |
| `mcopt.shade.vl.underwater` | true | caustic shafts under water |
| `mcopt.shade.vl.temporal` | true | temporal accumulation (false: raw march, for comparisons) |
| `mcopt.shade.vl.canopy` | -1 | force the canopy factor 0..1 (debug; negative = measured on the GPU) |
| `mcopt.shade.vl.lossy` | false | lossy-compressed VL targets |
| `mcopt.shade.vl.fold` | true | m2: blend in the post chain's final pass (false: `vl_composite_hdr` pass) |
| `mcopt.shade.vl.debug` | 0 | 1 the light alone, 2 the marched distance, 3 the canopy mist's transmittance |
| `mcopt.shade.vl.log` | false | a line every 2 s: canopy (smoothed, raw shadowed fraction), haze, mist, canopy mist, strength, g, humidity, sun elevation |
| `mcopt.shade.vl.gpuTimer` | false | timestamp counters around march / temporal / composite; their average ms in the log (turns the log on) |

## 3. Frame (m1)

```
render3dHud INVOKE (VlGameRendererMixin -> ShadeVl.beforeHand), on the frame's command buffer (mc_frame_cmd):
  vl_canopy    compute, 1 threadgroup x 64   far cascade at 4 rings x 16 points (r 3/7/11/15, eye+1.5/+5): shadowed
                                              fraction -> smoothstep(0.12, 0.5) -> smoothed (rise 1.5 s, fall 4 s)
                                              into a 16-byte state buffer (GPU only, no readback)
  vl_march     VL res (preset x main)         per pixel: shadow-map march of the medium to the scene depth (t = D((i+xi)/N)^2;
                                              xi = per-frame R1 offset + a quarter-step IGN spread: coherent rays keep the
                                              shadow fetches in cache, 2-3x cheaper than full per-pixel jitter), near
                                              cascade (alpha-tested leaves) where it covers, else far; + screen-space march
                                              toward the sun over last frame's R8 sky mask (crepuscular streaks); under
                                              water: caustic-patterned shafts through the water column instead.
                                              out 0 (RGBA16F): rgb = in-scattered radiance (linear, pre-exposure), a = marched
                                              distance; out 1 (R8): sky mask for the next frame's streaks (ping-pong)
  vl_temporal  VL res                         3x3 depth-aware filter + reprojection (ShadeFrame.prevViewProj, camera delta),
                                              neighbourhood clamp, distance-mismatch rejection; history ping-pong (0.9)
  vl_composite full res, onto main RGBA8      depth-aware 2x2 bilateral upsample (thin foliage against the sky: nearest
               (programmable blending)        depth sample scaled by the marched length -> no halos); canopy mist
                                              transmittance T analytic at full res; result added BEFORE M1's output curve:
                                              e = curve^-1(dst); out = dst + curve(e*T + light) - curve(e)  (no light and
                                              T = 1 -> the exact same pixel; curve = M1's hue-preserving part, half
                                              precision); +-1 LSB triangular dither. Pixels getting < 1/1024 are untouched.
  (no pass at all when nothing would be drawn: deep in caves, no light and no canopy mist)
```

### The look (what makes it pretty)

- **Medium** (1/block, camera-relative heights): thin haze everywhere (sea + 16, H 48), sea-level ground mist (thick at
  dawn, lighter at dusk), and the **canopy mist**: a camera-local layer `s * e^(-t/16) * e^(-max(y-4,0)/8)` scaled by the
  canopy factor, `s` 0.008 (noon) .. 0.03 (golden hour) x humidity x rain x eye sky light. The open-air layers are
  gated to 0.7 and keep only what the shadows carve out of their glow (`max(lit - 0.5 x unshadowed, 0)`: the sky model
  and M1's haze already have the uniform glow, so the sky doesn't go milky), the canopy mist counts fully, gated to 1.6:
  shafts are strong under trees, overhangs and cave mouths, subtle over open plains (Complementary's "scene-aware"
  behaviour, our own measure).
- The canopy mist also **dims the scene behind it** toward its sky-lit colour (0.2 x ambient sky + a little warm sun),
  with half its scattering coefficient (art: a light veil, bright shafts). Analytic, full resolution: crisp edges.
- **Phase**: HG forward lobe (g 0.5 at noon .. 0.72 at golden hour) + 5% back lobe + 35% isotropic (shafts stay visible
  from the side).
- **Light colour**: ShadeFrame's sun colour, saturated x(1 + 0.55 golden) toward the low sun (sunset orange shafts);
  moonlight at night x2 x `vl.moon` of the (dim) moon colour; fades through the sun/moon handover.
- **Golden hour** = sun elevation below ~30 degrees (`1 - smoothstep(0.05, 0.55, sunY)`; the default 30-degree sun-path
  tilt caps noon at sunY 0.87).
- **Crepuscular streaks**: 6-12 taps toward the sun's screen position count open sky (last frame's sky mask) with
  decaying weights; `pow(saturate(mu), 6)`, 0.12 + 0.2 golden, x0.8 over open sky, faded by how far the sun is off
  screen and by the eye's sky light. Clouds write depth, so rays fan out of cloud gaps at sunset too.
- **Under water**: extinction (0.40, 0.11, 0.06)/block, scattering 0.022 (blue, the scene stays readable); light reaches
  each sample through the water above it, patterned by two-scale procedural caustics (broad ~13-block cells x a ~5-block
  web, mean 1, gaps 0.1; half precision) that develop with depth (none right under the surface) -> curtains converging
  toward the sun; N + 2 steps, D <= 48. (The pattern is evaluated at camera block mod 256: one pop when crossing a
  256-block line.)
- **No leaks**: below the far cascade's depth range = shadowed (underground); caves get no sun anyway (strength x eye
  sky light); the game's own fogs (lava, powder snow, blindness, darkness) switch VL off; Nether/End: off.

## 4. Measurements (MacBook Pro 14" M4, 10-core GPU, 3024x1964 fullscreen, harness `mcopt DEV`, Sodium)

In-game GPU time per pass (`-Dmcopt.shade.vl.gpuTimer=true`, fragment start..end timestamps, ~300-frame averages;
bench-results 20261006-213735-vl11, vlp11-*):

| Scene (balanced, VL 998x648) | march | temporal | composite | total |
|---|---|---|---|---|
| noon overview (open) | 0.44 | 0.11 | 0.80 | 1.35 ms |
| sunset over water | 0.47 | 0.12 | 0.84 | 1.43 ms |
| golden forest (morning / side / edge) | 0.38-0.59 | 0.12-0.13 | 0.94-1.01 | 1.45-1.73 ms |
| overhang, low sun | 0.52 | 0.12 | 0.98 | 1.64 ms |
| under water | 0.75 | 0.12 | 0.84 | 1.71 ms |
| perf route (spin+flight), balanced | 0.51-0.53 | 0.12 | 0.86 | 1.50 ms |
| perf route, performance (756x491) | 0.37-0.42 | 0.07 | 0.86 | 1.30 ms |
| perf route, quality (1512x982, before the jitter change) | 1.6-2.0 | 0.26 | 0.85 | 2.7-3.1 ms |

Whole frame (same session, harness `--scenes perf --seconds 15`): VL off 235 / 212 fps (GPU 4.25 / 4.73 ms per frame,
spin / flight); balanced 173 / 158 fps (5.79 / 6.33 ms); performance 179 / 162 fps (5.58 / 6.18 ms). So the
Performance preset stays well above 120 fps at native Retina with god rays on.

- The composite (~0.85 ms) is the floor on m1: a full-resolution read-modify-write of the RGBA8 main target plus its
  depth (0.41 ms of pure bandwidth in vlbench), the bilateral upsample (0.19), the curve difference (0.3, half
  precision) and, under trees, the mist (0.14). It also ends the backend's main pass (the hand pass reloads it).
- On m2 the composite is a blend into the HDR target without curves: 0.60 ms at native in vlbench, and with TAAU it
  runs at the internal resolution (0.67x -> ~0.27 ms). Estimated m2 cost at balanced with TAAU 0.67: march ~0.3,
  temporal ~0.05, composite ~0.27 = ~0.6 ms (bible E07 budget 0.40-0.55).
- `tools/shade/vlbench.sh [w h scale steps taps]` prices each pass outside the game (synthetic golden-hour forest,
  blocky 2048^2 shadow map). Run it while no game is running (other agents' bench games load the GPU).

Development history of the look and cost (screenshots in bench-results `*-vl3` .. `*-vl11`, Complementary reference of
the same poses in `20261006-181431-vlref-iris`, VL off in `20261006-192528-vl6off`): v2 nearly invisible (physical
haze densities, memAddress bug) -> v4 canopy mist with extinction (visible shafts, grey veil at dusk) -> v6 separate gates,
lighter veil -> v7 flatter phase (side shafts) -> v8 open-glow subtraction (no milky sky), bluer water -> v9-v11
optimisation (2.7 -> 1.5 ms in game, same look).

## 5. On the HDR pipeline (m2, merged)

Done on branch `m2` (see M2_STATUS.md "Volumetric light" for numbers and screenshots, M2_INTEGRATION.md section 4 for the
interfaces):

1. **Hook**: `ShadePost.addBeforeTonemap(100, ShadeVl::beforeTonemap)`, registered by the render3dHud mixin's first call
   with `ShadePost.active()`; the mixin's M1 path (LDR composite) stays for `mcopt.shade.hdr=false`. Native state made
   with flag 2 (HDR): canopy kernel with VL_HDR, `vl_temporal_fold`.
2. **Bindings**: `mc_shade_bind_render` binds 16-19 (ShadeFrame, ShadeLook, ShadeGpu, ShadeResources), `mc_shade_bind_compute`
   16-18; source = `ShadeMsl.prelude()` + vl.metal (`VlConfig.source`).
3. **Fold** (default): `vl_temporal_fold` also writes `lightT` (light + canopy-mist veil before exposure, a = T at VL res);
   `ShadePost.setVolumetric(lightT, history, dist)`; `shade_final` upsamples (gather fast path) and blends it after the
   haze. `mcopt.shade.vl.fold=false` / debug views: `vl_composite_hdr` (no target read; blend One/SourceAlpha into the
   RG11B10F/RGBA16F HDR target).
4. **Haze agreement**: open-air medium from ShadeLook (E06 haze luma ×4.6, mist ×4.2 × humidity, base heights and scale
   heights, rain), strength × `ShadeLook.vlFactor()` (keyframe VL column × look preset, softened).
5. **Canopy factor** → `ShadeGpu.scene.y`.
6. **HDR look constants** (ShadeVl `HDR_*`): air ×0.6, water ×0.45, veil 0.1, extinction 0.35, open glow cut 0.7, carve
   0.4 (canopy mist) / 0.55 (caustic shafts; `VlParams.carve`).
7. **Still to do**: TAAU (NoJitter matrices in `vlDirection`/`vl_temporal`, VL size from `renderScale.zw`), Distant
   Horizons merged distance for the march and the sky mask (FT2), cloud shadows (M3).

Measured at native 3024x1964 (performance): VL ≈ +0.52 ms GPU per frame (march 0.32, temporal 0.08, final-pass share
≈ 0.13); balanced ≈ +0.68 ms.

### M1 notes for reference (how the m1 → m2 move was planned)


The passes are the same; only the hook point, the exposure source and the blend change. `vl.metal` already contains the
HDR composite (`VL_HDR = true`): it returns `float4(light, T)` and reads the exposure from `ShadeGpu.exposure.x`
(`buffer(18)`, byte 176, a function-constant-gated argument, so the LDR variant doesn't need M2 structs). `mcvl.m`
builds it with blending `src * One + dst * SourceAlpha` (scene x T + light) when `mc_vl_render` gets flag 4.

Steps on m2:

1. **Hook**: drop `VlGameRendererMixin` (and its mixins json entry); register instead
   `ShadePost.addBeforeTonemap(100, c -> ShadeVl.render(c))` from ShadeVl's first use after `ShadePost.active()`. In the
   hook: `color = c.scene()` (RGBA16F HDR target), `depth = c.depth()`, size `c.width() x c.height()` (internal res with
   TAAU), encoder `c.enc()` (already a native Enc*: pass it where `MetalBridge.enc(encoder)` is used now) and
   `flags |= 4`. Keep the m1 mixin path for `mcopt.shade.hdr=false`.
2. **Bindings**: mcvl.m's `pass()` must also bind ShadeGpu at `buffer(18)` for the composite (on m2 `mc_shade_bind_render`
   binds slots 16-19 already: check, else `[r setFragmentBuffer:gpu offset:0 atIndex:18]`).
3. **Prelude**: `VlConfig.source()` -> `ShadeRender.prelude() + vl.metal` (shade_common alone is no longer the prelude).
   `ShadeFrame` (16) is unchanged.
4. **Haze coefficients**: take the open-air layers from ShadeLook so shafts and E06 haze agree: in `ShadeVl.pack` use
   `L.fogR` / `L.fogM` (and their heights) for `medium.x/y`, `haze.x/y`, `medium.z/w` instead of the local constants;
   the art gates (`gate.x/y`) and the canopy mist stay ours. `ShadeLook.vl` (x strength from the keyframes x look
   preset, y distance, z g, w steps) replaces `VlConfig.STRENGTH`/`DIST`/`g`/`STEPS` when present.
5. **Canopy factor**: also write it to `ShadeGpu.scene.y` (reserved for it) from `vl_canopy` (bind ShadeGpu as
   `device` there) so other passes (E06 mist, bloom fog) can use it.
6. **Rows**: m2's targets are GL row order like m1's main target; vl.metal maps pixel -> NDC with no flip already.
7. **TAAU** (`m2-taau`): the march's temporal pass reprojects with `ShadeFrame.prevViewProj`; with jitter in the
   projection use `ShadeLook.viewProjNoJitter/prevViewProjNoJitter` (or subtract `ShadeFrame.jitter`) in `vlDirection` and
   `vl_temporal`, otherwise the history wobbles by the jitter. With DRS, restrict the composite to `renderScale.zw`
   (viewport sub-rect) and size the VL targets from the active size, not the allocation.
8. **Distant Horizons**: DH pixels are depth 0 in the main depth (they look like sky): VL marches them to the max
   distance, which matches a far scene. When FT2 provides the merged scene distance, feed it to march/composite as
   `vlDistance` instead of the main depth.
9. **Bloom** then picks up the bright shafts automatically (they're in the HDR scene before the bloom chain).

## 6. Testing

```sh
W=".../work"; JAR=$W/wt/m2-vl/metal/build/libs/metal-0.2.0-alpha.3.jar
SOD="$HOME/Library/Application Support/PrismLauncher/instances/mcopt Metal 26.3/minecraft/mods/sodium-fabric-0.9.2+mc26.3.jar"
# god-ray scenes only (fast look-dev), with the VL log
$W/bin/mcbench-run --instance "mcopt DEV" --mods "$JAR" --mods "$SOD" --scenes shots \
  --jvm "-Dmcopt.shade=true -Dmcbench.godRays=true -Dmcbench.standardShots=false -Dmcopt.shade.vl.log=true" --label vl
# the light alone: add -Dmcopt.shade.vl.debug=1 ; without VL: -Dmcopt.shade.vl=false
```

mcbench god-ray scenes (`-Dmcbench.godRays=true`, mcbench `GodRayShots.java`, poses found in the loaded world):
`vl_forest_morning` (under the canopy, sun east, time 250), `vl_forest_side` (same spot looking south, 600),
`vl_forest_evening` (forest edge, sun west behind the trees, 11750), `vl_overhang` (under a natural roof / cave mouth
looking out into the low sun), `vl_shelter` (inside a village house looking out of an east/west door; skipped when none),
`vl_underwater` (7 blocks down looking up toward the sun, 2600).

`tools/shade/validate.sh` (needs a built metal module) checks every VL pipeline (`vlcheck`).

## 7. Known issues and follow-ups

- **Clouds don't cast shafts** in the march (M1 has no cloud shadow map); crepuscular streaks do fan out of cloud gaps
  because clouds write depth. With E05's `cloudShadow` (M2 clouds), multiply `v` in the march by it and add the bible's
  sparse far steps beyond the shadow range.
- **No coloured shafts through stained glass / water surfaces seen from above** (needs the M3 tint map); rays end at the
  water surface (translucents write depth).
- The canopy factor reads the far cascade (leaves opaque there): a lone tree counts like a forest edge; good enough for
  the look, smoothed over 1.5-4 s.
- `vl_forest_morning` / `vl_forest_evening` poses (mcbench) aim into dense leaves; the most dramatic default-world views
  are `vl_forest_side`, `vl_overhang` and `sunset_water`. No village door faces east/west near the spot, so `vl_shelter`
  is skipped in the default world.
- The m1 composite costs ~0.85 ms at native (see 4); on m2 it becomes a ~0.27-0.6 ms HDR blend.
- Under water the caustic pattern pops once when the camera crosses a 256-block line (float precision guard).

## M2 review changes (2026-10-07)

- **Live sub-rect**: the VL targets stay allocated from the world targets' allocation (scaleMax), but the march and
  temporal passes run on a viewport of `VlConfig.SCALE` x the *live* render (VlParams.size.xy), so VL gets cheaper with
  the dynamic resolution (balanced at 0.67: 669x434 instead of 898x583, 0.41 ms with the near march added). The history is
  read with a uv scale (`carve.zw`, last frame's live part) and the final pass upsamples with `ShadeFinalParams.v.zw`.
- **Canopy mist near march** (`mcopt.shade.vl.nearSteps`, P 6 / B 8 / Q 10 / U 12): the canopy mist has its own linear march
  over its first 2.5 ranges (40 blocks), so leaf gaps resolve into separate beams; carve 0.6 (was 0.4), canopy gate 2.4 in
  HDR (was 1.6), a 5-tap plus spatial pre-filter (was a 3x3) with a tighter depth weight.
- **Crepuscular rays**: on sky pixels (HDR) they are now the *shadows* of clouds and hills toward the sun (negative light,
  floored at 55 % of the pixel in shade_final) instead of an added glow, which had turned the sunset sky into a grey veil;
  in front of geometry only light through gaps (openness above 0.25) is added. Strength 0.08 + 0.12 golden (HDR).
- The M1/LDR path (`mcopt.shade.hdr=false`) under TAAU passes the live size.

## FT2.2: the shafts' local contrast (2026-10-09)

Gael saw the stronger look of a802352 as "un lente empañado": more canopy-mist light is mostly a uniform glow (with the sun
under the canopies every ray near the camera crosses lit air), not more shafts, and no per-pixel measure tells the two apart.
`vl_temporal_fold` now compares each VL texel with the local mean of r = in-scattered luminance / (the canopy mist's optical
depth along its ray + 0.5) (path-length free, so no halos at depth edges): light = max(gain·l − (gain − keep)·r̄·R, 0) →
keep × a uniform glow, more for what stands out, nothing for air darker than its surroundings. r̄ = last frame's
`vl_veil_down` (4×4 means of the history, R16F at a quarter of the VL size) + `vl_veil_blur_h/v` (9 taps, `hpRadius` apart).
In full from a canopy factor of 0.2; the three passes run only when the factor read back is above 0.005 (~0.1 ms under trees,
0 in the open). VlParams.hp (320 bytes): `mcopt.shade.vl.hp` / `hpKeep` 0.2 / `hpGain` 1.8 / `hpRadius` 2, live. With it the
canopy mist is ×2.5 (`canopyGain`), the cuts are FT2.1's, a soft knee 0.4 on the light the final pass adds (`knee`).
Measurements, tools and rounds: `work/notes/godrays-veil-2026-10-09/README.md` (velo FT2.1 → FT2.2 in the forest views
−18…−34 %, shafts +4…+18 %).
