// Frame generation (-Dmcopt.metal.framegen=true, FrameGen.java): MetalFX frame interpolation between the last two real
// frames' level images, presented in between them, with the GUI of the newer frame composited on top.
// Built without ARC like mcmetal.m. macOS 26+ only (MTLFXFrameInterpolator); older systems get a clear error.
#import "mcmetal.h"
#import <CoreVideo/CoreVideo.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CABase.h>
#include <mach/mach_time.h>
#include <objc/message.h>
#include <simd/simd.h>
#include <math.h>
#include <os/lock.h>
#include <pthread.h>
#include <time.h>
#include <stdio.h>
#include <string.h>

// Texture rows follow OpenGL's order everywhere in the backend (row 0 is the scene's bottom row; see mcmetal.m's
// present_vs), so texture row r holds NDC y = (r + 0.5) / h * 2 - 1 and uv, NDC and motion line up without flips until
// the drawable, where the composite pass flips like mc_present.
static NSString *fgSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	// m: this frame's clip space -> last frame's clip space. prev: camera-relative world (this frame's camera) -> last frame's
	// clip space. cur, curInv: camera-relative world <-> this frame's clip space. size: output (= level) size in pixels.
	// hand: device depth at and above which a pixel belongs to a camera-locked hand (the pack runtime draws the hand at
	// 0.4375..0.5625, like Iris; world geometry only gets there closer than ~0.11 blocks).
	"struct Reproject { float4x4 m; float4x4 prev; float4x4 cur; float4x4 curInv; float2 size; float hand; float pad; };\n"
	"static inline float2 motionOf(float4 prev, float2 uv) { return prev.w > 0.0 ? prev.xy / prev.w * 0.5 + 0.5 - uv : float2(0.0); }\n"
	// Depth-based (shaders off, packs): the level's reversed-Z depth, reprojected.
	"kernel void fg_motion_depth(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> depth [[texture(1)]],\n"
	"                            texture2d<half, access::write> motion [[texture(2)]], constant Reproject &r [[buffer(0)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= uint(r.size.x) || id.y >= uint(r.size.y)) return;\n"
	"  float d = src.read(id).r;\n"
	"  float2 uv = (float2(id) + 0.5) / r.size;\n"
	"  float2 mv = d >= r.hand ? float2(0.0) : motionOf(r.m * float4(uv * 2.0 - 1.0, d, 1.0), uv);\n"
	"  depth.write(float4(d), id);\n"
	"  motion.write(half4(half2(mv), 0.0h, 0.0h), id);\n"
	"}\n"
	// mcopt-shade M3.3 (our native shading's TAAU, -Dmcopt.shade=true): the level's depth is the TAAU world target's, whose
	// live sub-rect (0, 0, src) holds this frame's render (smaller than the output when the render scale is below 1); each
	// output pixel reads the depth texel under its uv there. The matrices are the unjittered ones.
	"struct ReprojectSub { float4x4 m; float4x4 prev; float4x4 cur; float4x4 curInv; float2 size; float hand; float pad; float2 src; float2 pad2; };\n"
	"kernel void fg_motion_depth_sub(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> depth [[texture(1)]],\n"
	"                                texture2d<half, access::write> motion [[texture(2)]], constant ReprojectSub &r [[buffer(0)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= uint(r.size.x) || id.y >= uint(r.size.y)) return;\n"
	"  float2 uv = (float2(id) + 0.5) / r.size;\n"
	"  uint2 sid = min(uint2(uv * r.src), uint2(r.src) - 1u);\n"
	"  float d = src.read(sid).r;\n"
	"  float2 mv = d >= r.hand ? float2(0.0) : motionOf(r.m * float4(uv * 2.0 - 1.0, d, 1.0), uv);\n"
	"  depth.write(float4(d), id);\n"
	"  motion.write(half4(half2(mv), 0.0h, 0.0h), id);\n"
	"}\n"
	// Distance-based (native shading): its lit scene copy holds each pixel's distance from the eye in alpha (65000 = sky).
	"kernel void fg_motion_distance(texture2d<float, access::read> scene [[texture(0)]], texture2d<float, access::write> depth [[texture(1)]],\n"
	"                               texture2d<half, access::write> motion [[texture(2)]], constant Reproject &r [[buffer(0)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= uint(r.size.x) || id.y >= uint(r.size.y)) return;\n"
	"  float2 uv = (float2(id) + 0.5) / r.size;\n"
	"  uint2 sid = min(uint2(uv * float2(scene.get_width(), scene.get_height())), uint2(scene.get_width() - 1, scene.get_height() - 1));\n"
	"  float dist = scene.read(sid).a;\n"
	"  float4 near = r.curInv * float4(uv * 2.0 - 1.0, 1.0, 1.0);\n"
	"  float3 dir = normalize(near.xyz / near.w);\n"
	"  float4 p = dist >= 60000.0 ? float4(dir, 0.0) : float4(dir * dist, 1.0);\n"
	"  float4 c = r.cur * p;\n"
	"  depth.write(float4(p.w == 0.0 || c.w <= 0.0 ? 0.0 : saturate(c.z / c.w)), id);\n"
	"  motion.write(half4(half2(motionOf(r.prev * p, uv)), 0.0h, 0.0h), id);\n"
	"}\n"
	// The level image, and (shaders off) the camera-locked hand: after the game's hand pass the main depth holds only the
	// hand's depth, where the level's motion and depth are replaced (it moves with the camera).
	"kernel void fg_capture(texture2d<float, access::read> color [[texture(0)]], texture2d<float, access::write> level [[texture(1)]],\n"
	"                       texture2d<float, access::read> hand [[texture(2)]], texture2d<float, access::write> depth [[texture(3)]],\n"
	"                       texture2d<half, access::write> motion [[texture(4)]], constant uint3 &a [[buffer(0)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= a.x || id.y >= a.y) return;\n"
	"  level.write(color.read(id), id);\n"
	"  if (a.z != 0) {\n"
	"    float h = hand.read(id).r;\n"
	"    if (h > 0.0) { depth.write(float4(h), id); motion.write(half4(0.0h), id); }\n"
	"  }\n"
	"}\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V fg_vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = p; return v;\n"
	"}\n"
	// The generated level image with the GUI on top. The GUI was drawn twice more, over black and over white: per channel
	// it maps a background b to b * (white - black) + black, whatever its blend modes (over, multiply, invert, add).
	"fragment float4 fg_composite(V v [[stage_in]], texture2d<float> g [[texture(0)]], texture2d<float> black [[texture(1)]],\n"
	"                             texture2d<float> white [[texture(2)]], sampler s [[sampler(0)]]) {\n"
	"  float3 b = black.sample(s, v.uv).rgb;\n"
	"  return float4(g.sample(s, v.uv).rgb * (white.sample(s, v.uv).rgb - b) + b, 1.0);\n"
	"}\n"
	// Ring image -> drawable: both top-down, no flip.
	"vertex V fg_copy_vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = float2(p.x, 1.0 - p.y); return v;\n"
	"}\n"
	"fragment float4 fg_plain(V v [[stage_in]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]]) { return t.sample(s, v.uv); }\n"
	// On a 64x36 grid of samples, how many of the generated frame's differ from the newer level image, from the older one,
	// and how many of the two level images differ from each other (RGB; alpha isn't shown). The newer frame given back
	// (as MetalFX does for a few frames after a history reset, or when it declines to interpolate): none differ from it.
	"kernel void fg_same(texture2d<float, access::read> g [[texture(0)]], texture2d<float, access::read> newer [[texture(1)]],\n"
	"                    texture2d<float, access::read> older [[texture(2)]], device atomic_uint *out [[buffer(0)]],\n"
	"                    constant uint &slot [[buffer(1)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= 64 || id.y >= 36) return;\n"
	"  uint2 p = uint2((float2(id) + 0.5) / float2(64.0, 36.0) * float2(g.get_width(), g.get_height()));\n"
	"  float3 a = g.read(p).rgb, n = newer.read(p).rgb, o = older.read(p).rgb;\n"
	"  if (any(abs(a - n) > 0.003)) atomic_fetch_add_explicit(&out[slot * 3], 1u, memory_order_relaxed);\n"
	"  if (any(abs(a - o) > 0.003)) atomic_fetch_add_explicit(&out[slot * 3 + 1], 1u, memory_order_relaxed);\n"
	"  if (any(abs(n - o) > 0.003)) atomic_fetch_add_explicit(&out[slot * 3 + 2], 1u, memory_order_relaxed);\n"
	"}\n"
	// Debug views (-Dmcopt.metal.framegen.view): motion magnitude in pixels, or the generated frame alone.
	"fragment float4 fg_motion_view(V v [[stage_in]], texture2d<float> m [[texture(0)]], sampler s [[sampler(0)]]) {\n"
	"  float2 mv = m.sample(s, v.uv).rg * float2(m.get_width(), m.get_height());\n"
	"  return float4(saturate(abs(mv.x) / 32.0), saturate(abs(mv.y) / 32.0), mv.x == 0.0 && mv.y == 0.0 ? 1.0 : 0.0, 1.0);\n"
	"}\n";

