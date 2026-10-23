package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.merchant.villager.VillagerProfession;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * "find_job_site" goal: use the vanilla <b>point-of-interest manager</b> to find the nearest
 * unclaimed workstation (48-block sphere, exactly like vanilla {@code AcquirePoi}), walk there,
 * and claim it by writing {@code JOB_SITE} (+ {@code POTENTIAL_JOB_SITE}) memory. Replaces the old
 * hand-rolled 16-radius block scan in {@code VillagerEventHandler.assignNearestJobSite}.
 *
 * <p>Two triggers reach this skill (行为生成器统一重构方案):
 * <ul>
 *   <li><b>Routine</b> (type2): a jobless villager has a REPEATABLE {@code find_job_site} routine
 *       and seeks <em>any</em> job site ({@code PointOfInterestType.ALL_JOBS}); on claim it takes on
 *       the workstation's profession.</li>
 *   <li><b>Recovery</b> (type1): {@code FailureRecoveryPlanner} injects a {@code find_job_site} goal
 *       when a restock/craft fails with {@code NO_JOB_SITE} (an employed villager whose workstation
 *       was destroyed). Here the target profession is already known, so it searches that specific
 *       POI type and only re-establishes {@code JOB_SITE} — no profession change.</li>
 * </ul>
 */
public class FindJobSiteSkill implements VillagerSkill {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Search radius in blocks — mirrors vanilla's 48-block job-site acquisition sphere. */
    private static final int SEARCH_RADIUS = 48;

