package dev.lumina.render;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

/**
 * Backing storage for the mod's shader uniforms. VulkanMod copies these into the uniform buffer
 * every time a pipeline's descriptor sets are bound, so a value may be changed between draws.
 */
public final class LuminaUniforms {
    public static final MappedBuffer SHADOW_MAT = new MappedBuffer(16 * 4);
    public static final MappedBuffer SHADOW_MAT_FAR = new MappedBuffer(16 * 4);
    public static final MappedBuffer SHADOW_PARAMS = new MappedBuffer(4 * 4);
    public static final MappedBuffer LIGHT_DIR = new MappedBuffer(4 * 4);
    public static final MappedBuffer LIT_COLOR = new MappedBuffer(4 * 4);
    public static final MappedBuffer SHADOW_COLOR = new MappedBuffer(4 * 4);
    public static final MappedBuffer PARAMS = new MappedBuffer(4 * 4);
    public static final MappedBuffer SUN_SCREEN = new MappedBuffer(4 * 4);
    public static final MappedBuffer BLUR = new MappedBuffer(4 * 4);
    public static final MappedBuffer GRADE = new MappedBuffer(4 * 4);
    public static final MappedBuffer TINT = new MappedBuffer(4 * 4);
    public static final MappedBuffer RAY_COLOR = new MappedBuffer(4 * 4);
    public static final MappedBuffer POST = new MappedBuffer(4 * 4);
    public static final MappedBuffer WAVE = new MappedBuffer(4 * 4);
    public static final MappedBuffer INV_VIEW = new MappedBuffer(16 * 4);
    /** x: 0 = leave entities alone (menus, mod off), 1 = entities in the world, 2 = first-person hand. */
    public static final MappedBuffer ENTITY_PARAMS = new MappedBuffer(4 * 4);

    /** Name of the uniform block declared in assets/minecraft/shaders/include/lumina_entity.glsl. */
    private static final String ENTITY_BLOCK = "LuminaEntity";
    public static final MappedBuffer CAMERA = new MappedBuffer(4 * 4);
    public static final MappedBuffer REFLECT = new MappedBuffer(4 * 4);

    // View and sky
    public static final MappedBuffer VIEW_TO_WORLD = new MappedBuffer(16 * 4);
    public static final MappedBuffer CELESTIAL = new MappedBuffer(16 * 4);
    public static final MappedBuffer PROJ = new MappedBuffer(4 * 4);
    public static final MappedBuffer SUN_DIR = new MappedBuffer(4 * 4);
    public static final MappedBuffer MOON_DIR = new MappedBuffer(4 * 4);
    public static final MappedBuffer SKY_COLOR = new MappedBuffer(4 * 4);
    public static final MappedBuffer SUNSET_COLOR = new MappedBuffer(4 * 4);
    public static final MappedBuffer SKY_PARAMS = new MappedBuffer(4 * 4);
    public static final MappedBuffer SKY_ORIGIN = new MappedBuffer(4 * 4);

    // Screen-space occlusion
    public static final MappedBuffer LIGHT_VIEW = new MappedBuffer(4 * 4);
    public static final MappedBuffer AO_PARAMS = new MappedBuffer(4 * 4);
    public static final MappedBuffer AO_APPLY = new MappedBuffer(4 * 4);
    public static final MappedBuffer SKY = new MappedBuffer(4 * 4);

    private static boolean registered = false;

    private LuminaUniforms() {
    }