typedef struct {
	id interpolator;  // id<MTLFXFrameInterpolator>
	id<MTLComputePipelineState> motionDepth, motionDistance, capture, same;
	id<MTLComputePipelineState> motionDepthSub;  // mcopt-shade M3.3 (fg_motion_depth_sub; nil if it failed: mcf_motion_sub then does nothing)
	id<MTLRenderPipelineState> composite, plain, motionView, copy;
	id<MTLCommandQueue> presentQueue;  // the presents' own queue: they never wait behind the next frame's work
	id<MTLSamplerState> sampler;
	id<MTLBuffer> sameCounts;  // fg_same's counts, a slot per frame in flight
	long sameSlot;
	id<MTLTexture> color[2];  // the last two level images (current = this frame's once captured)
	id<MTLTexture> depth, motion, out;
	int width, height, current;
	// -Dmcopt.metal.framegen.matrices: the camera's matrices for MetalFX (macOS 27 worldToViewMatrix / viewToClipMatrix),
	// set by mcf_camera_matrices; hasMatrices 0 until then
	simd_float4x4 worldToView, viewToClip;
	int hasMatrices;
} Fg;

// ---- display timing: a display link on the main display (host time = CACurrentMediaTime's clock) ----

static _Atomic double vsyncAnchor, vsyncPeriod;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"  // CVDisplayLink: its replacement needs a run loop to deliver on
static CVReturn onVsync(CVDisplayLinkRef link, const CVTimeStamp *now, const CVTimeStamp *next, CVOptionFlags flags, CVOptionFlags *outFlags, void *user) {
	static double hostSeconds;
	if (hostSeconds == 0) {
		mach_timebase_info_data_t tb;
		mach_timebase_info(&tb);
		hostSeconds = (double) tb.numer / tb.denom / 1e9;
	}
	vsyncAnchor = next->hostTime * hostSeconds;
	vsyncPeriod = next->videoTimeScale ? (double) next->videoRefreshPeriod / next->videoTimeScale : 0;
	return kCVReturnSuccess;
}

