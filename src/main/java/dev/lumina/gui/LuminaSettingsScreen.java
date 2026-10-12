package dev.lumina.gui;

import dev.lumina.Lumina;
import dev.lumina.config.LuminaConfig;
import dev.lumina.render.LuminaRenderer;
import dev.lumina.util.LuminaLog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * All values apply while the screen is open: the world stays visible behind it, so the effect of a slider is seen at once.
 */
public class LuminaSettingsScreen extends Screen {
    private static final int WIDGET_WIDTH = 150;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int COLUMN_GAP = 10;
    private static final int[] SHADOW_RESOLUTIONS = {1024, 2048, 4096};

    private enum Tab {
        GENERAL, SHADOWS, COLOR, WATER, SKY, PERFORMANCE
    }

    /** Kept between openings, so adjusting one thing does not mean finding its tab again every time. */
    private static Tab lastTab = Tab.GENERAL;

    private final Screen parent;
    private Tab tab = lastTab;

    // Widgets of the current tab are laid out left to right, top to bottom
    private int nextSlot;
    private int contentTop;

    public LuminaSettingsScreen(Screen parent) {
        super(Component.translatable("lumina.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LuminaConfig config = Lumina.config();

        Tab[] tabs = Tab.values();
        int tabWidth = Math.min(92, (this.width - 16) / tabs.length);
        int tabsLeft = (this.width - tabWidth * tabs.length) / 2;

        for (int i = 0; i < tabs.length; ++i) {
            Tab target = tabs[i];
            Button button = Button.builder(Component.translatable("lumina.options.tab." + target.name().toLowerCase()), b -> {
                this.tab = target;
                lastTab = target;
                this.rebuildWidgets();
            }).bounds(tabsLeft + i * tabWidth, 28, tabWidth - 2, WIDGET_HEIGHT).build();

            button.active = target != this.tab;
            this.addRenderableWidget(button);
        }

        this.nextSlot = 0;
        this.contentTop = 58;

        switch (this.tab) {
            case GENERAL -> {
                toggle("enabled", () -> config.enabled, value -> {
                    LuminaRenderer.resetFailure();
                    config.enabled = value;
                });
                toggle("entity_shading", () -> config.entityShading, value -> config.entityShading = value);
                toggle("waving", () -> config.wavingPlants, value -> config.wavingPlants = value);
                toggle("lava", () -> config.lava, value -> config.lava = value);
                toggle("bloom", () -> config.bloom, value -> config.bloom = value);
                slider("bloom_strength", 0, 1, 0.05, true, () -> config.bloomStrength, value -> config.bloomStrength = (float) value);
                slider("godrays", 0, 1, 0.05, true, () -> config.godrays, value -> config.godrays = (float) value);
            }
            case SHADOWS -> {
                toggle("shadows", () -> config.shadows, value -> config.shadows = value);
                this.addRenderableWidget(Button.builder(resolutionLabel(config), button -> {
                    int index = 0;
                    for (int i = 0; i < SHADOW_RESOLUTIONS.length; ++i) {
                        if (SHADOW_RESOLUTIONS[i] == config.shadowResolution)
                            index = i;
                    }

                    config.shadowResolution = SHADOW_RESOLUTIONS[(index + 1) % SHADOW_RESOLUTIONS.length];
                    button.setMessage(resolutionLabel(config));
                }).bounds(slotX(), slotY(), WIDGET_WIDTH, WIDGET_HEIGHT).build());
                this.nextSlot++;

                slider("shadow_distance", 48, 192, 16, false, () -> config.shadowDistance, value -> config.shadowDistance = (int) value);
                slider("shadow_strength", 0, 1, 0.05, true, () -> config.shadowStrength, value -> config.shadowStrength = (float) value);
                slider("shadow_softness", 0, 3, 0.25, false, () -> config.shadowSoftness, value -> config.shadowSoftness = (float) value);
                toggle("entity_shadows", () -> config.entityShadows, value -> config.entityShadows = value);
                toggle("contact_shadows", () -> config.contactShadows, value -> config.contactShadows = value);
                slider("contact_shadow_strength", 0, 1, 0.05, true, () -> config.contactShadowStrength, value -> config.contactShadowStrength = (float) value);
                toggle("ambient_occlusion", () -> config.ambientOcclusion, value -> config.ambientOcclusion = value);
                slider("ambient_occlusion_strength", 0, 1, 0.05, true, () -> config.ambientOcclusionStrength, value -> config.ambientOcclusionStrength = (float) value);
            }
            case COLOR -> {
                slider("sun_brightness", 0.5, 1.5, 0.05, true, () -> config.sunBrightness, value -> config.sunBrightness = (float) value);
                slider("ambient_brightness", 0.5, 1.5, 0.05, true, () -> config.ambientBrightness, value -> config.ambientBrightness = (float) value);
                slider("warmth", 0, 1, 0.05, true, () -> config.warmth, value -> config.warmth = (float) value);
                slider("saturation", 0, 2, 0.02, true, () -> config.saturation, value -> config.saturation = (float) value);
                slider("contrast", 0.5, 1.5, 0.02, true, () -> config.contrast, value -> config.contrast = (float) value);
                slider("brightness", 0.5, 1.5, 0.02, true, () -> config.brightness, value -> config.brightness = (float) value);
                slider("vignette", 0, 1, 0.05, true, () -> config.vignette, value -> config.vignette = (float) value);
                slider("sharpen", 0, 1, 0.05, true, () -> config.sharpen, value -> config.sharpen = (float) value);
            }
            case WATER -> {
                toggle("water", () -> config.water, value -> config.water = value);
                slider("water_waves", 0, 2, 0.1, true, () -> config.waterWaves, value -> config.waterWaves = (float) value);
                toggle("reflections", () -> config.reflections, value -> config.reflections = value);
                slider("rain_puddles", 0, 1, 0.05, true, () -> config.rainPuddles, value -> config.rainPuddles = (float) value);
            }
            case SKY -> {
                toggle("custom_sky", () -> config.customSky, value -> config.customSky = value);
                toggle("end_sky", () -> config.endSky, value -> config.endSky = value);
                toggle("clouds", () -> config.clouds, value -> config.clouds = value);
                slider("cloud_coverage", 0, 1, 0.05, true, () -> config.cloudCoverage, value -> config.cloudCoverage = (float) value);
                slider("stars", 0, 2, 0.1, true, () -> config.stars, value -> config.stars = (float) value);
            }
            case PERFORMANCE -> {
                toggle("crowd", () -> config.crowdOptimization, value -> config.crowdOptimization = value);
                slider("crowd_budget", 10, 120, 5, false, () -> config.crowdBudget, value -> config.crowdBudget = (int) value);
            }
        }

        int bottom = this.height - 28;
        int left = this.width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("lumina.options.reset"), button -> {
            config.resetToDefaults();
            this.rebuildWidgets();
        }).bounds(left, bottom, WIDGET_WIDTH, WIDGET_HEIGHT).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                                       .bounds(left + WIDGET_WIDTH + COLUMN_GAP, bottom, WIDGET_WIDTH, WIDGET_HEIGHT).build());
    }

