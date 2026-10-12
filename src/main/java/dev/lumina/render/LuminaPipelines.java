package dev.lumina.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.lumina.util.LuminaLog;
import dev.lumina.util.ResourceTracker;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

public final class LuminaPipelines {
    /**
     * Texture slots of the shadow depth maps in VulkanMod's texture selector. Slots 0-7 are refreshed from the
     * game's own sampler bindings before terrain is drawn, higher ones are left alone.
     */
    public static final int SHADOW_SLOT = 10;
    public static final int SHADOW_FAR_SLOT = 11;
    /** Colour and depth of the frame as it was before translucent terrain, for water refraction and reflections. */
    public static final int SCENE_COLOR_SLOT = 8;
    public static final int SCENE_DEPTH_SLOT = 9;
    /**
     * Where the overridden entity shader finds the shadow maps: it declares them after the three vanilla samplers,
     * and VulkanMod numbers a converted shader's samplers in order of appearance.
     */
    public static final int ENTITY_SHADOW_SLOT = 3;
    public static final int ENTITY_SHADOW_FAR_SLOT = 4;

    public static GraphicsPipeline terrain;
    public static GraphicsPipeline shadow;
    public static GraphicsPipeline shadowEntity;
    public static GraphicsPipeline copyDepth;
    public static GraphicsPipeline copy;
    public static GraphicsPipeline godrays;
    public static GraphicsPipeline bloomDown;
    public static GraphicsPipeline blur;
    public static GraphicsPipeline finalPass;
    public static GraphicsPipeline sky;
    public static GraphicsPipeline occlusion;
    public static GraphicsPipeline occlusionBlur;
    public static GraphicsPipeline occlusionApply;

    private LuminaPipelines() {
    }