static void startVsync(void) {
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		CVDisplayLinkRef link;
		if (CVDisplayLinkCreateWithCGDisplay(CGMainDisplayID(), &link) != kCVReturnSuccess) return;
		CVDisplayLinkSetOutputCallback(link, onVsync, NULL);
		CVDisplayLinkStart(link);  // runs for the life of the process
	});
}
#pragma clang diagnostic pop

double mcf_now(void) { return CACurrentMediaTime(); }

// The refresh period (0 until the display link has ticked) and a recent refresh's host time.
double mcf_period(void) {
	startVsync();
	return vsyncPeriod;
}

double mcf_anchor(void) { return vsyncAnchor; }

// ---- presented-frame log: what the display showed and when, for FrameGen's once-a-second report ----

#define LOG_SIZE 1024
typedef struct { double target, presented, handler, start, encoded; int kind; } Present;  // kind: 0 real, 1 generated
static Present presentLog[LOG_SIZE];
static long presentHead;  // entries written; readers keep their own position
static os_unfair_lock logLock = OS_UNFAIR_LOCK_INIT;

// Copies entries from *from on (at most max, oldest first) as 6 doubles each: kind, target, presentedTime (0 if the system
// gave none), handler time, frame start, encode time. Returns the count; *from advances.
int mcf_log_read(long *from, double *out, int max) {
	os_unfair_lock_lock(&logLock);
	if (*from < presentHead - LOG_SIZE) *from = presentHead - LOG_SIZE;
	int n = 0;
	for (; *from < presentHead && n < max; (*from)++, n++) {
		Present p = presentLog[*from % LOG_SIZE];
		double *o = out + n * 6;
		o[0] = p.kind; o[1] = p.target; o[2] = p.presented; o[3] = p.handler; o[4] = p.start; o[5] = p.encoded;
	}
	os_unfair_lock_unlock(&logLock);
	return n;
}

// ---- frame timing: a frame's start to its GPU work done, and its GPU time ----

static _Atomic double lastWork, lastGpu;
static _Atomic long completedFrames;

void mcf_frame_times(double *out) {
	out[0] = lastWork;
	out[1] = lastGpu;
	out[2] = completedFrames;
}

// Generated frames checked so far, how many of them came between two real frames that differ, and how many of those were
// the newer real frame given back (fg_same).
static _Atomic long checkedFrames, movingFrames, copiedFrames;

void mcf_copies(long *out) {
	out[0] = checkedFrames;
	out[1] = movingFrames;
	out[2] = copiedFrames;
}

// ---- setup ----

static id<MTLTexture> newTexture(id<MTLDevice> d, MTLPixelFormat f, int w, int h, MTLTextureUsage usage) {
	MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	t.storageMode = MTLStorageModePrivate;
	t.usage = usage;
	return [d newTextureWithDescriptor:t];
}

