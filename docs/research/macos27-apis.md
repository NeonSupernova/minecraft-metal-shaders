# macOS 27 APIs for Metal Shaders

Source: the `sdk27` CI job (GitHub's `xcode-27` image: macOS 27.0.1, Xcode 27.0, macOS SDK 27.0), which lists every
declaration marked `API_AVAILABLE(macos(27.0))` in Metal, MetalFX and QuartzCore. Counts there: Metal 47, MetalFX 31,
QuartzCore 0. The native library builds against this SDK unchanged.

Items 1 and 2 are in, opt-in only (`-Dmcopt.metal.framegen.matrices=true`, `-Dmcopt.metal.fxDirect=true`); the rest are not adopted yet: each changes an image path that can only be judged in-game. Every adoption goes
behind `if (@available(macOS 27.0, *))`, so macOS 26 keeps today's behaviour.

## Worth adopting, in order

1. **Frame generation: camera matrices** (`MTLFXFrameInterpolator.worldToViewMatrix`, `viewToClipMatrix`).
   `mcf_interpolate` (mcframegen.m) describes the camera to MetalFX only as near, far, vertical FOV and aspect ratio.
   The full view and projection matrices let MetalFX reproject exactly, including the camera's roll and any off-centre
   projection (view bobbing, the nausea effect). Expected result: fewer interpolation artifacts on camera motion. Cost:
   two matrices passed down from Java (FrameGen already has the camera).
   **Status:** opt-in with `-Dmcopt.metal.framegen.matrices=true` (with frame generation on). The setters are sent by
   selector after `respondsToSelector:`, so the jar still builds on the macOS 26 SDK and does nothing on macOS 26. The
   log says once whether MetalFX took them. Compare the same scene with the flag on and off before making it the default.

2. **Upscaler output straight into the target** (`MTLFXTemporalScaler.outputOffsetX/Y`, plus the per-input
   `colorContentOffset*`, `depthContentOffset*` and `motionContentOffset*`).
   `mc_fx_upscale` (mcmetal.m) has MetalFX write into its own `fx->output` texture and then blits that to `dst`: one
   full-resolution copy every frame. With an output offset, MetalFX can write into the sub-rect of the destination
   directly, if `dst` is created with the scaler's `outputTextureUsage`. Expected result: one full-screen copy less per
   frame (about 24 MB read and 24 MB written per frame at the 14-inch panel's 3024x1964 in BGRA8). The input offsets also let the scaler read a sub-rect
   of a larger target. That is what dynamic resolution renders into, which today it can't hand MetalFX without a copy.
   **Status:** the same-size case needs no macOS 27 API, so it's in for every macOS as an opt-in,
   `-Dmcopt.metal.fxDirect=true`: MetalFX writes into the game's target when that target has the same size, format and
   output usage, and the log says once whether it could and why not. The offsets (macOS 27) are for the sub-rect case.

3. **Frame generation input size** (`MTLFXFrameInterpolator.contentWidth/Height`, `depthContentOffset*`,
   `motionContentOffset*`, `outputOffset*`). Frame generation could take the dynamic-resolution sub-rect directly
   instead of full-size copies of the level image.

4. **Jittered motion vectors** (`MTLFXTemporalScaler.jitteredMotionVectorsEnabled`). The motion pass in `mc_fx_upscale`
   writes unjittered vectors. If MetalFX accepts jittered ones, that pass could reuse the shaders' own motion vectors
   instead of reprojecting depth in a separate compute dispatch.

5. **`requiresPrevColorTexture`** (frame interpolator). If MetalFX keeps its own history, setting it to NO would drop
   one of the two full-size colour copies `FrameGen` keeps in rotation. Needs a look at the header documentation for
   what MetalFX does instead.

## Probably not

- `MTLTexture.minLOD` (writable on the descriptor, readable on the texture): a sampler-free LOD clamp. Useful only if a
  pass wants to hide mips it hasn't written yet (streamed textures); nothing here does.
- `MTLLanguageVersion4_1`: newer shading language, not needed by today's shaders; check the MSL 4.1 spec before any use.
- RGB8/RGB16/RGB32 pixel formats: 3-channel formats save a quarter of the memory of RGBA, but GPUs often pad them and
  they can't be render targets in most cases; nothing here stores 3-channel data at size.
- Tensor APIs (`MTLTensor` auxiliary planes, FP8/FP4 types) and persistent compute kernels (`optimizeForPersistentKernel`,
  `forwardProgressUsage`): machine-learning work, no use in the renderer today.
