package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a target item to the game's own recipe (read from {@link ServerWorld#getRecipeManager()})
 * and performs the actual consume → produce on the villager's {@link AgentInventory}.
 *
 * <p>The mod's {@link AgentInventory} is the villager's working inventory, so materials and
 * output live entirely there — no interaction with the vanilla {@code VillagerEntity} inventory.
 *
 * <p>Design choice (per design doc): simplified {@code consume → produce} rather than simulating a
 * crafting grid. Shaped vs shapeless only affects <em>placement</em>, never the set/count of
 * ingredients, so we consume the required item counts and produce the output stack directly.
 */
public final class NativeRecipeResolver {

    private NativeRecipeResolver() {}

    /** Find the first recipe whose output item matches {@code target}. Returns null if none. */
    public static IRecipe<?> findRecipe(ServerWorld world, Item target) {
        if (target == null) return null;
        for (IRecipe<?> recipe : world.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem();
            if (result != null && !result.isEmpty() && result.getItem() == target) {
                return recipe;
            }
        }
        return null;
    }

    public static IRecipe<?> findRecipe(ServerWorld world, String itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        return findRecipe(world, item);
    }

    /**
     * Consume the recipe's ingredients from the inventory and produce the output.
     * Materials are verified and consumed <em>here, at execution time</em> — never at goal
     * creation — so a goal whose materials are lost beforehand fails honestly.
     *
     * @param qty how many copies to produce (ingredients are multiplied accordingly)
     * @return true if the craft succeeded, false if materials were insufficient
     */
    public static boolean consumeAndProduce(AgentInventory inv, IRecipe<?> recipe, int qty) {
        if (recipe == null) return false;
        if (qty < 1) qty = 1;

        // Resolve each ingredient slot to a concrete item, preferring one the villager already has.
        Map<Item, Integer> need = new HashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == null || ing == Ingredient.EMPTY) continue;
            ItemStack[] options = ing.getItems();
            if (options == null || options.length == 0) continue;
            Item pick = null;
            for (ItemStack o : options) {
                if (inv.countItem(o) > 0) {
                    pick = o.getItem();
                    break;
                }
            }
            if (pick == null) pick = options[0].getItem();
            need.put(pick, need.getOrDefault(pick, 0) + 1);
        }

        // Verify availability (multiplied by qty).
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            if (inv.countItem(new ItemStack(e.getKey())) < e.getValue() * qty) {
                return false;
            }
        }

        // Consume.
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            inv.removeItem(new ItemStack(e.getKey()), e.getValue() * qty);
        }

        // Produce.
        ItemStack out = recipe.getResultItem().copy();
        out.setCount(out.getCount() * qty);
        inv.addItem(out);
        return true;
    }

    /** Convenience: list the ingredient items required (for diagnostics/logging). */
    public static List<String> describeIngredients(AgentInventory inv, IRecipe<?> recipe) {
        List<String> out = new ArrayList<>();
        if (recipe == null) return out;
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == null || ing == Ingredient.EMPTY) continue;
            ItemStack[] options = ing.getItems();
            if (options == null || options.length == 0) continue;
            out.add(options[0].getItem().getRegistryName().toString());
        }
        return out;
    }
}