static id<MTLRenderPipelineState> renderPipeline(id<MTLDevice> d, id<MTLLibrary> lib, NSString *vertex, NSString *fragment, NSError **e) {
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.vertexFunction = [[lib newFunctionWithName:vertex] autorelease];
	pd.fragmentFunction = [[lib newFunctionWithName:fragment] autorelease];
	pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
	return [d newRenderPipelineStateWithDescriptor:pd error:e];
}

static id<MTLComputePipelineState> computePipeline(id<MTLDevice> d, id<MTLLibrary> lib, NSString *name, NSError **e) {
	return [d newComputePipelineStateWithFunction:[[lib newFunctionWithName:name] autorelease] error:e];
}

void mcf_free(Fg *fg);
static void freeTextures(Fg *fg);

// The presentation pipelines (paced mode needs only these); mcf_resize adds the interpolator.
Fg *mcf_new(Ctx *ctx, char *err, int errCap) {
	@autoreleasepool {
		id<MTLDevice> d = ctx->device;
		startVsync();
		NSError *e = nil;
		id<MTLLibrary> lib = [d newLibraryWithSource:fgSource options:nil error:&e];
		if (!lib) {
			strlcpy(err, e.description.UTF8String, errCap);
			return NULL;
		}
		Fg *fg = calloc(1, sizeof(Fg));
		fg->motionDepth = computePipeline(d, lib, @"fg_motion_depth", &e);
		fg->motionDistance = computePipeline(d, lib, @"fg_motion_distance", &e);
		{
			NSError *e2 = nil; // optional (mcopt-shade): its failure must not change upstream's checks below
			fg->motionDepthSub = computePipeline(d, lib, @"fg_motion_depth_sub", &e2);
		}
		fg->capture = computePipeline(d, lib, @"fg_capture", &e);
		fg->same = computePipeline(d, lib, @"fg_same", &e);
		fg->sameCounts = [d newBufferWithLength:16 * 3 * sizeof(uint32_t) options:MTLResourceStorageModeShared];
		fg->composite = renderPipeline(d, lib, @"fg_vs", @"fg_composite", &e);
		fg->plain = renderPipeline(d, lib, @"fg_vs", @"fg_plain", &e);
		fg->motionView = renderPipeline(d, lib, @"fg_vs", @"fg_motion_view", &e);
		fg->copy = renderPipeline(d, lib, @"fg_copy_vs", @"fg_plain", &e);
		fg->presentQueue = [d newCommandQueue];
		[lib release];
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterNearest;
		fg->sampler = [d newSamplerStateWithDescriptor:sd];
		if (!fg->motionDepth || !fg->motionDistance || !fg->capture || !fg->same || !fg->composite || !fg->plain || !fg->motionView || !fg->copy) {
			strlcpy(err, e ? e.description.UTF8String : "pipeline creation failed", errCap);
			mcf_free(fg);
			return NULL;
		}
		return fg;
	}
}

// (Re)creates the interpolator and its textures for level images of width x height in colorFormat (the main target's).
// Returns 0 with err set if MetalFX can't.
int mcf_resize(Ctx *ctx, Fg *fg, int width, int height, int colorFormat, char *err, int errCap) {
	@autoreleasepool {
		freeTextures(fg);
		id<MTLDevice> d = ctx->device;
		if (@available(macOS 26.0, *)) {
			if (![MTLFXFrameInterpolatorDescriptor supportsDevice:d]) {
				strlcpy(err, "MetalFX frame interpolation isn't supported on this GPU", errCap);
				return 0;
			}
			MTLFXFrameInterpolatorDescriptor *fd = [[MTLFXFrameInterpolatorDescriptor new] autorelease];
			fd.colorTextureFormat = fd.outputTextureFormat = (MTLPixelFormat) colorFormat;
			fd.depthTextureFormat = MTLPixelFormatR32Float;
			fd.motionTextureFormat = MTLPixelFormatRG16Float;
			fd.inputWidth = fd.outputWidth = width;
			fd.inputHeight = fd.outputHeight = height;
			id<MTLFXFrameInterpolator> fi = [fd newFrameInterpolatorWithDevice:d];
			if (!fi) {
				snprintf(err, errCap, "MetalFX refused a %dx%d frame interpolator for color format %d", width, height, colorFormat);
				return 0;
			}
			fg->interpolator = fi;
			fg->width = width;
			fg->height = height;
			MTLTextureUsage rw = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
			for (int i = 0; i < 2; i++) fg->color[i] = newTexture(d, (MTLPixelFormat) colorFormat, width, height, rw | fi.colorTextureUsage);
			fg->depth = newTexture(d, MTLPixelFormatR32Float, width, height, rw | fi.depthTextureUsage);
			fg->motion = newTexture(d, MTLPixelFormatRG16Float, width, height, rw | fi.motionTextureUsage);
			fg->out = newTexture(d, (MTLPixelFormat) colorFormat, width, height, MTLTextureUsageShaderRead | fi.outputTextureUsage);
			fg->color[0].label = @"framegen level 0";
			fg->color[1].label = @"framegen level 1";
			fg->depth.label = @"framegen depth";
			fg->motion.label = @"framegen motion";
			fg->out.label = @"framegen output";
			return 1;
		}
		strlcpy(err, "MetalFX frame interpolation needs macOS 26", errCap);
		return 0;
	}
}

