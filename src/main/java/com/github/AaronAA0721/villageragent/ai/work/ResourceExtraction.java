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
    private TreeFelling tree;
    private final Set<Long> rejectedChunks=new HashSet<>();
    private net.minecraft.util.math.ChunkPos searchChunk;
    private long travelStarted;
    private BlockPos travelTarget;
    private final TargetAttempts attempts = new TargetAttempts(3);

    /** Nearest remembered chunk containing ore; mirrors {@link TreeFelling#remembered} without village exclusion. */
    private static com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory rememberedOre(
            VillagerAgentData a, VillagerEntity v, ServerWorld w, Set<Long> rejectedChunks) {
        com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory best=null; double distance=Double.POSITIVE_INFINITY;
        for(com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm:a.getAllChunkMemories()) {
            if(cm.count(com.github.AaronAA0721.villageragent.ai.memory.BlockCategory.ORE)<=0||rejectedChunks.contains(cm.chunkKey)) continue;
            double d=ReachablePaths.distance(v,ReachablePaths.forestDestination(w,v,cm.getX(),cm.getZ()));
            if(d<distance) { best=cm;distance=d; }
        }
        return best;
    }

    public SkillResult tick(VillagerAgentData a, AgentGoal goal, VillagerEntity v, ServerWorld world,
                            ResourceCatalog.Source source, int desired) {
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return SkillResult.WAITING;
        if (attempts.exhausted()) {
            goal.setLastFailure(FailureReason.UNREACHABLE.with("three failed extraction targets"));
            return SkillResult.FAIL;
        }
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
            BlockInteractionAction.StepResult result;
            if (tree != null) result = tree.tick(a, v, world, action);
            else if (!source.consumesBlock) result = BlockInteractionAction.tickMine(v, world, a, action, source.output, 1);
            else result = BlockInteractionAction.tickBreak(v, world, a, action);
            if (result == BlockInteractionAction.StepResult.IN_PROGRESS) return SkillResult.RUNNING;
            if (result == BlockInteractionAction.StepResult.ABANDONED) {
                rejected.add(target.immutable()); attempts.reject(target.immutable()); ReachablePaths.invalidate(v);
            }
            else {
                a.gainCapabilityXp(source.capability, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
                goal.markProgress();
                // Mined-in-place ore stays in the world; throttle it to one yield per day and
                // exclude it from this attempt's further searches.
                if (!source.consumesBlock) { rejected.add(target.immutable()); }
            }
            tree=null;
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
        // Lumberjacks travel to remembered log chunks; miners travel to remembered ore chunks.
        // Quarrying (cobblestone from ordinary stone) stays local since stone is everywhere.
        com.github.AaronAA0721.villageragent.ai.memory.BlockCategory remoteCategory =
                source.profession==ResourceProfession.LUMBERJACK ? com.github.AaronAA0721.villageragent.ai.memory.BlockCategory.WOOD_LOG
                : source.capability==com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId.MINING ? com.github.AaronAA0721.villageragent.ai.memory.BlockCategory.ORE
                : null;
        if(remoteCategory!=null) {
            if(searchChunk==null) {
                com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm=
                        remoteCategory==com.github.AaronAA0721.villageragent.ai.memory.BlockCategory.WOOD_LOG
                                ? TreeFelling.remembered(a,v,world,rejectedChunks)
                                : rememberedOre(a,v,world,rejectedChunks);
                if(cm==null) {
                    goal.setLastFailure(FailureReason.UNREACHABLE.with("no reachable remembered resource"));
                    return SkillResult.FAIL;
                }
                searchChunk=new net.minecraft.util.math.ChunkPos(cm.getX(),cm.getZ());travelStarted=now;
                travelTarget=ReachablePaths.forestDestination(world,v,searchChunk.x,searchChunk.z);
            }
            if((v.blockPosition().getX()>>4)!=searchChunk.x||(v.blockPosition().getZ()>>4)!=searchChunk.z) {
                BlockPos destination=travelTarget;
                net.minecraft.pathfinding.Path path=destination==null?null:v.getNavigation().createPath(destination,0);
                if(now-travelStarted>600 || path==null || !path.canReach()
                        || !v.getNavigation().moveTo(path,VillagerNavigationSupport.WALK_SPEED)) {
                    rejectChunk(a,v,world); return SkillResult.RUNNING;
                }
                return SkillResult.RUNNING;
            }
        }
        ResourceSensor.Target found = ResourceSensor.find(world, v, a, source, rejected);
        if (found == null) {
            if(searchChunk!=null) { rejectChunk(a,v,world); return SkillResult.RUNNING; }
            goal.setLastFailure(FailureReason.UNREACHABLE.with("no reachable resource target"));
            return SkillResult.FAIL;
        }
        // An ore block already mined today is skipped without being broken; retry the search.
        if (!source.consumesBlock && MiningWorldData.get(world).mined(v.getUUID(), found.block)) { rejected.add(found.block); return SkillResult.RUNNING; }
        if(source.profession==ResourceProfession.LUMBERJACK) { tree=TreeFelling.scan(world,found.block);if(tree==null) { rejected.add(found.block);return SkillResult.RUNNING; } }
        VillagerAction breaking = new VillagerAction(VillagerAction.ActionType.BREAK, source.methodId());
        breaking.setTargetBlockPos(found.block); breaking.setStandCell(found.stand);
        breaking.setBreakTool(source.availableTool(a));
        breaking.setGoalDriven(true); breaking.setPhase(VillagerAction.ActionPhase.WALKING);
        a.setCurrentAction(breaking); a.setCurrentActivity(source.methodId());
        return SkillResult.RUNNING;
    }
    private void rejectChunk(VillagerAgentData a,VillagerEntity v,ServerWorld world) {
        long key=searchChunk.toLong(); rejectedChunks.add(key); attempts.reject(key);
        BrainLog.diagnostic(a,"extraction-target-rejected","chunk="+key+" failures="+attempts.failures());
        searchChunk=null; travelTarget=null; ActionLifecycle.clearBody(a,v,world); ReachablePaths.invalidate(v);
    }

}
