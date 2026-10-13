package mcopt.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/** Native Metal rendering for Minecraft's renderpearl layer, next to Mojang's OpenGL and Vulkan (MoltenVK) backends. */
public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() {
		GlShim.install(); // as early as SDL's video allows (else at createWindow)
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		long window = SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
		if (window != 0) GlShim.install(); // OpenGL-backend mods query OpenGL at startup: without a context LWJGL aborts the JVM
		return window;
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		return new FrontendGpuDevice(new MetalDevice());
	}
}
