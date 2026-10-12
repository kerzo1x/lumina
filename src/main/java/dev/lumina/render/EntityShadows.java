package dev.lumina.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Stand-in geometry that lets players and mobs cast shadows.
 *
 * Re-running the game's entity renderer from the light's point of view is not something VulkanMod's pipeline
 * allows for, so each creature is drawn into the near shadow cascade as a few boxes instead: a jointed figure with
 * swinging limbs for humanoids, the bounding box for everything else. At shadow-map resolution that is enough to
 * read as the creature's shadow.
 */
public final class EntityShadows {
    private static final int MAX_ENTITIES = 96;
    private static final int BOXES_PER_ENTITY = 6;
    private static final int VERTICES_PER_BOX = 24;
    private static final int VERTEX_SIZE = 12;

    /** Vanilla scales the 32 pixel tall player model to 1.875 blocks. */
    private static final float PIXEL = 0.9375f / 16.0f;

    // Allocated once for the lifetime of the game
    private final ByteBuffer buffer = MemoryUtil.memAlloc(MAX_ENTITIES * BOXES_PER_ENTITY * VERTICES_PER_BOX * VERTEX_SIZE);
    private int vertexCount;
    private int entityCount;

    private final Matrix4f body = new Matrix4f();
    private final Matrix4f limb = new Matrix4f();
    private final Vector3f corner = new Vector3f();
    private final float[] corners = new float[24];

    public int vertexCount() {
        return this.vertexCount;
    }

    public int entityCount() {
        return this.entityCount;
    }

    public ByteBuffer buffer() {
        return this.buffer;
    }

    /**
     * @param range creatures further than this from the camera are left out, their shadow would fall outside the cascade
     */
    public void collect(ClientLevel level, float partialTick, double camX, double camY, double camZ, float range) {
        this.buffer.clear();
        this.buffer.limit(0);
        this.vertexCount = 0;
        this.entityCount = 0;

        if (level == null)
            return;

        this.buffer.clear();

        float rangeSqr = range * range;

        for (Entity entity : level.entitiesForRendering()) {
            if (this.entityCount >= MAX_ENTITIES)
                break;

            if (!(entity instanceof LivingEntity living) || entity.isInvisible() || entity.isSpectator())
                continue;

            float x = (float) (Mth.lerp(partialTick, entity.xo, entity.getX()) - camX);
            float y = (float) (Mth.lerp(partialTick, entity.yo, entity.getY()) - camY);
            float z = (float) (Mth.lerp(partialTick, entity.zo, entity.getZ()) - camZ);

            if (x * x + y * y + z * z > rangeSqr)
                continue;

            float yaw = Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
            this.body.translation(x, y, z).rotateY(-yaw * Mth.DEG_TO_RAD);

            if (isHumanoid(living))
                addHumanoid(living, partialTick);
            else
                addBox(this.body, -living.getBbWidth() * 0.5f, 0.0f, -living.getBbWidth() * 0.5f,
                       living.getBbWidth() * 0.5f, living.getBbHeight(), living.getBbWidth() * 0.5f);

            this.entityCount++;
        }

        this.buffer.flip();
    }

    private static boolean isHumanoid(LivingEntity entity) {
        return entity instanceof Player || entity instanceof Zombie || entity instanceof AbstractSkeleton
               || entity instanceof AbstractPiglin || entity instanceof AbstractIllager || entity instanceof AbstractVillager
               || entity instanceof ArmorStand;
    }

    private void addHumanoid(LivingEntity entity, float partialTick) {
        // Babies and crouching players are shorter than the 1.8 block reference
        float scale = PIXEL * Mth.clamp(entity.getBbHeight() / 1.8f, 0.3f, 1.5f);
        this.body.scale(scale);

        float walkSpeed = Math.min(entity.walkAnimation.speed(partialTick), 1.0f);
        float swing = Mth.cos(entity.walkAnimation.position(partialTick) * 0.6662f) * 1.4f * walkSpeed;

        // Model pixels: feet at 0, hips at 12, shoulders at 24, top of the head at 32
        addBox(this.body, -4, 12, -2, 4, 24, 2);
        addBox(this.body, -4, 24, -4, 4, 32, 4);

        addLimb(-2, 12, swing);
        addLimb(2, 12, -swing);
        addLimb(-6, 24, -swing * 0.6f);
        addLimb(6, 24, swing * 0.6f);
    }

    /** A 4x12x4 pixel limb hanging from a joint at (x, top), swung forwards or backwards around it. */
    private void addLimb(float x, float top, float angle) {
        this.limb.set(this.body).translate(x, top, 0.0f).rotateX(angle);
        addBox(this.limb, -2, -12, -2, 2, 0, 2);
    }

    private void addBox(Matrix4f transform, float x0, float y0, float z0, float x1, float y1, float z1) {
        if (this.buffer.remaining() < VERTICES_PER_BOX * VERTEX_SIZE)
            return;

        for (int i = 0; i < 8; ++i) {
            transform.transformPosition(this.corner.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1));
            this.corners[i * 3] = this.corner.x;
            this.corners[i * 3 + 1] = this.corner.y;
            this.corners[i * 3 + 2] = this.corner.z;
        }

        // Corner index bits: 1 = +x, 2 = +y, 4 = +z. Winding is irrelevant, the shadow pass does not cull
        quad(0, 1, 3, 2);
        quad(4, 6, 7, 5);
        quad(0, 4, 5, 1);
        quad(2, 3, 7, 6);
        quad(0, 2, 6, 4);
        quad(1, 5, 7, 3);
    }

    private void quad(int a, int b, int c, int d) {
        vertex(a);
        vertex(b);
        vertex(c);
        vertex(d);
        this.vertexCount += 4;
    }

    private void vertex(int index) {
        this.buffer.putFloat(this.corners[index * 3]).putFloat(this.corners[index * 3 + 1]).putFloat(this.corners[index * 3 + 2]);
    }
}
