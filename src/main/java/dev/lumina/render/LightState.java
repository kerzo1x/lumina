package dev.lumina.render;

import dev.lumina.config.LuminaConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame description of the directional light (sun or moon) and everything derived from it.
 */
public final class LightState {
    /** Tilt of the sun's path away from the zenith, in degrees. The sky renderer applies the same rotation. */
    public static final float SUN_TILT = 25.0f;

    /** Half extent of the shadow volume along the light direction, enough to cover the build height. */
    private static final float DEPTH_RANGE = 384.0f;

    private static final float ANGLE_STEP = 0.1f * Mth.DEG_TO_RAD;

    public final Vector3f lightDir = new Vector3f(0, 1, 0);
    public final Vector3f sunDir = new Vector3f(0, 1, 0);
    /** {@link #lightDir} in view space, for the passes that trace against the depth buffer. */
    public final Vector3f lightDirView = new Vector3f(0, 1, 0);

    /** 0 = no directional light at all (no sky, heavy rain, twilight), 1 = full sun. */
    public float strength;
    public boolean sunIsLight;
    public float sunHeight;
    public float rain;
    /** Smoothed sky light at the camera, 0..1. Used to hide sky effects indoors and underground. */
    public float eyeSkyLight = 1.0f;
    /** The Nether lights every face the same, unlike the per-direction shading of other dimensions. */
    public boolean uniformFaceShade;

    public final Vector3f litColor = new Vector3f(1);
    /** Colour of the sky overhead, used for water reflections. */
    public final Vector3f skyColor = new Vector3f(0.5f, 0.7f, 1.0f);
    public final Vector3f shadowColor = new Vector3f(1);

    private long lastUpdateNanos;
    private final Matrix4f rotation = new Matrix4f();
    private final Vector3f tmp = new Vector3f();

    public void update(ClientLevel level, Camera camera, LuminaConfig config, double camX, double camY, double camZ) {
        float partialTick = camera.getPartialTickTime();
        boolean hasSky = level.dimensionType().hasSkyLight();
        this.uniformFaceShade = level.dimensionType().cardinalLightType() == DimensionType.CardinalLightType.NETHER;

        float sunAngle = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * Mth.DEG_TO_RAD;
        float moonAngle = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, partialTick) * Mth.DEG_TO_RAD;

        // The light moves in small steps instead of every frame: a continuously rotating light re-rasterises every
        // shadow edge slightly differently each frame, which reads as shimmering
        celestialDirection(quantize(sunAngle), this.sunDir);
        Vector3f moonDir = celestialDirection(quantize(moonAngle), this.tmp);

        this.sunHeight = this.sunDir.y;
        this.sunIsLight = this.sunDir.y >= moonDir.y;
        this.rain = level.getRainLevel(partialTick);

        int sky = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
        this.skyColor.set(((sky >> 16) & 0xFF) / 255.0f, ((sky >> 8) & 0xFF) / 255.0f, (sky & 0xFF) / 255.0f);

        float day = Mth.clamp(smoothstep(0.0f, 0.14f, this.sunDir.y), 0.0f, 1.0f);
        float night = Mth.clamp(smoothstep(0.0f, 0.14f, moonDir.y), 0.0f, 1.0f) * 0.4f;

        if (this.sunIsLight) {
            this.lightDir.set(this.sunDir);
            this.strength = day;
        } else {
            this.lightDir.set(moonDir);
            this.strength = night;
        }

        this.strength *= 1.0f - 0.85f * this.rain;
        // Sky light alone is not enough: the End has it too, but nothing in its sky to cast a shadow
        if (!hasSky || level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD)
            this.strength = 0.0f;

        updateColors(config);
        updateEyeSkyLight(level, camera, hasSky);

