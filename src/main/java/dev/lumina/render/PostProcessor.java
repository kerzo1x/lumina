package dev.lumina.render;

import dev.lumina.config.LuminaConfig;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Screen-space passes. The world is rendered by the game straight into the swapchain image, so the frame is
 * copied into textures at two points: before translucent terrain (for water, reflections and occlusion) and
 * at the end (for bloom, light shafts, exposure and colour grading).
 */
public final class PostProcessor {
    private static final int BLOOM_DIVISOR = 4;
    /** Light shafts are blurred twice after the march, a quarter of the screen size keeps all the detail that shows. */
    private static final int GODRAYS_DIVISOR = 4;
    private static final int OCCLUSION_DIVISOR = 2;

    /** Occlusion and contact shadows are not computed further away than this, in blocks. */
    private static final float OCCLUSION_RANGE = 110.0f;
    private static final float OCCLUSION_RADIUS = 1.0f;
    private static final float CONTACT_RAY_LENGTH = 0.45f;

    /** Seconds for the eye to get most of the way to a brighter or a darker exposure. */
    private static final float ADAPT_TO_DARK_SECONDS = 1.6f;
    private static final float ADAPT_TO_BRIGHT_SECONDS = 0.5f;
    /** A pause longer than this (menu, loading) restarts the adaptation at the metered value. */
    private static final long EXPOSURE_RESET_NANOS = 2_000_000_000L;

    /**
     * The frame before translucent terrain. Kept until the same point of the next frame: opaque terrain reads it
     * for its reflections, one frame late, because its own frame is still being drawn.
     */
    private RenderTarget scene;
    private RenderTarget sceneDepth;
    /** The finished frame, input of the final pass. */
    private RenderTarget composite;
    private RenderTarget bloomA;
    private RenderTarget bloomB;
    private RenderTarget raysA;
    private RenderTarget raysB;
    private RenderTarget occlusionA;
    private RenderTarget occlusionB;
    /** 16x9 log luminance grid and the 1x1 exposure, written and read in turns. */
    private RenderTarget luminance;
    private final RenderTarget[] exposure = new RenderTarget[2];
    private int exposureIndex;
    private long lastExposureNanos;

    private int width;
    private int height;

    private boolean godraysValid;
    private final Vector4f sunClip = new Vector4f();
    private final Matrix4f viewProjection = new Matrix4f();

