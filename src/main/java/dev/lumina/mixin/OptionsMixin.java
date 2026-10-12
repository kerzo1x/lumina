package dev.lumina.mixin;

import dev.lumina.render.LuminaRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class OptionsMixin {

    /**
     * The mod's sky paints its own clouds, so the game's cloud layer has to stay away while it does. The setting
     * itself is left untouched: the game's clouds are back as soon as the mod's sky is switched off.
     */
    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void lumina$hideGameClouds(CallbackInfoReturnable<CloudStatus> cir) {
        if (LuminaRenderer.ownsClouds())
            cir.setReturnValue(CloudStatus.OFF);
    }
}
