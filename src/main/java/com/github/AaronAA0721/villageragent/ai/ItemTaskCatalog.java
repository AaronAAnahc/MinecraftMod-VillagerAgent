package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.*;
import net.minecraft.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.function.*;
import static com.github.AaronAA0721.villageragent.ai.AcquisitionMethods.Context;

/** Output-indexed method providers. Bind an item demand once; agents need no personal method lists. */
public final class ItemTaskCatalog {
    private static final Logger LOGGER = LogManager.getLogger("VillagerBrain");
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
        register(new Definition("smelt", c -> com.github.AaronAA0721.villageragent.ai.behavior.SmeltSkill.oreFor(c.target) != Items.AIR,
                ItemTaskCatalog::smeltContract, c -> new AcquisitionMethods.Produce(false, true)));
        // Wildcard output: a free village request is a waiting dependency even when no supplier exists yet.
        register(new Definition(AcquisitionMethods.RequestSupply.ID, c -> c.target != Items.AIR,
                ItemTaskCatalog::outputContract, c -> new AcquisitionMethods.RequestSupply()));

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
        List<Definition> matched = matching(c);
        for (Definition d : matched) {
            TaskContract contract = d.bind(c);
            if (contract.levelsMet(c.agent.getCapabilityPool()::levelOf)) {
                result.add(d.factory.apply(c));
            } else {
                LOGGER.debug("{} | DIAG | method-rejected | item={} method={} reason=capability_gate capabilities={}",
                        c.agent.getName(), c.target.getRegistryName(), d.id,
                        c.agent.getCapabilityPool().snapshot());
            }
        }
        if (result.isEmpty() && c.executing) {
            // Log why no method was found at all — helps diagnose stuck acquire loops.
            StringBuilder sb = new StringBuilder();
            for (Definition d : DEFINITIONS.values()) {
                boolean matches = d.matches.test(c);
                boolean produces = matches && d.bind(c).produces(c.target.getRegistryName().toString());
                boolean leveled = produces && d.bind(c).levelsMet(c.agent.getCapabilityPool()::levelOf);
                sb.append(d.id).append("(match=").append(matches)
                        .append(",produce=").append(produces)
                        .append(",level=").append(leveled).append(") ");
            }
            LOGGER.info("{} | DIAG | discover-empty | item={} profession={} methods=[{}]",
                    c.agent.getName(), c.target.getRegistryName(), c.agent.getProfession(), sb);
        }
        return result;
    }
    /**
     * True when a registered production method (crafting, extraction, smelting) exists for this villager
     * and its capability gates are met. Missing ingredients do not make the output unproducible.
     */
    public static boolean canProduce(Context c) {
        String id = c.target.getRegistryName().toString();
        for (Definition d : matching(c)) {
            if (AcquisitionMethods.RequestSupply.ID.equals(d.id)) continue;
            if (!d.bind(c).levelsMet(c.agent.getCapabilityPool()::levelOf)) continue;
            if ("extract".equals(d.id)) {
                ResourceCatalog.Source source = ResourceCatalog.get(id);
                if (source == null || !source.skilled(c.agent)) continue;
            }
            return true;
        }
        return false;
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
    /** A physical batch needs one ore. Existing furnace fuel/output avoids needless purchases. */
    public static TaskContract smeltContract(Context c) {
        net.minecraft.item.Item ore = com.github.AaronAA0721.villageragent.ai.behavior.SmeltSkill.oreFor(c.target);
        List<TaskContract.ItemNeed> items = new ArrayList<>();
        net.minecraft.util.math.BlockPos pos = c.furnace();
        net.minecraft.tileentity.AbstractFurnaceTileEntity furnace = pos == null ? null
                : (net.minecraft.tileentity.AbstractFurnaceTileEntity) c.world.getBlockEntity(pos);
        boolean ready = furnace != null && !furnace.getItem(2).isEmpty()
                && furnace.getItem(2).getItem() == c.target
                && InventoryTransfer.spaceFor(c.agent.getInventory(), furnace.getItem(2)) > 0;
        if (!ready) {
            boolean pending = furnace != null && !furnace.getItem(0).isEmpty()
                    && net.minecraft.item.ItemStack.isSame(furnace.getItem(0), new net.minecraft.item.ItemStack(ore))
                    && net.minecraft.item.ItemStack.tagMatches(furnace.getItem(0), new net.minecraft.item.ItemStack(ore));
            if (!pending) items.add(new TaskContract.ItemNeed(ore.getRegistryName().toString(), 1, true));
            boolean fuel = furnace != null && (FurnaceInventory.burning(furnace)
                    || net.minecraft.tileentity.AbstractFurnaceTileEntity.isFuel(furnace.getItem(1)));
            for (net.minecraft.item.ItemStack stack : c.agent.getInventory().getItems())
                if (FurnaceInventory.carriedFuel(stack)) fuel = true;
            if (!fuel) items.add(new TaskContract.ItemNeed("minecraft:coal", 1, true));
        }
        return new TaskContract(items, Collections.singletonMap(CapabilityId.SMITHING, 1),
                Collections.singletonMap(c.target.getRegistryName().toString(), 1),
                Collections.singletonMap(CapabilityId.SMITHING, 1));
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
