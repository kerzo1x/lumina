package dev.lumina.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.lumina.config.LuminaConfig;
import dev.lumina.util.ResourceTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.SamplerInfo;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Renders terrain depth from the light's point of view into two cascades.
 *
 * The near cascade covers a small area around the camera at high detail and is redrawn every frame.
 * The far cascade covers the whole shadow distance; it is by far the more expensive one, so it is drawn into
 * a back buffer a quarter of the terrain per frame and swapped in when complete. Both are plain orthographic
 * maps snapped to their texel grid, which is what keeps shadow edges still while the camera moves.
 *
 * VulkanMod only meshes and draws sections reachable by its camera visibility graph. Reusing that list
 * for shadows makes them pop while moving, so this pass builds its own list of every section inside the
 * shadow volume and asks for the ones the camera never saw to be meshed.
 */
public final class ShadowRenderer {
    /** Half size in blocks of the area covered by the near cascade. */
    public static final float NEAR_EXTENT = 28.0f;

    private static final TerrainRenderType[] CASTER_TYPES = {TerrainRenderType.SOLID, TerrainRenderType.CUTOUT};
    private static final int MAX_SCHEDULED_PER_FRAME = 3;
    private static final int FAR_SLICES = 4;
    /** Half diagonal of a chunk section: how far outside a cascade a section's centre can be while still reaching into it. */
    private static final float SECTION_RADIUS = 13.9f;
    /** Block columns (x << 4 | z) probed for the surface height of a chunk: corners, edge centres and the middle. */
    private static final int[] SURFACE_SAMPLES = {0x00, 0x0F, 0xF0, 0xFF, 0x78, 0x08, 0x80, 0x8F, 0xF8};

    private Cascade near;
    private Cascade farFront;
    private Cascade farBack;
    private int resolution;

    private int farSlice;
    private boolean comparisonSampling = true;
    private boolean farValid;
    private float farExtent;

    private StaticQueue<RenderSection>[] areaQueues;
    private final List<ChunkArea> usedAreas = new ArrayList<>();
    private final Vector3d lightCamera = new Vector3d();
    private final Vector3d cameraPos = new Vector3d();
    private final EntityShadows entityShadows = new EntityShadows();
    private int[] surfaceHeights = new int[0];
    private int[] columnHeights = new int[0];

    public int lastNearSections;
    public int lastFarSections;
    public int lastScheduled;
    public long totalScheduled;

    public void ensureResources(int resolution) {
        if (this.near == null) {
            this.near = new Cascade("shadowNear", resolution);
            this.farFront = new Cascade("shadowFarA", resolution);
            this.farBack = new Cascade("shadowFarB", resolution);
        } else if (this.resolution != resolution) {
            this.near.resize(resolution, this.comparisonSampling);
            this.farFront.resize(resolution, this.comparisonSampling);
            this.farBack.resize(resolution, this.comparisonSampling);
            invalidate();
        }

        this.resolution = resolution;
    }

    /** Forces the far cascade to be rebuilt from scratch, for example after the terrain was reloaded. */
    public void invalidate() {
        this.farValid = false;
        this.farSlice = 0;
    }

    public boolean isFarValid() {
        return this.farValid;
    }

    /**
     * The terrain shader reads the maps through a depth-comparison sampler, the converted entity shader can only
     * use a plain one. An image carries a single sampler, so it is swapped between the two kinds of drawing.
     */
    public void setComparisonSampling(boolean comparison) {
        if (this.near == null || this.comparisonSampling == comparison)
            return;

        this.comparisonSampling = comparison;
        this.near.applySampler(comparison);
        this.farFront.applySampler(comparison);
        this.farBack.applySampler(comparison);
    }

    public VulkanImage nearDepth() {
        return this.near.framebuffer.getDepthAttachment();
    }

    public VulkanImage farDepth() {
        return this.farFront.framebuffer.getDepthAttachment();
    }