// For readbacks (-Dmcopt.metal.framegen.dump): 0 this frame's level image, 1 the previous one, 2 the generated frame,
// 3 the depth (R32Float), 4 the motion (RG16Float) MetalFX was given.
id<MTLTexture> mcf_texture(Fg *fg, int which) {
	switch (which) {
	case 0: return fg->color[fg->current];
	case 1: return fg->color[fg->current ^ 1];
	case 3: return fg->depth;
	case 4: return fg->motion;
	default: return fg->out;
	}
}

static void freeTextures(Fg *fg) {
	[fg->interpolator release];
	for (int i = 0; i < 2; i++) [fg->color[i] release];
	[fg->depth release];
	[fg->motion release];
	[fg->out release];
	fg->interpolator = nil;
	fg->color[0] = fg->color[1] = fg->depth = fg->motion = fg->out = nil;
	fg->width = fg->height = 0;
}

void mcf_free(Fg *fg) {
	freeTextures(fg);
	[fg->motionDepth release];
	[fg->motionDistance release];
	[fg->motionDepthSub release];
	[fg->capture release];
	[fg->same release];
	[fg->sameCounts release];
	[fg->composite release];
	[fg->plain release];
	[fg->motionView release];
	[fg->copy release];
	[fg->presentQueue release];
	[fg->sampler release];
	free(fg);
}

// ---- per frame ----

typedef struct { simd_float4x4 m, prev, cur, curInv; simd_float2 size; float hand, pad; } Reproject;

// matrices: 4 column-major 4x4 floats (m, prev, cur, curInv; see fgSource). source: the level's depth (distance 0) or native
// shading's lit scene copy (distance 1). Writes this frame's depth and motion.
void mcf_motion(Enc *enc, Fg *fg, id<MTLTexture> source, int distance, const float *matrices, float hand) {
	Reproject r;
	memcpy(&r, matrices, sizeof(simd_float4x4) * 4);
	r.size = simd_make_float2(fg->width, fg->height);
	r.hand = hand;
	r.pad = 0;
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setLabel:@"framegen motion"];
	[c setComputePipelineState:distance ? fg->motionDistance : fg->motionDepth];
	[c setTexture:source atIndex:0];
	[c setTexture:fg->depth atIndex:1];
	[c setTexture:fg->motion atIndex:2];
	[c setBytes:&r length:sizeof r atIndex:0];
	[c dispatchThreads:MTLSizeMake(fg->width, fg->height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c endEncoding];
}

// mcopt-shade M3.3: mcf_motion's depth path for a level depth whose live sub-rect is srcW x srcH texels (native shading's
// TAAU world target). Returns 0 when its pipeline is missing (nothing encoded).
typedef struct { simd_float4x4 m, prev, cur, curInv; simd_float2 size; float hand, pad; simd_float2 src, pad2; } ReprojectSub;

int mcf_motion_sub(Enc *enc, Fg *fg, id<MTLTexture> source, const float *matrices, float hand, int srcW, int srcH) {
	if (!fg->motionDepthSub) return 0;
	ReprojectSub r;
	memcpy(&r, matrices, sizeof(simd_float4x4) * 4);
	r.size = simd_make_float2(fg->width, fg->height);
	r.hand = hand;
	r.pad = 0;
	r.src = simd_make_float2(srcW, srcH);
	r.pad2 = simd_make_float2(0, 0);
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setLabel:@"framegen motion (shade TAAU)"];
	[c setComputePipelineState:fg->motionDepthSub];
	[c setTexture:source atIndex:0];
	[c setTexture:fg->depth atIndex:1];
	[c setTexture:fg->motion atIndex:2];
	[c setBytes:&r length:sizeof r atIndex:0];
	[c dispatchThreads:MTLSizeMake(fg->width, fg->height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c endEncoding];
	return 1;
}

