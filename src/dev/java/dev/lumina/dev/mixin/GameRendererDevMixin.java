package dev.lumina.dev.mixin;

import dev.lumina.dev.DevScript;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererDevMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void luminaDev$frameDone(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        DevScript.onFrameDone();
    }
}
