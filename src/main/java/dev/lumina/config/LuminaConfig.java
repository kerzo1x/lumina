package dev.lumina.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lumina.util.LuminaLog;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything the settings screen can change. Stored as config/lumina.json; a field missing from an older file
 * keeps the default written here.
 */
public class LuminaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path path;

    public boolean enabled = true;

    // Shadows
    public boolean shadows = true;
    public int shadowResolution = 2048;
    public int shadowDistance = 96;
    public float shadowStrength = 0.65f;
    public float shadowSoftness = 1.0f;
    /** Players and mobs cast shadows. */
    public boolean entityShadows = true;
    /** Players, mobs, block entities and the hand are lit and shadowed like the terrain. */
    public boolean entityShading = true;
    /** Small shadows traced on screen for things the shadow map is too coarse for. */
    public boolean contactShadows = true;
    public float contactShadowStrength = 0.5f;
    /** Darkening of corners and crevices, traced on screen. */
    public boolean ambientOcclusion = true;
    public float ambientOcclusionStrength = 0.5f;

    // Light and colour
    public float sunBrightness = 1.0f;
    public float ambientBrightness = 1.0f;
    public float warmth = 0.5f;
    public float saturation = 1.12f;
    public float contrast = 1.06f;
    public float brightness = 1.0f;
    public float vignette = 0.2f;
    public float sharpen = 0.25f;

    // Effects
    public boolean bloom = true;
    public float bloomStrength = 0.25f;
    public float godrays = 0.3f;
    public boolean wavingPlants = true;
    /** Lava glows on its own, ignores sun and shadow, and churns slowly. */
    public boolean lava = true;

    // Water and reflections
    public boolean water = true;
    /** Height of the ripples on water, 1 = default. */
    public float waterWaves = 1.0f;
    /** Reflections on ice and stained glass. */
    public boolean reflections = true;
    /** How much of the ground turns into reflecting puddles in the rain, 0 = none. */
    public float rainPuddles = 0.6f;

    // Sky
    /** The mod's own sky in the overworld: atmosphere, round sun, moon with phases, stars. */
    public boolean customSky = true;
    /** The mod's own sky in the End. */
    public boolean endSky = true;
    /** Soft clouds painted into the mod's sky instead of the game's flat ones. */
    public boolean clouds = true;
    public float cloudCoverage = 0.5f;
    /** Brightness of the stars, 1 = default. */
    public float stars = 1.0f;

    // Performance
    /** Hide distant creatures when a lot of them are around, see CrowdControl. */
    public boolean crowdOptimization = true;
    /** How many of the nearest creatures are still drawn once there are more than that around. */
    public int crowdBudget = 30;

    public boolean debugLog = true;

    public static LuminaConfig load(Path path) {
        LuminaConfig config = null;

        if (Files.exists(path)) {
            try {
                config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), LuminaConfig.class);
            } catch (Exception e) {
                LuminaLog.error("Config %s is unreadable, falling back to defaults".formatted(path), e);
            }
        }

        if (config == null)
            config = new LuminaConfig();

        config.path = path;
        config.sanitize();
        config.save();
        return config;
    }

    public void sanitize() {
        if (shadowResolution != 1024 && shadowResolution != 2048 && shadowResolution != 4096)
            shadowResolution = 2048;

        shadowDistance = clamp(shadowDistance, 48, 192);
        shadowStrength = clamp(shadowStrength, 0.0f, 1.0f);
        shadowSoftness = clamp(shadowSoftness, 0.0f, 3.0f);
        contactShadowStrength = clamp(contactShadowStrength, 0.0f, 1.0f);
        ambientOcclusionStrength = clamp(ambientOcclusionStrength, 0.0f, 1.0f);
        sunBrightness = clamp(sunBrightness, 0.5f, 1.5f);
        ambientBrightness = clamp(ambientBrightness, 0.5f, 1.5f);
        warmth = clamp(warmth, 0.0f, 1.0f);
        saturation = clamp(saturation, 0.0f, 2.0f);
        contrast = clamp(contrast, 0.5f, 1.5f);
        brightness = clamp(brightness, 0.5f, 1.5f);
        vignette = clamp(vignette, 0.0f, 1.0f);
        sharpen = clamp(sharpen, 0.0f, 1.0f);
        bloomStrength = clamp(bloomStrength, 0.0f, 1.0f);
        godrays = clamp(godrays, 0.0f, 1.0f);
        waterWaves = clamp(waterWaves, 0.0f, 2.0f);
        rainPuddles = clamp(rainPuddles, 0.0f, 1.0f);
        cloudCoverage = clamp(cloudCoverage, 0.0f, 1.0f);
        stars = clamp(stars, 0.0f, 2.0f);
        crowdBudget = clamp(crowdBudget, 10, 120);
    }

    /** Puts every setting back to its default, except whether the shaders are on at all. */
    public void resetToDefaults() {
        LuminaConfig defaults = new LuminaConfig();

        try {
            for (Field field : LuminaConfig.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("enabled") || field.getName().equals("debugLog"))
                    continue;

                field.set(this, field.get(defaults));
            }
        } catch (IllegalAccessException e) {
            LuminaLog.error("Unable to reset the config", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LuminaLog.error("Unable to save config to %s".formatted(path), e);
        }
    }

    public String describe() {
        return GSON.toJson(this).replaceAll("\\s+", " ");
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
