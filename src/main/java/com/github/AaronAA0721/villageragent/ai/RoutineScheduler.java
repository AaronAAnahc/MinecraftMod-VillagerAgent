package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.RestockSkill;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Seeds persistent behaviour templates. Availability and utility come from live facts;
 * day/night fields are retained only for compatibility with older goal producers. */
public final class RoutineScheduler {

    private static final Logger LOGGER = LogManager.getLogger("VillagerRoutine");

    /**
     * Every routine goal type this scheduler understands. Used for the eligibility reconcile pass:
     * a type listed here whose {@link #seed} returns null gets <b>evicted</b>, so types we stop
     * seeding (e.g. {@code socialize}, now event-driven) must stay listed to clean up leftovers.
     */
    private static final List<String> KNOWN_TYPES = Arrays.asList(
            "rest", "wander", "farm", "restock", "find_job_site", "craft", "trade", "socialize",
            "smelt", "collect_smelt", "fish", "butcher_work", "butcher_cook", "shepherd_work", "fletcher_work",
            "lumberjack_work", "miner_work");

    /** Routine notice-board check: at most every ~4 minutes, and a low pull against real work. */
    static final int BOARD_CHECK_COOLDOWN = 4800;
    static final double BOARD_CHECK_WEIGHT = 0.5;

    private RoutineScheduler() {}

    /**
     * Profession work sessions a villager may also be asked for outside its routine (LLM {@code work} goals,
     * asks from neighbours). Main jobs first, so {@link #primaryWork} picks the profession's own job before
     * a hobby (any villager with some FARMING can farm) or the daily restock.
     */
    public static final List<String> WORK_TYPES = Collections.unmodifiableList(Arrays.asList(
            "fish", "smelt", "butcher_work", "shepherd_work", "fletcher_work", "lumberjack_work", "miner_work",
            "butcher_cook", "collect_smelt", "farm", "restock"));

    /** True if {@code type} is one of the profession work sessions in {@link #WORK_TYPES}. */
    public static boolean isWork(String type) { return type != null && WORK_TYPES.contains(type); }

    /** True when this villager's profession and skills qualify it for work {@code type} (the routine's own gate). */
    public static boolean eligible(String type, VillagerAgentData agent, ServerWorld world) {
        return isWork(type) && seed(type, agent, world) != null;
    }

    /** The villager's main job for a "do your work" request, or null when it has none. */
    public static String primaryWork(VillagerAgentData agent, ServerWorld world) {
        for (String type : WORK_TYPES) if (eligible(type, agent, world)) return type;
        return null;
    }

    /**
     * Reconcile the villager's REPEATABLE routine set against their current eligibility, then seed
     * any missing eligible goal. Runs on the think cadence (~every {@code AGENT_THINK_INTERVAL}
     * ticks, not every tick), so the per-type eligibility check is cheap enough.
     *
     * <p>Two responsibilities: (1) <b>evict</b> REPEATABLE routine goals whose type is no longer
     * eligible — e.g. a freshly-employed villager drops their {@code find_job_site} routine, a
     * profession change drops {@code craft}; (2) <b>seed</b> any eligible type that is missing.
     * Both are idempotent, so the routine set is stable across calls.
     */
    public static void ensureRepeatableGoals(VillagerAgentData agent, ServerWorld world) {
        List<AgentGoal> goals = agent.getGoals();
        final AgentGoal running = agent.getRunningGoal();

        for (String type : KNOWN_TYPES) {
            AgentGoal seeded = seed(type, agent, world);
            if (seeded == null) {
                // Ineligible now → evict any lingering REPEATABLE goal of this type (never the one
                // currently running, which self-resolves via NOHANDLER/cooldown).
                goals.removeIf(g -> g.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE
                        && type.equals(g.getGoalType()) && g != running);
                continue;
            }
            // Eligible → ensure present (reuse the built goal; no double-build).
            boolean present = false;
            for (AgentGoal g : goals) {
                if (type.equals(g.getGoalType()) && g.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE) {
                    present = true;
                    if ("craft".equals(type) && g != running
                            && !java.util.Objects.equals(g.getTargetItem(), seeded.getTargetItem())) {
                        g.setTargetItem(seeded.getTargetItem());
                        g.setOutcomeItems(java.util.Collections.singleton(seeded.getTargetItem()));
                        g.resetFailures();
                        g.setCooldownUntilTick(0);
                    }
                    if ("craft".equals(type) && g != running) g.setTargetQuantity(seeded.getTargetQuantity());
                    // Existing saves retain routine fields; migrate the old 1000-tick wander
                    // pause so already-managed villagers do not keep standing idle for ~50 s.
                    // Older saves carry the old board-check cadence (every minute, weight 1, priority 6).
                    if ("trade".equals(type)) {
                        g.setCooldownTicks(seeded.getCooldownTicks());
                        g.setWeight(seeded.getWeight());
                        g.setPriority(seeded.getPriority());
                    }
                    if ("wander".equals(type) || "find_job_site".equals(type)) {
                        g.setCooldownTicks(seeded.getCooldownTicks());
                        long latest = world.getGameTime() + seeded.getCooldownTicks();
                        if (g.getCooldownUntilTick() > latest) g.setCooldownUntilTick(latest);
                    }
                    break;
                }
            }
            if (!present && world.getGameTime() >= agent.brain().retiredRoutines.getOrDefault(type, 0L)) {
                goals.add(seeded);
                BrainLog.goalAdded(agent, seeded, "repeatable:seed");
            }
        }
    }

