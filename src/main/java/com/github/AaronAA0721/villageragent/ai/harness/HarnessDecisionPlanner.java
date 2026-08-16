package com.github.AaronAA0721.villageragent.ai.harness;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.UUID;

/**
 * Drives the harness decision loop end-to-end (plan: 技术力提升计划书 §1 / P1).
 *
 * <p>{@link #decideDailyGoal} is the concrete "take over one step of the daily decision" hook:
 * build a {@link DecisionRequest} from the villager's live state, ask the LLM through
 * {@link LLMGuard} (timeout + circuit breaker), parse the {@link DecisionSchema}, validate + ground
 * it with {@link ActionValidator}, record the whole trajectory in {@link DecisionJournal}, and — if
 * accepted — enqueue a concrete {@link AgentGoal}. Any failure degrades gracefully to the rule-based
 * goals that already exist; the harness only ever <em>adds</em> a validated goal.
 *
 * <p>Disabled unless {@code harness_drive_daily_goal} is true, so existing behaviour is untouched
 * until the user opts in.
 */
public final class HarnessDecisionPlanner {

    private HarnessDecisionPlanner() {}

    public static void decideDailyGoal(VillagerAgentData agent, VillagerEntity villager,
                                       ServerWorld world, long gameTick) {
        if (!ModConfig.HARNESS_ENABLED.get()) return;
        if (!ModConfig.HARNESS_DRIVE_DAILY_GOAL.get()) return;

        UUID id = agent.getVillagerId();
        boolean isNight = (world.getDayTime() % 24000L) >= 13000L;

        DecisionRequest req = DecisionRequest.from(agent, agent.getEnvironmentSummary(), isNight, "none");
        String system = "You are the decision brain of a Minecraft villager. Output only the JSON decision.";
        String user = req.toPrompt();

        LLMGuard.query(id, gameTick, system, user).thenAccept(raw -> {
            DecisionSchema schema = DecisionSchema.parse(raw);
            String parsed = (schema == null) ? "(unparseable)" : schema.toString();
            String outcome;
            String validation;

            if (schema == null) {
                outcome = "parse-failed";
                validation = "";
            } else {
                ActionValidator.ValidationResult vr = ActionValidator.validate(schema, agent, villager, world);
                validation = vr.reason;
                if (vr.ok) {
                    enqueueGoal(agent, schema);
                    outcome = "accepted";
                } else {
                    agent.addMemory("Harness rejected decision: " + vr.reason);
                    outcome = "rejected:" + vr.reason;
                }
            }
            DecisionJournal.record(id, DecisionJournal.Kind.DAILY_GOAL, gameTick, user, raw, parsed, validation, outcome);
        }).exceptionally(e -> {
            DecisionJournal.record(id, DecisionJournal.Kind.DAILY_GOAL, gameTick,
                    "decideDailyGoal:" + (agent == null ? "?" : agent.getName()),
                    "(exception)", "error:" + e.getMessage());
            return null;
        });
    }

    /** Map a validated decision to a concrete AgentGoal and enqueue it (capped by max goals). */
    private static void enqueueGoal(VillagerAgentData agent, DecisionSchema schema) {
        DecisionSchema.DecisionAction act = schema.decisionAction();
        String goalType;
        switch (act) {
            case CRAFT:    goalType = "craft"; break;
            case GATHER:
            case HARVEST:
            case GROW:     goalType = "gather"; break;
            case MOVE:     goalType = "move"; break;
            case SOCIALIZE: goalType = "socialize"; break;
            case TRADE:    goalType = "trade"; break;
            case REST:     goalType = "rest"; break;
            default:       goalType = "explore"; break;
        }

        String desc = (schema.rationale != null && !schema.rationale.isEmpty())
                ? schema.rationale
                : (act + (schema.target != null && !schema.target.isEmpty() ? " " + schema.target : ""));

        AgentGoal goal = new AgentGoal(goalType, desc, 6);
        if (schema.target != null && !schema.target.isEmpty()
                && (act == DecisionSchema.DecisionAction.CRAFT || act == DecisionSchema.DecisionAction.GATHER)) {
            goal.setTargetItem(schema.target);
        }

        if (agent.getGoals().size() < VillagerAgentManager.getMaxGoals()) {
            agent.getGoals().add(goal);
            agent.addMemory("Harness goal: " + goal.getDescription());
        }
    }
}
