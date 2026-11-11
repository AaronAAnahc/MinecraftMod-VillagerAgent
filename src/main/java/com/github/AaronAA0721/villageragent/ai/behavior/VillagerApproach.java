package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.ActionLifecycle;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/**
 * Walk up to another villager for a face-to-face action (invite, ask, give a gift). Walking reuses the ordinary
 * {@code move} skill as a child task whose target follows the other villager; arrival uses the same
 * block-distance measure as the move skill, so "arrived" and "close enough" always agree.
 */
final class VillagerApproach {
    /** Close enough to talk or hand something over (3 blocks). */
    static final double TALK_SQ = 9.0;

    /** Per-task walk state; keep one in the skill's task-frame state. */
    static final class Walk { AgentGoal goal; }

    private VillagerApproach() {}

    /**
     * One tick of approaching {@code other}.
     * @return SUCCESS when within {@link #TALK_SQ}, RUNNING while walking, FAIL when the walk gave up
     */
    static SkillResult tick(VillagerAgentData agent, VillagerEntity self, ServerWorld world, VillagerEntity other,
                            String otherName, AgentGoal owner, Walk walk) {
        if (self.blockPosition().distSqr(other.blockPosition()) <= TALK_SQ) return SkillResult.SUCCESS;
        if (walk.goal != null) walk.goal.setTargetLocation(other.blockPosition());
        SkillResult r = BehaviorExecutor.executeChild(agent, self, world, () -> {
            AgentGoal g = new AgentGoal("move", "walk to " + otherName, owner.getPriority());
            g.setSource(owner.getSource());
            g.setArrivalDistanceSq(TALK_SQ);
            g.setTargetLocation(other.blockPosition());
            walk.goal = g;
            return g;
        });
        if (r == SkillResult.RUNNING) return SkillResult.RUNNING;
        return r == SkillResult.SUCCESS ? SkillResult.SUCCESS : SkillResult.FAIL;
    }

    /** Stop walking: drop a walk still in flight (or just the navigation) and face {@code other} if given. */
    static void stop(VillagerAgentData agent, VillagerEntity self, ServerWorld world, VillagerEntity other, Walk walk) {
        if (agent.brain().tasks.current().child() != null) {
            ActionLifecycle.clearBody(agent, self, world);
            ActionLifecycle.releaseTree(agent.brain().tasks.current().child(), agent, world);
            agent.brain().tasks.discardChild();
        } else {
            self.getNavigation().stop();
        }
        walk.goal = null;
        if (other != null) {
            self.getLookControl().setLookAt(other, 30f, 30f);
            other.getLookControl().setLookAt(self, 30f, 30f);
        }
    }
}