        // View looks from the light towards the scene. The path tilt keeps the light away from +-Z, so Z is a safe up
        this.rotation.identity().lookAlong(-this.lightDir.x, -this.lightDir.y, -this.lightDir.z, 0.0f, 0.0f, 1.0f);
    }

    private static float quantize(float angle) {
        return Math.round(angle / ANGLE_STEP) * ANGLE_STEP;
    }

    /**
     * Where a body on the sun's path stands for the given angle (0 = highest point). The path is the game's,
     * tipped by {@link #SUN_TILT}: a rotation about Z by the angle, then about X by the tilt.
     */
    public static Vector3f celestialDirection(float angle, Vector3f dest) {
        float tilt = SUN_TILT * Mth.DEG_TO_RAD;
        float cos = Mth.cos(angle);
        return dest.set(-Mth.sin(angle), cos * Mth.cos(tilt), cos * Mth.sin(tilt)).normalize();
    }

    private void updateColors(LuminaConfig config) {
        float warmth = config.warmth;

        if (this.sunIsLight) {
            // Warm orange near the horizon, close to neutral at noon
            float high = smoothstep(0.0f, 0.45f, this.sunHeight);

            this.litColor.set(Mth.lerp(high, 1.0f + 0.20f * warmth, 1.0f + 0.08f * warmth),
                              Mth.lerp(high, 1.0f - 0.16f * warmth, 1.0f),
                              Mth.lerp(high, 1.0f - 0.50f * warmth, 1.0f - 0.16f * warmth));
            this.litColor.mul(1.04f * config.sunBrightness);

            this.shadowColor.set(Mth.lerp(high, 0.56f, 0.50f),
                                 Mth.lerp(high, 0.54f, 0.56f),
                                 Mth.lerp(high, 0.66f, 0.72f));
        } else {
            // Moonlight: a cool tint and faint shadows, never darker than the game already is at night
            this.litColor.set(0.96f, 1.02f, 1.16f).mul(config.sunBrightness);
            this.shadowColor.set(0.80f, 0.84f, 0.94f);
        }

        // shadowStrength 0 leaves shadowed surfaces at vanilla brightness
        this.shadowColor.lerp(this.tmp.set(1.0f), 1.0f - config.shadowStrength);
    }

    private void updateEyeSkyLight(ClientLevel level, Camera camera, boolean hasSky) {
        float target = 0.0f;

        if (hasSky) {
            BlockPos pos = camera.blockPosition();
            target = level.getBrightness(LightLayer.SKY, pos) / 15.0f;
        }

        long now = System.nanoTime();
        float dt = this.lastUpdateNanos == 0 ? 1.0f : Math.min((now - this.lastUpdateNanos) / 1.0e9f, 1.0f);
        this.lastUpdateNanos = now;

        // Roughly half a second to adapt when walking in or out of cover
        this.eyeSkyLight += (target - this.eyeSkyLight) * Math.min(1.0f, dt * 4.0f);
    }

    /**
     * Builds an orthographic shadow matrix for positions relative to the given camera position.
     * The volume is snapped to whole texels in light space, so shadow edges stay put while the camera moves.
     */
    public void buildShadowMatrix(Matrix4f dest, float halfExtent, int resolution, double camX, double camY, double camZ) {
        float texel = 2.0f * halfExtent / resolution;

        double lx = this.rotation.m00() * camX + this.rotation.m10() * camY + this.rotation.m20() * camZ;
        double ly = this.rotation.m01() * camX + this.rotation.m11() * camY + this.rotation.m21() * camZ;
        float fracX = (float) (lx - Math.floor(lx / texel) * texel);
        float fracY = (float) (ly - Math.floor(ly / texel) * texel);

        dest.setOrtho(-halfExtent, halfExtent, -halfExtent, halfExtent, -DEPTH_RANGE, DEPTH_RANGE, true)
            .translate(fracX, fracY, 0.0f)
            .mul(this.rotation);
    }

    /** World to light space, rotation only. */
    public Matrix4f rotation() {
        return this.rotation;
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
