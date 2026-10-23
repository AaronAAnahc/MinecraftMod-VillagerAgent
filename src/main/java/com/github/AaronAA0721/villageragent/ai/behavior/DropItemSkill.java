package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

/** Throw real inventory stacks, preserving NBT and debiting only accepted spawns. */
public final class DropItemSkill implements VillagerSkill {
    @Override public boolean isPrimitive() { return true; }
    public String id() { return "drop_item"; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return SkillResult.WAITING;
        if (g.getTargetItem() == null || g.getTargetQuantity() <= 0) return SkillResult.DELETE;
        Item item = ResourceCatalog.item(g.getTargetItem());
        if (item == Items.AIR) return SkillResult.DELETE;
        if (a.getInventory().countItem(new ItemStack(item)) < g.getTargetQuantity()) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with(g.getTargetItem(), g.getTargetQuantity()));
            return SkillResult.BLOCKED;
        }
        Vector3d direction = v.getLookAngle();
        for (ItemStack source : a.getInventory().getItems()) {
            if (source.isEmpty() || source.getItem() != item) continue;
            int n = Math.min(source.getCount(), g.getTargetQuantity());
            ItemStack thrown = source.copy();
            thrown.setCount(n);
            ItemEntity drop = new ItemEntity(w, v.getX(), v.getEyeY() - 0.3, v.getZ(), thrown);
            drop.setDeltaMovement(direction.scale(0.3).add(0, 0.15, 0));
            drop.setPickUpDelay(40);
            drop.setThrower(v.getUUID());
            if (!w.addFreshEntity(drop)) return SkillResult.WAITING;
            source.shrink(n);
            g.setTargetQuantity(g.getTargetQuantity() - n);
            VillagerEquipmentHelper.refreshEquipment(v, a);
            if (g.getTargetQuantity() == 0) return SkillResult.SUCCESS;
        }
        return SkillResult.WAITING;
    }
}
