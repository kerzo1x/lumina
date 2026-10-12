package dev.lumina.render;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;

/**
 * Terrain vertices have no room for a block id, but the lowest four bits of the packed block light are a
 * fraction nothing reads. They are used to tell the shaders what kind of block a vertex belongs to.
 *
 * Bits 0-1 are the kind. For plants, bits 2-3 say how far up the swaying plant the vertex is (0 = root, 2 = tip).
 * For kind 0, bits 2-3 pick a surface that does not sway but is shaded specially.
 * terrain.vsh, shadow.vsh and waving.glsl decode the same layout.
 */
public final class BlockFlags {
    public static final int MASK = 0xF;

    public static final int KIND_NONE = 0;
    public static final int KIND_LEAVES = 1;
    public static final int KIND_PLANT = 2;
    public static final int KIND_WATER = 3;
    /** Ice, stained glass and the like: smooth enough to mirror their surroundings. */
    public static final int KIND_GLOSSY = 4;
    public static final int KIND_LAVA = 8;

    /** Plant spanning one block: roots at weight 0, tip at weight 2. */
    public static final int PLANT_SINGLE = KIND_PLANT;
    /** Lower and upper half of a two block plant: weights 0 to 1 and 1 to 2, so the halves stay joined. */
    public static final int PLANT_LOWER = KIND_PLANT | 0x10;
    public static final int PLANT_UPPER = KIND_PLANT | 0x20;

    private BlockFlags() {
    }

    /** Kind of the block, with the plant variant in the bits above the vertex flag. */
    public static int classify(BlockState state) {
        Block block = state.getBlock();

        if (state.is(BlockTags.LEAVES) || block instanceof VineBlock)
            return KIND_LEAVES;

        if (block instanceof DoublePlantBlock)
            return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? PLANT_UPPER : PLANT_LOWER;

        if (block instanceof VegetationBlock)
            return PLANT_SINGLE;

        // Glass, tinted and stained glass, ice, slime and honey share this base class
        if (block instanceof HalfTransparentBlock || block instanceof StainedGlassPaneBlock || state.is(BlockTags.ICE))
            return KIND_GLOSSY;

        return KIND_NONE;
    }

    public static int classify(FluidState state) {
        if (state.is(FluidTags.WATER))
            return KIND_WATER;

        return state.is(FluidTags.LAVA) ? KIND_LAVA : KIND_NONE;
    }

    /**
     * @param classified result of {@link #classify}
     * @param height     height of the vertex above the bottom of its block, 0..1
     */
    public static int apply(int light, int classified, float height) {
        int flag = classified & MASK;

        if ((classified & 0x3) == KIND_PLANT) {
            int weight = switch (classified) {
                case PLANT_LOWER -> height > 0.5f ? 1 : 0;
                case PLANT_UPPER -> height > 0.5f ? 2 : 1;
                default -> height > 0.5f ? 2 : 0;
            };
            flag = KIND_PLANT | weight << 2;
        }

        return (light & ~MASK) | flag;
    }
}
