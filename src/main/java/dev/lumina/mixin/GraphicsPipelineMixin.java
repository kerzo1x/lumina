package dev.lumina.mixin;

import dev.lumina.render.LuminaUniforms;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineMixin {

    /**
     * VulkanMod fills the uniform blocks of converted vanilla shaders from buffers the game hands it. The block
     * the mod adds to the entity shader has no such buffer, so its fields are pointed at the mod's own values here.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lumina$bindEntityUniforms(CallbackInfo ci) {
        LuminaUniforms.attachEntityBlock((Pipeline) (Object) this);
    }
}
