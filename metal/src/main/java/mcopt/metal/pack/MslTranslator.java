package mcopt.metal.pack;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;

/**
 * Iris-patched GLSL to Metal Shading Language, the route docs/research/iris-internals.md §5 proved on all 56 stages of
 * Complementary: the version forced to 460 core (SSBO bindings need it), shaderc to SPIR-V with relaxed Vulkan rules (packs
 * declare ~100 loose uniforms, which go into gl_DefaultUniformBlock) and automatic locations, then SPIRV-Cross to MSL 3.0
 * with argument buffers (a composite pass can sample more than Metal's 16 samplers per stage) and GL's Y convention
 * (FLIP_VERTEX_Y, as the backend's own shaders). One instance per thread: shaderc's compiler isn't shared.
 */
final class MslTranslator implements AutoCloseable {
	/** The MSL of one stage and its entry point (SPIRV-Cross renames main). */
	record Msl(String source, String entry) {
	}

	private final long compiler = shaderc_compiler_initialize();
	private final long options = shaderc_compile_options_initialize();

	MslTranslator() {
		if (this.compiler == 0 || this.options == 0) throw new IllegalStateException("shaderc unavailable");
		shaderc_compile_options_set_source_language(this.options, shaderc_source_language_glsl);
		shaderc_compile_options_set_target_env(this.options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
		shaderc_compile_options_set_vulkan_rules_relaxed(this.options, true);
		shaderc_compile_options_set_auto_map_locations(this.options, true);
		shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
		shaderc_compile_options_set_optimization_level(this.options, shaderc_optimization_level_zero);
	}

	/** stage: PackProgram.VERTEX / FRAGMENT / COMPUTE. Throws with the failing step's message. */
	Msl translate(String name, String stage, String glsl) {
		int kind = switch (stage) {
			case PackProgram.VERTEX -> shaderc_vertex_shader;
			case PackProgram.FRAGMENT -> shaderc_fragment_shader;
			case PackProgram.COMPUTE -> shaderc_compute_shader;
			default -> throw new IllegalArgumentException("Metal has no " + stage + " shaders");
		};
		ByteBuffer spirv = spirv(name, stage, PackText.forceVersion(glsl), kind);
		try {
			return msl(spirv.asIntBuffer(), stage);
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	private ByteBuffer spirv(String name, String stage, String glsl, int kind) {
		long result = shaderc_compile_into_spv(this.compiler, glsl, kind, name + "." + stage, "main", this.options);
		if (result == 0) throw new IllegalStateException("shaderc returned no result");
		try {
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException("GLSL -> SPIR-V: " + PackText.firstLines(shaderc_result_get_error_message(result), 6));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			copy.put(bytes).flip();
			return copy;
		} finally {
			shaderc_result_release(result);
		}
	}

	private static Msl msl(IntBuffer spirv, String stage) {
		int model = switch (stage) {
			case PackProgram.VERTEX -> Spv.SpvExecutionModelVertex;
			case PackProgram.FRAGMENT -> Spv.SpvExecutionModelFragment;
			default -> Spv.SpvExecutionModelGLCompute;
		};
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, true);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				if (model == Spv.SpvExecutionModelVertex) spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				check(context, spvc_compiler_compile(compiler, out), "compile");
				return new Msl(MemoryUtil.memUTF8(out.get(0)), spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	private static void check(long context, int result, String step) {
		if (result != 0) {
			throw new IllegalStateException("SPIR-V -> MSL (" + step + "): " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	@Override
	public void close() {
		shaderc_compile_options_release(this.options);
		shaderc_compiler_release(this.compiler);
	}
}