    /** Matrix taking positions relative to the current camera into the near cascade's clip space. */
    public Matrix4f nearMatrix(Matrix4f dest, double camX, double camY, double camZ) {
        return this.near.matrixFor(dest, camX, camY, camZ);
    }

    public Matrix4f farMatrix(Matrix4f dest, double camX, double camY, double camZ) {
        return this.farFront.matrixFor(dest, camX, camY, camZ);
    }

    public float nearTexel() {
        return 2.0f * NEAR_EXTENT / this.resolution;
    }

    public float farTexel() {
        return 2.0f * this.farExtent / this.resolution;
    }

    /**
     * Must be called outside of the pass that will sample the shadow maps.
     *
     * @param drawCasters false only makes sure the maps are in a state that can be sampled
     */
    public void render(LightState light, LuminaConfig config, float partialTick, double camX, double camY, double camZ, boolean drawCasters) {
        this.lastScheduled = 0;

        if (!drawCasters) {
            // The maps stay bound to the terrain shader even when unused, so they must at least be readable
            clearIfUnreadable(this.near);
            clearIfUnreadable(this.farFront);
            clearIfUnreadable(this.farBack);
            invalidate();
            return;
        }

        float farExtent = Math.max(config.shadowDistance, NEAR_EXTENT * 2.0f);
        if (farExtent != this.farExtent) {
            this.farExtent = farExtent;
            invalidate();
        }

        // Near cascade: everything, every frame
        this.near.capture(light, NEAR_EXTENT, this.resolution, camX, camY, camZ);
        collectSections(this.near, config, -1, false);

        ClientLevel level = Minecraft.getInstance().level;
        if (config.entityShadows && level != null)
            this.entityShadows.collect(level, partialTick, camX, camY, camZ, NEAR_EXTENT + 8.0f);
        else
            this.entityShadows.collect(null, 0.0f, 0.0, 0.0, 0.0, 0.0f);

        this.lastNearSections = drawCascade(this.near, light, true, true);

        // Far cascade: one slice of the chunk areas per frame into the back buffer
        if (this.farSlice == 0) {
            this.farBack.capture(light, this.farExtent, this.resolution, camX, camY, camZ);
            this.farBack.sectionCount = 0;
        }

        // The far cascade is assembled over several frames and cannot follow moving foliage
        LuminaUniforms.set(LuminaUniforms.WAVE, 0.0f, 0.0f, 0.0f, 0.0f);

        collectSections(this.farBack, config, this.farSlice, true);
        this.farBack.sectionCount += drawCascade(this.farBack, light, this.farSlice == 0, false);

        if (++this.farSlice >= FAR_SLICES) {
            this.farSlice = 0;
            this.lastFarSections = this.farBack.sectionCount;

            Cascade swap = this.farFront;
            this.farFront = this.farBack;
            this.farBack = swap;
            this.farValid = true;
        }

        clearIfUnreadable(this.farFront);
    }

    private void clearIfUnreadable(Cascade cascade) {
        if (cascade.framebuffer.getDepthAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            Renderer renderer = Renderer.getInstance();
            renderer.endRenderPass();
            renderer.beginRenderPass(cascade.clearPass, cascade.framebuffer);
            renderer.endRenderPass();
        }
    }

