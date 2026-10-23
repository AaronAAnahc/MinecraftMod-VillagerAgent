package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Small HTN executor: supply(order) -> reserve, acquire(tool), find, break, collect, deliver.
 * Methods are rule-authored. The selected primitive is advanced one real server tick at a time. */
public final class ResourceWorkSkill implements VillagerSkill {
    private static final class Plan {
        final Set<BlockPos> rejected = new HashSet<>();
        long collectUntil, nextSearch, deliveryStarted;
        BlockPos lastBlock;
    }
    private final Map<AgentGoal, Plan> plans = new WeakHashMap<>();
    @Override public String id() { return "fulfill_order"; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "fulfill_order".equals(g.getGoalType()) && g.getWorkOrderId() != null;
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld world) {
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get() || !ModConfig.ENABLE_ECONOMY.get()) {
            BrainLog.diagnostic(a, "order-wait", "worldInteraction=" + ModConfig.ENABLE_WORLD_INTERACTION.get()
                    + " economy=" + ModConfig.ENABLE_ECONOMY.get());
            return SkillResult.WAITING;
        }
        long now = world.getGameTime();
        WorkOrderSavedData data = WorkOrderSavedData.get(world);
        WorkOrderBook.Order order = data.book.get(g.getWorkOrderId());
        if (order == null || !order.active() || now >= order.expiresAt) {
            BrainLog.diagnostic(a, "order-delete", "id=" + g.getWorkOrderId() + " status="
                    + (order == null ? "missing" : order.status) + " tick=" + now);
            return SkillResult.DELETE;
        }
        if (a.getResourceProfession() != order.profession) {
            BrainLog.diagnostic(a, "order-delete", "id=" + order.id + " role="
                    + a.getResourceProfession() + " required=" + order.profession);
            return SkillResult.DELETE;
        }
        boolean owned = a.getVillagerId().equals(order.worker);
        if (!(owned ? data.book.renew(order.id, a.getVillagerId(), now) : data.book.claim(order.id, a.getVillagerId(), now))) {
            BrainLog.diagnostic(a, "order-wait", "claim/renew denied id=" + order.id + " owner="
                    + order.worker + " leaseUntil=" + order.leaseUntil + " tick=" + now);
            return SkillResult.DELETE;
        }
        if (!owned) BrainLog.diagnostic(a, "order-claim", "id=" + order.id + " item=" + order.item
                + " remaining=" + order.remaining + " requester=" + order.requester);
        data.setDirty();
        VillagerAgentData requester = VillagerAgentManager.getAgent(order.requester);
        Entity target = world.getEntity(order.requester);
        if (requester == null || !(target instanceof VillagerEntity) || !target.isAlive()) {
            BrainLog.diagnostic(a, "order-wait", "requester unavailable id=" + order.id
                    + " requesterData=" + (requester != null) + " entity=" + target);
            return SkillResult.WAITING;
        }
        data.book.reconcile(order.id, ResourceWorkSystem.count(requester, order.item));
        ResourceWorkSystem.clearSatisfiedWant(order);
        if (!order.active()) {
            BrainLog.diagnostic(a, "order-satisfied", "id=" + order.id + " stock="
                    + ResourceWorkSystem.count(requester, order.item) + " target=" + order.targetStock);
            return SkillResult.SUCCESS;
        }
        Plan plan = plans.computeIfAbsent(g, k -> new Plan());
        if (!owned) { plan.deliveryStarted = 0; plan.collectUntil = 0; }
        Item item = ResourceCatalog.item(order.item);
        int have = a.getInventory().countItem(new ItemStack(item));
        // Deliver a bounded batch; partial delivery is real progress, never falsely closes the order.
        if (have >= Math.min(8, order.remaining) || (plan.deliveryStarted > 0 && have > 0)) {
            return deliverBatch(a, requester, g, v, target, item, have, order, data, plan, now);
        }
        VillagerAction action = a.getCurrentAction();
        if (action != null && action.getActionType() == VillagerAction.ActionType.BREAK) {
            BlockInteractionAction.StepResult r = BlockInteractionAction.tickBreak(v, world, a, action);
            if (r == BlockInteractionAction.StepResult.IN_PROGRESS) return SkillResult.RUNNING;
            BrainLog.diagnostic(a, "order-break", "id=" + order.id + " block="
                    + action.getTargetBlockPos() + " outcome=" + r + " progress="
                    + action.getBreakProgress() + "/" + action.getBreakTargetTicks());
            world.destroyBlockProgress(v.getId(), action.getTargetBlockPos(), -1);
            plan.lastBlock = action.getTargetBlockPos();
            if (r == BlockInteractionAction.StepResult.ABANDONED) plan.rejected.add(plan.lastBlock);
            plan.collectUntil = now + 40;
            a.setCurrentAction(null); v.getNavigation().stop();
            return SkillResult.RUNNING;
        }
        if (now < plan.collectUntil) {
            // ItemAttractionSystem also picks up; explicitly collecting makes this task independent
            // of passive pickup settings and preserves leftover stacks when the inventory fills.
            for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, v.getBoundingBox().inflate(3))) {
                if (drop.getItem().getItem() != item) continue;
                ItemStack rest = drop.getItem().copy();
                int inserted = InventoryTransfer.insert(a.getInventory(), rest);
                if (inserted > 0) { drop.getItem().shrink(inserted); if (drop.getItem().isEmpty()) drop.remove(); }
            }
            return SkillResult.RUNNING;
        }
        if (now == plan.collectUntil && plan.lastBlock != null) {
            BrainLog.diagnostic(a, "order-collected", "id=" + order.id + " block="
                    + plan.lastBlock + " inventory=" + a.getInventory().countItem(new ItemStack(item)));
        }
        if (now < plan.nextSearch) return SkillResult.RUNNING;
        plan.nextSearch = now + 40;
        if (InventoryTransfer.spaceFor(a.getInventory(), new ItemStack(item)) == 0) {
            BrainLog.diagnostic(a, "order-wait", "inventory full id=" + order.id + " item=" + order.item);
            return SkillResult.WAITING;
        }
        ResourceCatalog.Source source = ResourceCatalog.get(order.item);
        if (source == null) {
            BrainLog.diagnostic(a, "order-fail", "no resource source id=" + order.id + " item=" + order.item);
            return SkillResult.FAIL;
        }
        ItemStack tool = VillagerEquipmentHelper.equipBestToolForBlock(v, a, source.block.defaultBlockState());
        boolean suitable = !tool.isEmpty() && (source.profession == ResourceProfession.LUMBERJACK
                ? tool.getItem() instanceof AxeItem : tool.isCorrectToolForDrops(source.block.defaultBlockState()));
        if (!suitable) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with(source.tool.getRegistryName().toString()));
            BrainLog.diagnostic(a, "order-blocked", "missing correct tool id=" + order.id
                    + " item=" + order.item + " required=" + source.tool.getRegistryName()
                    + " equipped=" + tool);
            return SkillResult.BLOCKED;
        }
        ResourceSensor.Target found = ResourceSensor.find(world, v, a, source, plan.rejected);
        if (found == null) {
            // Finish delivering even a small batch before exploring further.
            if (have > 0) { g.setTargetQuantity(have); return deliverBatch(a, requester, g, v, target, item, have, order, data, plan, now); }
            BrainLog.diagnostic(a, "order-wait", "no visible reachable source id=" + order.id
                    + " item=" + order.item + " pos=" + v.blockPosition()
                    + " rejected=" + plan.rejected.size());
            a.addMemory("No visible reachable " + order.item + " nearby; explore before retrying");
            return SkillResult.WAITING;
        }
        BrainLog.diagnostic(a, "order-source", "id=" + order.id + " item=" + order.item
                + " block=" + found.block + " stand=" + found.stand
                + " workerPos=" + v.blockPosition() + " have=" + have);
        VillagerAction breaking = new VillagerAction(VillagerAction.ActionType.BREAK, "Extract " + order.item);
        breaking.setTargetBlockPos(found.block); breaking.setStandCell(found.stand);
        breaking.setGoalDriven(true); breaking.setPhase(VillagerAction.ActionPhase.WALKING);
        a.setCurrentAction(breaking); a.setCurrentActivity(source.profession == ResourceProfession.MINER ? "mining" : "logging");
        return SkillResult.RUNNING;
    }
    private SkillResult deliverBatch(VillagerAgentData a, VillagerAgentData to, AgentGoal goal,
                                     VillagerEntity v, Entity target, Item item, int have,
                                     WorkOrderBook.Order order, WorkOrderSavedData data, Plan p, long now) {
        if (p.deliveryStarted == 0) p.deliveryStarted = now;
        if (v.distanceToSqr(target) > 9) {
            VillagerAction move = a.getCurrentAction();
            if (move == null || move.getActionType() != VillagerAction.ActionType.MOVE) {
                if (move != null && move.getActionType() == VillagerAction.ActionType.BREAK)
                    v.level.destroyBlockProgress(v.getId(), move.getTargetBlockPos(), -1);
                move = new VillagerAction(VillagerAction.ActionType.MOVE, "Deliver " + order.item);
                move.setGoalDriven(true);
                a.setCurrentAction(move);
                // The first route must start now, not on a global time modulo boundary.
                v.getNavigation().stop();
            }
            move.setTargetBlockPos(target.blockPosition());
            move.incrementStuckTicks(); // observeMovement in the dispatcher resets on actual progress.
            if (move.getStuckTicks() > VillagerAgentManager.GOAL_STUCK_TIMEOUT
                    || ((v.getNavigation().isDone() || now % 40 == 0)
                        && !v.getNavigation().moveTo(target, VillagerNavigationSupport.WALK_SPEED))) {
                goal.setLastFailure(FailureReason.UNREACHABLE.with(target.blockPosition().toString()));
                return SkillResult.FAIL;
            }
            a.setCurrentActivity("delivering");
            return SkillResult.RUNNING;
        }
        int moved = InventoryTransfer.move(a.getInventory(), to.getInventory(), item, Math.min(have, order.remaining));
        BrainLog.diagnostic(a, "order-delivery", "id=" + order.id + " have=" + have
                + " moved=" + moved + " requesterStockAfter=" + ResourceWorkSystem.count(to, order.item));
        data.book.reconcile(order.id, ResourceWorkSystem.count(to, order.item));
        ResourceWorkSystem.clearSatisfiedWant(order);
        return moved > 0 ? SkillResult.SUCCESS : SkillResult.WAITING;
    }
}
