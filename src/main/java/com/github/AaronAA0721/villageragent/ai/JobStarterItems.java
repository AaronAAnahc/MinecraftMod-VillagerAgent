package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

/**
 * Defines starter items for each profession
 * Villagers receive these items when they get a job or restock at their job block
 */
public class JobStarterItems {
    
    /**
     * Starter item entry: item + quantity
     */
    public static class StarterItem {
        public Item item;
        public int quantity;
        
        public StarterItem(Item item, int quantity) {
            this.item = item;
            this.quantity = quantity;
        }
    }
    
    private static final Map<String, List<StarterItem>> jobStarterItems = new HashMap<>();
    
    static {
        initializeStarterItems();
    }
    
    private static void initializeStarterItems() {
        jobStarterItems.put("lumberjack", Collections.singletonList(new StarterItem(Items.WOODEN_AXE, 1)));
        jobStarterItems.put("miner", Collections.singletonList(new StarterItem(Items.STONE_PICKAXE, 1)));
        // Farmer: seeds, hoe, bone meal
        List<StarterItem> farmerItems = new ArrayList<>();
        farmerItems.add(new StarterItem(Items.WHEAT_SEEDS, 32));
        farmerItems.add(new StarterItem(Items.IRON_HOE, 1));
        farmerItems.add(new StarterItem(Items.BONE_MEAL, 16));
        jobStarterItems.put("farmer", farmerItems);
        
        // Weaponsmith: iron ingots, sticks, coal
        List<StarterItem> weaponsmithItems = new ArrayList<>();
        weaponsmithItems.add(new StarterItem(Items.IRON_INGOT, 8));
        weaponsmithItems.add(new StarterItem(Items.STICK, 32));
        weaponsmithItems.add(new StarterItem(Items.COAL, 8));
        jobStarterItems.put("weaponsmith", weaponsmithItems);
        
        // Toolsmith: iron ingots, sticks, coal
        List<StarterItem> toolsmithItems = new ArrayList<>();
        toolsmithItems.add(new StarterItem(Items.IRON_INGOT, 16));
        toolsmithItems.add(new StarterItem(Items.STICK, 32));
        toolsmithItems.add(new StarterItem(Items.COAL, 8));
        jobStarterItems.put("toolsmith", toolsmithItems);
        
        // Librarian: paper, leather, ink sacs
        List<StarterItem> librarianItems = new ArrayList<>();
        librarianItems.add(new StarterItem(Items.PAPER, 32));
        librarianItems.add(new StarterItem(Items.LEATHER, 16));
        librarianItems.add(new StarterItem(Items.INK_SAC, 8));
        jobStarterItems.put("librarian", librarianItems);
        
        // Cleric: redstone, glowstone, lapis lazuli (1.16.5 compatible)
        List<StarterItem> clericItems = new ArrayList<>();
        clericItems.add(new StarterItem(Items.REDSTONE, 16));
        clericItems.add(new StarterItem(Items.GLOWSTONE, 8));
        clericItems.add(new StarterItem(Items.LAPIS_LAZULI, 8));
        jobStarterItems.put("cleric", clericItems);
        
        // Fisherman: fishing rod, bucket
        List<StarterItem> fishermanItems = new ArrayList<>();
        fishermanItems.add(new StarterItem(Items.FISHING_ROD, 1));
        fishermanItems.add(new StarterItem(Items.BUCKET, 1));
        jobStarterItems.put("fisherman", fishermanItems);
        
        // Shepherd: shears, wool
        List<StarterItem> shepherdItems = new ArrayList<>();
        shepherdItems.add(new StarterItem(Items.SHEARS, 1));
        shepherdItems.add(new StarterItem(Items.WHITE_WOOL, 16));
        jobStarterItems.put("shepherd", shepherdItems);
        
        // Cartographer: paper, compass
        List<StarterItem> cartographerItems = new ArrayList<>();
        cartographerItems.add(new StarterItem(Items.PAPER, 32));
        cartographerItems.add(new StarterItem(Items.COMPASS, 1));
        jobStarterItems.put("cartographer", cartographerItems);
    }
    
    /**
     * Get starter items for a profession
     */
    public static List<StarterItem> getStarterItems(String profession) {
        return jobStarterItems.getOrDefault(profession.toLowerCase(), new ArrayList<>());
    }
    
    /**
     * Give starter items to a villager
     * Respects item stack limits - won't exceed max stack size
     * For example: if farmer has 20 seeds and max is 64, only give 44 more (total 64)
     */
    public static void giveStarterItems(VillagerAgentData agent) {
        // ResourceWorkSystem owns their lifetime-once starter flag, including older saves.
        if (agent.getResourceProfession() != com.github.AaronAA0721.villageragent.ai.work.ResourceProfession.NONE) return;
        List<StarterItem> items = getStarterItems(agent.getProfession());

        for (StarterItem starterItem : items) {
            // Create a temporary ItemStack to get the actual max stack size for this item
            int maxStackSize = starterItem.quantity;

            // Count how many of this item the villager already has
            int currentCount = 0;
            for (ItemStack existingStack : agent.getInventory().getItems()) {
                if (!existingStack.isEmpty() && existingStack.getItem() == starterItem.item) {
                    currentCount += existingStack.getCount();
                }
            }

            // Calculate how many more we can give (don't exceed max stack size)
            int canGive = Math.max(0, maxStackSize - currentCount);

            if (canGive > 0) {
                // Only give what we can fit
                int quantityToGive = Math.min(starterItem.quantity, canGive);
                ItemStack stack = new ItemStack(starterItem.item, quantityToGive);
                agent.getInventory().addItem(stack);
            }
        }
    }
    
    /**
     * Check if a profession has starter items defined
     */
    public static boolean hasStarterItems(String profession) {
        return jobStarterItems.containsKey(profession.toLowerCase());
    }
    
    /**
     * Get all professions with starter items
     */
    public static Set<String> getAllProfessions() {
        return jobStarterItems.keySet();
    }
}

