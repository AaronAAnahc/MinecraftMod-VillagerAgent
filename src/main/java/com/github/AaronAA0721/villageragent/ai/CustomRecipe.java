package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Read-only data describing a custom (non-vanilla) crafting recipe, loaded from
 * {@code /villageragent/custom_recipes.json}. Used for items a villager may craft per
 * {@link ProfessionCraftCatalog} but which have no vanilla crafting/smelting recipe
 * (e.g. chainmail_*, saddle, ender_pearl).
 */
public class CustomRecipe {

    public final ResourceLocation output;
    public final int outputCount;
    public final List<IngredientStack> inputs;

    public CustomRecipe(ResourceLocation output, int outputCount, List<IngredientStack> inputs) {
        this.output = output;
        this.outputCount = Math.max(1, outputCount);
        this.inputs = inputs;
    }

    /** One required input material: a registry item id plus a stack count. */
    public static class IngredientStack {
        public final ResourceLocation item;
        public final int count;

        public IngredientStack(ResourceLocation item, int count) {
            this.item = item;
            this.count = Math.max(1, count);
        }
    }
}
