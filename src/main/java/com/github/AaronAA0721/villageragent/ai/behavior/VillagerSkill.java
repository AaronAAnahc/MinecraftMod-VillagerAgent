package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/**
 * A concrete, programmatic behaviour a villager can perform without LLM involvement.
 *
 * <p>Skills are the <em>execution</em> half of the dual-track design:
 * the LLM is the <em>planner</em> that only authors/adjusts {@link AgentGoal}s (their
 * content, priority, existence); the {@link BehaviorExecutor} is the autonomous runner
 * that, on a timer and with no LLM call, picks the highest-priority goal and dispatches
 * it to the first skill whose {@link #canHandle} returns true.
 *
 * <p>A skill is deliberately dumb about <em>why</em> a goal exists — it only knows
 * <em>how</em> to achieve the intent encoded in the goal. That keeps LLM latency, cost,
 * and rate-limits entirely out of the per-tick action path.
 */
public interface VillagerSkill {

    /** Stable unique id (e.g. {@code "sleep"}). Used for logging/debug only, never for routing. */
    String id();

    /**
     * Whether this skill can satisfy {@code goal} for this villager right now.
     * Must be a pure predicate — never mutate state. The executor uses it to pick a handler.
     */
    boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world);

    /**
     * Run one tick of the skill toward {@code goal}. May start movement, consume items,
     * or set the villager's current action. Returns a {@link SkillResult} telling the
     * executor whether to keep going, mark done, or escalate.
     */
    SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world);
}