    public static void create() {
        LuminaUniforms.register();

        // Bindings must be contiguous: VulkanMod indexes the layout array by binding number
        PipelineConfig.UB sectionData = PipelineConfig.UB.builder(2, VK_SHADER_STAGE_VERTEX_BIT).setSize(4096).build();
        PipelineConfig.UB modelOffset = PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                                                         .addUniform("vec3", "ModelOffset")
                                                         .build();

        terrain = create("lumina_terrain", CustomVertexFormat.COMPRESSED_TERRAIN, "terrain/terrain.vsh", "terrain/terrain.fsh",
                         PipelineConfig.builder()
                                       .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_ALL_GRAPHICS)
                                                               .addUniform("mat4", "MVP")
                                                               .build())
                                       .addUB(PipelineConfig.UB.builder(1, VK_SHADER_STAGE_ALL_GRAPHICS)
                                                               .addUniform("vec4", "FogColor")
                                                               .addUniform("float", "FogEnvironmentalStart")
                                                               .addUniform("float", "FogEnvironmentalEnd")
                                                               .addUniform("float", "FogRenderDistanceStart")
                                                               .addUniform("float", "FogRenderDistanceEnd")
                                                               .addUniform("float", "AlphaCutout")
                                                               .addUniform("vec2", "TexelSize")
                                                               .addUniform("vec2", "ScreenSize")
                                                               .addUniform("int", "UseRgss")
                                                               .build())
                                       .addUB(sectionData)
                                       .addUB(PipelineConfig.UB.builder(3, VK_SHADER_STAGE_ALL_GRAPHICS)
                                                               .addUniform("mat4", "LuminaShadowMat")
                                                               .addUniform("mat4", "LuminaShadowMatFar")
                                                               .addUniform("vec4", "LuminaShadowParams")
                                                               .addUniform("vec4", "LuminaLightDir")
                                                               .addUniform("vec4", "LuminaLitColor")
                                                               .addUniform("vec4", "LuminaShadowColor")
                                                               .addUniform("vec4", "LuminaParams")
                                                               .addUniform("vec4", "LuminaCamera")
                                                               .addUniform("vec4", "LuminaSky")
                                                               .addUniform("vec4", "LuminaWave")
                                                               .addUniform("vec4", "LuminaReflect")
                                                               .build())
                                       .setPushConstants(modelOffset)
                                       .addImageDescriptor(4, "sampler2D", "Sampler0", 0)
                                       .addImageDescriptor(5, "sampler2D", "Sampler2", 2)
                                       .addImageDescriptor(6, "sampler2D", "ShadowMap", SHADOW_SLOT)
                                       .addImageDescriptor(7, "sampler2D", "ShadowMapFar", SHADOW_FAR_SLOT)
                                       .addImageDescriptor(8, "sampler2D", "SceneColor", SCENE_COLOR_SLOT)
                                       .addImageDescriptor(9, "sampler2D", "SceneDepth", SCENE_DEPTH_SLOT)
                                       .build());

        shadow = create("lumina_shadow", CustomVertexFormat.COMPRESSED_TERRAIN, "shadow/shadow.vsh", "shadow/shadow.fsh",
                        PipelineConfig.builder()
                                      .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                                                              .addUniform("mat4", "LuminaShadowMat")
                                                              .build())
                                      .addUB(PipelineConfig.UB.builder(1, VK_SHADER_STAGE_VERTEX_BIT)
                                                              .addUniform("vec4", "LuminaCamera")
                                                              .addUniform("vec4", "LuminaWave")
                                                              .build())
                                      .addUB(sectionData)
                                      .setPushConstants(modelOffset)
                                      .addImageDescriptor(3, "sampler2D", "Sampler0", 0)
                                      .build());

        copyDepth = create("lumina_copy_depth", CustomVertexFormat.NONE, "post/screen.vsh", "post/copy_depth.fsh",
                           PipelineConfig.builder()
                                         .addImageDescriptor(0, "sampler2D", "Sampler0", 0)
                                         .build());

        shadowEntity = create("lumina_shadow_entity", DefaultVertexFormat.POSITION, "shadow/shadow_entity.vsh", "shadow/shadow_entity.fsh",
                              PipelineConfig.builder()
                                            .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                                                                    .addUniform("mat4", "LuminaShadowMat")
                                                                    .build())
                                            .build());

        copy = create("lumina_copy", CustomVertexFormat.NONE, "post/screen.vsh", "post/copy.fsh",
                      PipelineConfig.builder()
                                    .addImageDescriptor(0, "sampler2D", "Sampler0", 0)
                                    .build());

        godrays = create("lumina_godrays", CustomVertexFormat.NONE, "post/screen.vsh", "post/godrays.fsh",
                         PipelineConfig.builder()
                                       .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                               .addUniform("vec4", "LuminaSunScreen")
                                                               .build())
                                       .addImageDescriptor(1, "sampler2D", "Sampler1", 1)
                                       .build());

        PipelineConfig blurConfig = PipelineConfig.builder()
                                                  .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                                          .addUniform("vec4", "LuminaBlur")
                                                                          .build())
                                                  .addImageDescriptor(1, "sampler2D", "Sampler0", 0)
                                                  .build();

        bloomDown = create("lumina_bloom_down", CustomVertexFormat.NONE, "post/screen.vsh", "post/bloom_down.fsh", blurConfig);
        blur = create("lumina_blur", CustomVertexFormat.NONE, "post/screen.vsh", "post/blur.fsh", blurConfig);

        finalPass = create("lumina_final", CustomVertexFormat.NONE, "post/screen.vsh", "post/final.fsh",
                           PipelineConfig.builder()
                                         .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                                 .addUniform("vec4", "LuminaGrade")
                                                                 .addUniform("vec4", "LuminaTint")
                                                                 .addUniform("vec4", "LuminaRayColor")
                                                                 .addUniform("vec4", "LuminaPost")
                                                                 .build())
                                         .addImageDescriptor(1, "sampler2D", "Sampler0", 0)
                                         .addImageDescriptor(2, "sampler2D", "Sampler1", 1)
                                         .addImageDescriptor(3, "sampler2D", "Sampler2", 2)
                                         .build());

        createSkyAndOcclusion();
    }

    private static void createSkyAndOcclusion() {
        sky = create("lumina_sky", CustomVertexFormat.NONE, "post/screen.vsh", "sky/sky.fsh",
                     PipelineConfig.builder()
                                   .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                           .addUniform("mat4", "LuminaViewToWorld")
                                                           .addUniform("mat4", "LuminaCelestial")
                                                           .addUniform("vec4", "LuminaProj")
                                                           .addUniform("vec4", "LuminaSunDir")
                                                           .addUniform("vec4", "LuminaMoonDir")
                                                           .addUniform("vec4", "LuminaSkyColor")
                                                           .addUniform("vec4", "LuminaSunsetColor")
                                                           .addUniform("vec4", "LuminaSkyParams")
                                                           .addUniform("vec4", "LuminaSkyOrigin")
                                                           .addUniform("vec4", "LuminaLitColor")
                                                           .addUniform("vec4", "FogColor")
                                                           .build())
                                   .build());

        occlusion = create("lumina_occlusion", CustomVertexFormat.NONE, "post/screen.vsh", "post/ao.fsh",
                           PipelineConfig.builder()
                                         .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                                 .addUniform("vec4", "LuminaProj")
                                                                 .addUniform("vec4", "LuminaLightView")
                                                                 .addUniform("vec4", "LuminaAoParams")
                                                                 .build())
                                         .addImageDescriptor(1, "sampler2D", "Sampler0", 0)
                                         .build());

        occlusionBlur = create("lumina_occlusion_blur", CustomVertexFormat.NONE, "post/screen.vsh", "post/ao_blur.fsh",
                               PipelineConfig.builder()
                                             .addImageDescriptor(0, "sampler2D", "Sampler0", 0)
                                             .addImageDescriptor(1, "sampler2D", "Sampler1", 1)
                                             .build());

        occlusionApply = create("lumina_occlusion_apply", CustomVertexFormat.NONE, "post/screen.vsh", "post/ao_apply.fsh",
                                PipelineConfig.builder()
                                              .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_FRAGMENT_BIT)
                                                                      .addUniform("vec4", "LuminaAoApply")
                                                                      .build())
                                              .addImageDescriptor(1, "sampler2D", "Sampler0", 0)
                                              .addImageDescriptor(2, "sampler2D", "Sampler1", 1)
                                              .build());
    }

    private static GraphicsPipeline create(String name, VertexFormat format, String vertexPath, String fragmentPath, PipelineConfig config) {
        long start = System.nanoTime();
        LuminaLog.debug("Compiling pipeline %s (%s, %s)", name, vertexPath, fragmentPath);

        Pipeline.Builder builder = new Pipeline.Builder(format, name);
        builder.applyConfig(config);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ShaderSource.load(vertexPath));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ShaderSource.load(fragmentPath));

        GraphicsPipeline pipeline;
        try {
            pipeline = builder.createGraphicsPipeline();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Pipeline %s failed to build".formatted(name), e);
        }

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        ResourceTracker.created("pipeline", name, "%.1f ms".formatted((System.nanoTime() - start) / 1.0e6));
        return pipeline;
    }
}
