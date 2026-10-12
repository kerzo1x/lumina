package dev.lumina.mixin;

import dev.lumina.render.LuminaRenderer;
import net.minecraft.core.Direction;
import net.vulkanmod.render.chunk.build.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderRegion.class, remap = false)
public abstract class RenderRegionMixin {

    /**
     * Vanilla bakes a fixed brightness per face direction into terrain vertex colours. The terrain shader computes
     * that term itself so it can fade it out where real sunlight applies, hence meshes are built without it.
     */
    @Inject(method = "getShade", at = @At("HEAD"), cancellable = true)
    private void lumina$flatShade(Direction direction, boolean shade, CallbackInfoReturnable<Float> cir) {
        if (LuminaRenderer.isActive())
            cir.setReturnValue(1.0f);
    }
}
