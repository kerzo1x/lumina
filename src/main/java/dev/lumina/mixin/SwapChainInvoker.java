package dev.lumina.mixin;

import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SwapChain.class, remap = false)
public interface SwapChainInvoker {
    @Invoker("createDepthResources")
    void lumina$createDepthResources();
}
