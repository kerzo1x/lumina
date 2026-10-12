package dev.lumina.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.lumina.render.LightState;
import dev.lumina.render.LuminaRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's sky for the mod's. The game draws its overworld sky in steps (dome, sunrise glow, then sun,
 * moon and stars); the first two are skipped with their colours kept, and the whole sky is drawn at the third.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void lumina$skyDisc(int skyColor, CallbackInfo ci) {
        if (LuminaRenderer.ownsSky()) {
            LuminaRenderer.sky().setSkyColor(skyColor);
            ci.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void lumina$sunrise(PoseStack poseStack, float sunAngle, int color, CallbackInfo ci) {
        if (LuminaRenderer.ownsSky()) {
            LuminaRenderer.sky().setSunsetColor(color);
            ci.cancel();
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void lumina$sky(PoseStack poseStack, float sunAngle, float moonAngle, float starAngle, MoonPhase moonPhase,
                            float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (LuminaRenderer.ownsSky()) {
            LuminaRenderer.renderOverworldSky(sunAngle, moonAngle, starAngle, moonPhase.index(), rainBrightness, starBrightness);
            ci.cancel();
        }
    }

    /** The disc that blacks out the void below the horizon: the mod's sky already ends in the fog colour there. */
    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void lumina$darkDisc(CallbackInfo ci) {
        if (LuminaRenderer.ownsSky())
            ci.cancel();
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
    private void lumina$endSky(CallbackInfo ci) {
        if (LuminaRenderer.ownsEndSky()) {
            LuminaRenderer.renderEndSky();
            ci.cancel();
        }
    }

    /**
     * With the game's own sky left in place: tilts the path of the sun, moon and stars so the sun is never straight
     * overhead and shadows always have a direction. {@link LightState} uses the same tilt, which keeps shadows
     * pointing away from the visible sun.
     */
    @Inject(method = "renderSunMoonAndStars",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V",
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void lumina$tiltSky(PoseStack poseStack, float sunAngle, float moonAngle, float starAngle, MoonPhase moonPhase,
                                float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (LuminaRenderer.isActive())
            poseStack.mulPose(Axis.ZP.rotationDegrees(-LightState.SUN_TILT));
    }
}
