package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Find a suitable adjacent position and delegate to {@link PlaceBlockSkill} as a child goal.
 *
 * Goal contract:
 *   goalType   = "place_near_self"
 *   targetItem = registry id of the block item (e.g. "minecraft:crafting_table")
 *
 * Flow:
 *   1. Check inventory for the block → if missing, BLOCKED + MISSING_ITEM (triggers acquire_item)
 *   2. Find an air-above-solid position adjacent to the villager
 *   3. executeChild("place_block") with that position
 */
public final class PlaceNearSelfSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("PlaceNearSelfSkill");

    @Override public String id() { return "place_near_self"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "place_near_self".equals(g.getGoalType()) && g.getTargetItem() != null;
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        String itemId = g.getTargetItem();

        // ── Check inventory ──
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null || !(item instanceof BlockItem)) {
            return SkillResult.FAIL;
        }

        boolean hasItem = false;
        for (ItemStack s : a.getInventory().getItems()) {
            if (!s.isEmpty() && s.getItem() == item) { hasItem = true; break; }
        }
        if (!hasItem) {
            // Don't have the block → BLOCKED with MISSING_ITEM → triggers acquire_item recovery
            g.setLastFailure(FailureReason.MISSING_ITEM.with(itemId));
            return SkillResult.BLOCKED;
        }

        // ── Find a placement spot ──
        BlockPos target = findPlacementSpot(v, w);
        if (target == null) {
            LOG.debug("{} place_near_self: no suitable spot near {}", a.getName(), v.blockPosition());
            return SkillResult.FAIL;
        }

        // ── Delegate to place_block as child goal ──
        return BehaviorExecutor.executeChild(a, v, w, () -> {
            AgentGoal child = new AgentGoal("place_block", "Place " + itemId, g.getPriority());
            child.setTargetItem(itemId);
            child.setTargetLocation(target);
            return child;
        });
    }

    /** Find an air block on top of a solid block, adjacent to the villager. */
    private static BlockPos findPlacementSpot(VillagerEntity v, ServerWorld w) {
        BlockPos origin = v.blockPosition();
        // Prefer horizontal neighbours at the same Y level.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(dir);
            if (w.isEmptyBlock(candidate) && !w.isEmptyBlock(candidate.below())) return candidate;
        }
        // Try one block up from neighbours.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(dir).above();
            if (w.isEmptyBlock(candidate) && !w.isEmptyBlock(candidate.below())) return candidate;
        }
        return null;
    }
}
