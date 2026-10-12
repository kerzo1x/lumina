package dev.lumina.mixin;

import dev.lumina.render.BlockFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.renderer.BlockRenderer;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags every block vertex with its {@link BlockFlags}. One renderer instance belongs to one meshing thread. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Unique private int lumina$classified;
    @Unique private float lumina$blockBottom;

    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void lumina$classifyBlock(BlockState blockState, BlockPos blockPos, Vector3f pos, CallbackInfo ci) {
        this.lumina$classified = BlockFlags.classify(blockState);
        this.lumina$blockBottom = pos.y();
    }

    @Redirect(method = "bufferQuad",
              at = @At(value = "INVOKE", target = "Lnet/vulkanmod/render/vertex/TerrainBufferBuilder;vertex(FFFIFFII)V"))
    private void lumina$flagVertex(TerrainBufferBuilder builder, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
        builder.vertex(x, y, z, color, u, v, BlockFlags.apply(light, this.lumina$classified, y - this.lumina$blockBottom), packedNormal);
    }
}