    @Override public String id() { return "find_job_site"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!"find_job_site".equals(goal.getGoalType())) return false;
        if (villager.isBaby()) return false;                 // babies don't work
        if (villager.getVillagerData().getProfession() == VillagerProfession.NITWIT) return false;
        // Already claimed one (e.g. another villager's claim synced in) — nothing left to find.
        if (villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent()) return false;
        return true;
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        // Defensive: a JOB_SITE appeared since canHandle ran → done.
        if (villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent()) {
            goal.setCompleted(true);
            return SkillResult.DELETE;
        }

        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && !cur.isGoalDriven()) { agent.setCurrentAction(null); cur = null; }

        // ── Phase A: walking to the workstation ──
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
            BlockPos target = cur.getTargetBlockPos();
            if (target == null) { agent.setCurrentAction(null); return SkillResult.RUNNING; }
            if (villager.blockPosition().distSqr(target) <= VillagerAgentManager.GOAL_ARRIVE_SQ) {
                if (!claim(villager, agent, world, target)) {
                    goal.setLastFailure(FailureReason.NO_JOB_SITE.with((String) null));
                    return SkillResult.FAIL;
                }
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                return SkillResult.DELETE;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > VillagerAgentManager.GOAL_STUCK_TIMEOUT) {
                agent.brain().rejectedJobSites.put(target.immutable(), world.getGameTime() + 1200);
                agent.addMemory("Couldn't reach the job site I found");
                goal.setLastFailure(FailureReason.UNREACHABLE.with((String) null));
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                return SkillResult.FAIL;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
            }
            return SkillResult.RUNNING;
        }

        // ── Phase B: find the nearest unclaimed job site ──
        if (cur != null) return SkillResult.RUNNING; // unrelated action in progress

        BlockPos found = findJobSite(agent, villager, world);
        if (found == null) {
            BrainLog.diagnostic(agent, "job-site-empty", "no free POI within " + SEARCH_RADIUS
                    + " blocks of " + villager.blockPosition() + " profession=" + agent.getProfession());
            // No workstation within range. No failure recorded (lastFailure stays null) so the
            // recovery planner does NOT re-inject another find_job_site — the REPEATABLE routine
            // simply cools down and retries later.
            agent.addMemory("Looked for a job site but found none nearby");
            goal.setCompleted(true);
            return SkillResult.FAIL;
        }

        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Walking to a job site");
        move.setTargetBlockPos(found);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        move.setGoalDriven(true);
        agent.setCurrentAction(move);
        boolean started = villager.getNavigation().moveTo(found.getX() + 0.5, found.getY(), found.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
        BrainLog.diagnostic(agent, "job-site-approach", "target=" + found + " pathStarted=" + started);
        if (!started) {
            goal.setLastFailure(FailureReason.UNREACHABLE.with(found.toString()));
            return SkillResult.FAIL;
        }
        return SkillResult.RUNNING;
    }

    /** Find the nearest unclaimed workstation POI, or null. */
    private BlockPos findJobSite(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        PointOfInterestManager poi = world.getPoiManager();
        PointOfInterestType target = villager.getVillagerData().getProfession().getJobPoiType();
        boolean seekingAny = (target == null || target == PointOfInterestType.UNEMPLOYED);

        Predicate<PointOfInterestType> typePred;
        if (seekingAny) {
            typePred = PointOfInterestType.ALL_JOBS;
        } else {
            final PointOfInterestType t = target;
            typePred = po -> po == t;
        }

        long now = world.getGameTime();
        agent.brain().rejectedJobSites.entrySet().removeIf(e -> e.getValue() <= now);
        java.util.List<BlockPos> candidates = poi.findAllClosestFirst(typePred,
                p -> !agent.brain().rejectedJobSites.containsKey(p), villager.blockPosition(),
                SEARCH_RADIUS, PointOfInterestManager.Status.HAS_SPACE)
                .limit(8).collect(java.util.stream.Collectors.toList());
        for (BlockPos p : candidates) {
            if (!world.isLoaded(p)) continue;
            net.minecraft.pathfinding.Path path = villager.getNavigation().createPath(p, 2);
            if (path != null && path.getNodeCount() > 0
                    && (path.canReach() || path.getNodePos(path.getNodeCount() - 1).distSqr(p)
                        < villager.blockPosition().distSqr(p) - 4)) {
                BrainLog.diagnostic(agent, "job-site-select", "target=" + p + " candidates=" + candidates.size()
                        + " complete=" + path.canReach() + " rejected=" + agent.brain().rejectedJobSites.size());
                return p;
            }
            agent.brain().rejectedJobSites.put(p.immutable(), now + 1200);
            BrainLog.diagnostic(agent, "job-site-reject", "target=" + p + " reason=no useful path retryAt=" + (now + 1200));
        }
        return null;
    }

    /** Claim {@code pos} as this villager's job site; adopt its profession if the villager was jobless. */
    private boolean claim(VillagerEntity villager, VillagerAgentData agent, ServerWorld world, BlockPos pos) {
        PointOfInterestType expected = villager.getVillagerData().getProfession().getJobPoiType();
        // Revalidate and reserve atomically: the block may have gone or another villager arrived first.
        Optional<BlockPos> reserved = world.getPoiManager().take(
                type -> PointOfInterestType.ALL_JOBS.test(type)
                        && (expected == null || expected == PointOfInterestType.UNEMPLOYED || type == expected),
                candidate -> candidate.equals(pos), pos, 1);
        if (!reserved.isPresent()) return false;
        GlobalPos gp = GlobalPos.of(world.dimension(), pos);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, gp);
        villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, gp);

        // If the villager was unemployed, take on the workstation's profession (vanilla behaviour).
        Optional<PointOfInterestType> typeOpt = world.getPoiManager().getType(pos);
        if (typeOpt.isPresent()) {
            VillagerProfession prof = professionForPoi(typeOpt.get());
            if (prof != null && prof != VillagerProfession.NONE && prof != VillagerProfession.NITWIT
                    && villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                villager.setVillagerData(villager.getVillagerData().setProfession(prof));
                agent.updateProfession(humanizeProfession(prof));
            }
        }
        agent.addMemory("Claimed my " + agent.getProfession() + " workstation at " + pos);
        LOGGER.info("{} claimed job site {} ({})", agent.getName(), pos, agent.getProfession());
        return true;
    }

    /** Reverse-map a job-site POI type to the profession it grants, or null. */
    private static VillagerProfession professionForPoi(PointOfInterestType type) {
        if (type == PointOfInterestType.FARMER)        return VillagerProfession.FARMER;
        if (type == PointOfInterestType.ARMORER)       return VillagerProfession.ARMORER;
        if (type == PointOfInterestType.BUTCHER)       return VillagerProfession.BUTCHER;
        if (type == PointOfInterestType.CARTOGRAPHER)  return VillagerProfession.CARTOGRAPHER;
        if (type == PointOfInterestType.CLERIC)        return VillagerProfession.CLERIC;
        if (type == PointOfInterestType.FISHERMAN)     return VillagerProfession.FISHERMAN;
        if (type == PointOfInterestType.FLETCHER)      return VillagerProfession.FLETCHER;
        if (type == PointOfInterestType.LEATHERWORKER) return VillagerProfession.LEATHERWORKER;
        if (type == PointOfInterestType.LIBRARIAN)     return VillagerProfession.LIBRARIAN;
        if (type == PointOfInterestType.MASON)         return VillagerProfession.MASON;
        if (type == PointOfInterestType.SHEPHERD)      return VillagerProfession.SHEPHERD;
        if (type == PointOfInterestType.TOOLSMITH)     return VillagerProfession.TOOLSMITH;
        if (type == PointOfInterestType.WEAPONSMITH)   return VillagerProfession.WEAPONSMITH;
        return null;
    }

    /** "minecraft:farmer" / "farmer" → "Farmer" (matches {@code VillagerEventHandler}'s convention). */
    private static String humanizeProfession(VillagerProfession prof) {
        String key = prof == null ? null : prof.toString();
        if (key != null && key.contains(":")) key = key.substring(key.indexOf(":") + 1);
        if (key == null || key.isEmpty() || "none".equals(key)) return "Unemployed Villager";
        return key.substring(0, 1).toUpperCase() + key.substring(1).toLowerCase();
    }
}