    public static void register() {
        if (registered)
            return;

        new Matrix4f().get(SHADOW_MAT.buffer.asFloatBuffer());

        new Matrix4f().get(SHADOW_MAT_FAR.buffer.asFloatBuffer());
        new Matrix4f().get(INV_VIEW.buffer.asFloatBuffer());

        Uniforms.mat4f_uniformMap.put("LuminaShadowMat", () -> SHADOW_MAT);
        Uniforms.mat4f_uniformMap.put("LuminaShadowMatFar", () -> SHADOW_MAT_FAR);
        Uniforms.vec4f_uniformMap.put("LuminaShadowParams", () -> SHADOW_PARAMS);
        Uniforms.vec4f_uniformMap.put("LuminaLightDir", () -> LIGHT_DIR);
        Uniforms.vec4f_uniformMap.put("LuminaLitColor", () -> LIT_COLOR);
        Uniforms.vec4f_uniformMap.put("LuminaShadowColor", () -> SHADOW_COLOR);
        Uniforms.vec4f_uniformMap.put("LuminaParams", () -> PARAMS);
        Uniforms.vec4f_uniformMap.put("LuminaSunScreen", () -> SUN_SCREEN);
        Uniforms.vec4f_uniformMap.put("LuminaBlur", () -> BLUR);
        Uniforms.vec4f_uniformMap.put("LuminaGrade", () -> GRADE);
        Uniforms.vec4f_uniformMap.put("LuminaTint", () -> TINT);
        Uniforms.vec4f_uniformMap.put("LuminaRayColor", () -> RAY_COLOR);
        Uniforms.vec4f_uniformMap.put("LuminaPost", () -> POST);
        Uniforms.vec4f_uniformMap.put("LuminaWave", () -> WAVE);
        Uniforms.mat4f_uniformMap.put("LuminaInvView", () -> INV_VIEW);
        Uniforms.vec4f_uniformMap.put("LuminaEntityParams", () -> ENTITY_PARAMS);
        Uniforms.vec4f_uniformMap.put("LuminaCamera", () -> CAMERA);
        Uniforms.vec4f_uniformMap.put("LuminaReflect", () -> REFLECT);

        new Matrix4f().get(VIEW_TO_WORLD.buffer.asFloatBuffer());
        new Matrix4f().get(CELESTIAL.buffer.asFloatBuffer());

        Uniforms.mat4f_uniformMap.put("LuminaViewToWorld", () -> VIEW_TO_WORLD);
        Uniforms.mat4f_uniformMap.put("LuminaCelestial", () -> CELESTIAL);
        Uniforms.vec4f_uniformMap.put("LuminaProj", () -> PROJ);
        Uniforms.vec4f_uniformMap.put("LuminaSunDir", () -> SUN_DIR);
        Uniforms.vec4f_uniformMap.put("LuminaMoonDir", () -> MOON_DIR);
        Uniforms.vec4f_uniformMap.put("LuminaSkyColor", () -> SKY_COLOR);
        Uniforms.vec4f_uniformMap.put("LuminaSunsetColor", () -> SUNSET_COLOR);
        Uniforms.vec4f_uniformMap.put("LuminaSkyParams", () -> SKY_PARAMS);
        Uniforms.vec4f_uniformMap.put("LuminaSkyOrigin", () -> SKY_ORIGIN);
        Uniforms.vec4f_uniformMap.put("LuminaLightView", () -> LIGHT_VIEW);
        Uniforms.vec4f_uniformMap.put("LuminaAoParams", () -> AO_PARAMS);
        Uniforms.vec4f_uniformMap.put("LuminaAoApply", () -> AO_APPLY);
        Uniforms.vec4f_uniformMap.put("LuminaSky", () -> SKY);

        registered = true;
    }

    /** Called for every pipeline VulkanMod builds, including the ones converted from vanilla shaders. */
    public static void attachEntityBlock(Pipeline pipeline) {
        // The game compiles its pipelines before the mod's own are created
        register();

        for (UBO ubo : pipeline.getBuffers()) {
            if (!ENTITY_BLOCK.equals(ubo.name))
                continue;

            for (Uniform uniform : ubo.getUniforms()) {
                Supplier<MappedBuffer> supplier = Uniforms.getUniformSupplier(uniform.getInfo().type, uniform.getName());

                if (supplier == null)
                    throw new IllegalStateException("Entity shader uses unknown uniform " + uniform.getName());

                uniform.setSupplier(supplier);
            }
        }
    }

    public static void set(MappedBuffer buffer, float x, float y, float z, float w) {
        MemoryUtil.memPutFloat(buffer.ptr, x);
        MemoryUtil.memPutFloat(buffer.ptr + 4, y);
        MemoryUtil.memPutFloat(buffer.ptr + 8, z);
        MemoryUtil.memPutFloat(buffer.ptr + 12, w);
    }

    public static void set(MappedBuffer buffer, Matrix4f matrix) {
        matrix.getToAddress(buffer.ptr);
    }
}
