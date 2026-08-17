package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.FarmingAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Reclaim-new-land skill: open a fresh patch of farmland by flood-filling from a start block.
 *
 * <p>On its first tick it PLANS: pick the nearest cultivatable dirt (already near water by
 * definition), then BFS outward over cultivatable dirt, marking up to N blocks as "to reclaim".
 * N is a random number generated per run (how much land we open this time). The plan is stored
 * on the agent as {@link FarmReclaimState} (NBT-persisted) so it survives chunk unloads.
 *
 * <p>After planning it TILLS: walk to each pending block in turn, plough it, drop it from the
 * pending list, repeat until empty — then complete the goal. The routine-goal generator
 * re-issues a fresh reclaim goal (new random N) on the next farming tick.
 *
 * <p>Trigger: a {@code farm} goal whose {@code farmMode} is {@code "reclaim"}.
 */
public class FarmReclaimSkill implements VillagerSkill {
    private static final double ARRIVE_SQ = 2.0;
    private static final int STUCK_TIMEOUT = 200;
    /** Max BFS radius from the villager when gathering a patch to reclaim. */
    private static final int PATCH_RADIUS = 12;

    @Override public String id() { return "farm_reclaim"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "farm".equals(goal.getGoalType()) && "reclaim".equals(goal.getFarmMode());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!FarmingAction.hasHoe(agent)) {
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        FarmReclaimState st = agent.getReclaimState();
        if (st == null) {
            st = planReclaim(world, villager.blockPosition());
            if (st == null || st.isEmpty()) {
                agent.setReclaimState(null);
                goal.setCompleted(true);
                return SkillResult.SUCCESS; // nothing suitable to reclaim
            }
            agent.setReclaimState(st);
        }

        if (st.isEmpty()) {
            agent.setReclaimState(null);
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        BlockPos target = st.getPending().get(0);
        int arrive = FarmingAction.approachTarget(agent, villager, target, true, ARRIVE_SQ, STUCK_TIMEOUT,
                "Reclaiming new farmland");
        if (arrive == 1) {
            if (FarmingAction.tillBlockAt(villager, world, agent, target)) {
                st.getPending().remove(0);
                st.setTilledCount(st.getTilledCount() + 1);
                agent.setReclaimState(st); // persist progress
            }
            return SkillResult.RUNNING;
        }
        if (arrive == 2) {
            // Unreachable block — drop it and continue with the rest of the patch.
            st.getPending().remove(0);
            agent.setReclaimState(st);
            return SkillResult.RUNNING;
        }
        return SkillResult.RUNNING;
    }

    /**
     * Build a reclaim plan: choose a start block and flood-fill N cultivatable dirt blocks.
     * N is random each call (4..10) — how much land we open this session.
     */
    private static FarmReclaimState planReclaim(ServerWorld world, BlockPos origin) {
        BlockPos start = FarmingAction.findNearestCultivatableDirt(world, origin);
        if (start == null) return null;

        int n = 4 + world.getRandom().nextInt(7); // [4, 10]
        FarmReclaimState st = new FarmReclaimState();
        st.setTargetCount(n);

        Set<BlockPos> seen = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty() && st.getPending().size() < n) {
            BlockPos cur = queue.poll();
            if (cur.distSqr(origin) > PATCH_RADIUS * PATCH_RADIUS) continue;
            if (FarmingAction.isCultivatableDirt(world, cur) && !FarmingAction.isFarmland(world, cur)) {
                st.addPending(cur);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos nb = cur.offset(dx, 0, dz);
                    if (seen.add(nb)) queue.add(nb);
                }
            }
        }
        return st.isEmpty() ? null : st;
    }
}
