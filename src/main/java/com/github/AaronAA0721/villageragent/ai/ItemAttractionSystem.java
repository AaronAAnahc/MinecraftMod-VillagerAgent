package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;


/** Player-style contact pickup: normal drop physics, then the vanilla take animation. */
public class ItemAttractionSystem {

    /** Same non-riding contact box expansion as vanilla PlayerEntity.aiStep. */
    public static AxisAlignedBB pickupBox(VillagerEntity villager) {
        return villager.getBoundingBox().inflate(1.0, 0.5, 1.0);
    }

    public static void processItemAttraction(VillagerEntity villager, World world, VillagerAgentData agent) {
        if (villager == null || world == null || world.isClientSide || agent == null) return;
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, pickupBox(villager)))
            collect(item, villager, agent, Integer.MAX_VALUE);
    }

    /** Exact insertion, including partial inventory capacity; also used by explicit pickup tasks. */
    public static int collect(ItemEntity item, VillagerEntity villager, VillagerAgentData agent, int limit) {
        if (villager.level.isClientSide || item == null || !item.isAlive() || item.hasPickUpDelay()
                || item.getItem().isEmpty() || limit <= 0
                || !pickupBox(villager).intersects(item.getBoundingBox())
                || (item.getOwner() != null && !item.getOwner().equals(villager.getUUID()))) return 0;
        ItemStack offered = item.getItem().copy();
        offered.setCount(Math.min(limit, offered.getCount()));
        int moved = InventoryTransfer.insert(agent.getInventory(), offered);
        if (moved == 0) return 0;
        // take() broadcasts the vanilla client pickup particle; no per-tick velocity is sent.
        villager.take(item, moved);
        ItemStack remainder = item.getItem().copy(); remainder.shrink(moved);
        if (remainder.isEmpty()) item.remove(); else item.setItem(remainder);
        playPickupSound(villager);
        agent.gainCapabilityXp(capabilityForItem(offered), ModConfig.CAPABILITY_PRACTICE_XP.get(),
                villager, villager.level.getGameTime());
        VillagerEquipmentHelper.refreshEquipment(villager, agent);
        BrainLog.diagnostic(agent, "pickup-collected", "item=" + offered.getItem().getRegistryName() + " count=" + moved);
        return moved;
    }

    private static void playPickupSound(VillagerEntity villager) {
        villager.level.playSound(null,
                villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.ITEM_PICKUP, SoundCategory.PLAYERS,
                0.2F,
                ((villager.getRandom().nextFloat() - villager.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F
        );
    }

    /** Map an absorbed drop to the capability it trains (ores→smithing, crops→farming, else gathering). */
    private static CapabilityId capabilityForItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return CapabilityId.GATHERING;
        Item item = stack.getItem();
        if (item == null) return CapabilityId.GATHERING;
        String id = item.getRegistryName() == null ? "" : item.getRegistryName().toString();
        if (id.contains("ore") || id.contains("ingot") || id.contains("nugget")
                || id.contains("diamond") || id.contains("emerald") || id.contains("gold")
                || id.contains("iron") || id.contains("coal")) {
            return CapabilityId.SMITHING;
        }
        if (id.contains("wheat") || id.contains("carrot") || id.contains("potato")
                || id.contains("beetroot") || id.contains("seed") || id.contains("pumpkin")
                || id.contains("melon") || id.contains("sugar_cane") || id.contains("apple")) {
            return CapabilityId.FARMING;
        }
        return CapabilityId.GATHERING;
    }

    /** Horizontal contact-box expansion; no magnetic attraction is applied. */
    public static double getAttractionRange() {
        return 1.0;
    }
}

