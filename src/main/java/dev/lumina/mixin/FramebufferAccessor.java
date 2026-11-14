package dev.lumina.mixin;

import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Framebuffer.class, remap = false)
public interface FramebufferAccessor {
    /**
     * Pipelines compiled for a pass declare the framebuffer's depth format even when it has no depth image.
     * Setting it to VK_FORMAT_UNDEFINED is what lets a pass run without one.
     */
    @Accessor("depthFormat")
    void lumina$setDepthFormat(int format);
}