    private int slotX() {
        int left = this.width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;
        return left + (this.nextSlot % 2) * (WIDGET_WIDTH + COLUMN_GAP);
    }

    private int slotY() {
        return this.contentTop + (this.nextSlot / 2) * ROW_HEIGHT;
    }

    private static Component resolutionLabel(LuminaConfig config) {
        return Component.translatable("lumina.options.shadow_resolution", config.shadowResolution);
    }

    private void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        String fullKey = "lumina.options." + key;

        this.addRenderableWidget(Button.builder(toggleLabel(fullKey, getter.getAsBoolean()), button -> {
            setter.accept(!getter.getAsBoolean());
            button.setMessage(toggleLabel(fullKey, getter.getAsBoolean()));
        }).bounds(slotX(), slotY(), WIDGET_WIDTH, WIDGET_HEIGHT).build());

        this.nextSlot++;
    }

    private static Component toggleLabel(String key, boolean value) {
        return Component.translatable(key).append(": ").append(value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    private void slider(String key, double min, double max, double step, boolean percent, DoubleSupplier getter, DoubleConsumer setter) {
        this.addRenderableWidget(new ConfigSlider(slotX(), slotY(), "lumina.options." + key, min, max, step, percent, getter.getAsDouble(), setter));
        this.nextSlot++;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        if (LuminaRenderer.hasFailed())
            graphics.drawCenteredString(this.font, Component.translatable("lumina.options.failed"), this.width / 2, this.height - 42, 0xFFFF5555);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur and no dimming in a world: the point of the screen is to watch the picture change
        if (this.minecraft == null || this.minecraft.level == null)
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        LuminaConfig config = Lumina.config();
        config.sanitize();
        config.save();
        LuminaLog.info("Settings saved: %s", config.describe());

        if (this.minecraft != null)
            this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class ConfigSlider extends AbstractSliderButton {
        private final String key;
        private final double min;
        private final double max;
        private final double step;
        private final boolean percent;
        private final DoubleConsumer setter;

        ConfigSlider(int x, int y, String key, double min, double max, double step, boolean percent, double initial, DoubleConsumer setter) {
            super(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(), (initial - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.percent = percent;
            this.setter = setter;
            this.updateMessage();
        }

        private double actual() {
            double raw = this.min + this.value * (this.max - this.min);
            return Math.round(raw / this.step) * this.step;
        }

        @Override
        protected void updateMessage() {
            double actual = actual();
            String text = this.percent ? Math.round(actual * 100) + "%" : (this.step >= 1 ? String.valueOf((int) actual) : String.format("%.2f", actual));
            this.setMessage(Component.translatable(this.key).append(": " + text));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(actual());
        }
    }
}
