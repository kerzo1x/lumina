package dev.lumina;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lumina.config.LuminaConfig;
import dev.lumina.gui.LuminaSettingsScreen;
import dev.lumina.render.LuminaRenderer;
import dev.lumina.util.CrowdControl;
import dev.lumina.util.LuminaLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class Lumina implements ClientModInitializer {
    public static final String MOD_ID = "lumina";

    private static LuminaConfig config;

    private KeyMapping toggleKey;
    private KeyMapping settingsKey;

    public static LuminaConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();

        LuminaLog.init(loader.getGameDir().resolve("logs"));
        config = LuminaConfig.load(loader.getConfigDir().resolve("lumina.json"));
        LuminaLog.setDebug(config.debugLog);

        String version = loader.getModContainer(MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String vulkanMod = loader.getModContainer("vulkanmod").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("missing");
        LuminaLog.info("Lumina %s, VulkanMod %s, Java %s, max heap %d MB", version, vulkanMod,
                       System.getProperty("java.version"), Runtime.getRuntime().maxMemory() >> 20);
        LuminaLog.info("Config: %s", config.describe());

        Thread.setDefaultUncaughtExceptionHandler(wrapExceptionHandler(Thread.getDefaultUncaughtExceptionHandler()));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keys"));
        this.toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.lumina.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category));
        this.settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.lumina.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LuminaRenderer.shutdown());
    }

    private void onTick(Minecraft minecraft) {
        LuminaRenderer.tick();
        CrowdControl.tick(minecraft);

        while (this.toggleKey.consumeClick()) {
            // Pressing the key after a failure is an explicit request to try again
            LuminaRenderer.resetFailure();
            config.enabled = !config.enabled;
            config.save();

            LuminaLog.info("Shaders toggled %s by key", config.enabled ? "on" : "off");

            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(config.enabled ? "lumina.message.enabled" : "lumina.message.disabled"), true);
            }
        }

        while (this.settingsKey.consumeClick()) {
            if (minecraft.screen == null)
                minecraft.setScreen(new LuminaSettingsScreen(null));
        }
    }

    /** A crash on any thread leaves the renderer state in lumina.log next to the stack trace. */
    private static Thread.UncaughtExceptionHandler wrapExceptionHandler(Thread.UncaughtExceptionHandler previous) {
        return (thread, throwable) -> {
            try {
                LuminaLog.error("Uncaught exception on thread " + thread.getName(), throwable);
                LuminaLog.info("State at crash: %s", LuminaRenderer.describeState());
            } catch (Throwable ignored) {
            }

            if (previous != null)
                previous.uncaughtException(thread, throwable);
        };
    }
}
