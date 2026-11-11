package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.Invitations;
import com.github.AaronAA0721.villageragent.ai.KnownVillagers;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.UUID;

/**
 * "invite" goal: walk to each invitee in turn ({@link VillagerApproach}) and hand them the proposed plan
 * ({@link Invitations#propose}). The invitee's answer arrives asynchronously; this goal does not wait for it —
 * it moves on to the next invitee and succeeds once everyone was visited.
 */
public final class InviteSkill implements VillagerSkill {
    @Override public String id() { return "invite"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "invite".equals(goal.getGoalType()) && goal.getInvitePlan() != null && !goal.getInvitees().isEmpty();
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (goal.getInviteIndex() >= goal.getInvitees().size()) { goal.setCompleted(true); return SkillResult.SUCCESS; }
        if (Invitations.tooLate(goal.getInvitePlan(), world.getGameTime())) {
            agent.addMemory("It is too late to invite anyone to " + goal.getInvitePlan().getDescription());
            return SkillResult.DELETE;
        }
        VillagerApproach.Walk walk = agent.brain().tasks.current().state(VillagerApproach.Walk.class, VillagerApproach.Walk::new);
        UUID id = goal.getInvitees().get(goal.getInviteIndex());
        VillagerAgentData invitee = VillagerAgentManager.getAgent(id);
        Entity e = world.getEntity(id);
        if (invitee == null || !(e instanceof VillagerEntity) || !e.isAlive()) {
            agent.addMemory("Couldn't find " + KnownVillagers.nameOf(id) + " to invite");
            return next(agent, villager, world, goal, null, walk);
        }
        VillagerEntity other = (VillagerEntity) e;
        SkillResult r = VillagerApproach.tick(agent, villager, world, other, invitee.getName(), goal, walk);
        if (r == SkillResult.RUNNING) return SkillResult.RUNNING;
        if (r == SkillResult.SUCCESS) {
            VillagerApproach.stop(agent, villager, world, other, walk);
            Invitations.propose(agent, villager, invitee, other, goal, world);
        } else {
            agent.addMemory("Couldn't reach " + invitee.getName() + " to invite them");
        }
        return next(agent, villager, world, goal, other, walk);
    }

    /** Done with the current invitee: drop any walk still in flight, then advance the cursor. */
    private static SkillResult next(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                    AgentGoal goal, VillagerEntity other, VillagerApproach.Walk walk) {
        VillagerApproach.stop(agent, villager, world, null, walk);
        goal.setInviteIndex(goal.getInviteIndex() + 1);
        goal.markProgress();
        if (goal.getInviteIndex() >= goal.getInvitees().size()) { goal.setCompleted(true); return SkillResult.SUCCESS; }
        return SkillResult.RUNNING;
    }
}
