package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.RestockSkill;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
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
            "rest", "wander", "farm", "restock", "find_job_site", "craft", "trade", "socialize");

    private RoutineScheduler() {}

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
                    // Existing saves retain routine fields; migrate the old 1000-tick wander
                    // pause so already-managed villagers do not keep standing idle for ~50 s.
                    if ("wander".equals(type)) {
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
                apply(g, 1000, 6.0, 0.0, 6);   // daytime only, re-runs every 1000 ticks (~50 s)
                break;
            case "restock":
                // GATE (行为生成器统一重构方案 §3.4): only employed villagers restock. An
                // unemployed/nitwit villager has no starter items and no workstation — a restock
                // routine for them is a dead goal (the NOHANDLER we saw in the live log).
                if (!JobStarterItems.hasStarterItems(agent.getProfession())) return null;
                g = new AgentGoal("restock", "Restock supplies at the workstation (routine)", 3);
                apply(g, 24000, 2.0, 0.0, 5);  // daytime only, once per day
                break;
            case "rest":
                g = new AgentGoal("rest", "Sleep at night or rest when fatigued", 8);
                apply(g, 0, 0.0, 8.0, 7);      // nighttime only, no cooldown (sleep skill holds it)
                break;
            case "wander":
                g = new AgentGoal("wander", "Wander around (routine)", 2);
                apply(g, 100, 1.0, 1.0, 2);   // short pause before another walk if no other task is ready
                break;
            case "find_job_site":
                // GATE (行为生成器统一重构方案 §六): only jobless non-nitwit villagers seek work.
                // Employed villagers whose workstation was destroyed re-find it via
                // FailureRecoveryPlanner (NO_JOB_SITE), not via this routine.
                if (JobStarterItems.hasStarterItems(agent.getProfession())) return null;
                if (isNitwit(agent.getProfession())) return null;
                if (agent.getResourceProfession() != com.github.AaronAA0721.villageragent.ai.work.ResourceProfession.NONE) return null;
                g = new AgentGoal("find_job_site", "Find a job site to work at (routine)", 4);
                apply(g, 2000, 4.0, 0.0, 4);  // daytime only, re-check every 100 s
                break;
            case "craft":
                // GATE: only professions whose pool lists craft as a main duty (smiths, librarian…)
                // AND who have an unlocked, recipe-backed item to craft.
                if (!DeterministicPlanGenerator.isPoolType(agent, "craft")) return null;
                String item = DeterministicPlanGenerator.chooseCraftTarget(agent, world);
                if (item == null) return null; // nothing craftable — no craft routine
                g = new AgentGoal("craft", "Craft goods at my workstation (routine)", 5);
                g.setTargetItem(item);
                g.setOutcomeItems(java.util.Collections.singleton(item));
                apply(g, 2000, 5.0, 0.0, 5);
                break;
            case "trade":
                // GATE: only professions whose pool lists trade as a main duty (fisherman).
                if (!DeterministicPlanGenerator.isPoolType(agent, "trade")) return null;
                g = new AgentGoal("trade", "Trade with another villager (routine)", 3);
                apply(g, 2000, 2.0, 0.0, 3);
                break;
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
}
