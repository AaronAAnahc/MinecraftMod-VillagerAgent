package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.BlockState;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.WeakHashMap;

/**
 * Server-side helper for managing villager equipment (armor and held items).
 *
 * <p>Armor is stored <b>only</b> in the vanilla entity equipment slots
 * ({@link EquipmentSlotType#HEAD}, CHEST, LEGS, FEET). It is <b>not</b>
 * duplicated in the {@link AgentInventory}. When a better armor piece is
 * found in inventory it is <em>moved</em> to the equipment slot and the
 * old piece is returned to inventory.
 *
 * <p>Call {@link #refreshEquipment(VillagerEntity, VillagerAgentData)} after any
 * inventory change (item pickup, trade, etc.) to keep equipment in sync.
 */
public class VillagerEquipmentHelper {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Equipment slots to scan for armor. */
    private static final EquipmentSlotType[] ARMOR_SLOTS = {
            EquipmentSlotType.HEAD,
            EquipmentSlotType.CHEST,
            EquipmentSlotType.LEGS,
            EquipmentSlotType.FEET
    };

    /**
     * Scan the agent's inventory for armor items. For each slot, if a piece in
     * the inventory is better than the currently equipped one, swap them:
     * the new piece is <b>removed</b> from inventory and equipped; the old
     * piece (if any) is <b>added</b> back to inventory.
     */
    public static void refreshEquipment(VillagerEntity villager, VillagerAgentData agent) {
        if (villager == null || agent == null) return;
        AgentInventory inv = agent.getInventory();

        for (EquipmentSlotType slot : ARMOR_SLOTS) {
            int bestIdx = findBestArmorIndexForSlot(inv, slot);
            if (bestIdx < 0) continue; // no armor for this slot in inventory

            ItemStack candidate = inv.getStackInSlot(bestIdx);
            ItemStack current = villager.getItemBySlot(slot);

            if (isBetterArmor(candidate, current)) {
                // Remove candidate from inventory
                inv.setStackInSlot(bestIdx, ItemStack.EMPTY);

                // Equip the new piece
                villager.setItemSlot(slot, candidate.copy());

                // Return old piece to inventory (if any)
                if (!current.isEmpty()) {
                    inv.addItem(current.copy());
                }

                LOGGER.debug("Villager equipped {} in {} slot (replaced {})",
                        candidate.getItem().getRegistryName(), slot.getName(),
                        current.isEmpty() ? "nothing" : current.getItem().getRegistryName());
            }
        }
    }

    /**
     * Try to equip a single armor item directly (e.g. on pickup).
     * If the item is better than the currently equipped armor for its slot,
     * equip it and return the old piece (which the caller should put into
     * inventory). If not better, returns the original item unchanged so
     * the caller can add it to inventory normally.
     *
     * @return {@link ItemStack#EMPTY} if the item was equipped (nothing to store),
     *         the old equipped piece if a swap happened, or the original
     *         {@code armorStack} if it was not equipped.
     */
    public static ItemStack tryEquipArmor(VillagerEntity villager, ItemStack armorStack) {
        if (villager == null || armorStack.isEmpty()) return armorStack;
        if (!(armorStack.getItem() instanceof ArmorItem)) return armorStack;

        ArmorItem armorItem = (ArmorItem) armorStack.getItem();
        EquipmentSlotType slot = armorItem.getSlot();
        ItemStack current = villager.getItemBySlot(slot);

        if (isBetterArmor(armorStack, current)) {
            // Equip the new piece
            villager.setItemSlot(slot, armorStack.copy());
            // Return old piece (may be EMPTY)
            return current.isEmpty() ? ItemStack.EMPTY : current.copy();
        }
        // Not better — caller should store in inventory
        return armorStack;
    }

