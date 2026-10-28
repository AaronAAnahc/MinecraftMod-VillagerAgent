package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.behavior.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** One parameterized extractor, shared by personal acquisition and cooperative orders. */
public final class ResourceExtraction {
    private final Set<BlockPos> rejected = new HashSet<>();
    private long collectUntil, nextSearch;

    public SkillResult tick(VillagerAgentData a, AgentGoal goal, VillagerEntity v, ServerWorld world,
                            ResourceCatalog.Source source, int desired) {
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return SkillResult.WAITING;
        if (!source.skilled(a)) {
            goal.setLastFailure(FailureReason.CAPABILITY_TOO_LOW.with(source.capability, 1));
            return SkillResult.BLOCKED;
        }
        int have = a.getInventory().countItem(new ItemStack(source.output));
        if (have >= desired) return SkillResult.SUCCESS;
        if (!source.hasTool(a)) {
            goal.setLastFailure(FailureReason.MISSING_ITEM.with(source.tool.getRegistryName().toString()));
            return SkillResult.BLOCKED;
        }
        long now = world.getGameTime();
        VillagerAction action = a.getCurrentAction();
        if (action != null && action.getActionType() == VillagerAction.ActionType.BREAK) {
            BlockPos target = action.getTargetBlockPos();
            // Never destroy a replacement block or an immature crop after a world change.
            if (!source.matches(world.getBlockState(target))) {
                ActionLifecycle.clearBody(a, v, world); collectUntil = now + 40;
                return SkillResult.RUNNING;
            }
            BlockInteractionAction.StepResult result = BlockInteractionAction.tickBreak(v, world, a, action);
            if (result == BlockInteractionAction.StepResult.IN_PROGRESS) return SkillResult.RUNNING;
            if (result == BlockInteractionAction.StepResult.ABANDONED) rejected.add(target.immutable());
            else {
                a.gainCapabilityXp(source.capability, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
                goal.markProgress();
            }
            ActionLifecycle.clearBody(a, v, world);
            collectUntil = now + 40;
            return SkillResult.RUNNING;
        }
        if (now < collectUntil) {
            for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, v.getBoundingBox().inflate(3))) {
                if (drop.getItem().getItem() != source.output) continue;
                if (ItemAttractionSystem.collect(drop, v, a, desired - have) > 0) goal.markProgress();
                else if (drop.isAlive()) v.getNavigation().moveTo(drop, VillagerNavigationSupport.WALK_SPEED);
                have = a.getInventory().countItem(new ItemStack(source.output));
                if (have >= desired) return SkillResult.SUCCESS;
            }
            return SkillResult.RUNNING;
        }
        if (now < nextSearch) return SkillResult.RUNNING;
        nextSearch = now + 40;
        if (InventoryTransfer.spaceFor(a.getInventory(), new ItemStack(source.output)) == 0) return SkillResult.WAITING;
        ResourceSensor.Target found = ResourceSensor.find(world, v, a, source, rejected);
        if (found == null) return SkillResult.WAITING;
        VillagerAction breaking = new VillagerAction(VillagerAction.ActionType.BREAK, source.methodId());
        breaking.setTargetBlockPos(found.block); breaking.setStandCell(found.stand);
        breaking.setBreakTool(source.availableTool(a));
        breaking.setGoalDriven(true); breaking.setPhase(VillagerAction.ActionPhase.WALKING);
        a.setCurrentAction(breaking); a.setCurrentActivity(source.methodId());
        return SkillResult.RUNNING;
    }
}