    /**
     * @param slice only chunk areas whose index falls into this slice are kept, -1 keeps all
     */
    @SuppressWarnings("unchecked")
    private void collectSections(Cascade cascade, LuminaConfig config, int slice, boolean scheduleMissing) {
        for (ChunkArea area : this.usedAreas) {
            this.areaQueues[area.index].clear();
        }
        this.usedAreas.clear();

        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        SectionGrid grid = worldRenderer.getSectionGrid();
        ClientLevel level = Minecraft.getInstance().level;

        if (grid == null || level == null)
            return;

        ChunkAreaManager areaManager = grid.getChunkAreaManager();
        if (this.areaQueues == null || this.areaQueues.length != areaManager.size) {
            this.areaQueues = new StaticQueue[areaManager.size];
        }

        double camX = cascade.camX;
        double camY = cascade.camY;
        double camZ = cascade.camZ;

        // The grid wraps around, so never look further than the render distance
        int radius = Math.min((config.shadowDistance >> 4) + 1, worldRenderer.getRenderDistance());
        int centerX = SectionPos.blockToSectionCoord(camX);
        int centerZ = SectionPos.blockToSectionCoord(camZ);
        int cameraLayer = SectionPos.blockToSectionCoord(camY) - level.getMinSectionY();
        int layers = level.getSectionsCount();
        int minY = level.getMinY();

        updateSurfaceHeights(level, centerX, centerZ, radius);
        int surfaceWidth = 2 * radius + 1;

        // A caster matters when it overlaps the cascade's rectangle as seen from the light, at any depth
        Matrix4f rotation = cascade.rotation;
        float limit = cascade.extent + SECTION_RADIUS;

        this.cameraPos.set(camX, camY, camZ);

        for (int x = centerX - radius; x <= centerX + radius; ++x) {
            for (int z = centerZ - radius; z <= centerZ + radius; ++z) {
                float relX = (float) ((x << 4) + 8 - camX);
                float relZ = (float) ((z << 4) + 8 - camZ);
                int lowestSurface = this.surfaceHeights[(z - centerZ + radius) * surfaceWidth + (x - centerX + radius)];

                for (int layer = 0; layer < layers; ++layer) {
                    // Buried below the surface of this chunk and all its neighbours: cannot shade anything under the sky.
                    // Skipping these also keeps cave sections from being meshed just for shadows.
                    if (minY + (layer << 4) + 16 < lowestSurface - 8)
                        continue;

                    float relY = (float) (minY + (layer << 4) + 8 - camY);

                    float lightX = rotation.m00() * relX + rotation.m10() * relY + rotation.m20() * relZ;
                    float lightY = rotation.m01() * relX + rotation.m11() * relY + rotation.m21() * relZ;
                    if (Math.abs(lightX) > limit || Math.abs(lightY) > limit)
                        continue;

                    RenderSection section = grid.getSectionAtSectionPos(x, layer, z);

                    // Wrapped lookups can return a section that currently represents another position
                    if (section == null || section.xOffset != x << 4 || section.zOffset != z << 4 || section.yOffset != minY + (layer << 4))
                        continue;

                    if (!section.isCompiled()) {
                        // Only bother with casters that can plausibly be above something the player sees
                        if (scheduleMissing && section.isDirty() && layer >= cameraLayer - 2 && this.lastScheduled < MAX_SCHEDULED_PER_FRAME
                            && section.rebuildChunkAsync(worldRenderer.getTaskDispatcher(), worldRenderer.renderRegionCache, this.cameraPos)) {
                            section.setNotDirty();
                            this.lastScheduled++;
                            this.totalScheduled++;
                        }
                        continue;
                    }

                    if (section.isCompletelyEmpty())
                        continue;

                    ChunkArea area = section.getChunkArea();
                    if (area == null || area.index < 0 || area.index >= this.areaQueues.length)
                        continue;

                    if (slice >= 0 && area.index % FAR_SLICES != slice)
                        continue;

                    StaticQueue<RenderSection> queue = this.areaQueues[area.index];
                    if (queue == null) {
                        queue = this.areaQueues[area.index] = new StaticQueue<>(ChunkAreaManager.AREA_SIZE);
                    }

                    if (queue.size() == 0)
                        this.usedAreas.add(area);

                    if (queue.size() < ChunkAreaManager.AREA_SIZE)
                        queue.add(section);
                }
            }
        }
    }

