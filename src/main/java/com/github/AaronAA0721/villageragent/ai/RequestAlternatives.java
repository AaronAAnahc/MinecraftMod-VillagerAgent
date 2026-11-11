package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * "Or" requests. A villager that needs 2 sticks and can turn planks into sticks and logs into planks posts
 * {@code 2 sticks | 2 planks | 1 log}: any one of them, delivered in full, is enough because it finishes the
 * conversion itself. Only single-input recipe steps the requester has unlocked are followed (a pickaxe needs
 * sticks <em>and</em> iron, so it has no alternatives), at most {@link #MAX_DEPTH} steps down.
 *
 * <p>Responders treat every alternative as the request's own item: while serving it they may not ask the
 * board for any of them (that would post the same request again), see {@link #forbidden}.
 */
public final class RequestAlternatives {
    private static final int MAX_DEPTH = 3;

    private RequestAlternatives() {}

    /** The spec to post for {@code qty} of the context's exact target. */
    public static SupplyPolicy.Spec build(AcquisitionMethods.Context c, int qty) {
        List<SupplyPolicy.Alt> alts = new ArrayList<>();
        alts.add(new SupplyPolicy.Alt(label(c.target), qty));
        Set<Item> seen = new HashSet<>();
        seen.add(c.target);
        Item current = c.target;
        int currentQty = qty;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (!unlocked(c.agent, current)) break;
            Step step = singleInputStep(c.world, current);
            if (step == null || !seen.add(step.input)) break;
            // Circular inputs (wheat <- hay bale <- wheat) are never alternatives.
            if (VillageProducible.onlyMadeFrom(c.world, step.input, current)) break;
            int batches = (currentQty + step.outputCount - 1) / step.outputCount;
            int inputQty = batches * step.inputCount;
            alts.add(new SupplyPolicy.Alt(label(step.input), inputQty));
            current = step.input;
            currentQty = inputQty;
        }
        return SupplyPolicy.Spec.anyOf(alts);
    }

    /**
     * Items a responder serving a request with this spec must not request from the board itself: the
     * request's own item and every alternative. A category request forbids nothing extra (its output is
     * already production-only); the inputs of a different item may still be requested.
     */
    public static Set<Item> forbidden(SupplyPolicy.Spec spec) {
        if (spec == null) return Collections.emptySet();
        Set<Item> out = new HashSet<>();
        for (String id : spec.items()) {
            Item item = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(id);
            if (item != null && item != Items.AIR) out.add(item);
        }
        return out;
    }

    /** True when the requester now holds one non-primary alternative in full (it should convert it itself). */
    public static boolean holdsAlternative(AcquisitionMethods.Context c, SupplyPolicy.Spec spec) {
        if (spec == null || !spec.hasAlternatives()) return false;
        for (int i = 1; i < spec.alternatives.size(); i++) {
            SupplyPolicy.Alt alt = spec.alternatives.get(i);
            int held = 0;
            for (ItemStack stack : c.agent.getInventory().getItems())
                if (!stack.isEmpty() && SupplyPolicy.altMatches(alt, stack.getItem())) held += stack.getCount();
            if (held >= alt.qty) return true;
        }
        return false;
    }

    /** Wood is interchangeable: any log or any planks is asked for as {@code #log}/{@code #planks}. */
    static String label(Item item) {
        String material = SupplyPolicy.materialCategory(item);
        return material != null ? SupplyPolicy.CATEGORY_PREFIX + material : item.getRegistryName().toString();
    }

    private static boolean unlocked(VillagerAgentData agent, Item item) {
        String id = item.getRegistryName().toString();
        return agent.getUnlockedRecipes().contains(id)
                || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), id);
    }

    private static final class Step {
        final Item input; final int inputCount, outputCount;
        Step(Item input, int inputCount, int outputCount) {
            this.input = input; this.inputCount = inputCount; this.outputCount = outputCount;
        }
    }

    /**
     * A crafting recipe for {@code output} whose ingredients are all the same single input, preferring an
     * input the village can produce (sticks from planks, never from bamboo).
     */
    private static Step singleInputStep(ServerWorld world, Item output) {
        Step fallback = null;
        for (IRecipe<?> recipe : world.getRecipeManager().getRecipes()) {
            if (recipe.getType() != IRecipeType.CRAFTING) continue;
            ItemStack result = recipe.getResultItem();
            if (result == null || result.isEmpty() || result.getItem() != output) continue;
            Step step = singleInput(recipe, result.getCount());
            if (step == null) continue;
            if (VillageProducible.producible(world, step.input)) return step;
            if (fallback == null) fallback = step;
        }
        return fallback;
    }

    private static Step singleInput(IRecipe<?> recipe, int outputCount) {
        Item input = null;
        int count = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            if (input == null) {
                ItemStack[] options = ingredient.getItems();
                if (options.length == 0) return null;
                input = options[0].getItem();
            } else if (!ingredient.test(new ItemStack(input))) return null;
            count++;
        }
        if (input == null || input == Items.AIR || count == 0) return null;
        return new Step(input, count, outputCount);
    }
}
