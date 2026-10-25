package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Bounded, inventory-aware dependency search over ordinary crafting recipes. */
public final class CraftingPlan {
    public final List<Step> steps = new ArrayList<>();
    public final Map<String, Integer> missing = new LinkedHashMap<>();
    public boolean complete;
    private static final Map<ServerWorld, Catalog> CACHE = new WeakHashMap<>();
    private static final class Catalog {
        long tick;
        Map<Item, List<ICraftingRecipe>> recipes;
    }
    private int budget = 4096;
    private final ServerWorld world;
    private final Map<Item, List<ICraftingRecipe>> recipes;

    private CraftingPlan(ServerWorld world) {
        this.world = world;
        Catalog cached = CACHE.get(world);
        if (cached != null && world.getGameTime() >= cached.tick && world.getGameTime() - cached.tick < 100) {
            recipes = cached.recipes; return;
        }
        recipes = new HashMap<>();
        for (IRecipe<?> r : world.getRecipeManager().getRecipes()) {
            // Special recipes have dynamic inputs/output; only standard grids are planned here.
            if (!(r instanceof ShapedRecipe) && !(r instanceof ShapelessRecipe)) continue;
            if (r.isSpecial() || r.getResultItem().isEmpty() || !r.canCraftInDimensions(3, 3)) continue;
            recipes.computeIfAbsent(r.getResultItem().getItem(), k -> new ArrayList<>()).add((ICraftingRecipe) r);
        }
        for (List<ICraftingRecipe> list : recipes.values())
            list.sort(Comparator.comparing(r -> r.getId().toString()));
        Catalog next = new Catalog(); next.tick = world.getGameTime(); next.recipes = recipes;
        CACHE.put(world, next);
    }

    public static boolean supports(ServerWorld w, Item item) {
        return new CraftingPlan(w).recipes.containsKey(item);
    }