    /** Build a fully-configured REPEATABLE seed goal for {@code type}, or null if ineligible. */
    private static AgentGoal seed(String type, VillagerAgentData agent, ServerWorld world) {
        AgentGoal g;
        switch (type) {
            case "farm":
                // Only villagers who can actually farm get this routine (FarmSkill gates FARMING>=1).
                if (agent.getCapabilityPool().levelOf(CapabilityId.FARMING) < 1) return null;
                g = new AgentGoal("farm", "Tend the fields (routine)", 5);
                apply(g, 1200, 6.0, 0.0, 6);   // 60-second cooldown after a bounded field session
                break;
            case "restock":
                // GATE (Unified Behavior Generator Refactoring Plan §3.4): only employed villagers restock. An
                // unemployed/nitwit villager has no starter items and no workstation — a restock
                // routine for them is a dead goal (the NOHANDLER we saw in the live log).
                if (!DailySupplies.has(agent.getProfession())) return null;
                g = new AgentGoal("restock", "Restock supplies at the workstation (routine)", 3);
                apply(g, 24000, 2.0, 0.0, 5);  // daytime only, once per day
                break;
            case "rest":
                g = new AgentGoal("rest", "Sleep at night or rest when fatigued", 8);
                apply(g, 0, 0.0, 8.0, 7);      // nighttime only, no cooldown (sleep skill holds it)
                break;
            case "wander":
                g = new AgentGoal("wander", "Wander around (routine)", 2);
                apply(g, 40, 1.0, 1.0, 2);    // 2-second pause before another walk if no other task is ready
                break;
            case "find_job_site":
                net.minecraft.entity.Entity worker = world.getEntity(agent.getVillagerId());
                if (!(worker instanceof net.minecraft.entity.merchant.villager.VillagerEntity)) return null;
                if (com.github.AaronAA0721.villageragent.ai.behavior.FindJobSiteSkill.hasValidJobSite(
                        (net.minecraft.entity.merchant.villager.VillagerEntity) worker, world)) return null;
                if (isNitwit(agent.getProfession())) return null;
                g = new AgentGoal("find_job_site", "Find a job site to work at (routine)", 4);
                apply(g, 100, 4.0, 0.0, 4);   // retry after five seconds
                break;
            case "craft":
                // GATE: only professions whose pool lists craft as a main duty (smiths, librarian…)
                // AND who have an unlocked, recipe-backed item to craft.
                if (!DeterministicPlanGenerator.isPoolType(agent, "craft")) return null;
                String item = DeterministicPlanGenerator.chooseCraftTarget(agent, world);
                if (item == null) return null; // nothing craftable — no craft routine
                g = new AgentGoal("craft", "Craft goods at my workstation (routine)", 5);
                g.setTargetItem(item);
                g.setTargetQuantity(DeterministicPlanGenerator.craftBatch(agent, world, item));
                g.setOutcomeItems(java.util.Collections.singleton(item));
                apply(g, 2000, 5.0, 0.0, 5);
                break;
            case "smelt":
                // All smithing professions (armorer, toolsmith, weaponsmith) smelt ores at a blast furnace.
                if (agent.getCapabilityPool().levelOf(CapabilityId.SMITHING) < 1) return null;
                if (!isSmith(agent.getProfession())) return null;
                g = new AgentGoal("smelt", "Smelt ore at the blast furnace (routine)", 5);
                apply(g, 600, 4.0, 0.0, 5);  // 30-second cooldown between batches
                break;
            case "collect_smelt":
                // Same gate as smelt — collect finished products from nearby furnaces.
                if (agent.getCapabilityPool().levelOf(CapabilityId.SMITHING) < 1) return null;
                if (!isSmith(agent.getProfession())) return null;
                g = new AgentGoal("collect_smelt", "Collect smelted items from furnace (routine)", 6);
                apply(g, 100, 5.0, 2.0, 5);  // short cooldown, active day and night
                break;
            case "fish":
                if (agent.getCapabilityPool().levelOf(CapabilityId.FISHING) < 1) return null;
                if (!isFisherman(agent.getProfession())) return null;
                g = new AgentGoal("fish", "Go fishing (routine)", 5);
                apply(g, 1200, 5.0, 0.0, 5);
                break;
            case "butcher_work":
                if (!isButcher(agent.getProfession())) return null;
                g = new AgentGoal("butcher_work", "Feed, breed and slaughter livestock (routine)", 5);
                apply(g, 1200, 5.0, 0.0, 5);
                break;
            case "butcher_cook":
                // Second butcher routine: cook raw meat in a smoker or on a campfire. CookSkill's chance factor
                // is zero while there is nothing to cook or collect, so the routine only runs when useful.
                if (!isButcher(agent.getProfession())) return null;
                g = new AgentGoal("butcher_cook", "Cook meat in the smoker or on a campfire (routine)", 5);
                apply(g, 600, 4.0, 0.0, 5);
                break;
            case "fletcher_work":
                if (!isFletcher(agent.getProfession())) return null;
                g = new AgentGoal("fletcher_work", "Dig gravel for flint, hunt chickens for feathers, fletch arrows (routine)", 5);
                apply(g, 600, 5.0, 0.0, 5);
                break;
            case "shepherd_work":
                if (!isShepherd(agent.getProfession())) return null;
                g = new AgentGoal("shepherd_work", "Shear sheep (routine)", 5);
                apply(g, 1200, 5.0, 0.0, 5);
                break;
            case "lumberjack_work":
                if (!isLumberjack(agent.getProfession())) return null;
                if (agent.getCapabilityPool().levelOf(CapabilityId.WOODCUTTING) < 1) return null;
                g = new AgentGoal("lumberjack_work", "Fell trees and gather wood (routine)", 5);
                apply(g, 1200, 5.0, 0.0, 5);
                break;
            case "miner_work":
                if (!isMiner(agent.getProfession())) return null;
                if (agent.getCapabilityPool().levelOf(CapabilityId.MINING) < 1) return null;
                g = new AgentGoal("miner_work", "Mine ores underground (routine)", 5);
                // No hardcoded cooldown — recency factor in GoalScheduler handles pacing.
                apply(g, 0, 5.0, 0.0, 5);
                break;
            case "trade": {
                // Checking the village notice board is a standing routine for every resident.
                if (!com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_ECONOMY.get()) return null;
                net.minecraft.entity.Entity reader = world.getEntity(agent.getVillagerId());
                if (!(reader instanceof net.minecraft.entity.merchant.villager.VillagerEntity)
                        || VillageNoticeBoards.position(world, (net.minecraft.entity.merchant.villager.VillagerEntity) reader) == null) return null;
                g = new AgentGoal("trade", "Check the village notice board (routine)", 4);
                // An occasional errand, not a job: a long cooldown and low weight keep board visits rare.
                apply(g, BOARD_CHECK_COOLDOWN, BOARD_CHECK_WEIGHT, BOARD_CHECK_WEIGHT, 4);
                break;
            }
            case "socialize":
                if (!com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_VILLAGER_SOCIAL.get()) return null;
                g = new AgentGoal("socialize", "Chat with a nearby neighbour", 3);
                apply(g, 400, 2.0, 2.0, 4);
                break;
            default:
                return null;
        }
        if ("restock".equals(type)) {
            g.setOutcomeItems(RestockSkill.outcomeFor(agent.getProfession()));
        }
        GoalScheduler.applyWorkPriority(g);
        return g;
    }

