package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.BehaviorCatalog;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityPool;
import net.minecraft.world.server.ServerWorld;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic derivation of a villager's <em>routine behaviour set</em> — the union of their
 * profession's main duties and any capability-unlocked hobbies. No sampling, no per-day plan:
 * this class only answers "which routine goal types does this villager qualify for?", and
 * {@link RoutineScheduler} seeds each as a persistent {@link AgentGoal.Lifecycle#REPEATABLE} goal
 * exactly once (gated by cooldown + day/night weight at pick time, never evicted).
 *
 * <p>This replaces the old {@code generateRoutineGoals} that sampled {@code GOALS_PER_DAY} ONE_SHOT
 * goals each dawn (the per-day re-injection churn the unified-generator redesign removes).
 */
public final class DeterministicPlanGenerator {

    /** profession (lowercase) -> (behaviour id -> base weight). Main duties get the high weights. */
    private static final Map<String, Map<String, Integer>> PROFESSION_POOL = new HashMap<>();

    static {
        pool("farmer",      "farm", 5, "craft", 2, "socialize", 2);
        pool("fisherman",   "fish", 5, "trade", 2, "socialize", 2);
        pool("weaponsmith", "craft", 5, "socialize", 2);
        pool("toolsmith",   "craft", 5, "socialize", 2);
        pool("armorer",     "craft", 3, "smelt", 5, "socialize", 2);
        pool("librarian",   "craft", 4, "socialize", 4);
        pool("cleric",      "craft", 4, "socialize", 3);
        pool("fletcher",    "fletcher_work", 5, "craft", 4, "socialize", 2);
        pool("leatherworker","craft", 4, "socialize", 2);
        pool("mason",       "craft", 4, "socialize", 2);
        pool("butcher",     "butcher_work", 5, "butcher_cook", 4, "craft", 3, "socialize", 2);
        pool("shepherd",    "shepherd_work", 5, "craft", 3, "socialize", 2);
        pool("cartographer","craft", 4, "socialize", 2);
        pool("lumberjack",  "lumberjack_work", 5, "craft", 2, "socialize", 2);
        pool("miner",       "miner_work", 5, "craft", 2, "socialize", 2);
        // unemployed / nitwit: no main duty — their routine is whatever their hobby unlocks.
        pool("none",        "socialize", 2);
        pool("nitwit",      "socialize", 2);
    }

    private DeterministicPlanGenerator() {}

    private static void pool(String profession, Object... pairs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        PROFESSION_POOL.put(profession, m);
    }

    /**
     * True if {@code type} ({@code craft}/{@code trade}/{@code socialize}) is part of this
     * villager's routine pool — i.e. their profession lists it as a main duty and any capability
     * gate passes. Does <em>not</em> resolve recipes (that is deferred to {@link #chooseCraftTarget},
     * called only when actually seeding the goal). Used by {@link RoutineScheduler} to gate the
     * profession-pool routine types.
     */
    public static boolean isPoolType(VillagerAgentData agent, String type) {
        String profession = agent.getProfession() == null ? "none" : agent.getProfession().toLowerCase(Locale.ROOT);
        Map<String, Integer> profPool = PROFESSION_POOL.getOrDefault(profession, PROFESSION_POOL.get("none"));
        if (!profPool.containsKey(type)) return false;
        return "craft".equals(type) || !isLocked(type, agent.getCapabilityPool());
    }

    /**
     * The routine "craft goods" target: the first item of this profession's stock table
     * ({@link CraftTargets}, {@code craft_targets.json}) that is below its stock count, unlocked for this
     * villager, recipe-backed and makeable by the village. Items whose ingredients are already carried win;
     * otherwise the first one short of materials. Null (no craft routine) when nothing is listed or short.
     */
    public static String chooseCraftTarget(VillagerAgentData agent, ServerWorld world) {
        String needsMaterials = null;
        for (Map.Entry<String, Integer> e : CraftTargets.forProfession(agent.getProfession()).entrySet()) {
            String id = e.getKey();
            if (com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, id) >= e.getValue()) continue;
            if (!agent.getUnlockedRecipes().contains(id)
                    && !SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), id)) continue;
            ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, id);
            if (recipe == null) continue;
            if (!VillageProducible.craftable(world, id)) continue;
            if (NativeRecipeResolver.missingMaterialQuantities(agent.getInventory(), recipe, 1).isEmpty()) return id;
            if (needsMaterials == null) needsMaterials = id;
        }
        return needsMaterials;
    }

    /** How many of {@code id} the routine should still make (stock target minus what is carried). */
    public static int craftShortfall(VillagerAgentData agent, String id) {
        int target = CraftTargets.target(agent.getProfession(), id);
        return Math.max(0, target - com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, id));
    }

    /** One routine run makes one recipe batch (4 torches, 1 bread), capped by the shortfall. */
    public static int craftBatch(VillagerAgentData agent, ServerWorld world, String id) {
        int shortfall = craftShortfall(agent, id);
        ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, id);
        int perCraft = 1;
        if (recipe != null && recipe.kind == ResolvedRecipe.Kind.VANILLA)
            perCraft = Math.max(1, recipe.vanilla.getResultItem().getCount());
        else if (recipe != null) perCraft = Math.max(1, recipe.custom.outputCount);
        return Math.max(1, Math.min(shortfall, perCraft));
    }

    private static boolean isLocked(String behaviorId, CapabilityPool pool) {
        BehaviorCatalog.Entry e = BehaviorCatalog.get(behaviorId);
        return e != null && e.requiredCapability != null && e.minLevel > 0
                && pool.levelOf(e.requiredCapability) < e.minLevel;
    }
}