// This frame's level image (the main color before the GUI draws), becoming the newer of the two; hand (or nil): the
// main depth holding only the camera-locked hand.
void mcf_capture(Enc *enc, Fg *fg, id<MTLTexture> color, id<MTLTexture> hand) {
	fg->current ^= 1;
	uint32_t a[4] = {(uint32_t) fg->width, (uint32_t) fg->height, hand != nil, 0};
	id<MTLComputeCommandEncoder> c = [mc_frame_cmd(enc) computeCommandEncoder];
	[c setLabel:@"framegen capture"];
	[c setComputePipelineState:fg->capture];
	[c setTexture:color atIndex:0];
	[c setTexture:fg->color[fg->current] atIndex:1];
	[c setTexture:hand ? hand : color atIndex:2];
	[c setTexture:fg->depth atIndex:3];
	[c setTexture:fg->motion atIndex:4];
	[c setBytes:a length:sizeof a atIndex:0];
	[c dispatchThreads:MTLSizeMake(fg->width, fg->height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c endEncoding];
}

// The camera's world-to-view and view-to-clip matrices (column-major float[16] each) for the next mcf_interpolate. Returns
// 1 if this MetalFX takes them (macOS 27), else 0 and they are ignored.
int mcf_camera_matrices(Fg *fg, const float *view, const float *clip) {
	if (!fg->interpolator || ![fg->interpolator respondsToSelector:@selector(setWorldToViewMatrix:)]
		|| ![fg->interpolator respondsToSelector:@selector(setViewToClipMatrix:)]) return 0;
	memcpy(&fg->worldToView, view, sizeof fg->worldToView);
	memcpy(&fg->viewToClip, clip, sizeof fg->viewToClip);
	fg->hasMatrices = 1;
	return 1;
}

// The frame halfway between the previous level image and this one, into fg->out. camera: near, far, vertical field of
// view in degrees, aspect ratio; dt: seconds between the two real frames. Returns the slot of its check (mcf_check_counts).
int mcf_interpolate(Enc *enc, Fg *fg, float dt, float near, float far, float fov, float aspect, int reset) {
	if (@available(macOS 26.0, *)) {
		id<MTLFXFrameInterpolator> fi = fg->interpolator;
		fi.colorTexture = fg->color[fg->current];
		fi.prevColorTexture = fg->color[fg->current ^ 1];
		fi.depthTexture = fg->depth;
		fi.motionTexture = fg->motion;
		fi.outputTexture = fg->out;
		fi.motionVectorScaleX = fg->width;
		fi.motionVectorScaleY = fg->height;
		fi.deltaTime = dt;
		fi.nearPlane = near;
		fi.farPlane = far;
		fi.fieldOfView = fov;
		fi.aspectRatio = aspect;
		fi.jitterOffsetX = fi.jitterOffsetY = 0;
		fi.depthReversed = YES;
		fi.shouldResetHistory = reset != 0;
		if (fg->hasMatrices) {
			// macOS 27's setters, sent by selector (respondsToSelector checked in mcf_camera_matrices) so this builds on the
			// macOS 26 SDK too
			((void (*)(id, SEL, simd_float4x4)) objc_msgSend)(fi, @selector(setWorldToViewMatrix:), fg->worldToView);
			((void (*)(id, SEL, simd_float4x4)) objc_msgSend)(fi, @selector(setViewToClipMatrix:), fg->viewToClip);
		}
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		[fi encodeToCommandBuffer:cb];
		// Did MetalFX interpolate, or give back the newer frame?
		uint32_t slot = (uint32_t) (fg->sameSlot++ % 16);
		uint32_t *counts = (uint32_t *) fg->sameCounts.contents + slot * 3;
		counts[0] = counts[1] = counts[2] = 0;
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		[c setLabel:@"framegen check"];
		[c setComputePipelineState:fg->same];
		[c setTexture:fg->out atIndex:0];
		[c setTexture:fg->color[fg->current] atIndex:1];
		[c setTexture:fg->color[fg->current ^ 1] atIndex:2];
		[c setBuffer:fg->sameCounts offset:0 atIndex:0];
		[c setBytes:&slot length:sizeof slot atIndex:1];
		[c dispatchThreads:MTLSizeMake(64, 36, 1) threadsPerThreadgroup:MTLSizeMake(8, 4, 1)];
		[c endEncoding];
		[cb addCompletedHandler:^(id<MTLCommandBuffer> b) {
			checkedFrames++;
			if (counts[2] > 0) {
				movingFrames++;
				if (counts[0] == 0) copiedFrames++;
			}
		}];
		return (int) slot;
	}
	return -1;
}

// Check slot's counts (of 2304 samples; see fg_same) into out: generated vs newer, generated vs older, newer vs older.
// Valid once its frame completed.
void mcf_check_counts(Fg *fg, int slot, int *out) {
	for (int i = 0; i < 3; i++) out[i] = slot < 0 ? -1 : (int) ((uint32_t *) fg->sameCounts.contents)[slot * 3 + i];
}

// ---- presentation ----
// The frame's command buffer draws what it will show into ring images (drawable-ready: BGRA, top-down, the GUI on). When
// it completes, each image gets its refresh: the first on the first refresh it can make (but not before the last one's
// hold is up), the next `hold` refreshes later; then a serial queue acquires drawables, copies the images in on the
// presents' own command queue and presents each at its refresh. Acquiring drawables can block for a refresh; that never
// holds up the GPU or the game.

#define RING 6
enum { FREE, WRITING, READY, SHOWING };
typedef struct { id<MTLTexture> texture; int state; } Image;
static Image ring[RING];
static os_unfair_lock ringLock = OS_UNFAIR_LOCK_INIT;
static dispatch_queue_t presenter;
static CAMetalLayer *presentLayer;  // nil when stopped
static double lastShown;  // host time of the refresh the last image was scheduled for
static int lastHold;
// Frames whose presents were scheduled, and the first refresh of the latest (for the game's throttle).
static long scheduledFrame;
static double scheduledFirst;
static pthread_mutex_t scheduledMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t scheduledCond = PTHREAD_COND_INITIALIZER;
static long frameCounter;

void mcf_layer(CAMetalLayer *layer) {
	os_unfair_lock_lock(&ringLock);
	presentLayer = layer;
	os_unfair_lock_unlock(&ringLock);
}

// Draws into a free ring image in the frame's command buffer. what: 0 the real frame (src), 1 the generated frame with
// the GUI layers black/white composited, 2 the generated frame alone, 3 the motion vectors. Returns the image, or -1 if
// the ring is full (the display is behind).
int mcf_image(Ctx *ctx, Enc *enc, Fg *fg, int what, id<MTLTexture> src, id<MTLTexture> black, id<MTLTexture> white) {
	id<MTLTexture> size = what == 0 ? src : fg->out;
	int slot = -1;
	os_unfair_lock_lock(&ringLock);
	for (int i = 0; i < RING && slot < 0; i++) {
		if (ring[i].state == FREE) slot = i;
	}
	if (slot >= 0) ring[slot].state = WRITING;
	os_unfair_lock_unlock(&ringLock);
	if (slot < 0) return -1;
	@autoreleasepool {
		Image *img = &ring[slot];
		if (!img->texture || img->texture.width != size.width || img->texture.height != size.height) {
			[img->texture release];
			MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:size.width height:size.height mipmapped:NO];
			t.storageMode = MTLStorageModePrivate;
			t.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
			img->texture = [ctx->device newTextureWithDescriptor:t];
			img->texture.label = [NSString stringWithFormat:@"framegen image %d", slot];
		}
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = img->texture;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [mc_frame_cmd(enc) renderCommandEncoderWithDescriptor:rp];
		[r setLabel:what == 0 ? @"framegen real image" : @"framegen generated image"];
		[r setRenderPipelineState:what == 1 ? fg->composite : what == 3 ? fg->motionView : fg->plain];
		[r setFragmentTexture:what == 0 ? src : what == 3 ? fg->motion : fg->out atIndex:0];
		if (what == 1) {
			[r setFragmentTexture:black atIndex:1];
			[r setFragmentTexture:white atIndex:2];
		}
		[r setFragmentSamplerState:fg->sampler atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	return slot;
}

static double gridAtOrAfter(double t) {
	double p = vsyncPeriod, a = vsyncAnchor;
	return p > 0 ? a + ceil((t - a) / p - 1e-3) * p : t;
}

static double gridNearest(double t) {
	double p = vsyncPeriod, a = vsyncAnchor;
	return p > 0 ? a + round((t - a) / p) * p : t;
}

// On the presenter queue: shows ring image slot on refresh `at` (0: as soon as it can).
static void showImage(Fg *fg, int slot, int kind, double at, double start, double done) {
	@autoreleasepool {
		os_unfair_lock_lock(&ringLock);
		CAMetalLayer *layer = [presentLayer retain];
		ring[slot].state = SHOWING;
		os_unfair_lock_unlock(&ringLock);
		id<CAMetalDrawable> drawable = layer ? [layer nextDrawable] : nil;
		[layer release];
		if (!drawable) {
			os_unfair_lock_lock(&ringLock);
			ring[slot].state = FREE;
			os_unfair_lock_unlock(&ringLock);
			return;
		}
		id<MTLCommandBuffer> cb = [fg->presentQueue commandBuffer];
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = drawable.texture;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		[r setLabel:kind ? @"framegen show generated" : @"framegen show real"];
		[r setRenderPipelineState:fg->copy];
		[r setFragmentTexture:ring[slot].texture atIndex:0];
		[r setFragmentSamplerState:fg->sampler atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
		[drawable addPresentedHandler:^(id<MTLDrawable> d) {
			Present p = {at, d.presentedTime, CACurrentMediaTime(), start, done, kind};
			os_unfair_lock_lock(&logLock);
			presentLog[presentHead++ % LOG_SIZE] = p;
			os_unfair_lock_unlock(&logLock);
		}];
		[cb addCompletedHandler:^(id<MTLCommandBuffer> b) {
			os_unfair_lock_lock(&ringLock);
			ring[slot].state = FREE;
			os_unfair_lock_unlock(&ringLock);
		}];
		if (at <= 0) {
			[cb presentDrawable:drawable];
			[cb commit];
			return;
		}
		[cb commit];
		// The copy runs now, but a present asked for more than about a refresh ahead goes up a refresh early (measured
		// with two-refresh holds: held 1, 3, 1, 3 instead of 2, 2), so it's asked for at most 0.85 refreshes ahead. Asked
		// for half a refresh early: shown on the first refresh at or after that, which is the one meant.
		double wait = at - 0.85 * vsyncPeriod - CACurrentMediaTime();
		if (wait > 0.0005 && wait < 0.2) {
			static mach_timebase_info_data_t tb;
			if (!tb.denom) mach_timebase_info(&tb);
			mach_wait_until(mach_absolute_time() + (uint64_t) (wait * 1e9 * tb.denom / tb.numer));
		}
		[cb waitUntilScheduled];
		[drawable presentAtTime:at - vsyncPeriod * 0.5];
	}
}

// Once the frame's command buffer completes, its images a (and b, or -1) are shown, a first, each held `hold` refreshes.
// kinds: 0 real, 1 generated. start: when the frame began. Returns the frame's number for mcf_wait_scheduled.
// The refreshes are picked (and the game told) as soon as the frame is done; acquiring the drawables, which can block
// until the display frees one, happens after on the presenter queue, so it never delays the game's next frame.
long mcf_submit(Enc *enc, Fg *fg, int a, int kindA, int holdA, int b, int kindB, int holdB, double start) {
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		presenter = dispatch_queue_create("mcopt.framegen.present", dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
	});
	long frame = ++frameCounter;
	[mc_frame_cmd(enc) addCompletedHandler:^(id<MTLCommandBuffer> cb) {
		double done = CACurrentMediaTime();
		lastWork = done - start;
		lastGpu = cb.GPUEndTime - cb.GPUStartTime;
		completedFrames++;
		os_unfair_lock_lock(&ringLock);
		if (a >= 0) ring[a].state = READY;
		if (b >= 0) ring[b].state = READY;
		os_unfair_lock_unlock(&ringLock);
		double p = vsyncPeriod, first = 0, second = 0;
		pthread_mutex_lock(&scheduledMutex);
		if (a >= 0) {
			// The first refresh it can make (the copy and the compositor's latch take a little), unless the last image's
			// hold isn't up by then; a long gap (a stall, a menu) starts afresh.
			first = p > 0 ? gridAtOrAfter(done + 0.002) : 0;
			double free = lastShown + lastHold * p;
			if (p > 0 && lastShown > 0 && free > first && free < first + 4 * p) first = gridNearest(free);
			lastShown = first;
			lastHold = holdA;
			if (b >= 0) {
				second = p > 0 ? first + holdA * p : 0;
				lastShown = second;
				lastHold = holdB;
			}
		}
		scheduledFrame = frame;
		scheduledFirst = first;
		pthread_cond_broadcast(&scheduledCond);
		pthread_mutex_unlock(&scheduledMutex);
		if (a >= 0) {
			dispatch_async(presenter, ^{
				showImage(fg, a, kindA, first, start, done);
				if (b >= 0) showImage(fg, b, kindB, second, start, done);
			});
		}
	}];
	return frame;
}

// Waits (at most timeout seconds) until frame's presents are scheduled; its first refresh into *first. 1 if they were.
int mcf_wait_scheduled(long frame, double timeout, double *first) {
	struct timespec deadline;
	clock_gettime(CLOCK_REALTIME, &deadline);
	long ns = deadline.tv_nsec + (long) (timeout * 1e9);
	deadline.tv_sec += ns / 1000000000L;
	deadline.tv_nsec = ns % 1000000000L;
	pthread_mutex_lock(&scheduledMutex);
	while (scheduledFrame < frame) {
		if (pthread_cond_timedwait(&scheduledCond, &scheduledMutex, &deadline) != 0) break;
	}
	int ok = scheduledFrame >= frame;
	*first = scheduledFirst;
	pthread_mutex_unlock(&scheduledMutex);
	return ok;
}
