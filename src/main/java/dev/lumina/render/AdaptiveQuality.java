package dev.lumina.render;

import dev.lumina.config.LuminaConfig;
import dev.lumina.util.LuminaLog;
import net.minecraft.client.Minecraft;

/**
 * Holds the frame rate by trading away the parts of the effects that cost the most and show the least.
 *
 * The time between frames is smoothed over about half a second. While it stays above the target, the level goes up
 * one step at a time (level 0 is full quality); once it has been below the target for a few seconds, it comes back
 * down. Single slow frames (server lag, chunk loading) are capped before they enter the average, so they cannot
 * push the level up on their own. If going back up makes frames slow again right away, the next attempt waits
 * twice as long, up to a minute, instead of switching back and forth. The target never exceeds the frame rate limit or, with vsync, the refresh rate: frames that wait on those
 * are not slow.
 */
public final class AdaptiveQuality {
    public static final int MAX_LEVEL = 3;

    private static final long DOWNGRADE_COOLDOWN_NANOS = 750_000_000L;
    private static final long UPGRADE_DELAY_NANOS = 3_000_000_000L;
    private static final long MAX_UPGRADE_DELAY_NANOS = 60_000_000_000L;
    /** A downgrade this soon after an upgrade means the upgrade did not fit. */
    private static final long FAILED_UPGRADE_NANOS = 10_000_000_000L;
    /** Frames longer than this are pauses (loading, a dragged window), not a measure of the rendering cost. */
    private static final double MAX_SAMPLE_MS = 100.0;

    // Index by level
    private static final int[] NEAR_SHADOW_HZ = {90, 72, 60, 45};
    private static final float[] FAR_SHADOW_SECONDS = {1.5f, 2.0f, 3.0f, 4.0f};
    private static final int[] REFLECTION_STEPS = {20, 17, 14, 11};
    private static final int[] AO_SAMPLES = {8, 6, 5, 4};
    private static final int[] CONTACT_STEPS = {8, 7, 6, 5};
    private static final int[] GODRAY_STEPS = {24, 20, 16, 12};

    private int level;
    private long lastFrameNanos;
    private double smoothedMs = -1.0;
    private long lastChangeNanos;
    private long fastSinceNanos;
    private long upgradeDelayNanos = UPGRADE_DELAY_NANOS;
    private long lastUpgradeNanos;

    public void update(LuminaConfig config) {
        long now = System.nanoTime();
        long previous = this.lastFrameNanos;
        this.lastFrameNanos = now;

        if (!config.adaptiveQuality) {
            setLevel(0, "adaptive quality off");
            this.upgradeDelayNanos = UPGRADE_DELAY_NANOS;
            this.smoothedMs = -1.0;
            return;
        }

        if (previous == 0)
            return;

        double frameMs = (now - previous) / 1.0e6;
        if (frameMs > MAX_SAMPLE_MS)
            return;

        double targetMs = 1000.0 / effectiveTargetFps(config);
        frameMs = Math.min(frameMs, targetMs * 2.0);

        this.smoothedMs = this.smoothedMs < 0.0 ? frameMs : this.smoothedMs + (frameMs - this.smoothedMs) * 0.03;

        if (this.smoothedMs > targetMs * 1.08) {
            this.fastSinceNanos = 0;

            if (this.level < MAX_LEVEL && now - this.lastChangeNanos > DOWNGRADE_COOLDOWN_NANOS) {
                if (this.lastUpgradeNanos != 0 && now - this.lastUpgradeNanos < FAILED_UPGRADE_NANOS)
                    this.upgradeDelayNanos = Math.min(this.upgradeDelayNanos * 2, MAX_UPGRADE_DELAY_NANOS);

                this.lastUpgradeNanos = 0;
                setLevel(this.level + 1, "%.2f ms per frame, target %.2f ms".formatted(this.smoothedMs, targetMs));
            }
        } else if (this.smoothedMs < targetMs * 0.97) {
            if (this.fastSinceNanos == 0)
                this.fastSinceNanos = now;

            if (this.level > 0 && now - this.fastSinceNanos > this.upgradeDelayNanos && now - this.lastChangeNanos > this.upgradeDelayNanos) {
                setLevel(this.level - 1, "%.2f ms per frame, target %.2f ms".formatted(this.smoothedMs, targetMs));
                this.fastSinceNanos = now;
                this.lastUpgradeNanos = now;
            }
        } else {
            this.fastSinceNanos = 0;
        }
    }

    private static int effectiveTargetFps(LuminaConfig config) {
        int target = config.adaptiveTargetFps;

        try {
            Minecraft minecraft = Minecraft.getInstance();
            int limit = minecraft.options.framerateLimit().get();

            // 260 is the slider's "unlimited"
            if (limit > 0 && limit < 260)
                target = Math.min(target, limit);

            if (minecraft.options.enableVsync().get()) {
                int refresh = minecraft.getWindow().getRefreshRate();
                if (refresh > 0)
                    target = Math.min(target, refresh);
            }
        } catch (Throwable ignored) {
            // Options not available yet: keep the configured target
        }

        return Math.max(30, target);
    }

    private void setLevel(int level, String reason) {
        if (level == this.level)
            return;

        LuminaLog.debug("[quality] level %d -> %d (%s)", this.level, level, reason);
        this.level = level;
        this.lastChangeNanos = System.nanoTime();
    }

    public int level() {
        return this.level;
    }

    public long nearShadowIntervalNanos() {
        return 1_000_000_000L / NEAR_SHADOW_HZ[this.level];
    }

    public long farShadowIntervalNanos() {
        return (long) (FAR_SHADOW_SECONDS[this.level] * 1.0e9);
    }

    public int reflectionSteps() {
        return REFLECTION_STEPS[this.level];
    }

    public int occlusionSamples() {
        return AO_SAMPLES[this.level];
    }

    public int contactSteps() {
        return CONTACT_STEPS[this.level];
    }

    public int godraySteps() {
        return GODRAY_STEPS[this.level];
    }

    public String describe() {
        return "level=%d frame=%.2fms".formatted(this.level, Math.max(this.smoothedMs, 0.0));
    }
}