    /**
     * Find the inventory index of the best armor for the given slot.
     * @return index into inventory, or -1 if none found
     */
    private static int findBestArmorIndexForSlot(AgentInventory inv, EquipmentSlotType slot) {
        int bestIdx = -1;
        int bestDefense = 0;

        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof ArmorItem)) continue;

            ArmorItem armor = (ArmorItem) stack.getItem();
            if (armor.getSlot() != slot) continue;

            int defense = armor.getDefense();
            if (defense > bestDefense) {
                bestDefense = defense;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Compare two armor pieces. Returns {@code true} if {@code candidate} is
     * strictly better than {@code current}.
     */
    private static boolean isBetterArmor(ItemStack candidate, ItemStack current) {
        if (candidate.isEmpty()) return false;
        if (current.isEmpty()) return true;

        if (!(candidate.getItem() instanceof ArmorItem)) return false;
        if (!(current.getItem() instanceof ArmorItem)) return true;

        return ((ArmorItem) candidate.getItem()).getDefense()
                > ((ArmorItem) current.getItem()).getDefense();
    }

    /**
     * Scan the agent's inventory for the tool that destroys {@code state} fastest and
     * equip it in the villager's main hand. Fully algorithmic (no LLM).
     *
     * @return the ItemStack of the chosen tool (may be {@link ItemStack#EMPTY} if the
     *         villager has nothing better than a bare hand)
     */
    public static ItemStack equipBestToolForBlock(VillagerEntity villager, VillagerAgentData agent, BlockState state) {
        AgentInventory inv = agent.getInventory();
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0f; // bare-hand baseline

        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = stack;
            }
        }

        if (villager != null) {
            villager.setItemSlot(EquipmentSlotType.MAINHAND, best.copy());
        }
        return best; // inventory-owned stack; durability must affect the real tool
    }

    /**
     * Compute how many ticks it takes the villager to break {@code state} with {@code tool},
     * matching the player's per-tick break progress exactly.
     *
     * <p>Primary path uses a shared {@link FakePlayer} + the vanilla
     * {@code BlockState.getDestroyProgress(PlayerEntity, IBlockReader, BlockPos)} formula, which
     * already accounts for tool effectiveness, tier speed and Efficiency/Haste enchantments.
     * Falls back to a hand-rolled formula if the FakePlayer path is unavailable.
     *
     * @return ticks to break, or {@link Integer#MAX_VALUE} if the block is unbreakable
     */
    public static int computeBreakTicks(ServerWorld world, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(world, pos);
        if (hardness < 0.0f) return Integer.MAX_VALUE; // unbreakable (bedrock, etc.)

        FakePlayer fp = getSharedFakePlayer(world);
        if (fp != null) {
            try {
                fp.setItemSlot(EquipmentSlotType.MAINHAND, tool.copy());
                fp.setOnGround(true); // mimic a grounded player so speed isn't /5
                float perTick = state.getDestroyProgress(fp, world, pos);
                if (perTick <= 0.0f) return Integer.MAX_VALUE;
                return Math.max(1, (int) Math.ceil(1.0f / perTick));
            } catch (Exception e) {
                LOGGER.warn("FakePlayer break-speed calc failed, using formula fallback: " + e.getMessage());
            }
        }

        // Fallback: vanilla-style formula (no Efficiency/Haste — conservative lower bound)
        float toolSpeed = tool.isEmpty() ? 1.0f : tool.getItem().getDestroySpeed(tool, state);
        boolean effective = !tool.isEmpty() && tool.getItem().isCorrectToolForDrops(state);
        float perTick = toolSpeed / hardness / (effective ? 30.0f : 100.0f);
        if (perTick <= 0.0f) return Integer.MAX_VALUE;
        return Math.max(1, (int) Math.ceil(1.0f / perTick));
    }

    /** One shared FakePlayer per ServerWorld (lightweight, never ticks). */
    private static final WeakHashMap<ServerWorld, FakePlayer> FAKE_PLAYERS = new WeakHashMap<>();
    private static FakePlayer getSharedFakePlayer(ServerWorld world) {
        synchronized (FAKE_PLAYERS) {
            FakePlayer fp = FAKE_PLAYERS.get(world);
            if (fp == null) {
                fp = FakePlayerFactory.getMinecraft(world);
                FAKE_PLAYERS.put(world, fp);
            }
            return fp;
        }
    }
}

