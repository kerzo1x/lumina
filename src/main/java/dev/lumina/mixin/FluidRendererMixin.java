package dev.lumina.mixin;

import dev.lumina.render.BlockFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags fluid vertices with their {@link BlockFlags}, which is how the terrain shader recognises water. */
@Mixin(value = FluidRenderer.class, remap = false)
public abstract class FluidRendererMixin {
    @Unique private int lumina$classified;

    @Inject(method = "renderLiquid", at = @At("HEAD"))
    private void lumina$classifyFluid(BlockState blockState, FluidState fluidState, BlockPos blockPos, CallbackInfo ci) {
        this.lumina$classified = BlockFlags.classify(fluidState);
    }

    @Redirect(method = "putQuad",
              at = @At(value = "INVOKE", target = "Lnet/vulkanmod/render/vertex/TerrainBufferBuilder;vertex(FFFIFFII)V"))
    private void lumina$flagVertex(TerrainBufferBuilder builder, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
        builder.vertex(x, y, z, color, u, v, BlockFlags.apply(light, this.lumina$classified, 0.0f), packedNormal);
    }
}