    public void ensureResources(int width, int height) {
        if (this.scene == null) {
            this.scene = new RenderTarget("scene", width, height);
            this.sceneDepth = new RenderTarget("sceneDepth", width, height, VK_FORMAT_R32_SFLOAT, false);
            this.composite = new RenderTarget("composite", width, height);
            this.bloomA = new RenderTarget("bloomA", width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.bloomB = new RenderTarget("bloomB", width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.raysA = new RenderTarget("raysA", width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
            this.raysB = new RenderTarget("raysB", width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
            // Half-float: blue carries the distance from the camera along with the two factors
            this.occlusionA = new RenderTarget("occlusionA", width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR, VK_FORMAT_R16G16B16A16_SFLOAT, false);
            this.occlusionB = new RenderTarget("occlusionB", width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR, VK_FORMAT_R16G16B16A16_SFLOAT, false);
            this.luminance = new RenderTarget("luminance", 16, 9, VK_FORMAT_R32_SFLOAT, false);
            this.exposure[0] = new RenderTarget("exposureA", 1, 1, VK_FORMAT_R32_SFLOAT, false);
            this.exposure[1] = new RenderTarget("exposureB", 1, 1, VK_FORMAT_R32_SFLOAT, false);
        } else if (this.width != width || this.height != height) {
            this.scene.resize(width, height);
            this.sceneDepth.resize(width, height);
            this.composite.resize(width, height);
            this.bloomA.resize(width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.bloomB.resize(width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.raysA.resize(width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
            this.raysB.resize(width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
            this.occlusionA.resize(width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR);
            this.occlusionB.resize(width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR);
        }

        this.width = width;
        this.height = height;
    }

    public VulkanImage sceneColor() {
        return this.scene.color();
    }

    public VulkanImage sceneDepth() {
        return this.sceneDepth.color();
    }

    /**
     * Runs just before translucent terrain. Copies the depth buffer (as distance from the camera, in a full 32 bit
     * float: with 16 bits flat ground shows up as bands in everything traced against it; the depth buffer itself
     * cannot be sampled while water is depth-tested against it), darkens the
     * frame by ambient occlusion and contact shadows traced against that copy, then copies the colour.
     */
    public void captureOpaque(LightState light, LuminaConfig config, AdaptiveQuality quality) {
        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Framebuffer mainFramebuffer = renderer.getMainPass().getMainFramebuffer();
        VulkanImage mainColor = mainFramebuffer.getColorAttachment();
        VulkanImage mainDepth = mainFramebuffer.getDepthAttachment();

        renderer.endRenderPass();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            mainDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(0, mainDepth);
        drawFullscreen(LuminaPipelines.copyDepth, this.sceneDepth);

        boolean occlusion = config.ambientOcclusion && config.ambientOcclusionStrength > 0.0f;

        // Contact shadows only make sense where the sun or moon is what lights the scene
        float contactStrength = config.contactShadows ? config.contactShadowStrength * light.strength * light.eyeSkyLight : 0.0f;
        boolean contact = contactStrength > 0.02f;

        if (occlusion || contact) {
            Vector3f lightView = light.lightDirView;
            LuminaUniforms.set(LuminaUniforms.LIGHT_VIEW, lightView.x, lightView.y, lightView.z, contact ? quality.contactSteps() : 0.0f);
            LuminaUniforms.set(LuminaUniforms.AO_PARAMS, occlusion ? OCCLUSION_RADIUS : 0.0f, CONTACT_RAY_LENGTH, OCCLUSION_RANGE,
                               quality.occlusionSamples());

            VTextureSelector.bindTexture(0, this.sceneDepth.color());
            drawFullscreen(LuminaPipelines.occlusion, this.occlusionA);

            VTextureSelector.bindTexture(0, this.occlusionA.color());
            VTextureSelector.bindTexture(1, this.sceneDepth.color());
            drawFullscreen(LuminaPipelines.occlusionBlur, this.occlusionB);

            // Multiply the result into the frame
            renderer.getMainPass().rebindMainTarget();
            applyFullscreenState();
            VRenderSystem.enableBlend();
            VRenderSystem.blendFunc(GL11.GL_DST_COLOR, GL11.GL_ZERO);

            LuminaUniforms.set(LuminaUniforms.AO_APPLY, occlusion ? config.ambientOcclusionStrength : 0.0f, contact ? contactStrength : 0.0f, 0.0f, 0.0f);
            VTextureSelector.bindTexture(0, this.occlusionB.color());
            VTextureSelector.bindTexture(1, this.sceneDepth.color());
            draw(LuminaPipelines.occlusionApply);

            VRenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            VRenderSystem.disableBlend();
            renderer.endRenderPass();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            mainColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(0, mainColor);
        drawFullscreen(LuminaPipelines.copy, this.scene);

        // The caller begins the main pass again, which returns both images to their attachment layouts
        restoreState();
    }

    /**
     * Runs right after the world is drawn and before the first-person hand clears the depth buffer:
     * the only moment the scene depth is available to tell sky from terrain.
     */
    public void renderGodrays(LightState light, LuminaConfig config, AdaptiveQuality quality, Matrix4f modelView, Matrix4f projection) {
        this.godraysValid = false;

        // Indoors and underground the sky is not what lights the scene, so there is nothing to scatter
        float intensity = config.godrays * light.strength * light.eyeSkyLight;
        if (intensity < 0.005f || modelView == null || projection == null)
            return;

        Vector3f dir = light.lightDir;
        projection.mul(modelView, this.viewProjection);
        this.viewProjection.transform(this.sunClip.set(dir.x, dir.y, dir.z, 0.0f));

        // Behind the camera
        if (this.sunClip.w <= 1.0e-4f)
            return;

        float ndcX = this.sunClip.x / this.sunClip.w;
        float ndcY = this.sunClip.y / this.sunClip.w;

        // Fade out as the light source leaves the screen instead of popping
        float offScreen = Math.max(Math.abs(ndcX), Math.abs(ndcY));
        intensity *= 1.0f - LightState.smoothstep(1.0f, 1.6f, offScreen);
        if (intensity < 0.005f)
            return;

        Renderer renderer = Renderer.getInstance();
        Framebuffer mainFramebuffer = renderer.getMainPass().getMainFramebuffer();
        VulkanImage depth = mainFramebuffer.getDepthAttachment();

        renderer.endRenderPass();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            depth.transitionImageLayout(stack, Renderer.getCommandBuffer(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        float aspect = (float) this.width / Math.max(1, this.height);
        LuminaUniforms.set(LuminaUniforms.SUN_SCREEN, ndcX * 0.5f + 0.5f, 0.5f - ndcY * 0.5f, intensity, aspect);
        LuminaUniforms.set(LuminaUniforms.RAY_PARAMS, quality.godraySteps(), 0.0f, 0.0f, 0.0f);

        VTextureSelector.bindTexture(1, depth);
        drawFullscreen(LuminaPipelines.godrays, this.raysA);

        // Soften the stepping of the radial march, both directions right away: the shafts have their own targets
        LuminaUniforms.set(LuminaUniforms.BLUR, 0.75f / this.raysA.width(), 0.0f, 0.0f, 0.0f);
        VTextureSelector.bindTexture(0, this.raysA.color());
        drawFullscreen(LuminaPipelines.blur, this.raysB);

        LuminaUniforms.set(LuminaUniforms.BLUR, 0.0f, 1.5f / this.raysB.height(), 0.0f, 0.0f);
        VTextureSelector.bindTexture(0, this.raysB.color());
        drawFullscreen(LuminaPipelines.blur, this.raysA);

        this.godraysValid = true;

        // Beginning the main pass again moves the depth image back to its attachment layout
        renderer.getMainPass().rebindMainTarget();
        restoreState();
    }

    /**
     * Runs once the world and the hand are drawn, before the HUD.
     */
    public void renderFinal(LightState light, LuminaConfig config) {
        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Framebuffer mainFramebuffer = renderer.getMainPass().getMainFramebuffer();
        VulkanImage mainColor = mainFramebuffer.getColorAttachment();

        renderer.endRenderPass();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            mainColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(0, mainColor);
        drawFullscreen(LuminaPipelines.copy, this.composite);

        boolean bloom = config.bloom && config.bloomStrength > 0.0f;
        if (bloom) {
            LuminaUniforms.set(LuminaUniforms.BLUR, 1.0f / this.width, 1.0f / this.height, 0.72f, 0.0f);
            VTextureSelector.bindTexture(0, this.composite.color());
            drawFullscreen(LuminaPipelines.bloomDown, this.bloomA);

            LuminaUniforms.set(LuminaUniforms.BLUR, 1.0f / this.bloomA.width(), 0.0f, 0.0f, 0.0f);
            VTextureSelector.bindTexture(0, this.bloomA.color());
            drawFullscreen(LuminaPipelines.blur, this.bloomB);

            LuminaUniforms.set(LuminaUniforms.BLUR, 0.0f, 1.0f / this.bloomB.height(), 0.0f, 0.0f);
            VTextureSelector.bindTexture(0, this.bloomB.color());
            drawFullscreen(LuminaPipelines.blur, this.bloomA);
        }

        boolean exposureOn = config.autoExposure && config.autoExposureStrength > 0.0f;
        if (exposureOn)
            meterExposure(config);
        else
            this.lastExposureNanos = 0;

        // A hint of warmth in the white balance by day, none at night
        float warm = config.warmth * 0.05f * (light.sunIsLight ? light.strength : 0.0f);
        LuminaUniforms.set(LuminaUniforms.GRADE, config.saturation, config.contrast, config.brightness, config.vignette);
        LuminaUniforms.set(LuminaUniforms.TINT, 1.0f + warm, 1.0f, 1.0f - warm, bloom ? config.bloomStrength : 0.0f);
        LuminaUniforms.set(LuminaUniforms.POST, config.sharpen, 1.0f / this.width, 1.0f / this.height, exposureOn ? 1.0f : 0.0f);
        LuminaUniforms.set(LuminaUniforms.RAY_COLOR, light.litColor.x, light.litColor.y * 0.95f, light.litColor.z * 0.8f,
                           this.godraysValid ? 1.0f : 0.0f);

        // Beginning the main pass moves the swapchain image back to its attachment layout
        renderer.getMainPass().rebindMainTarget();

        applyFullscreenState();
        VTextureSelector.bindTexture(0, this.composite.color());
        // Targets not drawn this frame may never have been drawn at all: something readable stands in for them
        VTextureSelector.bindTexture(1, bloom ? this.bloomA.color() : this.composite.color());
        VTextureSelector.bindTexture(2, this.godraysValid ? this.raysA.color() : this.composite.color());
        VTextureSelector.bindTexture(3, exposureOn ? this.exposure[this.exposureIndex].color() : this.composite.color());
        draw(LuminaPipelines.finalPass);

        this.godraysValid = false;
        restoreState();
    }

    /** Measures the finished frame and moves the exposure towards it. Leaves the new value in exposure[exposureIndex]. */
    private void meterExposure(LuminaConfig config) {
        long now = System.nanoTime();
        boolean reset = this.lastExposureNanos == 0 || now - this.lastExposureNanos > EXPOSURE_RESET_NANOS;
        float seconds = reset ? 0.0f : (now - this.lastExposureNanos) / 1.0e9f;
        this.lastExposureNanos = now;

        float toBright = 1.0f - (float) Math.exp(-seconds * 3.0f / ADAPT_TO_DARK_SECONDS);
        float toDark = 1.0f - (float) Math.exp(-seconds * 3.0f / ADAPT_TO_BRIGHT_SECONDS);

        VTextureSelector.bindTexture(0, this.composite.color());
        drawFullscreen(LuminaPipelines.luminanceDown, this.luminance);

        int previous = this.exposureIndex;
        int next = 1 - previous;

        LuminaUniforms.set(LuminaUniforms.EXPOSURE, config.autoExposureStrength, toBright, toDark, reset ? 1.0f : 0.0f);
        VTextureSelector.bindTexture(0, this.luminance.color());
        // On a reset the previous value is not read, and may never have been drawn: the grid stands in for it
        VTextureSelector.bindTexture(1, reset ? this.luminance.color() : this.exposure[previous].color());
        drawFullscreen(LuminaPipelines.exposure, this.exposure[next]);

        this.exposureIndex = next;
    }

    private void drawFullscreen(GraphicsPipeline pipeline, RenderTarget target) {
        Renderer.getInstance().beginRenderPass(target.renderPass, target.framebuffer);
        applyFullscreenState();
        draw(pipeline);
        Renderer.getInstance().endRenderPass();
    }

    static void applyFullscreenState() {
        VRenderSystem.disableCull();
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
    }

    static void restoreState() {
        VRenderSystem.enableCull();
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
    }

    /** Draws the screen-covering triangle of screen.vsh with the given pipeline into whatever pass is bound. */
    static void draw(GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        vkCmdDraw(Renderer.getCommandBuffer(), 3, 1, 0, 0);
    }

    public String describe() {
        return this.scene == null ? "no targets" : "scene=%dx%d bloom=%dx%d rays=%dx%d occlusion=%dx%d"
                .formatted(this.width, this.height, this.bloomA.width(), this.bloomA.height(), this.raysA.width(), this.raysA.height(),
                           this.occlusionA.width(), this.occlusionA.height());
    }
}
