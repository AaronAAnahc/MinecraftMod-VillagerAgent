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
            cleanup.requestCategory = context.requestCategory;
            cleanup.units = context.units;
        }
    }
    private final Map<AgentGoal, Map<Item, Session>> roots = new IdentityHashMap<>();
    private final DependencyPath<Item> executingItems = new DependencyPath<>(8, 64);

    public SkillResult execute(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                               AgentGoal goal, Item target, int desired) {
        AgentGoal root = agent.brain().tasks.root().goal;
        Map<Item, Session> sessions = roots.computeIfAbsent(root, key -> new HashMap<>());
        String category = goal.getRequestCategory() != null && SupplyPolicy.isCategory(goal.getRequestCategory())
                ? goal.getRequestCategory() : null;
        int units = desired;
        if (category != null) {
            // Food categories are measured in hunger points; production plans whole items of the target.
            SupplyPolicy.Spec spec = SupplyPolicy.Spec.category(category);
            if (spec.unit() == SupplyPolicy.Unit.FOOD_POINTS)
                desired = Math.max(1, SupplyPolicy.itemsToCover(spec, target, units));
        }
        AcquisitionMethods.Context context = new AcquisitionMethods.Context(agent, villager, world, target, desired, true);
        context.units = units;
        // A request responder must produce, not re-request, the output it is supplying.
        context.productionOnly=CooperativeSupply.RESPONSE_GOAL.equals(goal.getGoalType()) || goal.isSelfProductionOnly();
        context.requestCategory=category;
        // Everything below a village-request response may not re-request that request's item or its alternatives.
        if (root != null && CooperativeSupply.RESPONSE_GOAL.equals(root.getGoalType())) {
            Set<Item> blocked = new HashSet<>(RequestAlternatives.forbidden(SupplyPolicy.Spec.parse(root.getRequestSpec())));
            Item output = root.getTargetItem() == null ? net.minecraft.item.Items.AIR
                    : com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(root.getTargetItem());
            if (output != net.minecraft.item.Items.AIR) blocked.add(output);
            context.forbiddenRequests = blocked;
            context.noToolRequests = true;
        }
        // Check before looking up/replacing sessions: a cyclic child must never close its ancestor.
        if (executingItems.contains(target)) {
            if (!context.satisfied()) return SkillResult.FAIL;
            goal.setCompleted(true); return SkillResult.SUCCESS;
        }
        Session session = sessions.get(target);
        if (session != null && (session.desired != desired || !Objects.equals(session.parentAttempt, goal.getMethodAttempt()))) {
            session.execution.close(context); sessions.remove(target); session = null;
        }
        // A crafting table is wanted to craft at, not to own: a usable one nearby satisfies the goal and
        // stops any crafting/request for it.
        boolean tableNearby = target == net.minecraft.item.Items.CRAFTING_TABLE
                && com.github.AaronAA0721.villageragent.ai.behavior.CraftSkill.findNearestWorkbench(agent, villager, world) != null;
        if (context.satisfied() || tableNearby) {
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
        // A method that finished on its own terms without meeting the objective gives way to the next method.
        if (status == TaskMethod.Status.DONE && !context.satisfied()) session.execution.exhaustLast(world.getGameTime());
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
    /** Root task whose subtree runs the method attempt {@code attempt}; null when no live session owns it. */
    public AgentGoal rootOwning(UUID attempt) {
        if (attempt == null) return null;
        for (Map.Entry<AgentGoal, Map<Item, Session>> entry : roots.entrySet())
            for (Session session : entry.getValue().values())
                if (attempt.equals(session.execution.activeAttempt())) return entry.getKey();
        return null;
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
                context.requestCategory = session.cleanup.requestCategory;
                context.units = session.cleanup.units;
                boolean tableNearby = session.target == net.minecraft.item.Items.CRAFTING_TABLE && villager != null
                        && com.github.AaronAA0721.villageragent.ai.behavior.CraftSkill.findNearestWorkbench(agent, villager, world) != null;
                if (removed || session.cleanup.world != world || context.satisfied() || tableNearby) {
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