    /** Material recipes inherit permission from a known root, including every tag alternative. */
    public static void unlockDependencies(VillagerAgentData agent, ServerWorld world) {
        CraftingPlan catalog = new CraftingPlan(world);
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(agent.getUnlockedRecipes().ids());
        pending.addAll(SkillCraftCatalog.getUnlockedIds(agent.getCapabilityPool(), agent.getProfession()));
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.util.ResourceLocation(id));
            if (item == null) continue;
            if (!agent.getUnlockedRecipes().contains(id)) agent.getUnlockedRecipes().add(id, 0);
            for (ICraftingRecipe r : catalog.recipes.getOrDefault(item, Collections.emptyList()))
                for (Ingredient ing : r.getIngredients()) for (ItemStack option : ing.getItems()) {
                    if (option.getItem() == item || !catalog.recipes.containsKey(option.getItem())) continue;
                    pending.addLast(option.getItem().getRegistryName().toString());
                }
        }
    }

    /** Quantity is additional output items, not recipe repetitions. */
    public static CraftingPlan build(ServerWorld w, AgentInventory inv, Item target, int quantity) {
        CraftingPlan plan = new CraftingPlan(w);
        AgentInventory virtual = copy(inv);
        // Existing target stock does not count toward an explicit craft request.
        int initial = virtual.countItem(new ItemStack(target));
        plan.complete = plan.ensure(virtual, target, initial + Math.max(1, quantity), new HashSet<>(), 0);
        if (!plan.complete) plan.steps.clear();
        else plan.missing.clear();
        return plan;
    }

    private boolean ensure(AgentInventory inv, Item target, int count, Set<Item> ancestors, int depth) {
        if (inv.countItem(new ItemStack(target)) >= count) return true;
        if (--budget <= 0 || depth > 24 || ancestors.contains(target)) return false;
        Set<Item> chain = new HashSet<>(ancestors); chain.add(target);
        Map<String, Integer> bestMissing = null;
        for (ICraftingRecipe recipe : recipes.getOrDefault(target, Collections.emptyList())) {
            AgentInventory trial = copy(inv);
            int checkpoint = steps.size();
            Map<String, Integer> before = new LinkedHashMap<>(missing);
            boolean ok = true;
            while (trial.countItem(new ItemStack(target)) < count && ok) {
                if (--budget <= 0 || steps.size() >= 256) { ok = false; break; }
                CraftingInventory grid = grid();
                ok = fill(recipe, 0, trial, grid, chain, depth);
                if (ok) {
                    // fill reserved inputs in trial. Use the real matcher, assembler and containers.
                    if (!recipe.matches(grid, world)) { ok = false; break; }
                    ItemStack output = recipe.assemble(grid);
                    if (output.isEmpty() || output.getItem() != target) { ok = false; break; }
                    if (InventoryTransfer.insert(trial, output) != output.getCount()) { ok = false; break; }
                    for (ItemStack rest : recipe.getRemainingItems(grid))
                        if (!rest.isEmpty() && InventoryTransfer.insert(trial, rest) != rest.getCount()) { ok = false; break; }
                    if (ok) steps.add(new Step(recipe, grid));
                }
            }
            if (ok) { replace(inv, trial); return true; }
            if (!missing.equals(before) && (bestMissing == null || missing.size() < bestMissing.size()))
                bestMissing = new LinkedHashMap<>(missing);
            while (steps.size() > checkpoint) steps.remove(steps.size() - 1);
            missing.clear(); missing.putAll(before);
        }
        if (bestMissing != null) missing.putAll(bestMissing);
        else missing.merge(target.getRegistryName().toString(),
                count - inv.countItem(new ItemStack(target)), Math::max);
        return false;
    }

    /** Backtrack across tag alternatives and slot allocations; never reserve the same stock twice. */
    private boolean fill(ICraftingRecipe recipe, int slot, AgentInventory inv, CraftingInventory grid,
                         Set<Item> chain, int depth) {
        if (--budget <= 0) return false;
        if (slot >= recipe.getIngredients().size()) return true;
        Ingredient ingredient = recipe.getIngredients().get(slot);
        int index = recipe instanceof ShapedRecipe
                ? (slot / ((ShapedRecipe) recipe).getWidth()) * 3 + slot % ((ShapedRecipe) recipe).getWidth() : slot;
        if (ingredient == Ingredient.EMPTY || ingredient.getItems().length == 0)
            return fill(recipe, slot + 1, inv, grid, chain, depth);
        List<ItemStack> choices = new ArrayList<>();
        // Exact stacks first: Ingredient.test respects the recipe's matching semantics.
        for (ItemStack stock : inv.getItems()) if (!stock.isEmpty() && ingredient.test(stock)) choices.add(stock.copy());
        choices.addAll(Arrays.asList(ingredient.getItems()));
        Map<String, Integer> bestMissing = null;
        for (ItemStack choice : choices) {
            AgentInventory trial = copy(inv);
            int checkpoint = steps.size();
            Map<String, Integer> before = new LinkedHashMap<>(missing);
            boolean supplied = ensure(trial, choice.getItem(), 1, chain, depth + 1);
            if (supplied) {
                for (ItemStack stock : trial.getItems()) {
                    if (stock.isEmpty() || stock.getItem() != choice.getItem() || !ingredient.test(stock)) continue;
                    ItemStack one = stock.copy(); one.setCount(1); stock.shrink(1);
                    grid.setItem(index, one);
                    if (fill(recipe, slot + 1, trial, grid, chain, depth)) { replace(inv, trial); return true; }
                    break;
                }
            }
            if (!missing.equals(before) && (bestMissing == null || missing.size() < bestMissing.size())) bestMissing = new LinkedHashMap<>(missing);
            while (steps.size() > checkpoint) steps.remove(steps.size() - 1);
            missing.clear(); missing.putAll(before);
            grid.setItem(index, ItemStack.EMPTY);
        }
        if (bestMissing != null) missing.putAll(bestMissing);
        return false;
    }

    public static final class Step {
        public final ICraftingRecipe recipe;
        private final List<ItemStack> inputs = new ArrayList<>();
        Step(ICraftingRecipe recipe, CraftingInventory grid) {
            this.recipe = recipe;
            for (int i = 0; i < 9; i++) inputs.add(grid.getItem(i).copy());
        }
        public boolean needsTable() { return !recipe.canCraftInDimensions(2, 2); }
        /** Transaction on a copy: capacity, ingredients and container remainders cannot lose goods. */
        public boolean apply(AgentInventory inv, ServerWorld world) {
            AgentInventory trial = copy(inv);
            CraftingInventory grid = grid();
            for (int i = 0; i < inputs.size(); i++) {
                ItemStack need = inputs.get(i);
                if (need.isEmpty()) continue;
                boolean found = false;
                for (ItemStack stock : trial.getItems()) {
                    if (!stock.isEmpty() && ItemStack.isSame(stock, need) && ItemStack.tagMatches(stock, need)) {
                        stock.shrink(1); grid.setItem(i, need.copy()); found = true; break;
                    }
                }
                if (!found) return false;
            }
            if (!recipe.matches(grid, world)) return false;
            ItemStack output = recipe.assemble(grid);
            if (output.isEmpty() || InventoryTransfer.insert(trial, output) != output.getCount()) return false;
            for (ItemStack rest : recipe.getRemainingItems(grid))
                if (!rest.isEmpty() && InventoryTransfer.insert(trial, rest) != rest.getCount()) return false;
            replace(inv, trial); return true;
        }
    }
    private static CraftingInventory grid() {
        return new CraftingInventory(new Container(null, -1) {
            public boolean stillValid(PlayerEntity player) { return false; }
        }, 3, 3);
    }
    private static AgentInventory copy(AgentInventory inv) {
        AgentInventory result = new AgentInventory(); replace(result, inv); return result;
    }
    private static void replace(AgentInventory target, AgentInventory source) {
        for (int i = 0; i < source.getItems().size(); i++) target.setStackInSlot(i, source.getStackInSlot(i).copy());
    }
}
