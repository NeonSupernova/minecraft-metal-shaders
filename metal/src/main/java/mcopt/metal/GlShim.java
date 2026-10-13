package mcopt.metal;

import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;

/**
 * A hidden OpenGL context, current on the render thread while Metal draws. Mods written for the OpenGL backend (Iris,
 * ImmediatelyFast and others) ask OpenGL for its version, limits or extensions while the game starts. On the Metal
 * backend no context exists, so LWJGL's "no context is current" handler aborts the JVM from inside that call (a
 * jni_FatalError from liblwjgl, with no crash report). With this context those queries get the real Mac OpenGL 4.1
 * answers the same mods see on the OpenGL backend, and anything they draw goes to a 1x1 hidden window nobody sees.
 * <p>
 * It is created once, on the render thread, as soon as SDL's video is up: at the backend's loadLibrary when it already is,
 * else when the game window is made (both before the device exists).
 * -Dmcopt.glShim=false turns it off. Any failure is logged and leaves things as they were without it.
 */
final class GlShim {
	private static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.glShim", "true"));
	private static boolean done;

	private GlShim() {
	}

	static void install() {
		if (!ON || done) return;
		try {
			if (SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) == 0) return; // too early: the next call tries again
			done = true;
			// The OpenGL backend's own context: 4.1 core, forward-compatible (all macOS offers past 2.1).
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 1);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
			long window = SDLVideo.SDL_CreateWindow("mcopt GL shim", 1, 1, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
			if (window == 0) {
				Log.warn("[mcopt] gl-shim: no hidden window (" + SDLError.SDL_GetError() + "); mods calling OpenGL may abort the game");
				return;
			}
			long context = SDLVideo.SDL_GL_CreateContext(window);
			if (context == 0 || !SDLVideo.SDL_GL_MakeCurrent(window, context)) {
				Log.warn("[mcopt] gl-shim: no OpenGL context (" + SDLError.SDL_GetError() + "); mods calling OpenGL may abort the game");
				SDLVideo.SDL_DestroyWindow(window);
				return;
			}
			GL.createCapabilities();
			Log.info("[mcopt] gl-shim: hidden OpenGL context current on " + Thread.currentThread().getName()
				+ " for mods that query OpenGL (-Dmcopt.glShim=false turns it off)");
		} catch (Throwable t) {
			done = true;
			Log.warn("[mcopt] gl-shim: no hidden OpenGL context, so a mod that calls OpenGL at startup can abort the game (-Dmcopt.glShim=false skips this step)", t);
		}
	}
}
