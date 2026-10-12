package dev.lumina.mixin;

import dev.lumina.render.LuminaRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void lumina$everyFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        LuminaRenderer.ensureEntityTextures();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void lumina$beginFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
        LuminaRenderer.beginFrame();
    }

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                     shift = At.Shift.AFTER))
    private void lumina$afterWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        LuminaRenderer.afterWorld();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void lumina$finishFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
        LuminaRenderer.finishFrame();
    }
}
