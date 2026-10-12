package dev.lumina.mixin;

import dev.lumina.render.LuminaRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void lumina$beforeSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ,
                                           Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        LuminaRenderer.onTerrainLayer(renderType, camX, camY, camZ, modelView, projection);
    }

    @Inject(method = "renderSectionLayer", at = @At("TAIL"))
    private void lumina$afterSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ,
                                          Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        LuminaRenderer.afterTerrainLayer();
    }
}
