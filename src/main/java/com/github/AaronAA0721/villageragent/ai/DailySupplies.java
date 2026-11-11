package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

/**
 * Raw-material grants villagers receive once per day at their workstation.
 * Tools are NOT included — villagers craft or buy those through the economy.
 */
public final class DailySupplies {

    public static class Supply {
        public final Item item;
        public final int quantity;
        public Supply(Item item, int quantity) { this.item = item; this.quantity = quantity; }
    }

    private static final Map<String, List<Supply>> DAILY = new HashMap<>();

    static {
        // Farmer: seeds are the core consumable (hoe is a tool, not restocked)
        DAILY.put("farmer", Arrays.asList(
                new Supply(Items.WHEAT_SEEDS, 16)));

        // Lumberjack: wood to sell/trade when trees are scarce nearby
        DAILY.put("lumberjack", Arrays.asList(
                new Supply(Items.OAK_LOG, 8)));

        // Miner: cobblestone as base building/crafting material
        DAILY.put("miner", Arrays.asList(
                new Supply(Items.COBBLESTONE, 16)));

        // Smithing professions: iron + coal as smelting/crafting feedstock
        DAILY.put("armorer", Arrays.asList(
                new Supply(Items.IRON_INGOT, 4),
                new Supply(Items.COAL, 4)));
        DAILY.put("toolsmith", Arrays.asList(
                new Supply(Items.IRON_INGOT, 4),
                new Supply(Items.COAL, 4)));
        DAILY.put("weaponsmith", Arrays.asList(
                new Supply(Items.IRON_INGOT, 4),
                new Supply(Items.COAL, 4)));

        // Butcher: fuel for the smoker + feed for the breeding pairs
        DAILY.put("butcher", Arrays.asList(
                new Supply(Items.COAL, 4),
                new Supply(Items.WHEAT, 4),
                new Supply(Items.CARROT, 4),
                new Supply(Items.POTATO, 4)));

        // Fisherman: string to repair / craft rods
        DAILY.put("fisherman", Arrays.asList(
                new Supply(Items.STRING, 4)));

        // Librarian & cartographer: paper as writing material
        DAILY.put("librarian", Arrays.asList(
                new Supply(Items.PAPER, 8)));
        DAILY.put("cartographer", Arrays.asList(
                new Supply(Items.PAPER, 8)));

        // Fletcher: flint + sticks for arrows
        DAILY.put("fletcher", Arrays.asList(
                new Supply(Items.FLINT, 8),
                new Supply(Items.STICK, 8)));

        // Leatherworker: leather as crafting material
        DAILY.put("leatherworker", Arrays.asList(
                new Supply(Items.LEATHER, 4)));

        // Shepherd: wheat to feed the sheep (shearing itself is non-destructive)
        DAILY.put("shepherd", Arrays.asList(
                new Supply(Items.WHEAT, 4)));

        // Cleric: no raw material consumed for now
    }

    /** Get the daily supply list for a profession, or empty if none defined. */
    public static List<Supply> get(String profession) {
        return DAILY.getOrDefault(profession == null ? "" : profession.toLowerCase(Locale.ROOT),
                Collections.emptyList());
    }

    /** Whether a profession has any daily supplies defined. */
    public static boolean has(String profession) {
        return !get(profession).isEmpty();
    }

    /**
     * Grant daily supplies to the villager, capping each item so stock never exceeds
     * twice the daily amount (prevents unbounded accumulation).
     */
    public static int grant(VillagerAgentData agent) {
        int inserted = 0;
        for (Supply s : get(agent.getProfession())) {
            int cap = s.quantity * 2;
            int have = agent.getInventory().countItem(new ItemStack(s.item));
            int give = Math.max(0, Math.min(s.quantity, cap - have));
            if (give > 0) {
                inserted += InventoryTransfer.insert(agent.getInventory(), new ItemStack(s.item, give));
            }
        }
        return inserted;
    }

    private DailySupplies() {}
}
