package dev.lumina.render;

import dev.lumina.mixin.FramebufferAccessor;
import dev.lumina.util.LuminaLog;
import dev.lumina.util.ResourceTracker;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Off-screen colour target of the screen-space passes.
 *
 * It has no depth image: a full-screen pass never tests depth, and at 1440p an unused 32-bit depth buffer per
 * full-size target cost about 15 MB of video memory each. VulkanMod still declares the framebuffer's depth format
 * when it compiles a pipeline for the pass, so that format is set to VK_FORMAT_UNDEFINED to match.
 *
 * The framebuffer and pass objects are kept across resizes because VulkanMod caches compiled pipelines per pass
 * object: recreating the pass on every resize would leak one pipeline each time.
 */
final class RenderTarget {
    final String name;
    final Framebuffer framebuffer;
    final RenderPass renderPass;
    /** Same attachment, previous contents kept: for passes that blend into what is already there. */
    final RenderPass loadPass;

    RenderTarget(String name, int width, int height) {
        this(name, width, height, Framebuffer.DEFAULT_FORMAT, true);
    }

    RenderTarget(String name, int width, int height, int format, boolean linearFiltering) {
        this.name = name;
        this.framebuffer = new Framebuffer.Builder("Lumina " + name, Math.max(1, width), Math.max(1, height), 1, false)
                .setFormat(format)
                .setLinearFiltering(linearFiltering)
                .build();

        try {
            ((FramebufferAccessor) this.framebuffer).lumina$setDepthFormat(VK_FORMAT_UNDEFINED);
        } catch (Throwable t) {
            // Cannot happen with the VulkanMod build the mod is made for, logged in case another build differs
            LuminaLog.errorLimited("depthFormat", "Could not clear the depth format of " + name, t);
        }

        this.renderPass = createPass(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
        this.loadPass = createPass(VK_ATTACHMENT_LOAD_OP_LOAD);

        ResourceTracker.created("framebuffer", name, "%dx%d".formatted(width(), height()));
    }

    private RenderPass createPass(int loadOp) {
        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);
        builder.getColorAttachmentInfo().setOps(loadOp, VK_ATTACHMENT_STORE_OP_STORE)
               .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        return builder.build();
    }

    void resize(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);

        if (width == width() && height == height())
            return;

        this.framebuffer.resize(width, height);
        ResourceTracker.freed("framebuffer", this.name);
        ResourceTracker.created("framebuffer", this.name, "%dx%d".formatted(width(), height()));
    }

    VulkanImage color() {
        return this.framebuffer.getColorAttachment();
    }

    int width() {
        return this.framebuffer.getWidth();
    }

    int height() {
        return this.framebuffer.getHeight();
    }
}
