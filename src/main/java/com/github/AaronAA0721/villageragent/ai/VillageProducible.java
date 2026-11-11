package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SmeltSkill;
import com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Whether the villagers, as a whole, can ever make an item from what they gather themselves: logs, stone and
 * ores (lumberjack/miner), crops (farmer), meat (butcher), wool (shepherd), fish (fisherman), iron/gold ingots
 * (smelting), plus any crafting recipe (vanilla or custom) whose every ingredient has such a source.
 * Items like glass (needs sand) or paper (sugar cane) have none, so they are never chosen as a
 * routine craft target or a required tool: a request for them could never be answered.
 */
public final class VillageProducible {
    /**
     * Outputs of non-catalogued gathering skills (ButcherSkill, ShepherdSkill, FishSkill, FletcherSkill, harvest
     * drops): flint (fletcher gravel digging), feathers and leather (slaughtered livestock), string (fishing loot),
     * so a bow, a fishing rod or arrows can be made and their inputs requested from the village.
     */
    private static final Set<Item> GATHERED = new HashSet<>(Arrays.asList(
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
            Items.WHITE_WOOL, Items.COD, Items.SALMON,
            Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS,
            Items.FLINT, Items.FEATHER, Items.LEATHER, Items.STRING));
    private static final Map<Item, Boolean> CACHE = new HashMap<>();
    private static final Set<Item> IN_PROGRESS = new HashSet<>();

    private VillageProducible() {}

    public static boolean producible(ServerWorld world, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item != null && item != Items.AIR && producible(world, item);
    }

    public static synchronized boolean producible(ServerWorld world, Item item) {
        Boolean cached = CACHE.get(item);
        if (cached != null) return cached;
        if (!IN_PROGRESS.add(item)) return false; // recipe cycle: this path does not prove anything
        boolean result;
        try { result = compute(world, item); } finally { IN_PROGRESS.remove(item); }
        if (result || IN_PROGRESS.isEmpty()) CACHE.put(item, result);
        return result;
    }

    /**
     * True when {@code input} can only be obtained by crafting it from {@code output}: it is not gathered,
     * mined or smelted, and every recipe for it uses {@code output}. Crafting {@code output} from such an input
     * is circular (wheat from a hay bale that is itself made of wheat) and must never be planned.
     */
    private static final Map<Item, Map<Item, Boolean>> ONLY_FROM = new HashMap<>();

    public static boolean onlyMadeFrom(ServerWorld world, Item input, Item output) {
        if (input == null || output == null || input == output) return input == output;
        synchronized (VillageProducible.class) {
            Map<Item, Boolean> byOutput = ONLY_FROM.computeIfAbsent(input, k -> new HashMap<>());
            Boolean cached = byOutput.get(output);
            if (cached != null) return cached;
            boolean result = computeOnlyMadeFrom(world, input, output);
            byOutput.put(output, result);
            return result;
        }
    }

    private static boolean computeOnlyMadeFrom(ServerWorld world, Item input, Item output) {
        String id = input.getRegistryName().toString();
        if (ResourceCatalog.get(id) != null || GATHERED.contains(input) || SmeltSkill.oreFor(input) != Items.AIR) return false;
        ItemStack probe = new ItemStack(output);
        boolean anyRecipe = false;
        for (IRecipe<?> recipe : world.getRecipeManager().getRecipes()) {
            if (recipe.getType() != IRecipeType.CRAFTING) continue;
            ItemStack out = recipe.getResultItem();
            if (out == null || out.isEmpty() || out.getItem() != input) continue;
            anyRecipe = true;
            boolean usesOutput = false;
            for (Ingredient ingredient : recipe.getIngredients()) if (!ingredient.isEmpty() && ingredient.test(probe)) usesOutput = true;
            if (!usesOutput) return false;
        }
        CustomRecipe custom = CustomRecipeRegistry.get(id);
        if (custom != null) {
            anyRecipe = true;
            boolean usesOutput = false;
            for (CustomRecipe.IngredientStack in : custom.inputs) if (output.getRegistryName().equals(in.item)) usesOutput = true;
            if (!usesOutput) return false;
        }
        return anyRecipe;
    }

    /** Recipes or data packs changed (e.g. /reload): forget the answers. */
    public static synchronized void clear() { CACHE.clear(); ONLY_FROM.clear(); }

    private static boolean compute(ServerWorld world, Item item) {
        String id = item.getRegistryName().toString();
        if (ResourceCatalog.get(id) != null || GATHERED.contains(item)) return true;
        if (SmeltSkill.oreFor(item) != Items.AIR) return producible(world, SmeltSkill.oreFor(item));
        return craftable(world, item);
    }

    /**
     * True when a crafting recipe for the item exists whose every input is producible. Use this for craft
     * targets: an item that is only gathered (raw meat, wool from shearing) is producible but not craftable.
     */
    public static boolean craftable(ServerWorld world, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        if (item == null || item == Items.AIR) return false;
        synchronized (VillageProducible.class) {
            IN_PROGRESS.add(item);
            try { return craftable(world, item); } finally { IN_PROGRESS.remove(item); }
        }
    }

    private static boolean craftable(ServerWorld world, Item item) {
        String id = item.getRegistryName().toString();
        // Any crafting recipe with a producible option for every ingredient.
        for (IRecipe<?> recipe : world.getRecipeManager().getRecipes()) {
            if (recipe.getType() != IRecipeType.CRAFTING) continue;
            ItemStack out = recipe.getResultItem();
            if (out == null || out.isEmpty() || out.getItem() != item) continue;
            boolean all = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                boolean any = false;
                for (ItemStack option : ingredient.getItems())
                    if (option.getItem() != item && producible(world, option.getItem())) { any = true; break; }
                if (!any) { all = false; break; }
            }
            if (all) return true;
        }
        CustomRecipe custom = CustomRecipeRegistry.get(id);
        if (custom != null) {
            for (CustomRecipe.IngredientStack input : custom.inputs) {
                Item in = ForgeRegistries.ITEMS.getValue(input.item);
                if (in == null || in == Items.AIR || in == item || !producible(world, in)) return false;
            }
            return true;
        }
        return false;
    }
}
