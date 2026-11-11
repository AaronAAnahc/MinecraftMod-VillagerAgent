package com.github.AaronAA0721.villageragent.content;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

/**
 * A fishing bobber owned by a villager.
 *
 * <p>The vanilla {@code FishingBobberEntity} cannot be used: it requires a player owner who is holding a rod (the
 * server deletes it on the next tick otherwise) and the client only spawns it when the owner is a player, so a
 * villager's line never showed. This entity carries its owner's network id in synced data and is drawn by
 * {@code client.VillagerBobberRenderer} with the vanilla hook texture and a line to the villager's rod tip.
 *
 * <p>The server owns all motion: a short arc from the rod tip to the chosen water block, then vanilla-like floating
 * at the water surface. While {@link #isBiting()} the float is pulled under (the "bite"). The fishing skill must call
 * {@link #keepAlive} every tick; an abandoned bobber removes itself after {@link #KEEP_ALIVE_TICKS}.
 */
public class VillagerBobberEntity extends Entity {
    private static final DataParameter<Integer> OWNER =
            EntityDataManager.defineId(VillagerBobberEntity.class, DataSerializers.INT);
    private static final DataParameter<Boolean> BITING =
            EntityDataManager.defineId(VillagerBobberEntity.class, DataSerializers.BOOLEAN);
    public static final int KEEP_ALIVE_TICKS = 20;
    /** Rod tip relative to the villager's feet: forward along the body yaw and up. Matches VillagerHeldItemLayer. */
    public static final double ROD_TIP_FORWARD = 0.7, ROD_TIP_HEIGHT = 1.2;

    private Vector3d castFrom, castTo;
    private int castTick, castDuration;
    private long keepAliveUntil;
    private boolean landedOnGround;

    public VillagerBobberEntity(EntityType<? extends VillagerBobberEntity> type, World world) {
        super(type, world);
        this.noCulling = true; // the line can be on screen while the hook is not
    }

    /** Throw a bobber from the villager's rod tip so that it lands at {@code target} (a water surface point). */
    public static VillagerBobberEntity cast(World world, LivingEntity owner, Vector3d target) {
        VillagerBobberEntity bobber = new VillagerBobberEntity(ModEntities.VILLAGER_BOBBER.get(), world);
        bobber.entityData.set(OWNER, owner.getId());
        Vector3d start = rodTip(owner, 1.0f);
        bobber.moveTo(start.x, start.y, start.z, 0f, 0f);
        bobber.castFrom = start;
        bobber.castTo = target;
        bobber.castDuration = MathHelper.clamp((int) (start.distanceTo(target) * 1.6), 8, 20);
        bobber.keepAlive(world.getGameTime());
        world.addFreshEntity(bobber);
        return bobber;
    }

    /** World position of the rod tip of a villager holding a rod (also used by the renderer for the line). */
    public static Vector3d rodTip(LivingEntity owner, float partialTicks) {
        float yaw = MathHelper.lerp(partialTicks, owner.yBodyRotO, owner.yBodyRot) * ((float) Math.PI / 180f);
        double x = MathHelper.lerp(partialTicks, owner.xo, owner.getX()) - MathHelper.sin(yaw) * ROD_TIP_FORWARD;
        double y = MathHelper.lerp(partialTicks, owner.yo, owner.getY()) + ROD_TIP_HEIGHT;
        double z = MathHelper.lerp(partialTicks, owner.zo, owner.getZ()) + MathHelper.cos(yaw) * ROD_TIP_FORWARD;
        return new Vector3d(x, y, z);
    }

    public void keepAlive(long now) { keepAliveUntil = now + KEEP_ALIVE_TICKS; }

    public Entity getOwner() {
        int id = entityData.get(OWNER);
        return id < 0 ? null : level.getEntity(id);
    }

    public boolean isBiting() { return entityData.get(BITING); }
    public void setBiting(boolean biting) { entityData.set(BITING, biting); }

    /** The cast arc is finished. */
    public boolean hasLanded() { return castFrom == null; }
    /** The hook came down on dry ground instead of water: the caster should reel in and try another spot. */
    public boolean landedOnGround() { return landedOnGround; }

    /** Water surface height at the hook, or -1 when it is not in water. */
    public double waterSurface() {
        BlockPos pos = blockPosition();
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(FluidTags.WATER)) return -1;
        return pos.getY() + fluid.getHeight(level, pos);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) return;
        Entity owner = getOwner();
        if (owner == null || !owner.isAlive() || level.getGameTime() > keepAliveUntil || distanceToSqr(owner) > 32 * 32) {
            remove();
            return;
        }
        if (castFrom != null) {
            // Scripted cast: a parabola from the rod tip to the target, then free physics.
            castTick++;
            double t = Math.min(1.0, castTick / (double) castDuration);
            double arc = Math.sin(Math.PI * t) * Math.min(2.5, castFrom.distanceTo(castTo) * 0.35);
            Vector3d p = castFrom.add(castTo.subtract(castFrom).scale(t)).add(0, arc, 0);
            Vector3d last = position();
            setPos(p.x, p.y, p.z);
            setDeltaMovement(p.subtract(last));
            if (t >= 1.0) {
                castFrom = null;
                setDeltaMovement(0, -0.1, 0);
            }
            return;
        }
        Vector3d motion = getDeltaMovement();
        double surface = waterSurface();
        if (surface >= 0) {
            // Float like the vanilla bobber: settle a little below the surface, bob gently, dip hard on a bite.
            double rest = surface - 0.12 - (isBiting() ? 0.35 : 0.0) + Math.sin(tickCount * 0.15) * 0.02;
            motion = new Vector3d(motion.x * 0.6, (rest - getY()) * 0.25, motion.z * 0.6);
        } else {
            motion = new Vector3d(motion.x * 0.92, motion.y * 0.98 - 0.04, motion.z * 0.92);
        }
        move(MoverType.SELF, motion);
        setDeltaMovement(motion);
        if (onGround && surface < 0) landedOnGround = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER, -1);
        entityData.define(BITING, false);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 4096.0D;
    }

    @Override protected void readAdditionalSaveData(CompoundNBT tag) {}
    @Override protected void addAdditionalSaveData(CompoundNBT tag) {}

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
