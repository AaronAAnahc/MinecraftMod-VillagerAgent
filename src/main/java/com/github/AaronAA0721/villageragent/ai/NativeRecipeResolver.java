package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
     * Resolve a craftable item to a {@link ResolvedRecipe}. Tries the game's own vanilla recipe
     * (crafting table / furnace) first; if none exists, falls back to a {@link CustomRecipe}
     * loaded from {@code custom_recipes.json}. Returns null when neither applies.
     */
    public static ResolvedRecipe resolveRecipe(ServerWorld world, String itemId) {
        IRecipe<?> vanilla = findRecipe(world, itemId);
        if (vanilla != null) return ResolvedRecipe.vanilla(vanilla);
        CustomRecipe custom = CustomRecipeRegistry.get(itemId);
        if (custom != null) return ResolvedRecipe.custom(custom);
        return null;
    }

    public static ResolvedRecipe resolveRecipe(ServerWorld world, Item target) {
        if (target == null) return null;
        return resolveRecipe(world, target.getRegistryName().toString());
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

    /**
     * List the recipe's ingredient item ids (registry names) that the inventory does <em>not</em>
     * have enough of for the requested {@code qty}. Shared by {@link FailureRecoveryPlanner} (to
     * recurse the recovery chain on missing materials) and {@code CraftSkill} (to report which
     * material caused a craft to fail). Empty list = everything in stock.
     */
    public static List<String> missingMaterials(AgentInventory inv, ResolvedRecipe recipe, int qty) {
        return new ArrayList<>(missingMaterialQuantities(inv, recipe, qty).keySet());
    }

    /** Concrete deficits for the same recipe choices used during consumption. */
    public static Map<String, Integer> missingMaterialQuantities(AgentInventory inv, ResolvedRecipe recipe, int qty) {
        Map<Item, Integer> required = new java.util.LinkedHashMap<>();
        Map<String, Integer> missing = new java.util.LinkedHashMap<>();
        if (recipe == null) return missing;
        qty = Math.max(1, qty);
        if (recipe.kind == ResolvedRecipe.Kind.VANILLA) {
            for (Ingredient ingredient : recipe.vanilla.getIngredients()) {
                if (ingredient == null || ingredient == Ingredient.EMPTY) continue;
                ItemStack[] options = ingredient.getItems();
                if (options.length == 0) continue;
                Item pick = options[0].getItem();
                for (ItemStack option : options) if (inv.countItem(option) > 0) { pick = option.getItem(); break; }
                required.merge(pick, qty, Integer::sum);
            }
        } else {
            for (CustomRecipe.IngredientStack ingredient : recipe.custom.inputs) {
                Item item = ForgeRegistries.ITEMS.getValue(ingredient.item);
                if (item != null && item != Items.AIR) required.merge(item, ingredient.count * qty, Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            int shortage = entry.getValue() - inv.countItem(new ItemStack(entry.getKey()));
            if (shortage > 0) missing.put(entry.getKey().getRegistryName().toString(), shortage);
        }
        return missing;
    }
}
