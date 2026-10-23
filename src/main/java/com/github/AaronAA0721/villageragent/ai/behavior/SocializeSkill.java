package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.DefaultChatLines;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * No-LLM socialise: walk to the nearest idle villager and chat.
 *
 * <p>This is the LLM-off fallback for a {@code socialize} goal — it still happens (the routine
 * generator can emit it), it just degrades to a default chat line from
 * {@code default_chat_lines.json} instead of a language-model dialogue. When the LLM is on,
 * {@code VillagerSocialSystem} layers real conversation on top.
 *
 * <p>Chatting is also the "mentorship" mechanic: each villager slowly gains XP in the
 * <em>other's</em> main capability (talk to a smith → +smithing, talk to a farmer → +farming).
 */
public class SocializeSkill implements VillagerSkill {

    private static final double ARRIVE_SQ = 16.0; // within ~4 blocks counts as "together"
    private static final int STUCK_TIMEOUT = 200;  // ~10 s at 20 tps
    private static final int REL_GAIN = 2;

    @Override public String id() { return "socialize"; }

    // Chat is a universal social reflex — NOT capability-gated (otherwise a SOCIAL-0 villager could
    // never chat nor trade, a bootstrapping deadlock). Chatting itself is what trains SOCIAL.
    @Override public CapabilityId requiredCapability() { return null; }
    @Override public int minLevel() { return 0; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "socialize".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        VillagerAgentData partner = findPartner(world, villager, agent);
        if (partner == null) {
            BrainLog.diagnostic(agent, "social-empty", "no available partner near " + villager.blockPosition()
                    + " perceivedPartner=" + agent.brain().socialPartner);
            agent.addMemory("Wanted to socialise but no other villager is around");
            return SkillResult.WAITING;
        }
        Entity raw = world.getEntity(partner.getVillagerId());
        if (!(raw instanceof VillagerEntity)) {
            BrainLog.diagnostic(agent, "social-empty", "partner entity unloaded id=" + partner.getVillagerId());
            return SkillResult.WAITING;
        }
        VillagerEntity pv = (VillagerEntity) raw;

        // Walk toward the partner until close.
        if (villager.blockPosition().distSqr(pv.blockPosition()) > ARRIVE_SQ) {
            VillagerAction cur = agent.getCurrentAction();
            if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
                cur.setTargetBlockPos(pv.blockPosition());
                cur.incrementStuckTicks();
                if (cur.getStuckTicks() > STUCK_TIMEOUT) {
                    BrainLog.diagnostic(agent, "social-stuck", "partner=" + partner.getVillagerId()
                            + " from=" + villager.blockPosition() + " to=" + pv.blockPosition());
                    agent.setCurrentAction(null);
                    villager.getNavigation().stop();
                    goal.setLastFailure(FailureReason.UNREACHABLE.with(pv.blockPosition().toString()));
                    return SkillResult.FAIL;
                }
                if (cur.getStuckTicks() % 40 == 0) {
                    boolean started = villager.getNavigation().moveTo(pv.getX() + 0.5, pv.getY(), pv.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
                    if (!started) BrainLog.diagnostic(agent, "social-no-path", "partner=" + partner.getVillagerId());
                }
                return SkillResult.RUNNING;
            }
            VillagerAction mv = new VillagerAction(VillagerAction.ActionType.MOVE,
                    "Walking to chat with " + partner.getName());
            mv.setTargetBlockPos(pv.blockPosition());
            mv.setPhase(VillagerAction.ActionPhase.WALKING);
            mv.setGoalDriven(true);
            agent.setCurrentAction(mv);
            boolean started = villager.getNavigation().moveTo(pv.getX() + 0.5, pv.getY(), pv.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
            BrainLog.diagnostic(agent, "social-approach", "partner=" + partner.getVillagerId()
                    + " distanceSq=" + villager.distanceToSqr(pv) + " pathStarted=" + started);
            return SkillResult.RUNNING;
        }

        // Close enough — chat.
        agent.setCurrentAction(null);
        villager.getNavigation().stop();
        BrainLog.diagnostic(agent, "social-chat", "partner=" + partner.getVillagerId()
                + " distanceSq=" + villager.distanceToSqr(pv));
        chat(villager, agent, pv, partner, world);
        goal.setCompleted(true);
        return SkillResult.SUCCESS;
    }

    /** Nearest idle villager other than self, or null. */
    private static VillagerAgentData findPartner(ServerWorld world, VillagerEntity self, VillagerAgentData selfData) {
        VillagerAgentData best = null;
        double bestD = Double.MAX_VALUE;
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (other.getVillagerId().equals(selfData.getVillagerId())) continue;
            if (other.isSocializing() || world.getGameTime() - other.getLastSocialTick() < 400
                    || other.getCurrentAction() != null) continue; // busy
            Entity e = world.getEntity(other.getVillagerId());
            if (e instanceof VillagerEntity && !((VillagerEntity) e).isSleeping()) {
                double d = self.distanceToSqr(e);
                if (d <= 144 && d < bestD) { bestD = d; best = other; }
            }
        }
        return best;
    }

    private static void chat(VillagerEntity a, VillagerAgentData agentA,
                             VillagerEntity b, VillagerAgentData agentB, ServerWorld world) {
        com.github.AaronAA0721.villageragent.ai.GossipChannel.tryGossip(agentA, agentB, world.getGameTime());
        com.github.AaronAA0721.villageragent.ai.VillagerSocialSystem.startConversation(world, a, agentA, b, agentB, world.getGameTime());
    }
}