    /**
     * For every chunk column in range: the lowest surface height found in it and its eight neighbours.
     * Neighbours are included because a cliff face belongs to the high chunk while the ground it shades is next door.
     */
    private void updateSurfaceHeights(ClientLevel level, int centerX, int centerZ, int radius) {
        int width = 2 * radius + 1;
        int paddedWidth = width + 2;

        if (this.surfaceHeights.length < width * width) {
            this.surfaceHeights = new int[width * width];
            this.columnHeights = new int[paddedWidth * paddedWidth];
        }

        for (int dz = 0; dz < paddedWidth; ++dz) {
            for (int dx = 0; dx < paddedWidth; ++dx) {
                LevelChunk chunk = level.getChunkSource().getChunk(centerX - radius - 1 + dx, centerZ - radius - 1 + dz, ChunkStatus.FULL, false);
                int lowest = Integer.MIN_VALUE;

                if (chunk != null) {
                    lowest = Integer.MAX_VALUE;
                    for (int sample : SURFACE_SAMPLES) {
                        lowest = Math.min(lowest, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, sample >> 4, sample & 15));
                    }
                }

                // Unloaded neighbours give no information, MIN_VALUE disables the filter around them
                this.columnHeights[dz * paddedWidth + dx] = lowest;
            }
        }

