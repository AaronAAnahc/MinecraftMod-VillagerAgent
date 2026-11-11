package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The one door through which LLM-authored goals enter a villager's brain: chat replies, autonomous
 * thoughts, dawn reflection and accepted invitations all go through {@link #enqueue}.
 *
 * <p>Pipeline: {@link GoalDraft#bind} (names → ids, clock → ticks) → {@link GoalValidator}
 * (can it run at all?) → cap on requested goals ({@link VillagerAgentManager#getMaxGoals()}) →
 * {@link GoalQueue#add}. Server thread only.
 */
public final class LlmGoalIntake {
    private static final Logger LOGGER = LogManager.getLogger("LlmGoalIntake");

    private LlmGoalIntake() {}

    /** What happened to one LLM goal at the intake. */
    public static final class Outcome {
        /** Goal type as the LLM wrote it. */
        public final String type;
        /** The grounded goal (null when it could not be grounded). */
        public final AgentGoal goal;
        /** True when the goal is (or, in a dry run, would be) in the brain. */
        public final boolean accepted;
        /** Why it was refused; null when accepted. */
        public final String reason;
        Outcome(String type, AgentGoal goal, boolean accepted, String reason) {
            this.type = type; this.goal = goal; this.accepted = accepted; this.reason = reason;
        }
    }

    /** Bind, validate and enqueue drafts. Returns the goals that were actually added. */
    public static List<AgentGoal> enqueue(VillagerAgentData agent, List<GoalDraft> drafts,
                                          VillagerEntity villager, ServerWorld world, String source) {
        return accepted(process(agent, drafts, villager, world, source, true));
    }

    /**
     * Bind and validate drafts, reporting the fate of each. With {@code apply == false} nothing is written
     * to the brain (dry run: used by {@code /va preview}); the cap is still checked against the current list.
     */
    public static List<Outcome> process(VillagerAgentData agent, List<GoalDraft> drafts,
                                        VillagerEntity villager, ServerWorld world, String source, boolean apply) {
        List<Outcome> out = new ArrayList<>();
        int pending = 0; // goals accepted in this batch but (in a dry run) not added
        for (GoalDraft d : drafts) {
            if (LlmGoalCatalog.isRuleOnly(d.type())) { // e.g. TODO: rest from the plain-text protocol
                out.add(reject(agent, source, d.type(), null, "routine-only", apply));
                continue;
            }
            AgentGoal g;
            try {
                g = d.bind(agent, villager, world, true);
            } catch (GoalDraft.Unbindable e) {
                out.add(reject(agent, source, d.type(), null, e.getMessage(), apply));
                continue;
            }
            Outcome o = admit(agent, g, villager, world, source, apply, pending);
            if (o.accepted) pending++;
            out.add(o);
        }
        return out;
    }

    /** Validate and enqueue already-grounded goals (e.g. a copy of an accepted invitation plan). */
    public static List<AgentGoal> enqueueGoals(VillagerAgentData agent, List<AgentGoal> goals,
                                               VillagerEntity villager, ServerWorld world, String source) {
        List<Outcome> out = new ArrayList<>();
        for (AgentGoal g : goals) out.add(admit(agent, g, villager, world, source, true, 0));
        return accepted(out);
    }

    private static Outcome admit(VillagerAgentData agent, AgentGoal g, VillagerEntity villager, ServerWorld world,
                                 String source, boolean apply, int pending) {
        GoalValidator.Result r = GoalValidator.validate(g, agent, villager, world);
        if (!r.ok) return reject(agent, source, g.getGoalType(), g, r.reason, apply);
        String social = socialLimit(agent, g);
        if (social != null) return reject(agent, source, g.getGoalType(), g, social, apply);
        // The cap bounds requested goals only: routine goals are seeded outside this door and must not
        // crowd out everything a player or the LLM asks for.
        if (requestedCount(agent) + pending >= VillagerAgentManager.getMaxGoals()) {
            if (apply) BrainLog.goalSkipped(agent, g, source);
            return new Outcome(g.getGoalType(), g, false, "goal-cap");
        }
        if (!apply) return new Outcome(g.getGoalType(), g, true, null);
        if (!agent.getGoals().add(g)) {
            BrainLog.goalSkipped(agent, g, source);
            return new Outcome(g.getGoalType(), g, false, "no-root-handler");
        }
        agent.addMemory("New goal from " + source + ": " + g.getDescription()
                + " (importance " + g.getImportance() + ")");
        BrainLog.goalAdded(agent, g, source);
        return new Outcome(g.getGoalType(), g, true, null);
    }

    private static Outcome reject(VillagerAgentData agent, String source, String type, AgentGoal g,
                                  String reason, boolean log) {
        if (log) {
            LOGGER.info("[{}] {} rejected LLM goal '{}': {}", source, agent.getName(), type, reason);
            BrainLog.diagnostic(agent, "goal-rejected", "source=" + source + " type=" + type + " reason=" + reason);
        }
        return new Outcome(type, g, false, reason);
    }

    private static List<AgentGoal> accepted(List<Outcome> outcomes) {
        List<AgentGoal> added = new ArrayList<>();
        for (Outcome o : outcomes) if (o.accepted && o.goal != null) added.add(o.goal);
        return added;
    }

    /** At most one invitation under way and {@link #MAX_GROUP_PLANS} accepted group plans per villager. */
    public static final int MAX_GROUP_PLANS = 2;

    /**
     * Keeps self-started socialising in proportion now that villagers invite each other on their own:
     * a new invitation is refused while one is still being delivered or while the villager already has
     * enough shared plans. Returns the rejection reason, or null.
     */
    private static String socialLimit(VillagerAgentData agent, AgentGoal g) {
        if (!"invite".equals(g.getGoalType())) return null;
        int[] counts = socialCounts(agent);
        if (counts[0] > 0) return "already-inviting";
        if (counts[1] >= MAX_GROUP_PLANS) return "enough-group-plans";
        return null;
    }

    /** True while {@code agent} may start a new invitation — the same rule {@link #socialLimit} enforces. */
    public static boolean mayStartGroupPlan(VillagerAgentData agent) {
        int[] counts = socialCounts(agent);
        return counts[0] == 0 && counts[1] < MAX_GROUP_PLANS;
    }

    /** {unfinished invite goals, unfinished group-plan goals}. */
    private static int[] socialCounts(VillagerAgentData agent) {
        int invites = 0, plans = 0;
        for (AgentGoal o : agent.getGoals()) {
            if (o.isCompleted()) continue;
            if ("invite".equals(o.getGoalType())) invites++;
            if (o.getGroupId() != null) plans++;
        }
        return new int[]{invites, plans};
    }

    /** Unfinished goals that someone asked for (LLM or player), as opposed to routine/need goals. */
    public static int requestedCount(VillagerAgentData agent) {
        int n = 0;
        for (AgentGoal g : agent.getGoals()) if (!g.isCompleted() && g.getSource() != AgentGoal.Source.RULE) n++;
        return n;
    }

    /** True if this villager already holds a goal belonging to group activity {@code groupId}. */
    public static boolean hasGroupGoal(VillagerAgentData agent, java.util.UUID groupId) {
        if (groupId == null) return false;
        for (AgentGoal g : agent.getGoals()) if (groupId.equals(g.getGroupId()) && !g.isCompleted()) return true;
        return false;
    }
}
