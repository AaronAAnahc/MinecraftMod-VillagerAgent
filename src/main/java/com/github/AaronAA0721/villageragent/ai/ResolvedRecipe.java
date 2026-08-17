package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Unified recipe handle returned by {@link NativeRecipeResolver#resolveRecipe}.
 *
 * <p>Wraps either a vanilla {@link IRecipe} (crafting table / furnace) or a {@link CustomRecipe}
 * (loaded from JSON). Callers consume it uniformly through {@link #consumeAndProduce} without
 * needing to know which kind it is — this is what lets a villager craft both vanilla items and
 * items that only have a custom recipe (e.g. chainmail, saddle, ender_pearl).
 */
public class ResolvedRecipe {

    public enum Kind { VANILLA, CUSTOM }

    public final Kind kind;
    public final IRecipe<?> vanilla;
    public final CustomRecipe custom;

    private ResolvedRecipe(Kind kind, IRecipe<?> vanilla, CustomRecipe custom) {
        this.kind = kind;
        this.vanilla = vanilla;
        this.custom = custom;
    }

    public static ResolvedRecipe vanilla(IRecipe<?> r) {
        return new ResolvedRecipe(Kind.VANILLA, r, null);
    }

    public static ResolvedRecipe custom(CustomRecipe c) {
        return new ResolvedRecipe(Kind.CUSTOM, null, c);
    }

    /** Output item, for post-processing hooks such as random enchantment. */
    public Item getOutputItem() {
        if (kind == Kind.VANILLA) {
            ItemStack r = vanilla.getResultItem();
            return (r != null && !r.isEmpty()) ? r.getItem() : Items.AIR;
        }
        Item it = ForgeRegistries.ITEMS.getValue(custom.output);
        return (it != null) ? it : Items.AIR;
    }

    /**
     * Consume the required inputs from the inventory and produce the output.
     * @param qty how many copies to produce (inputs are multiplied accordingly)
     * @return true if the craft succeeded, false if materials were insufficient
     */
    public boolean consumeAndProduce(AgentInventory inv, int qty) {
        if (kind == Kind.VANILLA) return NativeRecipeResolver.consumeAndProduce(inv, vanilla, qty);
        return consumeCustom(inv, qty);
    }

    private boolean consumeCustom(AgentInventory inv, int qty) {
        if (qty < 1) qty = 1;

        // Verify availability.
        for (CustomRecipe.IngredientStack is : custom.inputs) {
            Item it = ForgeRegistries.ITEMS.getValue(is.item);
            if (it == null || it == Items.AIR) return false;
            if (inv.countItem(new ItemStack(it, 1)) < is.count * qty) return false;
        }

        // Consume.
        for (CustomRecipe.IngredientStack is : custom.inputs) {
            Item it = ForgeRegistries.ITEMS.getValue(is.item);
            inv.removeItem(new ItemStack(it, 1), is.count * qty);
        }

        // Produce.
        Item out = ForgeRegistries.ITEMS.getValue(custom.output);
        if (out == null || out == Items.AIR) return false;
        inv.addItem(new ItemStack(out, custom.outputCount * qty));
        return true;
    }
}
