package dev.lumina.mixin;

import dev.lumina.util.LuminaLog;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod frees the swap chain's depth image at the start of every swap chain rebuild and only makes a new one
 * once the window has a size again. If the main target is bound in between (minimised window, a fullscreen switch
 * that fails half way, a rebuild that ran out of memory), beginning the pass dereferences the missing image and the
 * game crashes with "Framebuffer.getDepthAttachment() is null". The missing image is created again before binding.
 */
@Mixin(value = DefaultMainPass.class, remap = false)
public abstract class DefaultMainPassMixin {
    @Shadow private Framebuffer mainFramebuffer;

    @Shadow public abstract void onResize();

    @Inject(method = "begin", at = @At("HEAD"))
    private void lumina$healBeforeBegin(VkCommandBuffer commandBuffer, MemoryStack stack, CallbackInfo ci) {
        this.lumina$healDepth("begin");
    }

    @Inject(method = "rebindMainTarget", at = @At("HEAD"))
    private void lumina$healBeforeRebind(CallbackInfo ci) {
        this.lumina$healDepth("rebindMainTarget");
    }

    @Unique
    private void lumina$healDepth(String where) {
        Framebuffer framebuffer = this.mainFramebuffer;

        if (!(framebuffer instanceof SwapChain swapChain) || swapChain.getDepthAttachment() != null)
            return;

        try {
            if (swapChain.hasImages() && swapChain.getWidth() > 0 && swapChain.getHeight() > 0) {
                ((SwapChainInvoker) swapChain).lumina$createDepthResources();
                LuminaLog.warn("Swap chain had no depth image in %s (%dx%d), created it again", where, swapChain.getWidth(), swapChain.getHeight());
            } else {
                // No images at all (minimised): switch to VulkanMod's placeholder target, as a finished rebuild would
                this.onResize();
                LuminaLog.warn("Swap chain had no images in %s, switched to the placeholder target", where);
            }
        } catch (Throwable t) {
            LuminaLog.errorLimited("healDepth", "Could not restore the swap chain depth image", t);
        }
    }
}