    /** Wire the repeatable fields (cooldown + day/night weights + importance) onto {@code g}. */
    private static void apply(AgentGoal g, int cooldownTicks, double dayWeight, double nightWeight, int importance) {
        g.setLifecycle(AgentGoal.Lifecycle.REPEATABLE);
        g.setCooldownTicks(cooldownTicks);
        g.setWeight(Math.max(dayWeight, nightWeight));
        g.setDayWeight(1);
        g.setNightWeight(1);
        g.setImportance(importance);
    }

    private static boolean isNitwit(String profession) {
        return profession != null && "nitwit".equals(profession.toLowerCase(Locale.ROOT));
    }

    private static boolean isArmorer(String profession) {
        return profession != null && "armorer".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isFisherman(String profession) {
        return profession != null && "fisherman".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isButcher(String profession) {
        return profession != null && "butcher".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isFletcher(String profession) {
        return profession != null && "fletcher".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isShepherd(String profession) {
        return profession != null && "shepherd".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isSmith(String profession) {
        if (profession == null) return false;
        String p = profession.toLowerCase(Locale.ROOT);
        return "armorer".equals(p) || "toolsmith".equals(p) || "weaponsmith".equals(p);
    }
    private static boolean isLumberjack(String profession) {
        return profession != null && "lumberjack".equals(profession.toLowerCase(Locale.ROOT));
    }
    private static boolean isMiner(String profession) {
        return profession != null && "miner".equals(profession.toLowerCase(Locale.ROOT));
    }
}
