package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.world.server.ServerWorld;

import java.util.UUID;

/**
 * "make_family": walk to the partner reserved by {@link VillagerBreeding}, spend a moment together (hearts), then
 * a baby villager is born with the vanilla offspring logic ({@code VillagerEntity.getBreedOffspring}: villager type
 * from the parents/biome, age −24000, love-hearts event). The partner stops what it is doing and waits while the
 * initiator is close. Both parents spend one day of food and get the vanilla breeding cooldown.
 */
public final class BreedSkill implements VillagerSkill {
    private static final int LOVE_TICKS = 60;
    private static final int TIMEOUT = 1200;
    private static final double MEET_SQ = 2.5 * 2.5;
    private static final double HOLD_PARTNER_SQ = 10 * 10;

    private static final class State { long started = -1; int love; }

    @Override public String id() { return VillagerBreeding.GOAL; }
    @Override public boolean isComposite() { return true; }

    /** Always selectable: without a live reservation (e.g. after a reload) the goal deletes itself on its first tick. */
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) { return 1.5; }

    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }

    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        if (s.started < 0) s.started = now;
        a.brain().goalStartedTick = now;
        UUID partnerId = VillagerBreeding.partnerOf(a.getVillagerId(), now);
        Entity entity = partnerId == null ? null : w.getEntity(partnerId);
        VillagerAgentData pa = partnerId == null ? null : VillagerAgentManager.getAgent(partnerId);
        if (!(entity instanceof VillagerEntity) || pa == null) return give(a, SkillResult.DELETE);
        VillagerEntity partner = (VillagerEntity) entity;
        if (!VillagerBreeding.ready(v, a) || !VillagerBreeding.ready(partner, pa) || now - s.started > TIMEOUT)
            return give(a, SkillResult.DELETE);
        a.setCurrentActivity("courting");

        double d = v.distanceToSqr(partner);
        if (d < HOLD_PARTNER_SQ) {
            // The partner pauses its own plan and waits, looking at the initiator.
            pa.brain().socialUntilTick = Math.max(pa.brain().socialUntilTick, now + 40);
            partner.getNavigation().stop();
            partner.getLookControl().setLookAt(v, 30f, 30f);
        }
        if (d > MEET_SQ) {
            if (now % 10 == 0 || v.getNavigation().isDone())
                v.getNavigation().moveTo(partner, VillagerNavigationSupport.WALK_SPEED);
            return SkillResult.RUNNING;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(partner, 30f, 30f);
        if (s.love++ % 10 == 0) {
            w.sendParticles(ParticleTypes.HEART, v.getX(), v.getY() + 2.1, v.getZ(), 1, 0.3, 0.2, 0.3, 0.0);
            w.sendParticles(ParticleTypes.HEART, partner.getX(), partner.getY() + 2.1, partner.getZ(), 1, 0.3, 0.2, 0.3, 0.0);
        }
        if (s.love < LOVE_TICKS) return SkillResult.RUNNING;

        if (!VillagerBreeding.roomForBaby(w, v)) return give(a, SkillResult.DELETE);
        VillagerEntity baby = v.getBreedOffspring(w, partner);
        if (baby == null) return give(a, SkillResult.FAIL);
        v.setAge(VillagerBreeding.PARENT_COOLDOWN);
        partner.setAge(VillagerBreeding.PARENT_COOLDOWN);
        float meal = VillagerBreeding.dailyFoodPoints();
        VillagerBreeding.spendFood(a, meal);
        VillagerBreeding.spendFood(pa, meal);
        baby.setAge(-24000);
        baby.moveTo(v.getX(), v.getY(), v.getZ(), 0.0F, 0.0F);
        w.addFreshEntityWithPassengers(baby);
        w.broadcastEntityEvent(baby, (byte) 12);
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_CELEBRATE, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        a.addMemory("Had a child with " + pa.getName());
        pa.addMemory("Had a child with " + a.getName());
        BrainLog.diagnostic(a, "breeding-birth", "partner=" + partnerId + " baby=" + baby.getUUID() + " at=" + v.blockPosition());
        g.markProgress();
        VillagerBreeding.release(a.getVillagerId());
        return SkillResult.SUCCESS;
    }

    private static SkillResult give(VillagerAgentData a, SkillResult result) {
        VillagerBreeding.release(a.getVillagerId());
        return result;
    }
}
