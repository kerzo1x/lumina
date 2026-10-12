package dev.lumina.mixin;

import dev.lumina.util.CrowdControl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void lumina$crowdLimit(double distanceSqr, CallbackInfoReturnable<Boolean> cir) {
        if (distanceSqr > CrowdControl.maxDistanceSqr())
            cir.setReturnValue(false);
    }
}
