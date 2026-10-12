package dev.lumina.util;

import dev.lumina.Lumina;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Arrays;

/**
 * Keeps frame rate up in crowds by rendering only the nearest creatures.
 *
 * There is a budget of how many creatures may be drawn. While no more than that are within {@link #COUNT_RADIUS}
 * blocks, nothing is hidden at any distance. Once there are more, the render distance for entities shrinks to
 * where the budget runs out, so a packed spawn area shows the people next to the player and drops everyone behind them.
 */
public final class CrowdControl {
    private static final double COUNT_RADIUS = 48.0;
    /** Nobody closer than this is ever hidden, however large the crowd. */
    private static final double MIN_DISTANCE = 10.0;
    private static final int UPDATE_INTERVAL_TICKS = 5;

    private static volatile double maxDistanceSqr = Double.MAX_VALUE;
    private static double[] distances = new double[256];
    private static double currentDistance = Double.MAX_VALUE;
    private static int ticks;
    private static int lastReported = -1;

    private CrowdControl() {
    }

    /** Squared distance beyond which entities are not rendered, MAX_VALUE when there is no limit. */
    public static double maxDistanceSqr() {
        return maxDistanceSqr;
    }

    public static void tick(Minecraft minecraft) {
        if (++ticks % UPDATE_INTERVAL_TICKS != 0)
            return;

        Entity camera = minecraft.getCameraEntity();

        if (minecraft.level == null || camera == null || !Lumina.config().crowdOptimization) {
            currentDistance = Double.MAX_VALUE;
            maxDistanceSqr = Double.MAX_VALUE;
            return;
        }

        int budget = Lumina.config().crowdBudget;
        int count = 0;

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity) || entity == camera)
                continue;

            double distanceSqr = entity.distanceToSqr(camera);
            if (distanceSqr > COUNT_RADIUS * COUNT_RADIUS)
                continue;

            if (count == distances.length)
                distances = Arrays.copyOf(distances, count * 2);

            distances[count++] = distanceSqr;
        }

        double target = Double.MAX_VALUE;

        if (count > budget) {
            Arrays.sort(distances, 0, count);
            target = Math.max(MIN_DISTANCE, Math.sqrt(distances[budget]));
        }

        // Tighten at once when a crowd appears, relax gradually so people at the edge do not flicker in and out
        if (target < currentDistance)
            currentDistance = target;
        else if (target == Double.MAX_VALUE)
            currentDistance = currentDistance > COUNT_RADIUS ? Double.MAX_VALUE : currentDistance * 1.15 + 1.0;
        else
            currentDistance = Math.min(target, currentDistance * 1.05 + 0.5);

        maxDistanceSqr = currentDistance == Double.MAX_VALUE ? Double.MAX_VALUE : currentDistance * currentDistance;

        int reported = currentDistance == Double.MAX_VALUE ? 0 : (int) (currentDistance / 8.0) + 1;
        if (reported != lastReported) {
            lastReported = reported;
            LuminaLog.debug("[crowd] %d creatures within %.0f blocks, budget %d, render distance for entities: %s", count, COUNT_RADIUS, budget,
                            currentDistance == Double.MAX_VALUE ? "unlimited" : "%.0f blocks".formatted(currentDistance));
        }
    }
}
