package dev.lumina.render;

import dev.lumina.Lumina;
import dev.lumina.config.LuminaConfig;
import dev.lumina.util.LuminaLog;
import dev.lumina.util.ResourceTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Entry points called from the mixins, in frame order: {@link #beginFrame()}, {@link #prepareLevel},
 * the sky ({@link #renderOverworldSky}, {@link #renderEndSky}), {@link #onTerrainLayer} and
 * {@link #afterTerrainLayer()} for every terrain layer, {@link #afterWorld()}, {@link #finishFrame()}.
 *
 * Every entry point catches its own failures: a broken pass disables the mod and leaves VulkanMod rendering
 * as if it was not installed, with the cause written to lumina.log.
 */
public final class LuminaRenderer {
    private static final long HEALTH_INTERVAL_NANOS = 30_000_000_000L;

    private static final LightState light = new LightState();
    private static final ShadowRenderer shadows = new ShadowRenderer();
    private static final PostProcessor post = new PostProcessor();
    private static final SkyPass sky = new SkyPass();
    private static final SkyPass.FrameInfo frame = new SkyPass.FrameInfo();

    private static boolean pipelinesCreated;
    /** Read by chunk meshing threads to decide whether to bake vanilla face shading. */
    private static volatile boolean active;
    /** Read by the cloud option hook, which the game may call from outside the frame. */
    private static volatile boolean ownClouds;
    private static boolean failed;
    private static String failureMessage;
    private static boolean pendingRebuild;

    private static boolean frameStarted;
    private static boolean levelPrepared;
    private static boolean shadowsRendered;
    private static final Matrix4f frameModelView = new Matrix4f();
    private static final Matrix4f frameProjection = new Matrix4f();
    private static final Matrix4f scratchMatrix = new Matrix4f();
    private static final Vector3f scratchVector = new Vector3f();
    private static final long startNanos = System.nanoTime();

    private static long lastHealthNanos;
    private static long framesSinceHealth;
    private static long totalFrames;
    private static int appliedShadowResolution;

    private LuminaRenderer() {
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean hasFailed() {
        return failed;
    }

    public static LightState light() {
        return light;
    }

    /** Start of GameRenderer.renderLevel: the only place where the mod is switched on or off. */
    public static void beginFrame() {
        frameStarted = false;
        levelPrepared = false;
        shadowsRendered = false;
        LuminaUniforms.set(LuminaUniforms.ENTITY_PARAMS, 0.0f, 0.0f, 0.0f, 0.0f);

        try {
            LuminaConfig config = Lumina.config();
            boolean wanted = config.enabled && !failed;

            if (wanted != active) {
                if (wanted)
                    activate();
                else
                    deactivate("disabled in settings");
            }

            if (pendingRebuild) {
                pendingRebuild = false;
                shadows.invalidate();
                rebuildTerrain();
            }

            if (!active)
                return;

            Framebuffer main = Renderer.getInstance().getMainPass().getMainFramebuffer();
            post.ensureResources(main.getWidth(), main.getHeight());
            shadows.ensureResources(config.shadowResolution);

            if (appliedShadowResolution != config.shadowResolution) {
                LuminaLog.info("Shadow map resolution set to %d", config.shadowResolution);
                appliedShadowResolution = config.shadowResolution;
            }

            frameStarted = true;
            totalFrames++;
            framesSinceHealth++;
            reportHealth();
        } catch (Throwable t) {
            fail("beginFrame", t);
        }
    }

    /**
     * Head of LevelRenderer.renderLevel, before the game draws anything of the world: works out the light and
     * every value that does not depend on the shadow maps.
     */
    public static void prepareLevel(Camera camera, Matrix4f modelView, Matrix4f projection) {
        ownClouds = false;

        if (!active || !frameStarted)
            return;

        try {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft.level;
            LuminaConfig config = Lumina.config();

            if (level == null)
                return;

            Vec3 cameraPos = camera.position();
            double camX = cameraPos.x;
            double camY = cameraPos.y;
            double camZ = cameraPos.z;

            light.update(level, camera, config, camX, camY, camZ);
            frameModelView.set(modelView);
            frameProjection.set(projection);

            frame.camX = camX;
            frame.camY = camY;
            frame.camZ = camZ;
            frame.timeSeconds = (System.nanoTime() - startNanos) / 1.0e9f % 3600.0f;

            updateViewUniforms(modelView, projection);

            // World position is only needed for repeating patterns, wrapping it keeps float precision far from the origin
            LuminaUniforms.set(LuminaUniforms.CAMERA, (float) (camX % 1024.0), (float) (camY % 1024.0), (float) (camZ % 1024.0), frame.timeSeconds);

            LuminaUniforms.set(LuminaUniforms.LIGHT_DIR, light.lightDir.x, light.lightDir.y, light.lightDir.z, 0.0f);
            LuminaUniforms.set(LuminaUniforms.LIT_COLOR, light.litColor.x, light.litColor.y, light.litColor.z, light.strength);
            LuminaUniforms.set(LuminaUniforms.SHADOW_COLOR, light.shadowColor.x, light.shadowColor.y, light.shadowColor.z,
                               light.uniformFaceShade ? 1.0f : 0.0f);
            LuminaUniforms.set(LuminaUniforms.PARAMS, config.warmth, config.shadowSoftness, config.shadowDistance, config.ambientBrightness);
            LuminaUniforms.set(LuminaUniforms.SKY, light.skyColor.x, light.skyColor.y, light.skyColor.z, config.water ? 1.0f : 0.0f);
            LuminaUniforms.set(LuminaUniforms.REFLECT, config.reflections ? 1.0f : 0.0f, wetness(level, camera, config),
                               config.lava ? 1.0f : 0.0f, config.waterWaves);

            LuminaUniforms.set(LuminaUniforms.INV_VIEW, scratchMatrix.set(modelView).invert());
            LuminaUniforms.set(LuminaUniforms.ENTITY_PARAMS, config.entityShading ? 1.0f : 0.0f, 0.0f, 0.0f, 0.0f);

            ownClouds = config.customSky && config.clouds && level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
            levelPrepared = true;
        } catch (Throwable t) {
            fail("level preparation", t);
        }
    }

    /**
     * The screen-space passes work in view space and the sky needs the direction each pixel looks in. Both come
     * from the combined matrix, because the game folds view bobbing into what it calls the projection.
     */
    private static void updateViewUniforms(Matrix4f modelView, Matrix4f projection) {
        Matrix4f viewProjection = projection.mul(modelView, scratchMatrix);

        // Rows of the combined matrix are the view axes scaled by the projection; the last row is the view direction
        float scaleX = scratchVector.set(viewProjection.m00(), viewProjection.m10(), viewProjection.m20()).length();
        float rightX = scratchVector.x / scaleX, rightY = scratchVector.y / scaleX, rightZ = scratchVector.z / scaleX;

        float scaleY = scratchVector.set(viewProjection.m01(), viewProjection.m11(), viewProjection.m21()).length();
        float upX = scratchVector.x / scaleY, upY = scratchVector.y / scaleY, upZ = scratchVector.z / scaleY;

        float backX = -viewProjection.m03(), backY = -viewProjection.m13(), backZ = -viewProjection.m23();

        LuminaUniforms.set(LuminaUniforms.PROJ, 1.0f / scaleX, 1.0f / scaleY, scaleX, scaleY);
        LuminaUniforms.set(LuminaUniforms.VIEW_TO_WORLD, scratchMatrix.set(rightX, rightY, rightZ, 0.0f,
                                                                          upX, upY, upZ, 0.0f,
                                                                          backX, backY, backZ, 0.0f,
                                                                          0.0f, 0.0f, 0.0f, 1.0f));

        Vector3f l = light.lightDir;
        light.lightDirView.set(rightX * l.x + rightY * l.y + rightZ * l.z,
                               upX * l.x + upY * l.y + upZ * l.z,
                               backX * l.x + backY * l.y + backZ * l.z);
    }

    /** How wet the ground is: follows the rain, but only where it actually rains rather than snows or stays dry. */
    private static float wetness(ClientLevel level, Camera camera, LuminaConfig config) {
        if (light.rain <= 0.01f || config.rainPuddles <= 0.0f)
            return 0.0f;

        BlockPos pos = camera.blockPosition();
        boolean rains = level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.RAIN;

        return rains ? light.rain * config.rainPuddles : 0.0f;
    }

    /** True while the game's own sky and clouds should be left out because the mod draws them. */
    public static boolean ownsSky() {
        return active && frameStarted && levelPrepared && Lumina.config().customSky;
    }

    public static boolean ownsEndSky() {
        return ownsSky() && Lumina.config().endSky;
    }

    public static boolean ownsClouds() {
        return active && ownClouds;
    }

    public static SkyPass sky() {
        return sky;
    }

    /** Called where the game would draw sun, moon and stars: the last step of its overworld sky. */
    public static void renderOverworldSky(float sunAngle, float moonAngle, float starAngle, int moonPhase, float clearness, float starBrightness) {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            float thunder = level == null ? 0.0f : level.getThunderLevel(Minecraft.getInstance().gameRenderer.getMainCamera().getPartialTickTime());

            sky.renderOverworld(Lumina.config(), frame, sunAngle, moonAngle, starAngle, moonPhase, clearness, starBrightness, thunder);
        } catch (Throwable t) {
            fail("sky pass", t);
        }
    }

    public static void renderEndSky() {
        try {
            sky.renderEnd(Lumina.config(), frame);
        } catch (Throwable t) {
            fail("end sky pass", t);
        }
    }

    /** Head of VulkanMod's terrain layer draw. The first call of a frame renders the shadow maps. */
    public static void onTerrainLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        if (!active || !frameStarted || !levelPrepared)
            return;

        try {
            LuminaConfig config = Lumina.config();

            if (!shadowsRendered) {
                Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();

                // Stronger and gustier wind in bad weather
                float wind = config.wavingPlants ? 1.0f + 0.8f * light.rain : 0.0f;
                LuminaUniforms.set(LuminaUniforms.WAVE, wind, 1.0f, 0.0f, 0.0f);

                boolean castShadows = config.shadows && light.strength > 0.01f;
                shadows.render(light, config, camera.getPartialTickTime(), camX, camY, camZ, castShadows);

                // The shadow pass switches the sway off for the far cascade
                LuminaUniforms.set(LuminaUniforms.WAVE, wind, 1.0f, 0.0f, 0.0f);

                // 0: no shadow maps, 1: near cascade only (far one still being built), 2: both
                float cascades = !castShadows ? 0.0f : (shadows.isFarValid() ? 2.0f : 1.0f);

                LuminaUniforms.set(LuminaUniforms.SHADOW_MAT, shadows.nearMatrix(scratchMatrix, camX, camY, camZ));
                LuminaUniforms.set(LuminaUniforms.SHADOW_MAT_FAR, shadows.farMatrix(scratchMatrix, camX, camY, camZ));
                LuminaUniforms.set(LuminaUniforms.SHADOW_PARAMS, shadows.nearTexel(), shadows.farTexel(), 0.0f, 0.0f);
                LuminaUniforms.set(LuminaUniforms.LIGHT_DIR, light.lightDir.x, light.lightDir.y, light.lightDir.z, cascades);

                shadowsRendered = true;
            }

            if (renderType == TerrainRenderType.TRANSLUCENT)
                post.captureOpaque(light, config);

            VTextureSelector.bindTexture(LuminaPipelines.SCENE_COLOR_SLOT, post.sceneColor());
            VTextureSelector.bindTexture(LuminaPipelines.SCENE_DEPTH_SLOT, post.sceneDepth());

            shadows.setComparisonSampling(true);

            // Other passes are free to reuse the slot between layers
            VTextureSelector.bindTexture(LuminaPipelines.SHADOW_SLOT, shadows.nearDepth());
            VTextureSelector.bindTexture(LuminaPipelines.SHADOW_FAR_SLOT, shadows.farDepth());
        } catch (Throwable t) {
            fail("terrain/shadow pass", t);
        }
    }

    /** Tail of VulkanMod's terrain layer draw: whatever comes next (entities, particles, the hand) is not terrain. */
    public static void afterTerrainLayer() {
        if (!active || !frameStarted || !shadowsRendered)
            return;

        try {
            shadows.setComparisonSampling(false);
            VTextureSelector.bindTexture(LuminaPipelines.ENTITY_SHADOW_SLOT, shadows.nearDepth());
            VTextureSelector.bindTexture(LuminaPipelines.ENTITY_SHADOW_FAR_SLOT, shadows.farDepth());
        } catch (Throwable t) {
            fail("entity shadow setup", t);
        }
    }

    /**
     * Start of every frame, menus included. The overridden entity shader always declares the shadow map samplers,
     * so something has to be bound to them even when the pipeline never ran.
     */
    public static void ensureEntityTextures() {
        try {
            if (VTextureSelector.getImage(LuminaPipelines.ENTITY_SHADOW_SLOT) == null)
                VTextureSelector.bindTexture(LuminaPipelines.ENTITY_SHADOW_SLOT, VTextureSelector.getWhiteTexture());

            if (VTextureSelector.getImage(LuminaPipelines.ENTITY_SHADOW_FAR_SLOT) == null)
                VTextureSelector.bindTexture(LuminaPipelines.ENTITY_SHADOW_FAR_SLOT, VTextureSelector.getWhiteTexture());
        } catch (Throwable t) {
            LuminaLog.errorLimited("entityTextures", "Could not bind placeholder textures for the entity shader", t);
        }
    }

    /** World is drawn, the first-person hand is not: last moment the scene depth exists. */
    public static void afterWorld() {
        if (!active || !frameStarted || !levelPrepared)
            return;

        // The hand is drawn in its own space: it takes the shadow at the camera position
        if (Lumina.config().entityShading)
            LuminaUniforms.set(LuminaUniforms.ENTITY_PARAMS, 2.0f, 0.0f, 0.0f, 0.0f);

        try {
            post.renderGodrays(light, Lumina.config(), frameModelView, frameProjection);
        } catch (Throwable t) {
            fail("godrays pass", t);
        }
    }

    /** End of GameRenderer.renderLevel, before the HUD. */
    public static void finishFrame() {
        // Entities drawn in the interface (inventory doll, advancement icons) must not pick up world shadows
        LuminaUniforms.set(LuminaUniforms.ENTITY_PARAMS, 0.0f, 0.0f, 0.0f, 0.0f);

        if (!active || !frameStarted)
            return;

        try {
            post.renderFinal(light, Lumina.config());
        } catch (Throwable t) {
            fail("final pass", t);
        } finally {
            frameStarted = false;
            levelPrepared = false;
        }
    }

    private static void activate() {
        LuminaLog.info("Activating shader pipeline");

        if (!pipelinesCreated) {
            logDevice();
            LuminaPipelines.create();
            pipelinesCreated = true;
        }

        PipelineManager.setShaderGetter(renderType -> LuminaPipelines.terrain);
        active = true;
        pendingRebuild = true;

        LuminaLog.info("Shader pipeline active. Config: %s", Lumina.config().describe());
    }

    private static void deactivate(String reason) {
        LuminaLog.info("Deactivating shader pipeline: %s", reason);

        active = false;
        ownClouds = false;
        PipelineManager.setDefaultTerrainShaderGetter();
        pendingRebuild = true;
    }

    /**
     * Face shading is baked into chunk meshes, so they have to be rebuilt whenever the mod toggles.
     * Only done at the start of a frame: a failure can switch the mod off in the middle of terrain drawing.
     */
    private static void rebuildTerrain() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level != null && minecraft.levelRenderer != null)
            minecraft.levelRenderer.allChanged();
    }

    private static void fail(String stage, Throwable t) {
        if (failed) {
            LuminaLog.errorLimited("repeat:" + stage, "Failure in %s after the pipeline was already disabled".formatted(stage), t);
            return;
        }

        failed = true;
        failureMessage = "%s: %s".formatted(stage, t);

        LuminaLog.error("Fatal error in %s, shaders are disabled for this session".formatted(stage), t);
        LuminaLog.info("State at failure: %s", describeState());

        try {
            // Leave whatever pass was half way through and hand the frame back to VulkanMod
            Renderer renderer = Renderer.getInstance();
            renderer.endRenderPass();
            renderer.getMainPass().rebindMainTarget();
        } catch (Throwable inner) {
            LuminaLog.error("Could not restore the main render target after the failure", inner);
        }

        try {
            deactivate("failure in " + stage);
        } catch (Throwable inner) {
            LuminaLog.error("Could not restore the vanilla terrain shader after the failure", inner);
        }
    }

    /** Called from the client tick, outside of rendering. */
    public static void tick() {
        if (failureMessage != null) {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.player != null) {
                minecraft.gui.getChat().addMessage(Component.literal("[Lumina] Shaders were disabled after an error, see logs/lumina.log (" + failureMessage + ")"));
                failureMessage = null;
            }
        }
    }

    /** Lets the player try again after a failure without restarting the game. */
    public static void resetFailure() {
        if (failed) {
            LuminaLog.info("Failure state cleared by the user");
            failed = false;
        }
    }

    private static void reportHealth() {
        long now = System.nanoTime();

        if (lastHealthNanos == 0) {
            lastHealthNanos = now;
            return;
        }

        if (now - lastHealthNanos < HEALTH_INTERVAL_NANOS)
            return;

        double seconds = (now - lastHealthNanos) / 1.0e9;
        LuminaLog.debug("[health] fps=%.0f %s", framesSinceHealth / seconds, describeState());

        lastHealthNanos = now;
        framesSinceHealth = 0;
    }

    public static String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("active=").append(active).append(" failed=").append(failed).append(" frames=").append(totalFrames);

        try {
            Runtime runtime = Runtime.getRuntime();
            MemoryManager memory = MemoryManager.getInstance();

            sb.append(" | heap=").append((runtime.totalMemory() - runtime.freeMemory()) >> 20).append('/').append(runtime.maxMemory() >> 20).append("MB");
            sb.append(" native=").append(memory.getNativeMemoryMB()).append("MB");
            sb.append(" gpu=").append(memory.getAllocatedDeviceMemoryMB()).append('/').append(memory.getDeviceMemoryMB()).append("MB");
        } catch (Throwable t) {
            sb.append(" | memory stats unavailable (").append(t).append(')');
        }

        sb.append(" | light: strength=%.2f sun=%s height=%.2f rain=%.2f eyeSky=%.2f"
                          .formatted(light.strength, light.sunIsLight, light.sunHeight, light.rain, light.eyeSkyLight));
        sb.append(" | shadow: ").append(shadows.describe());
        sb.append(" | post: ").append(post.describe());
        sb.append(" | resources: ").append(ResourceTracker.summary());
        return sb.toString();
    }

    private static void logDevice() {
        try {
            var device = DeviceManager.device;
            LuminaLog.info("GPU: %s (%s), driver %s, Vulkan %s", device.deviceName, device.vendorIdString, device.driverVersion, device.vkVersion);
        } catch (Throwable t) {
            LuminaLog.warn("Could not read GPU information: %s", t);
        }
    }

    /** Game shutdown. GPU objects are not released one by one: VulkanMod tears the whole device down right after. */
    public static void shutdown() {
        LuminaLog.info("Shutting down. Final state: %s", describeState());
        LuminaLog.info("Live resources:%s", ResourceTracker.liveList());
    }
}
