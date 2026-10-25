package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.GatherPOITask;
import net.minecraft.entity.ai.brain.task.AssignProfessionTask;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.merchant.villager.VillagerProfession;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.server.ServerWorld;
import java.util.Optional;

/** Vanilla POI reservation and profession assignment, driven by our goal scheduler. */
public class FindJobSiteSkill implements VillagerSkill {
    public String id() { return "find_job_site"; }

    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType()) && !v.isBaby()
                && v.getVillagerData().getProfession() != VillagerProfession.NITWIT
                && !v.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent();
    }

    /** Loaded blocks are authoritative. Unloaded sites are retained, never force-loaded. */
    public static boolean hasValidJobSite(VillagerEntity v, ServerWorld w) {
        Optional<GlobalPos> memory = v.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (!memory.isPresent()) return false;
        GlobalPos site = memory.get();
        ServerWorld owner = w.getServer().getLevel(site.dimension());
        if (owner == null || !owner.isLoaded(site.pos())) return true;
        PointOfInterestType expected = v.getVillagerData().getProfession().getJobPoiType();
        boolean valid = PointOfInterestType.forState(owner.getBlockState(site.pos()))
                .map(type -> expected.getPredicate().test(type)).orElse(false);
        if (!valid) {
            v.releasePoi(MemoryModuleType.JOB_SITE);
            v.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            if (memory.equals(v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)))
                v.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        }
        if (valid && memory.equals(v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)))
            v.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        return valid;
    }

    /** Cancel a reserved candidate on preemption; JOB_SITE ownership is kept. */
    public static void releaseCandidate(VillagerEntity v) {
        Optional<GlobalPos> candidate = v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        if (!candidate.isPresent()) return;
        if (!candidate.equals(v.getBrain().getMemory(MemoryModuleType.JOB_SITE)))
            v.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
        v.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
    }

    /** A physically adjacent, visible site needs no MOVE task. Tickets remain atomic. */
    private static void reserveAdjacent(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        java.util.function.Predicate<PointOfInterestType> predicate =
                v.getVillagerData().getProfession().getJobPoiType().getPredicate();
        Optional<BlockPos> nearby = w.getPoiManager().findAllClosestFirst(predicate,
                p -> p.closerThan(v.position(), 2.0) && w.isLoaded(p)
                        && PointOfInterestType.forState(w.getBlockState(p)).map(predicate::test).orElse(false)
                        && w.clip(new RayTraceContext(v.getEyePosition(1), Vector3d.atCenterOf(p),
                            RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, v)).getBlockPos().equals(p),
                v.blockPosition(), 2, PointOfInterestManager.Status.HAS_SPACE).findFirst();
        if (!nearby.isPresent()) return;
        Optional<BlockPos> reserved = w.getPoiManager().take(predicate, nearby.get()::equals, nearby.get(), 1);
        if (!reserved.isPresent()) return;
        v.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(w.dimension(), reserved.get()));
        BrainLog.diagnostic(a, "job-site-adjacent", "reserved=" + reserved.get() + " navigation=unnecessary");
    }

    private static final class State {
        GatherPOITask acquire;
        final AssignProfessionTask assign = new AssignProfessionTask();
        long started = -1;
    }

    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        if (state.started < 0) {
            state.started = now;
            BrainLog.diagnostic(a, "job-site-start", "mode=vanilla-poi-adjacent-v2 profession="
                    + v.getVillagerData().getProfession() + " pos=" + v.blockPosition());
            state.acquire = new GatherPOITask(v.getVillagerData().getProfession().getJobPoiType(),
                    MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, true, Optional.empty());
        }
        Optional<GlobalPos> candidate = v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        if (!candidate.isPresent()) {
            // Enclosed villagers can already touch the site even when navigation cannot build a path.
            reserveAdjacent(a, v, w);
            candidate = v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        }
        if (!candidate.isPresent()) {
            // Vanilla owns its jitter, retry cache, multi-target path search and ticket reservation.
            state.acquire.tryStart(w, v, now);
            state.acquire.doStop(w, v, now);
            candidate = v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
            if (!candidate.isPresent()) {
                if (now - state.started < 80) return SkillResult.RUNNING;
                java.util.function.Predicate<PointOfInterestType> predicate = v.getVillagerData().getProfession().getJobPoiType().getPredicate();
                long total = w.getPoiManager().getCountInRange(predicate, v.blockPosition(), 48, PointOfInterestManager.Status.ANY);
                long free = w.getPoiManager().getCountInRange(predicate, v.blockPosition(), 48, PointOfInterestManager.Status.HAS_SPACE);
                BrainLog.diagnostic(a, "job-site-empty", "mode=vanilla-poi-adjacent-v2 matching=" + total
                        + " free=" + free + " reason=" + (total == 0 ? "no_matching_poi" : free == 0
                        ? "all_occupied" : "no_reachable_candidate"));
                return SkillResult.WAITING;
            }
        }
        GlobalPos site = candidate.get();
        if (!site.dimension().equals(w.dimension()) || !w.isLoaded(site.pos())
                || !PointOfInterestType.forState(w.getBlockState(site.pos()))
                    .map(t -> v.getVillagerData().getProfession().getJobPoiType().getPredicate().test(t)).orElse(false)) {
            releaseCandidate(v);
            return SkillResult.FAIL;
        }
        if (state.assign.tryStart(w, v, now)) {
            state.assign.doStop(w, v, now);
            String name = v.getVillagerData().getProfession().getRegistryName().getPath();
            a.updateProfession(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            v.getNavigation().stop(); a.setCurrentAction(null);
            BrainLog.diagnostic(a, "job-site-claim", "vanilla assigned " + site);
            return SkillResult.SUCCESS;
        }
        if (now - state.started > 600) {
            releaseCandidate(v);
            g.setLastFailure(FailureReason.UNREACHABLE.with(site.pos().toString()));
            return SkillResult.FAIL;
        }
        if (a.getCurrentAction() == null || v.getNavigation().isDone()) {
            int range = w.getPoiManager().getType(site.pos()).map(PointOfInterestType::getValidRange).orElse(1);
            Path path = v.getNavigation().createPath(site.pos(), range);
            if (path == null || path.getNodeCount() == 0 || !path.canReach() || !v.getNavigation().moveTo(path, VillagerNavigationSupport.WALK_SPEED)) {
                releaseCandidate(v);
                g.setLastFailure(FailureReason.UNREACHABLE.with(site.pos().toString()));
                return SkillResult.FAIL;
            }
            VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Walking to reserved job site");
            move.setTargetBlockPos(site.pos()); move.setStandCell(path.getNodePos(path.getNodeCount() - 1)); move.setGoalDriven(true);
            move.setPhase(VillagerAction.ActionPhase.WALKING); a.setCurrentAction(move);
        }
        return SkillResult.RUNNING;
    }
}
