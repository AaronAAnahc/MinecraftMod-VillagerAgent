package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

import java.util.UUID;

/**
 * Visible crafting: while a villager works at a crafting table, the result floats on top of the
 * table as a decorative item (never pick-up-able, no gravity, tagged so every collector ignores it).
 * When the work time is over the display is removed and a pickup sound plays at the villager.
 *
 * <p>The display carries a short Forge {@code lifespan}, so if the craft is interrupted (goal
 * pre-empted, chunk unloaded, server stopped) it despawns by itself shortly afterwards.
 */
public final class CraftDisplay {
    /** Entity tag marking a display-only item. */
    public static final String TAG = "villageragent_craft_display";
    /** How long the villager stands at the table with the result on display (3 s). */
    public static final int DURATION_TICKS = 60;

    public static boolean isDisplay(Entity e) {
        return e != null && e.getTags().contains(TAG);
    }

    /** Spawns the display item on top of {@code table}; returns its UUID. */
    public static UUID show(ServerWorld world, BlockPos table, ItemStack result) {
        ItemEntity e = new ItemEntity(world, table.getX() + 0.5, table.getY() + 1.0, table.getZ() + 0.5, result.copy());
        e.setNoGravity(true);
        e.setDeltaMovement(Vector3d.ZERO);
        e.setNeverPickUp();          // pickupDelay 32767: players, vanilla mobs and merging all skip it
        e.setInvulnerable(true);
        e.lifespan = DURATION_TICKS + 40; // self-cleans if the craft is abandoned
        e.addTag(TAG);
        world.addFreshEntity(e);
        return e.getUUID();
    }

    public static boolean isAlive(ServerWorld world, UUID id) {
        Entity e = id == null ? null : world.getEntity(id);
        return isDisplay(e) && e.isAlive();
    }

    public static void remove(ServerWorld world, UUID id) {
        Entity e = id == null ? null : world.getEntity(id);
        if (isDisplay(e)) e.remove();
    }

    /** The same pickup "pop" a player hears when collecting an item. */
    public static void playPickupSound(ServerWorld world, VillagerEntity villager) {
        float pitch = ((villager.getRandom().nextFloat() - villager.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F;
        world.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.ITEM_PICKUP, SoundCategory.NEUTRAL, 0.2F, pitch);
    }

    private CraftDisplay() {}
}
