package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.*;
import net.minecraft.item.Items;
import java.util.*;
import java.util.function.*;
import static com.github.AaronAA0721.villageragent.ai.AcquisitionMethods.Context;

/** Output-indexed method providers. Bind an item demand once; agents need no personal method lists. */
public final class ItemTaskCatalog {
    public static final class Definition {
        public final String id;
        private final Predicate<Context> matches;
        private final Function<Context, TaskContract> contract;
        private final Function<Context, TaskMethod<Context>> factory;
        public Definition(String id, Predicate<Context> matches, Function<Context, TaskContract> contract,
                          Function<Context, TaskMethod<Context>> factory) {
            this.id = id; this.matches = matches; this.contract = contract; this.factory = factory;
        }
        public TaskContract bind(Context c) { return contract.apply(c); }
    }
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    static {
        register(new Definition("craft", c -> c.unlocked(), ItemTaskCatalog::craftContract, c -> new AcquisitionMethods.Craft()));
        register(new Definition("craft_inputs", c -> c.unlocked() && CraftingPlan.supports(c.world, c.target),
                ItemTaskCatalog::craftContract, c -> new AcquisitionMethods.Produce(false)));
        register(new Definition("extract", c -> ResourceCatalog.get(c.target.getRegistryName().toString()) != null,
                ItemTaskCatalog::extractionContract, c -> new AcquisitionMethods.Produce(true).bind(c)));
        // Wildcard output: discovery does not imply a seller exists, a price is known, or cash is sufficient.
        register(new Definition("buy_product", c -> c.target != Items.AIR, ItemTaskCatalog::outputContract, c -> new AcquisitionMethods.Buy()));
        register(new Definition("cooperative_supply", c -> {
            ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
            return source != null && source.profession != ResourceProfession.NONE;
        }, ItemTaskCatalog::outputContract, AcquisitionMethods.Supply::new));
    }
    public static void register(Definition definition) {
        if (DEFINITIONS.putIfAbsent(definition.id, definition) != null) throw new IllegalArgumentException("Duplicate method " + definition.id);
    }
    public static List<Definition> matching(Context c) {
        List<Definition> result = new ArrayList<>();
        for (Definition d : DEFINITIONS.values()) if (d.matches.test(c) && d.bind(c).produces(c.target.getRegistryName().toString())) result.add(d);
        return result;
    }
    public static List<TaskMethod<Context>> discover(Context c) {
        List<TaskMethod<Context>> result = new ArrayList<>();
        for (Definition d : matching(c)) if (d.bind(c).levelsMet(c.agent.getCapabilityPool()::levelOf)) result.add(d.factory.apply(c));
        return result;
    }
    public static TaskContract outputContract(Context c) {
        return new TaskContract(Collections.emptyList(), Collections.emptyMap(),
                Collections.singletonMap(c.target.getRegistryName().toString(), Math.max(1, c.desired - c.count(c.target))), Collections.emptyMap());
    }
    public static TaskContract extractionContract(Context c) {
        ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
        List<TaskContract.ItemNeed> items = new ArrayList<>();
        // A usable higher-tier tool satisfies this retained input too; only missing tools need acquisition.
        if (!source.hasTool(c.agent)) items.add(new TaskContract.ItemNeed(source.tool.getRegistryName().toString(), 1, false));
        return new TaskContract(items, Collections.singletonMap(source.capability, 1), outputContract(c).outputs,
                Collections.singletonMap(source.capability, 1));
    }
    public static TaskContract craftContract(Context c) {
        if (c.craftingContract != null) return c.craftingContract;
        List<TaskContract.ItemNeed> items = new ArrayList<>();
        Map<String, Integer> missing = Collections.emptyMap();
        boolean grid = CraftingPlan.supports(c.world, c.target);
        if (grid) {
            Set<net.minecraft.item.Item> relevant = CraftingPlan.acquisitionItems(c.world, c.target);
            Set<net.minecraft.item.Item> preferred = new HashSet<>();
            for (ResourceCatalog.Source source : ResourceCatalog.all())
                if (relevant.contains(source.output) && source.skilled(c.agent)
                        && ResourceSensor.available(c.world, c.villager, c.agent, source)) preferred.add(source.output);
            missing = CraftingPlan.materialRequirements(c.world, c.agent.getInventory(), c.target,
                    Math.max(1, c.desired - c.count(c.target)), preferred);
        }
        else {
            ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(c.world, c.target);
            if (recipe != null) missing = NativeRecipeResolver.missingMaterialQuantities(c.agent.getInventory(), recipe,
                    Math.max(1, c.desired - c.count(c.target)));
        }
        for (Map.Entry<String, Integer> entry : missing.entrySet())
            items.add(new TaskContract.ItemNeed(entry.getKey(), ResourceWorkSystem.count(c.agent, entry.getKey()) + entry.getValue(), true));
        CapabilityId practice = !grid && CapabilityId.mainForProfession(c.agent.getProfession()) == CapabilityId.SMITHING
                ? CapabilityId.SMITHING : CapabilityId.CRAFTING;
        c.craftingContract = new TaskContract(items, Collections.emptyMap(), outputContract(c).outputs, Collections.singletonMap(practice, 1));
        return c.craftingContract;
    }

    /** Bounded recursive cost estimate. Existing inventory is free; unknown/cyclic paths are unavailable. */
    private static final ThreadLocal<Search> SEARCH = new ThreadLocal<>();
    private static final class Search { final DependencyPath<net.minecraft.item.Item> path = new DependencyPath<>(6, 64); }
    public static double minimumCost(Context c) {
        if (c.count(c.target) >= c.desired) return 0;
        boolean root = SEARCH.get() == null;
        if (root) SEARCH.set(new Search());
        Search search = SEARCH.get();
        if (!search.path.enter(c.target)) {
            if (root) SEARCH.remove();
            return Double.POSITIVE_INFINITY;
        }
        try {
            double best = Double.POSITIVE_INFINITY;
            for (TaskMethod<Context> method : discover(c)) best = Math.min(best, method.estimate(c));
            return best;
        } finally { search.path.leave(c.target); if (root) SEARCH.remove(); }
    }
    private ItemTaskCatalog() {}
}
