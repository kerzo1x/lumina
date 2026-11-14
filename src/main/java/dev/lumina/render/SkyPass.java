package dev.lumina.render;

import dev.lumina.config.LuminaConfig;
import net.minecraft.util.Mth;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

/**
 * Draws the mod's own sky in place of the game's. The game's sky renderer is called in several steps (dome,
 * sunrise glow, sun and moon and stars); the colours it passes to the first steps are collected here and the
 * whole sky is drawn in one go when the last step arrives.
 *
 * Everything smooth (atmosphere, clouds, galaxy) is drawn at half the screen size and scaled up by the full-size
 * pass, which adds the sharp details on top: a quarter of the pixels for the part that costs nearly everything.
 */
public final class SkyPass {
    private static final int MODE_OVERWORLD = 1;
    private static final int MODE_END = 2;

    /** Blocks per second the cloud layer drifts, the same pace as the game's clouds. */
    private static final float CLOUD_SPEED = 0.6f;
    /** Cloud noise is sampled at the world position modulo this, far enough apart to never see the seam. */
    private static final double CLOUD_WRAP = 65536.0;

    private final Vector3f skyColor = new Vector3f(0.5f, 0.7f, 1.0f);
    private final Vector3f sunsetColor = new Vector3f();
    private float sunsetStrength;

    /** Half-size target of the smooth part. Half-float, so the dark gradients of the night sky do not band. */
    private RenderTarget lowRes;

    private final Vector3f sunDir = new Vector3f();
    private final Vector3f moonDir = new Vector3f();
    private final Matrix4f celestial = new Matrix4f();

    public void ensureResources(int width, int height) {
        if (this.lowRes == null)
            this.lowRes = new RenderTarget("skyLowRes", (width + 1) / 2, (height + 1) / 2, VK_FORMAT_R16G16B16A16_SFLOAT, true);
        else
            this.lowRes.resize((width + 1) / 2, (height + 1) / 2);
    }

    /** Position of the camera in the cloud layer, which drifts with the wind. Shared with the cloud shadows. */
    public static float cloudOriginX(FrameInfo frame) {
        return (float) ((frame.camX + frame.timeSeconds * CLOUD_SPEED) % CLOUD_WRAP);
    }

    public static float cloudOriginZ(FrameInfo frame) {
        return (float) (frame.camZ % CLOUD_WRAP);
    }

    public void setSkyColor(int rgb) {
        this.skyColor.set(((rgb >> 16) & 0xFF) / 255.0f, ((rgb >> 8) & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f);
    }

    public void setSunsetColor(int argb) {
        this.sunsetColor.set(((argb >> 16) & 0xFF) / 255.0f, ((argb >> 8) & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f);
        this.sunsetStrength = ((argb >>> 24) & 0xFF) / 255.0f;
    }

    /**
     * @param sunAngle, moonAngle, starAngle  in radians, as the game passes them to its own sky renderer
     * @param clearness                       1 in clear weather, falling to 0 in heavy rain
     */
    public void renderOverworld(LuminaConfig config, FrameInfo frame, float sunAngle, float moonAngle, float starAngle,
                                int moonPhase, float clearness, float starBrightness, float thunder) {
        LightState.celestialDirection(sunAngle, this.sunDir);
        LightState.celestialDirection(moonAngle, this.moonDir);

        // Undoes the rotation that carries the stars across the sky, see LightState.celestialDirection
        this.celestial.rotationZ(-starAngle).rotateX(-LightState.SUN_TILT * Mth.DEG_TO_RAD);

        LuminaUniforms.set(LuminaUniforms.CELESTIAL, this.celestial);
        LuminaUniforms.set(LuminaUniforms.SUN_DIR, this.sunDir.x, this.sunDir.y, this.sunDir.z, clearness);
        LuminaUniforms.set(LuminaUniforms.MOON_DIR, this.moonDir.x, this.moonDir.y, this.moonDir.z, moonPhase);
        LuminaUniforms.set(LuminaUniforms.SKY_COLOR, this.skyColor.x, this.skyColor.y, this.skyColor.z, MODE_OVERWORLD);
        LuminaUniforms.set(LuminaUniforms.SUNSET_COLOR, this.sunsetColor.x, this.sunsetColor.y, this.sunsetColor.z, this.sunsetStrength);
        LuminaUniforms.set(LuminaUniforms.SKY_PARAMS, starBrightness * config.stars, config.clouds ? Math.max(config.cloudCoverage, 0.02f) : 0.0f,
                           1.0f - clearness, thunder);
        LuminaUniforms.set(LuminaUniforms.SKY_ORIGIN, cloudOriginX(frame), (float) frame.camY, cloudOriginZ(frame), frame.timeSeconds);

        draw();

        // The game clears the sunrise colour itself only by not calling the sunrise step
        this.sunsetStrength = 0.0f;
    }

    public void renderEnd(LuminaConfig config, FrameInfo frame) {
        LuminaUniforms.set(LuminaUniforms.SKY_COLOR, 0.0f, 0.0f, 0.0f, MODE_END);
        LuminaUniforms.set(LuminaUniforms.SKY_PARAMS, config.stars, 0.0f, 0.0f, 0.0f);
        LuminaUniforms.set(LuminaUniforms.SKY_ORIGIN, 0.0f, (float) frame.camY, 0.0f, frame.timeSeconds);

        draw();
    }

    private void draw() {
        Renderer renderer = Renderer.getInstance();

        renderer.endRenderPass();
        renderer.beginRenderPass(this.lowRes.renderPass, this.lowRes.framebuffer);
        PostProcessor.applyFullscreenState();
        PostProcessor.draw(LuminaPipelines.skyLowRes);
        renderer.endRenderPass();

        // Nothing has been drawn into the frame yet, so depth is neither tested nor written: the sky stays the
        // furthest thing there is, which is also how the light shafts tell sky from terrain later
        renderer.getMainPass().rebindMainTarget();

        PostProcessor.applyFullscreenState();
        VTextureSelector.bindTexture(0, this.lowRes.color());
        PostProcessor.draw(LuminaPipelines.sky);
        PostProcessor.restoreState();
    }

    /** Camera and time for the frame being drawn, filled in by LuminaRenderer. */
    public static final class FrameInfo {
        public double camX, camY, camZ;
        public float timeSeconds;
    }
}
