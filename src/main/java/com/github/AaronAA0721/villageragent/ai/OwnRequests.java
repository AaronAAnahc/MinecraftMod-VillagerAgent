package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * The cooperative requests a villager has on the village board, and whether it still needs them.
 *
 * <p>A request belongs to the villager, not to the acquisition method that posted it: switching from
 * "ask the village" to "farm it myself" leaves the request up. It comes down only when
 * <ul>
 *   <li>it was delivered (CONSUMED) — retired as bookkeeping, no walk needed;</li>
 *   <li>it expired — retired as bookkeeping;</li>
 *   <li>the need was met some other way (or every task that needed it is gone) while it is still OPEN —
 *       the villager walks to the board and takes it down ({@code withdraw_request}).</li>
 * </ul>
 * A CLAIMED request is left alone: its delivery meeting is already booked.
 */
public final class OwnRequests {
    public static final String WITHDRAW_GOAL = "withdraw_request";
    private static final int CHECK_INTERVAL = 40;

    /** What one posted request is for: the acquisition objective(s) that wait on it. */
    public static final class Need {
        public final Item target;
        public final String category;
        public final int units;
        /** Root goals whose acquisition waits on this request. */
        public final Set<AgentGoal> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        /** A running request_supply method currently holds this request and will verify/retire it itself. */
        public boolean held;
        Need(Item target, String category, int units) { this.target = target; this.category = category; this.units = units; }
    }

    private OwnRequests() {}

    /** Record that the running root waits on {@code request} for this acquisition objective. */
    public static void attach(AcquisitionMethods.Context c, UUID request) {
        if (request == null) return;
        Map<UUID, Need> needs = c.agent.brain().ownRequests;
        Need need = needs.get(request);
        if (need == null || need.units < c.units) {
            Need replaced = new Need(c.target, c.requestCategory, c.units);
            if (need != null) { replaced.roots.addAll(need.roots); replaced.held = need.held; }
            need = replaced;
            needs.put(request, need);
        }
        TaskExecution.Frame root = c.agent.brain().tasks.root();
        if (c.executing && root != null) need.roots.add(root.goal);
    }

    /** This villager's live request that already covers the objective of {@code c}, or null. */
    public static VillageSignalBoard.VillageMessage covering(AcquisitionMethods.Context c, long now) {
        String id = c.target.getRegistryName().toString();
        for (VillageSignalBoard.VillageMessage m : VillageSignalBoard.requestsBy(c.agent.getVillagerId())) {
            VillageSignalBoard.RequestState state = m.state(now);
            if (state != VillageSignalBoard.RequestState.OPEN && state != VillageSignalBoard.RequestState.CLAIMED) continue;
            boolean same = c.requestCategory != null ? c.requestCategory.equals(m.spec.category) : m.spec.items().contains(id);
            if (same) return m;
        }
        return null;
    }

    /** True while some live task of this villager still waits on the request and its objective is unmet. */
    public static boolean needed(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                 VillageSignalBoard.VillageMessage m) {
        Need need = agent.brain().ownRequests.get(m.id);
        if (need == null) {
            // Unknown origin (e.g. posted before a reload): keep it while any goal still targets one of its items.
            for (AgentGoal g : agent.getGoals()) {
                if (g.isCompleted() || g.getTargetItem() == null) continue;
                if (m.spec.items().contains(g.getTargetItem())
                        || (m.spec.category != null && m.spec.category.equals(g.getRequestCategory()))) return true;
            }
            return false;
        }
        need.roots.removeIf(r -> r.isCompleted() || !agent.getGoals().contains(r));
        if (need.roots.isEmpty()) return false;
        AcquisitionMethods.Context check = new AcquisitionMethods.Context(agent, villager, world, need.target, need.units, false);
        check.requestCategory = need.category;
        check.units = need.units;
        return !check.satisfied();
    }

    /**
     * Periodic upkeep, run before scheduling: retire delivered/expired requests, and queue a board visit to
     * withdraw open requests that are no longer needed.
     */
    public static void maintain(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        if (now - agent.brain().ownRequestsCheckedTick < CHECK_INTERVAL) return;
        agent.brain().ownRequestsCheckedTick = now;
        boolean withdraw = false;
        Set<UUID> live = new HashSet<>();
        for (VillageSignalBoard.VillageMessage m : VillageSignalBoard.requestsBy(agent.getVillagerId())) {
            VillageSignalBoard.RequestState state = m.state(now);
            if (state == VillageSignalBoard.RequestState.EXPIRED) {
                BrainLog.diagnostic(agent, "request-expired", "request=" + m.id + " spec=" + m.spec.key());
                VillageSignalBoard.get().retire(m.id);
                continue;
            }
            if (state == VillageSignalBoard.RequestState.CONSUMED && !hasWaitingMethod(agent, m.id)) {
                // Delivered and nobody is verifying it any more: the requester's acquisition re-checks its inventory.
                BrainLog.diagnostic(agent, "request-retired", "request=" + m.id + " spec=" + m.spec.key() + " reason=delivered");
                VillageSignalBoard.get().retire(m.id);
                continue;
            }
            live.add(m.id);
            if (state == VillageSignalBoard.RequestState.OPEN && !needed(agent, villager, world, m)) withdraw = true;
        }
        agent.brain().ownRequests.keySet().retainAll(live);
        if (withdraw) ensureWithdrawGoal(agent);
    }

    /** A request_supply method still holds this request (it verifies and retires it itself). */
    private static boolean hasWaitingMethod(VillagerAgentData agent, UUID request) {
        Need need = agent.brain().ownRequests.get(request);
        return need != null && need.held;
    }

    /** The posting method took hold of / let go of the request. */
    public static void hold(VillagerAgentData agent, UUID request, boolean held) {
        Need need = request == null ? null : agent.brain().ownRequests.get(request);
        if (need != null) need.held = held;
    }

    private static void ensureWithdrawGoal(VillagerAgentData agent) {
        for (AgentGoal g : agent.getGoals()) if (WITHDRAW_GOAL.equals(g.getGoalType()) && !g.isCompleted()) return;
        AgentGoal g = new AgentGoal(WITHDRAW_GOAL, "Take my fulfilled request off the notice board", 6);
        g.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        agent.getGoals().add(g);
        BrainLog.goalAdded(agent, g, "request:withdraw");
    }

    /** At the board: take down every open request this villager no longer needs. Returns how many. */
    public static int withdrawUnneeded(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        int removed = 0;
        for (VillageSignalBoard.VillageMessage m : VillageSignalBoard.requestsBy(agent.getVillagerId())) {
            if (m.state(now) != VillageSignalBoard.RequestState.OPEN || needed(agent, villager, world, m)) continue;
            VillageSignalBoard.get().retire(m.id);
            agent.brain().ownRequests.remove(m.id);
            BrainLog.diagnostic(agent, "request-withdrawn", "request=" + m.id + " spec=" + m.spec.key()
                    + " reason=need_met_or_gone");
            removed++;
        }
        return removed;
    }
}
