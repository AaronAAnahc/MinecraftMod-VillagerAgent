package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Butcher routine 1 of 2 — livestock ("butcher_work"). The second routine, cooking the meat, is {@link CookSkill}.
 *
 * <p>Follows {@link AnimalCare}: for every species (cow, mooshroom, pig, sheep, chicken, rabbit) the village keeps
 * a breeding pair. With exactly two adults of a kind in the village the butcher walks over and feeds them
 * (carrots, wheat, potatoes, seeds — whatever each animal eats), so they breed; with more than two adults he
 * slaughters one adult for meat and by-products. Livestock outside the village is hunted. Drops are the vanilla
 * loot and are picked up where the animal fell.
 *
 * <p>One session handles up to {@link #UNITS_PER_SESSION} animals; each finished animal is a yield point.
 */
public final class ButcherSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("ButcherSkill");
    private static final int UNITS_PER_SESSION = 3;
    private static final int MAX_FAILED_TARGETS = 3;

    private static final class State {
        AnimalJob job;
        int done, failed;
        final Set<UUID> rejected = new HashSet<>();
    }

    @Override public String id() { return "butcher_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // More remembered animals = more motivation.
        int animals = 0;
        for (com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm : a.getAllChunkMemories())
            animals += cm.entityCount(com.github.AaronAA0721.villageragent.ai.memory.EntityCategory.ANIMAL);
        return Math.max(0.3, Math.min(3, animals / 5.0));
    }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        if (s.job == null) {
            AnimalCare.Plan plan = AnimalCare.plan(a, v, w, type -> true, true, true, true, s.rejected);
            if (plan == null) {
                if (s.done > 0) return SkillResult.SUCCESS;
                LOG.debug("{} found no livestock work", a.getName());
                g.setLastFailure(FailureReason.UNKNOWN.with("no livestock to tend"));
                return SkillResult.WAITING;
            }
            s.job = new AnimalJob(plan);
            a.setCurrentActivity(plan.kind == AnimalCare.Kind.FEED ? "feeding livestock" : "butchering");
            BrainLog.diagnostic(a, "butcher-plan", "kind=" + plan.kind + " animal=" + s.job.animalName
                    + " at=" + plan.animal.blockPosition());
        }
        AnimalJob.Status status = s.job.tick(a, v, w);
        if (status == AnimalJob.Status.RUNNING) return SkillResult.RUNNING;
        AnimalJob job = s.job;
        s.job = null;
        if (status == AnimalJob.Status.FAILED) {
            s.rejected.add(job.plan.animal.getUUID());
            if (++s.failed >= MAX_FAILED_TARGETS) {
                if (s.done > 0) return SkillResult.SUCCESS;
                g.setLastFailure(FailureReason.UNREACHABLE.with("livestock out of reach"));
                return SkillResult.FAIL;
            }
            return SkillResult.RUNNING;
        }
        switch (job.plan.kind) {
            case FEED: a.addMemory("Fed a " + job.animalName + " so the pair would breed"); break;
            case CULL: a.addMemory("Butchered a " + job.animalName + " from the village herd"); break;
            default:   a.addMemory("Hunted a " + job.animalName + " outside the village"); break;
        }
        a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
        g.markProgress();
        GoalCommitment.workUnitDone(a, now);
        return ++s.done >= UNITS_PER_SESSION ? SkillResult.SUCCESS : SkillResult.RUNNING;
    }
}
