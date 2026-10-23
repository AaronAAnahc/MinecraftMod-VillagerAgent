package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Item attraction and pickup system for villagers.
 *
 * <p>Mimics vanilla player item pickup behavior:
 * <ul>
 *   <li>Items within {@link #ATTRACTION_RANGE} are smoothly pulled toward the villager
 *       every tick with increasing speed as they get closer.</li>
 *   <li>Items are only collected when they overlap the villager's bounding box
 *       (just like player pickup — the item visually enters the body).</li>
 *   <li>A pickup sound effect plays when an item is collected.</li>
 * </ul>
 *
 * <p>This method must be called <b>every server tick</b> for smooth attraction.
 * The old 10-tick interval caused jerky, laggy-looking item movement.
 */
public class ItemAttractionSystem {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * How far away items start being pulled toward the villager (blocks).
     * Vanilla player attraction range is ~1 block; we use 2 for a slightly
     * more generous pull since villagers don't have player-like movement.
     */
    private static final double ATTRACTION_RANGE = 2.0;

    /**
     * Base attraction speed (blocks/tick²). Items accelerate toward the villager
     * with this force, scaled inversely by distance so closer items move faster.
     * Vanilla player uses ~0.1 at close range.
     */
    private static final double ATTRACTION_SPEED = 0.075;

    /**
     * Process item attraction and pickup for a single villager.
     * <b>Must be called every server tick</b> for smooth item movement.
     */
    public static void processItemAttraction(VillagerEntity villager, World world, VillagerAgentData agent) {
        if (villager == null || world == null || agent == null) return;
        // Search area: villager bounding box inflated by attraction range
        AxisAlignedBB searchBox = villager.getBoundingBox().inflate(ATTRACTION_RANGE);
        List<ItemEntity> nearbyItems = world.getEntitiesOfClass(ItemEntity.class, searchBox);

        for (ItemEntity itemEntity : nearbyItems) {
            if (!itemEntity.isAlive() || itemEntity.getItem().isEmpty()) continue;

            // Items have a pickup delay after spawning (default 10 ticks).
            // Vanilla checks this via the item's age vs pickup delay.
            // We skip items that aren't ready for pickup yet, but still
            // don't attract them (so they scatter naturally first).
            if (itemEntity.hasPickUpDelay()) continue;

            // --- Attraction: apply force toward villager center ---
            attractItemToVillager(itemEntity, villager);

            // --- Pickup: collect when item overlaps villager bounding box ---
            if (villager.getBoundingBox().intersects(itemEntity.getBoundingBox())) {
                pickupItem(itemEntity, villager, agent);
            }
        }
    }

    /**
     * Apply a smooth attraction force pulling the item toward the villager's
     * center mass. Closer items get pulled faster (inverse-distance scaling),
     * mimicking vanilla player pickup magnetism.
     */
    private static void attractItemToVillager(ItemEntity itemEntity, VillagerEntity villager) {
        // Target: villager's center (eye height / 2 roughly)
        double targetX = villager.getX();
        double targetY = villager.getY() + villager.getBbHeight() * 0.5;
        double targetZ = villager.getZ();

        double dx = targetX - itemEntity.getX();
        double dy = targetY - itemEntity.getY();
        double dz = targetZ - itemEntity.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq < 0.01) return; // Already overlapping

        double dist = Math.sqrt(distSq);

        // Normalize direction
        double nx = dx / dist;
        double ny = dy / dist;
        double nz = dz / dist;

        // Speed scales inversely with distance: closer = faster pull.
        // At 2 blocks: speed = 0.075 * (1 - 2/2.5) = 0.015
        // At 1 block:  speed = 0.075 * (1 - 1/2.5) = 0.045
        // At 0.5 blocks: speed = 0.075 * (1 - 0.5/2.5) = 0.06
        double maxRange = ATTRACTION_RANGE + 0.5; // slight buffer
        double speedFactor = ATTRACTION_SPEED * (1.0 - Math.min(dist / maxRange, 0.95));

        Vector3d currentVel = itemEntity.getDeltaMovement();
        itemEntity.setDeltaMovement(
                currentVel.x + nx * speedFactor,
                currentVel.y + ny * speedFactor,
                currentVel.z + nz * speedFactor
        );
    }

    /**
     * Collect an item into the agent's inventory and play the pickup sound.
     * <p>Armor items are handled specially: they are equipped directly into
     * the entity's equipment slots via {@link VillagerEquipmentHelper#tryEquipArmor}.
     * Only if the armor is worse than what is already equipped does it go into
     * the regular inventory. This means equipped armor is <b>not</b> duplicated
     * in the {@link AgentInventory}.
     */
    private static void pickupItem(ItemEntity itemEntity, VillagerEntity villager, VillagerAgentData agent) {
        ItemStack stack = itemEntity.getItem();
        int originalCount = stack.getCount();
        ItemStack toAdd = stack.copy();

        // --- Armor: try to equip directly ---
        if (toAdd.getItem() instanceof ArmorItem) {
            ItemStack result = VillagerEquipmentHelper.tryEquipArmor(villager, toAdd);
            if (result.isEmpty()) {
                // Armor was equipped, nothing left to store
                playPickupSound(villager);
                itemEntity.remove();
                LOGGER.debug("{} equipped {}", agent.getName(), toAdd.getItem().getRegistryName());
                return;
            } else if (result != toAdd) {
                // Swap happened: new armor equipped, old piece returned.
                // Put the old piece into inventory.
                agent.getInventory().addItem(result);
                playPickupSound(villager);
                itemEntity.remove();
                LOGGER.debug("{} equipped {} (swapped out {})", agent.getName(),
                        toAdd.getItem().getRegistryName(), result.getItem().getRegistryName());
                return;
            }
            // else: not better — fall through to add to inventory normally
        }

        // --- Normal item (or armor that wasn't equipped) ---
        if (agent.getInventory().addItem(toAdd)) {
            playPickupSound(villager);
            itemEntity.remove();
            LOGGER.debug("{} picked up {}x {}", agent.getName(), originalCount,
                    stack.getItem().getRegistryName());
            // Absorbing a drop trains the matching capability (ores→smithing, crops→farming, else gathering).
            agent.gainCapabilityXp(capabilityForItem(stack), ModConfig.CAPABILITY_PRACTICE_XP.get(),
                    villager, villager.level.getGameTime());
            // After adding items to inventory, re-evaluate equipment
            // (e.g. armor that wasn't better at direct-equip time may now
            // allow a full refresh to find the best combination)
            VillagerEquipmentHelper.refreshEquipment(villager, agent);
        } else if (toAdd.getCount() < originalCount) {
            // Partial pickup — update the world entity's stack with the remainder
            stack.setCount(toAdd.getCount());
            int absorbed = originalCount - toAdd.getCount();
            playPickupSound(villager);
            LOGGER.debug("{} partially picked up {}x {} ({} remaining)", agent.getName(),
                    absorbed, stack.getItem().getRegistryName(), toAdd.getCount());
        }
        // else: inventory completely full, item stays in world
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

    /** Attraction range in blocks. */
    public static double getAttractionRange() {
        return ATTRACTION_RANGE;
    }
}

