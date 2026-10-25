package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.world.server.ServerWorld;
import java.util.UUID;

/** Approach real drops and confirm the requested inventory amount using contact pickup. */
public final class PickupItemSkill implements VillagerSkill {
    @Override public boolean isPrimitive() { return true; }
    private static final class Pursuit {
        UUID target;
        long nextSearch, nextPath;
        int ticks;
    }
    public String id() { return "pickup_item"; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return SkillResult.WAITING;
        if (g.getTargetItem() == null) return SkillResult.DELETE;
        Item item = ResourceCatalog.item(g.getTargetItem());
        if (item == Items.AIR) return SkillResult.DELETE;
        int desired = Math.max(1, g.getTargetQuantity());
        if (a.getInventory().countItem(new net.minecraft.item.ItemStack(item)) >= desired) return SkillResult.SUCCESS;
        Pursuit p = a.brain().tasks.current().state(Pursuit.class, Pursuit::new);
        if (++p.ticks > 600) {
            g.setLastFailure(FailureReason.UNREACHABLE.with("pickup timeout"));
            return SkillResult.FAIL;
        }
        long now = w.getGameTime();
        UUID targetId = g.getTargetEntityId() != null ? g.getTargetEntityId() : p.target;
        Entity entity = targetId == null ? null : w.getEntity(targetId);
        ItemEntity drop = entity instanceof ItemEntity ? (ItemEntity) entity : null;
        if (!valid(drop, item, v)) {
            v.getNavigation().stop();
            a.setCurrentAction(null);
            if (g.getTargetEntityId() != null) {
                g.setLastFailure(FailureReason.UNKNOWN.with("specified drop disappeared before inventory target was met"));
                return SkillResult.FAIL;
            }
            if (now < p.nextSearch) return SkillResult.RUNNING;
            p.nextSearch = now + 20;
            drop = null;
            double best = Double.MAX_VALUE;
            for (ItemEntity candidate : w.getEntitiesOfClass(ItemEntity.class, v.getBoundingBox().inflate(16))) {
                if (!valid(candidate, item, v)) continue;
                double distance = v.distanceToSqr(candidate);
                if (distance < best) { drop = candidate; best = distance; }
            }
            if (drop == null) return SkillResult.RUNNING;
            p.target = drop.getUUID();
            p.nextPath = 0;
            BrainLog.diagnostic(a, "pickup-target", "entity=" + p.target + " pos=" + drop.blockPosition());
        }
        if (ItemAttractionSystem.pickupBox(v).intersects(drop.getBoundingBox())) {
            v.getNavigation().stop(); a.setCurrentAction(null);
            ItemAttractionSystem.collect(drop, v, a,
                    desired - a.getInventory().countItem(new net.minecraft.item.ItemStack(item)));
            if (a.getInventory().countItem(new net.minecraft.item.ItemStack(item)) >= desired) return SkillResult.SUCCESS;
            if (drop.isAlive() && InventoryTransfer.spaceFor(a.getInventory(), drop.getItem()) == 0) return SkillResult.WAITING;
            return SkillResult.RUNNING;
        }
        VillagerAction move = a.getCurrentAction();
        if (move == null) {
            move = new VillagerAction(VillagerAction.ActionType.MOVE, "Pick up " + g.getTargetItem());
            move.setGoalDriven(true);
            move.setPhase(VillagerAction.ActionPhase.WALKING);
            a.setCurrentAction(move);
            p.nextPath = 0;
        }
        move.setTargetBlockPos(drop.blockPosition());
        if (now >= p.nextPath) {
            p.nextPath = now + 10;
            if (!v.getNavigation().moveTo(drop, VillagerNavigationSupport.WALK_SPEED)) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(drop.blockPosition().toString()));
                return SkillResult.FAIL;
            }
        }
        a.setCurrentActivity("picking up");
        return SkillResult.RUNNING;
    }
    private static boolean valid(ItemEntity drop, Item item, VillagerEntity v) {
        return drop != null && drop.isAlive() && !drop.getItem().isEmpty()
                && drop.getItem().getItem() == item && v.distanceToSqr(drop) <= 32 * 32
                && (drop.getOwner() == null || drop.getOwner().equals(v.getUUID()));
    }
}
