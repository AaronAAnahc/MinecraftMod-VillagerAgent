package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Root-owned runtime sessions survive WAITING/frame rebuilds, but never save stale attempts to NBT. */
public final class AcquisitionTasks {
    private static final class Session {
        final Item target;
        final int desired;
        final UUID parentAttempt;
        final AcquisitionMethods.Context cleanup;
        final TaskMethodExecution<AcquisitionMethods.Context> execution =
                new TaskMethodExecution<>(TaskMethodExecution.Policy.defaults());
        int logged;
        Session(AcquisitionMethods.Context context, UUID parentAttempt) {
            this.parentAttempt = parentAttempt;
            target = context.target; desired = context.desired;
            cleanup = new AcquisitionMethods.Context(context.agent, context.villager, context.world, target, desired, false);
        }
    }
    private final Map<AgentGoal, Map<Item, Session>> roots = new IdentityHashMap<>();
    private final DependencyPath<Item> executingItems = new DependencyPath<>(8, 64);

    public SkillResult execute(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                               AgentGoal goal, Item target, int desired) {
        AgentGoal root = agent.brain().tasks.root().goal;
        Map<Item, Session> sessions = roots.computeIfAbsent(root, key -> new HashMap<>());
        AcquisitionMethods.Context context = new AcquisitionMethods.Context(agent, villager, world, target, desired, true);
        // Check before looking up/replacing sessions: a cyclic child must never close its ancestor.
        if (executingItems.contains(target)) {
            if (context.count(target) < desired) return SkillResult.FAIL;
            goal.setCompleted(true); return SkillResult.SUCCESS;
        }
        Session session = sessions.get(target);
        if (session != null && (session.desired != desired || !Objects.equals(session.parentAttempt, goal.getMethodAttempt()))) {
            session.execution.close(context); sessions.remove(target); session = null;
        }
        if (context.count(target) >= desired) {
            if (session != null) { session.execution.close(context); sessions.remove(target); }
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }
        if (session == null) { session = new Session(context, goal.getMethodAttempt()); sessions.put(target, session); }
        if (!executingItems.enter(target)) return SkillResult.FAIL;
        TaskMethod.Status status;
        long previousProgress = session.execution.progressVersion();
        try {
            status = session.execution.tick(context, world.getGameTime(), () -> AcquisitionMethods.candidates(context));
        } finally { executingItems.leave(target); }
        if (session.execution.progressVersion() != previousProgress) goal.markProgress();
        if (session.logged != session.execution.transitions()) {
            BrainLog.diagnostic(agent, "task-method", "target=" + target.getRegistryName()
                    + " transition=" + session.execution.lastTransition() + " active=" + session.execution.activeId()
                    + " score=" + session.execution.score() + " stalledTicks=" + session.execution.stalledTicks());
            session.logged = session.execution.transitions();
        }
        return status == TaskMethod.Status.RUNNING || status == TaskMethod.Status.DONE
                ? SkillResult.RUNNING : SkillResult.WAITING;
    }

    public double remainingCost(UUID parentAttempt, Item item) {
        for (Map<Item, Session> sessions : roots.values()) {
            Session session = sessions.get(item);
            if (session != null && Objects.equals(parentAttempt, session.parentAttempt))
                return session.execution.activeId() == null ? Double.POSITIVE_INFINITY : session.execution.remainingCost();
        }
        return Double.POSITIVE_INFINITY;
    }
    /** Closing a method also closes its nested acquisition attempts, including off-scheduler waits. */
    public void cancelChildren(UUID parentAttempt) {
        if (parentAttempt == null) return;
        for (Map<Item, Session> sessions : new ArrayList<>(roots.values())) {
            for (Session session : new ArrayList<>(sessions.values())) if (parentAttempt.equals(session.parentAttempt)) {
                sessions.remove(session.target); session.execution.close(session.cleanup);
            }
        }
    }
    /** Called before root scheduling, including roots removed by commands or routine pruning. */
    public void maintain(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        for (Map.Entry<AgentGoal, Map<Item, Session>> entry : new ArrayList<>(roots.entrySet())) {
            AgentGoal root = entry.getKey();
            boolean removed = !agent.getGoals().contains(root) || root.isCompleted() || root.isExpired(world.getGameTime());
            Map<Item, Session> sessions = entry.getValue();
            for (Session session : new ArrayList<>(sessions.values())) {
                AcquisitionMethods.Context context = new AcquisitionMethods.Context(agent, villager, world,
                        session.target, session.desired, false);
                if (removed || session.cleanup.world != world || context.count(session.target) >= session.desired) {
                    sessions.remove(session.target); session.execution.close(session.cleanup);
                }
            }
            if (sessions.isEmpty()) roots.remove(root);
        }
    }
    /** World unload and entity removal cannot leave attempts pointing at a previous world. */
    public void closeWorld(ServerWorld world) {
        for (Map<Item, Session> sessions : new ArrayList<>(roots.values())) {
            for (Session session : new ArrayList<>(sessions.values())) {
                if (world != null && session.cleanup.world != world) continue;
                sessions.remove(session.target); session.execution.close(session.cleanup);
            }
        }
        roots.values().removeIf(Map::isEmpty);
    }
}
