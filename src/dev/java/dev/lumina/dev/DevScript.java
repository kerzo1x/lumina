package dev.lumina.dev;

import dev.lumina.Lumina;
import dev.lumina.config.LuminaConfig;
import dev.lumina.util.LuminaLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Development only, not part of the released jar. Plays back the script named by -Dlumina.dev.script in a
 * singleplayer world so rendering changes can be checked from screenshots without touching the keyboard.
 *
 * Script lines: {@code cmd <server command>}, {@code wait <ticks>}, {@code shot <name>} (world only), {@code shotui <name>} (with HUD), {@code hitboxes},
 * {@code set <config field> <value>}, {@code fps <label>}, {@code quit}.
 *
 * With -Dlumina.dev.newWorld=&lt;name&gt; the world is not expected to exist: it is generated from the title screen
 * (creative, peaceful, seed from -Dlumina.dev.seed or random) and opened again on later runs.
 */
public class DevScript implements ClientModInitializer {
    private final Deque<String> lines = new ArrayDeque<>();
    private int waitTicks = 100;
    private boolean started;
    private boolean worldRequested;
    private String pendingShot;
    private static String pendingFullShot;

    @Override
    public void onInitializeClient() {
        String script = System.getProperty("lumina.dev.script");
        if (script == null)
            return;

        try {
            for (String line : Files.readAllLines(Path.of(script))) {
                if (!line.isBlank() && !line.startsWith("#"))
                    this.lines.add(line.trim());
            }
        } catch (Exception e) {
            LuminaLog.error("Dev script unreadable: " + script, e);
            return;
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        // The frame is only complete once the world is drawn: during a tick the swapchain image holds just the clear colour
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
            if (this.pendingShot != null) {
                Minecraft minecraft = Minecraft.getInstance();
                Screenshot.grab(minecraft.gameDirectory, this.pendingShot, minecraft.getMainRenderTarget(), 1, message -> {
                });
                this.pendingShot = null;
            }
        });
    }

    private void tick(Minecraft minecraft) {
        if (!this.worldRequested && minecraft.level == null && minecraft.screen instanceof TitleScreen) {
            String world = System.getProperty("lumina.dev.newWorld");

            if (world != null) {
                this.worldRequested = true;
                openOrCreateWorld(minecraft, world);
                return;
            }
        }

        if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null)
            return;

        if (!this.started) {
            this.started = true;
            LuminaLog.info("[dev] script started, %d lines", this.lines.size());
        }

        if (this.waitTicks > 0) {
            this.waitTicks--;
            return;
        }

        while (this.waitTicks == 0 && !this.lines.isEmpty()) {
            String line = this.lines.poll();
            String[] parts = line.split(" ", 2);
            String arg = parts.length > 1 ? parts[1] : "";

            LuminaLog.info("[dev] %s", line);

            try {
                switch (parts[0]) {
                    case "cmd" -> {
                        MinecraftServer server = minecraft.getSingleplayerServer();
                        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), arg));
                    }
                    case "wait" -> this.waitTicks = Integer.parseInt(arg);
                    case "shot" -> {
                        this.pendingShot = arg + ".png";
                        this.waitTicks = 2;
                    }
                    case "shotui" -> {
                        pendingFullShot = arg + ".png";
                        this.waitTicks = 2;
                    }
                    case "hitboxes" -> minecraft.debugEntries.toggleStatus(DebugScreenEntries.ENTITY_HITBOXES);
                    case "set" -> setConfig(arg);
                    case "fps" -> LuminaLog.info("[dev] fps %s = %d", arg, minecraft.getFps());
                    case "quit" -> minecraft.stop();
                    default -> LuminaLog.warn("[dev] unknown script line: %s", line);
                }
            } catch (Exception e) {
                LuminaLog.error("[dev] script line failed: " + line, e);
            }
        }
    }

    private static void openOrCreateWorld(Minecraft minecraft, String name) {
        if (minecraft.getLevelSource().levelExists(name)) {
            minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.setScreen(new TitleScreen()));
            return;
        }

        String seed = System.getProperty("lumina.dev.seed");
        WorldOptions options = seed == null ? WorldOptions.defaultWithRandomSeed() : new WorldOptions(Long.parseLong(seed), true, false);
        WorldDataConfiguration data = WorldDataConfiguration.DEFAULT;
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                                   new GameRules(data.enabledFeatures()), data);

        LuminaLog.info("[dev] generating world '%s', seed %s", name, seed == null ? "random" : seed);
        minecraft.createWorldOpenFlows().createFreshLevel(name, settings, options, WorldPresets::createNormalWorldDimensions, minecraft.screen);
    }

    /** End of a whole frame, HUD included. Called from the dev mixin. */
    public static void onFrameDone() {
        if (pendingFullShot != null) {
            Minecraft minecraft = Minecraft.getInstance();
            Screenshot.grab(minecraft.gameDirectory, pendingFullShot, minecraft.getMainRenderTarget(), 1, message -> {
            });
            pendingFullShot = null;
        }
    }

    private static void setConfig(String arg) throws ReflectiveOperationException {
        String[] parts = arg.split(" ", 2);
        LuminaConfig config = Lumina.config();
        Field field = LuminaConfig.class.getField(parts[0]);

        if (field.getType() == boolean.class)
            field.setBoolean(config, Boolean.parseBoolean(parts[1]));
        else if (field.getType() == int.class)
            field.setInt(config, Integer.parseInt(parts[1]));
        else
            field.setFloat(config, Float.parseFloat(parts[1]));
    }
}