        for (int z = 0; z < width; ++z) {
            for (int x = 0; x < width; ++x) {
                int lowest = Integer.MAX_VALUE;

                for (int dz = 0; dz < 3; ++dz) {
                    for (int dx = 0; dx < 3; ++dx) {
                        lowest = Math.min(lowest, this.columnHeights[(z + dz) * paddedWidth + x + dx]);
                    }
                }

                this.surfaceHeights[z * width + x] = lowest;
            }
        }
    }

    /** @return number of sections drawn */
    private int drawCascade(Cascade cascade, LightState light, boolean clear, boolean withEntities) {
        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        boolean drawEntities = withEntities && this.entityShadows.vertexCount() > 0;

        if (!clear && this.usedAreas.isEmpty() && !drawEntities)
            return 0;

        renderer.endRenderPass();
        renderer.beginRenderPass(clear ? cascade.clearPass : cascade.loadPass, cascade.framebuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Plain Vulkan viewport: clip space maps straight onto texture coordinates, no flip to undo when sampling
            VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
            viewport.x(0.0f).y(0.0f).width(this.resolution).height(this.resolution).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(this.resolution, this.resolution);
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }

        int sections = 0;

        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        Renderer.setDepthBias(0.0f, 1.5f);

        LuminaUniforms.set(LuminaUniforms.SHADOW_MAT, cascade.matrix);

        if (!this.usedAreas.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            GraphicsPipeline pipeline = LuminaPipelines.shadow;

            renderer.bindGraphicsPipeline(pipeline);

            var atlasView = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            VTextureSelector.bindTexture(0, ((VkGpuTexture) atlasView.texture()).getVulkanImage());

            IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
            Renderer.getDrawer().bindIndexBuffer(commandBuffer, indexBuffer, indexBuffer.indexType.value);

            long now = System.currentTimeMillis();
            float fadeTime = minecraft.options.chunkSectionFadeInTime().get().floatValue();
            int fadeTimeMs = (int) (fadeTime * 1000);
            float fadeTimeInv = fadeTime > 0 ? 1 / (fadeTime * 1000) : 1;

            // DrawBuffers skips faces pointing away from the given position: a point far along the light direction
            // keeps exactly the faces the light can reach
            this.lightCamera.set(cascade.camX + cascade.lightX * 1.0e6, cascade.camY + cascade.lightY * 1.0e6, cascade.camZ + cascade.lightZ * 1.0e6);

            for (ChunkArea area : this.usedAreas) {
                DrawBuffers drawBuffers = area.getDrawBuffers();
                StaticQueue<RenderSection> queue = this.areaQueues[area.index];
                sections += queue.size();

                for (TerrainRenderType type : CASTER_TYPES) {
                    if (drawBuffers.getAreaBuffer(type) == null)
                        continue;

                    drawBuffers.bindBuffers(commandBuffer, pipeline, type, cascade.camX, cascade.camY, cascade.camZ, now, fadeTimeMs, fadeTimeInv);
                    renderer.uploadAndBindUBOs(pipeline);
                    drawBuffers.buildDrawBatchesDirect(this.lightCamera, queue, type);
                }
            }
        }

        if (drawEntities) {
            // Positions are already relative to the camera the cascade was captured for
            renderer.bindGraphicsPipeline(LuminaPipelines.shadowEntity);
            renderer.uploadAndBindUBOs(LuminaPipelines.shadowEntity);
            Renderer.getDrawer().draw(this.entityShadows.buffer(), VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION, this.entityShadows.vertexCount());
        }

        Renderer.setDepthBias(0.0f, 0.0f);
        VRenderSystem.enableCull();

        renderer.endRenderPass();
        return sections;
    }

    public String describe() {
        return "near=%d far=%d entities=%d farValid=%s scheduledNow=%d scheduledTotal=%d res=%d"
                .formatted(this.lastNearSections, this.lastFarSections, this.entityShadows.entityCount(), this.farValid, this.lastScheduled, this.totalScheduled, this.resolution);
    }

    /**
     * One shadow map together with the camera position and light it was drawn for. Kept across resizes for the
     * same reason as the post-processing targets: VulkanMod caches compiled pipelines per pass object.
     */
    private static final class Cascade {
        final String name;
        final Framebuffer framebuffer;
        final RenderPass clearPass;
        final RenderPass loadPass;

        /** Clip-space matrix for positions relative to (camX, camY, camZ). */
        final Matrix4f matrix = new Matrix4f();
        final Matrix4f rotation = new Matrix4f();
        double camX, camY, camZ;
        float lightX, lightY = 1.0f, lightZ;
        float extent;
        int sectionCount;

        Cascade(String name, int resolution) {
            this.name = name;
            // The colour attachment is unused, VulkanMod's pipelines always expect one
            this.framebuffer = new Framebuffer.Builder("Lumina " + name, resolution, resolution, 1, true).build();
            this.clearPass = createPass(VK_ATTACHMENT_LOAD_OP_CLEAR);
            this.loadPass = createPass(VK_ATTACHMENT_LOAD_OP_LOAD);

            applySampler(true);
            ResourceTracker.created("framebuffer", name, "%dx%d".formatted(resolution, resolution));
        }

        private RenderPass createPass(int depthLoadOp) {
            RenderPass.Builder builder = RenderPass.builder(this.framebuffer);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
            builder.getDepthAttachmentInfo().setOps(depthLoadOp, VK_ATTACHMENT_STORE_OP_STORE)
                   .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            return builder.build();
        }

        void resize(int resolution, boolean comparison) {
            this.framebuffer.resize(resolution, resolution);
            applySampler(comparison);
            ResourceTracker.freed("framebuffer", this.name);
            ResourceTracker.created("framebuffer", this.name, "%dx%d".formatted(resolution, resolution));
        }

        void applySampler(boolean comparison) {
            // Hardware depth comparison with linear filtering gives 2x2 PCF per tap
            long sampler = comparison
                    ? SamplerManager.getSampler(new SamplerInfo(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                                                                VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                                                                0.0f, false, 0.0f,
                                                                true, VK_COMPARE_OP_LESS_OR_EQUAL,
                                                                -1))
                    : SamplerManager.getSampler(true, false, 0);
            this.framebuffer.getDepthAttachment().setSampler(sampler);
        }

        void capture(LightState light, float extent, int resolution, double camX, double camY, double camZ) {
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.extent = extent;
            this.lightX = light.lightDir.x;
            this.lightY = light.lightDir.y;
            this.lightZ = light.lightDir.z;
            this.rotation.set(light.rotation());
            light.buildShadowMatrix(this.matrix, extent, resolution, camX, camY, camZ);
        }

        /** The map was drawn around an older camera position: shift current camera-relative positions back to it. */
        Matrix4f matrixFor(Matrix4f dest, double camX, double camY, double camZ) {
            return dest.set(this.matrix).translate((float) (camX - this.camX), (float) (camY - this.camY), (float) (camZ - this.camZ));
        }
    }
}
