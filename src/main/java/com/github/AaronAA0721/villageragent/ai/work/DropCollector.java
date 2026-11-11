package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/**
 * Walks a villager to each dropped item around a work site, nearest first, until none is left.
 * It only moves the villager into contact range: the passive pickup in
 * {@link ItemAttractionSystem#processItemAttraction} does the actual transfer.
 */
public final class DropCollector {
    private static final int PER_ITEM_TICKS = 160;
    private static final int TOTAL_TICKS = 1200;
    private final BlockPos center;
    private final double radius;
    private final Set<UUID> rejected = new HashSet<>();
    private UUID current;
    private long started = -1, itemSince, nextPath;
    private int collected;

    public DropCollector(BlockPos center, double radius) {
        this.center = center.immutable(); this.radius = radius;
    }

    /** Items picked up while this collector was steering. */
    public int targets() { return collected; }

    /** @return true once no collectable drop remains (or the collector timed out). */
    public boolean tick(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        if (started < 0) started = now;
        if (now - started > TOTAL_TICKS) { villager.getNavigation().stop(); return true; }
        ItemEntity target = null;
        if (current != null) {
            Entity entity = world.getEntity(current);
            if (entity instanceof ItemEntity && collectable((ItemEntity) entity, agent, villager)) target = (ItemEntity) entity;
            else { collected++; current = null; }
        }
        if (target == null) {
            for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(center).inflate(radius, 4, radius))) {
                if (!collectable(drop, agent, villager)) continue;
                if (target == null || villager.distanceToSqr(drop) < villager.distanceToSqr(target)) target = drop;
            }
            if (target == null) { villager.getNavigation().stop(); return true; }
            current = target.getUUID(); itemSince = now; nextPath = 0;
        }
        if (now - itemSince > PER_ITEM_TICKS) {
            rejected.add(current); current = null; villager.getNavigation().stop(); return false;
        }
        villager.getLookControl().setLookAt(target, 30F, 30F);
        if (ItemAttractionSystem.pickupBox(villager).intersects(target.getBoundingBox())) {
            villager.getNavigation().stop();
            // Contact reached; the per-tick pickup system takes it. Fallback when that system is off.
            if (!ModConfig.ENABLE_AUTO_PICKUP.get()) ItemAttractionSystem.collect(target, villager, agent, Integer.MAX_VALUE);
            return false;
        }
        if (now >= nextPath) {
            nextPath = now + 10;
            if (!villager.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), VillagerNavigationSupport.WALK_SPEED)
                    && now - itemSince > 40) {
                rejected.add(current); current = null;
            }
        }
        return false;
    }

    private boolean collectable(ItemEntity drop, VillagerAgentData agent, VillagerEntity villager) {
        return drop.isAlive() && !com.github.AaronAA0721.villageragent.ai.CraftDisplay.isDisplay(drop) && !drop.getItem().isEmpty() && !rejected.contains(drop.getUUID())
                && (drop.getOwner() == null || drop.getOwner().equals(villager.getUUID()))
                && Math.abs(drop.getX() - center.getX() - .5) <= radius
                && Math.abs(drop.getZ() - center.getZ() - .5) <= radius
                && InventoryTransfer.spaceFor(agent.getInventory(), drop.getItem()) > 0;
    }
}
