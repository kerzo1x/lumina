package dev.lumina.render;

import dev.lumina.config.LuminaConfig;
import dev.lumina.util.ResourceTracker;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
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
 * at the end (for bloom, light shafts and colour grading).
 */
public final class PostProcessor {
    private static final int BLOOM_DIVISOR = 4;
    private static final int GODRAYS_DIVISOR = 2;
    private static final int OCCLUSION_DIVISOR = 2;

    /** Occlusion and contact shadows are not computed further away than this, in blocks. */
    private static final float OCCLUSION_RANGE = 110.0f;
    private static final float OCCLUSION_RADIUS = 1.0f;
    private static final float CONTACT_RAY_LENGTH = 0.45f;

    /**
     * The frame before translucent terrain. Kept until the same point of the next frame: opaque terrain reads it
     * for its reflections, one frame late, because its own frame is still being drawn.
     */
    private Target scene;
    private Target sceneDepth;
    /** The finished frame, input of the final pass. */
    private Target composite;
    private Target bloomA;
    private Target bloomB;
    private Target godrays;
    private Target occlusionA;
    private Target occlusionB;

    private int width;
    private int height;

    private boolean godraysValid;
    private final Vector4f sunClip = new Vector4f();
    private final Matrix4f viewProjection = new Matrix4f();

    public void ensureResources(int width, int height) {
        if (this.scene == null) {
            this.scene = new Target("scene", width, height);
            this.sceneDepth = new Target("sceneDepth", width, height, VK_FORMAT_R32_SFLOAT, false);
            this.composite = new Target("composite", width, height);
            this.bloomA = new Target("bloomA", width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.bloomB = new Target("bloomB", width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.godrays = new Target("godrays", width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
            this.occlusionA = new Target("occlusionA", width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR);
            this.occlusionB = new Target("occlusionB", width / OCCLUSION_DIVISOR, height / OCCLUSION_DIVISOR);
        } else if (this.width != width || this.height != height) {
            this.scene.resize(width, height);
            this.sceneDepth.resize(width, height);
            this.composite.resize(width, height);
            this.bloomA.resize(width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.bloomB.resize(width / BLOOM_DIVISOR, height / BLOOM_DIVISOR);
            this.godrays.resize(width / GODRAYS_DIVISOR, height / GODRAYS_DIVISOR);
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
    public void captureOpaque(LightState light, LuminaConfig config) {
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
            LuminaUniforms.set(LuminaUniforms.LIGHT_VIEW, lightView.x, lightView.y, lightView.z, contact ? 1.0f : 0.0f);
            LuminaUniforms.set(LuminaUniforms.AO_PARAMS, occlusion ? OCCLUSION_RADIUS : 0.0f, CONTACT_RAY_LENGTH, OCCLUSION_RANGE, 0.0f);

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
    public void renderGodrays(LightState light, LuminaConfig config, Matrix4f modelView, Matrix4f projection) {
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

        VTextureSelector.bindTexture(1, depth);
        drawFullscreen(LuminaPipelines.godrays, this.godrays);

        // Soften the stepping of the radial march
        LuminaUniforms.set(LuminaUniforms.BLUR, 1.5f / this.godrays.width(), 0.0f, 0.0f, 0.0f);
        VTextureSelector.bindTexture(0, this.godrays.color());
        drawFullscreen(LuminaPipelines.blur, this.bloomB);

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

        // The godray buffer was blurred into bloomB before the hand was drawn, move it out of the bloom chain's way
        if (this.godraysValid) {
            LuminaUniforms.set(LuminaUniforms.BLUR, 0.0f, 1.5f / this.bloomB.height(), 0.0f, 0.0f);
            VTextureSelector.bindTexture(0, this.bloomB.color());
            drawFullscreen(LuminaPipelines.blur, this.godrays);
        }

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

        // A hint of warmth in the white balance by day, none at night
        float warm = config.warmth * 0.05f * (light.sunIsLight ? light.strength : 0.0f);
        LuminaUniforms.set(LuminaUniforms.GRADE, config.saturation, config.contrast, config.brightness, config.vignette);
        LuminaUniforms.set(LuminaUniforms.TINT, 1.0f + warm, 1.0f, 1.0f - warm, bloom ? config.bloomStrength : 0.0f);
        LuminaUniforms.set(LuminaUniforms.POST, config.sharpen, 1.0f / this.width, 1.0f / this.height, 0.0f);
        LuminaUniforms.set(LuminaUniforms.RAY_COLOR, light.litColor.x, light.litColor.y * 0.95f, light.litColor.z * 0.8f,
                           this.godraysValid ? 1.0f : 0.0f);

        // Beginning the main pass moves the swapchain image back to its attachment layout
        renderer.getMainPass().rebindMainTarget();

        applyFullscreenState();
        VTextureSelector.bindTexture(0, this.composite.color());
        VTextureSelector.bindTexture(1, this.bloomA.color());
        VTextureSelector.bindTexture(2, this.godrays.color());
        draw(LuminaPipelines.finalPass);

        this.godraysValid = false;
        restoreState();
    }

    private void drawFullscreen(GraphicsPipeline pipeline, Target target) {
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
        return this.scene == null ? "no targets" : "scene=%dx%d bloom=%dx%d godrays=%dx%d occlusion=%dx%d"
                .formatted(this.width, this.height, this.bloomA.width(), this.bloomA.height(), this.godrays.width(), this.godrays.height(),
                           this.occlusionA.width(), this.occlusionA.height());
    }

    /**
     * Off-screen render target. The depth attachment is never used, it only keeps the attachment set identical to
     * what VulkanMod declares when it compiles a pipeline for the pass. The framebuffer and pass objects are kept
     * across resizes because VulkanMod caches compiled pipelines per pass object: recreating the pass on every
     * resize would leak one pipeline each time.
     */
    private static final class Target {
        final String name;
        final Framebuffer framebuffer;
        final RenderPass renderPass;

        Target(String name, int width, int height) {
            this(name, width, height, Framebuffer.DEFAULT_FORMAT, true);
        }

        Target(String name, int width, int height, int format, boolean linearFiltering) {
            this.name = name;
            this.framebuffer = new Framebuffer.Builder("Lumina " + name, Math.max(1, width), Math.max(1, height), 1, true)
                    .setFormat(format)
                    .setLinearFiltering(linearFiltering)
                    .build();

            RenderPass.Builder builder = RenderPass.builder(this.framebuffer);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE)
                   .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
            this.renderPass = builder.build();

            ResourceTracker.created("framebuffer", name, "%dx%d".formatted(width(), height()));
        }

        void resize(int width, int height) {
            this.framebuffer.resize(Math.max(1, width), Math.max(1, height));
            ResourceTracker.freed("framebuffer", this.name);
            ResourceTracker.created("framebuffer", this.name, "%dx%d".formatted(width(), height()));
        }

        VulkanImage color() {
            return this.framebuffer.getColorAttachment();
        }

        int width() {
            return this.framebuffer.getWidth();
        }

        int height() {
            return this.framebuffer.getHeight();
        }
    }
}
