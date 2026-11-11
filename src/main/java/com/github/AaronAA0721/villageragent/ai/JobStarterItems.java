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
        jobStarterItems.put("lumberjack", Collections.singletonList(new StarterItem(Items.IRON_AXE, 1)));
        jobStarterItems.put("miner", Collections.singletonList(new StarterItem(Items.IRON_PICKAXE, 1)));
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
        weaponsmithItems.add(new StarterItem(Items.CRAFTING_TABLE, 1));
        jobStarterItems.put("weaponsmith", weaponsmithItems);
        
        // Toolsmith: iron ingots, sticks, coal
        List<StarterItem> toolsmithItems = new ArrayList<>();
        toolsmithItems.add(new StarterItem(Items.IRON_INGOT, 16));
        toolsmithItems.add(new StarterItem(Items.STICK, 32));
        toolsmithItems.add(new StarterItem(Items.COAL, 8));
        // Tools need the 3x3 grid; the smith carries a crafting table and places it when there is none nearby.
        toolsmithItems.add(new StarterItem(Items.CRAFTING_TABLE, 1));
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
        
        // Butcher: animal feed (cows/sheep eat wheat, pigs carrots and potatoes, rabbits carrots) + coal for the smoker
        List<StarterItem> butcherItems = new ArrayList<>();
        butcherItems.add(new StarterItem(Items.CARROT, 16));
        butcherItems.add(new StarterItem(Items.WHEAT, 16));
        butcherItems.add(new StarterItem(Items.POTATO, 16));
        butcherItems.add(new StarterItem(Items.COAL, 4));
        jobStarterItems.put("butcher", butcherItems);
        
        // Armorer: iron ore, coal (raw materials for smelting)
        List<StarterItem> armorerItems = new ArrayList<>();
        armorerItems.add(new StarterItem(Items.IRON_ORE, 8));
        armorerItems.add(new StarterItem(Items.COAL, 8));
        jobStarterItems.put("armorer", armorerItems);
        
        // Fisherman: fishing rod, bucket
        List<StarterItem> fishermanItems = new ArrayList<>();
        fishermanItems.add(new StarterItem(Items.FISHING_ROD, 1));
        fishermanItems.add(new StarterItem(Items.BUCKET, 1));
        jobStarterItems.put("fisherman", fishermanItems);
        
        // Shepherd: shears, wool, wheat to feed the sheep so the flock breeds
        List<StarterItem> shepherdItems = new ArrayList<>();
        shepherdItems.add(new StarterItem(Items.SHEARS, 1));
        shepherdItems.add(new StarterItem(Items.WHITE_WOOL, 16));
        shepherdItems.add(new StarterItem(Items.WHEAT, 16));
        jobStarterItems.put("shepherd", shepherdItems);

        // Fletcher: shovel to dig gravel for flint, bow to defend the village
        List<StarterItem> fletcherItems = new ArrayList<>();
        fletcherItems.add(new StarterItem(Items.IRON_SHOVEL, 1));
        fletcherItems.add(new StarterItem(Items.BOW, 1));
        jobStarterItems.put("fletcher", fletcherItems);
        
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
        // ResourceWorkSystem has its own starter-item path; skip only if the resource
        // system has ALREADY given items (checked by its own flag, not by profession).
        // Note: ResourceWorkSystem.assign is called before this method in setProfession,
        // so we must NOT gate on resourceProfession != NONE — that would block all custom
        // professions (lumberjack, miner) from receiving their tools.
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

