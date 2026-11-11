package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.memory.*;
import net.minecraft.block.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Bounded connected-log snapshot, validated before any destructive commit. */
public final class TreeFelling {
    private final Map<BlockPos,BlockState> logs;
    private TreeFelling(Map<BlockPos,BlockState> logs) { this.logs=logs; }
    public static TreeFelling scan(ServerWorld world,BlockPos root) {
        Map<BlockPos,BlockState> logs=new LinkedHashMap<>();
        Set<BlockPos> connected=TreeGraph.scan(root.immutable(),
            pos->world.hasChunk(pos.getX()>>4,pos.getZ()>>4),
            pos->world.getBlockState(pos).is(BlockTags.LOGS),
            pos->world.getBlockState(pos).is(BlockTags.LEAVES),
            pos->VillageRegistry.get(world).contains(world,pos),
            pos->{ List<BlockPos> neighbors=new ArrayList<>(26);
                for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) for(int z=-1;z<=1;z++)
                    if(x!=0||y!=0||z!=0) neighbors.add(pos.offset(x,y,z));
                return neighbors; },512);
        for(BlockPos pos:connected) logs.put(pos,world.getBlockState(pos));
        return logs.isEmpty()?null:new TreeFelling(logs);
    }
    public static ChunkMemory remembered(VillagerAgentData a,VillagerEntity v,ServerWorld w,Set<Long> rejected) {
        ChunkMemory bestOutside=null;
        double distOutside=Double.POSITIVE_INFINITY;
        // Path-probe only the nearest few remembered forest chunks (each probe is a full A* search).
        List<ChunkMemory> candidates=new ArrayList<>();
        for(ChunkMemory cm:a.getAllChunkMemories()) {
            if(cm.count(BlockCategory.WOOD_LOG)<=0||rejected.contains(cm.chunkKey)
                    ||VillageRegistry.get(w).intersectsChunk(cm.getX(),cm.getZ())) continue;
            candidates.add(cm);
        }
        int vx=v.blockPosition().getX(), vz=v.blockPosition().getZ();
        candidates.sort(Comparator.comparingDouble(cm -> {
            double dx=cm.getX()*16+8-vx, dz=cm.getZ()*16+8-vz; return dx*dx+dz*dz; }));
        if(candidates.size()>6) candidates=candidates.subList(0,6);
        for(ChunkMemory cm:candidates) {
            double d=ReachablePaths.distance(v,ReachablePaths.forestDestination(w,v,cm.getX(),cm.getZ()));
            if(d<distOutside) { bestOutside=cm;distOutside=d; }
        }
        return bestOutside;
    }
    public BlockInteractionAction.StepResult tick(VillagerAgentData a,VillagerEntity v,ServerWorld w,VillagerAction action) {
        BlockPos root=action.getTargetBlockPos(),stand=action.getStandCell();
        if(!BlockInteractionAction.withinReach(v.blockPosition(),root)) {
            w.destroyBlockProgress(v.getId(),root,-1);action.setBreakProgress(0);
            action.incrementStuckTicks();
            if(stand==null||action.getStuckTicks()>600) return BlockInteractionAction.StepResult.ABANDONED;
            if(action.getStuckTicks()%20==1) {
                net.minecraft.pathfinding.Path path=v.getNavigation().createPath(stand,0);
                if(path==null || !path.canReach() || !v.getNavigation().moveTo(path,VillagerNavigationSupport.WALK_SPEED))
                    return BlockInteractionAction.StepResult.ABANDONED;
            }
            return BlockInteractionAction.StepResult.IN_PROGRESS;
        }
        v.getNavigation().stop();action.setPhase(VillagerAction.ActionPhase.ACTING);
        ItemStack tool=action.getBreakTool();
        if(tool==null||tool.isEmpty()||!a.getInventory().getItems().contains(tool)) return BlockInteractionAction.StepResult.ABANDONED;
        if(action.getBreakTargetTicks()<=0) {
            long ticks=0;
            for(Map.Entry<BlockPos,BlockState> e:logs.entrySet()) ticks+=VillagerEquipmentHelper.computeBreakTicks(w,e.getKey(),e.getValue(),tool);
            if(ticks>=Integer.MAX_VALUE) return BlockInteractionAction.StepResult.ABANDONED;
            action.setBreakTargetTicks((int)Math.max(1,ticks));
        }
        if(!logs.get(root).equals(w.getBlockState(root))) return BlockInteractionAction.StepResult.ABANDONED;
        action.incrementBreakProgress();
        w.destroyBlockProgress(v.getId(),root,(int)Math.min(9,10L*action.getBreakProgress()/action.getBreakTargetTicks()));
        if(action.getBreakProgress()%5==0) v.swing(net.minecraft.util.Hand.MAIN_HAND);
        // Chopping sound: wood "hit" every 4 ticks, plus a louder axe thunk on each swing.
        if(action.getBreakProgress()%4==1) BlockInteractionAction.playHitSound(w,root,logs.get(root),v);
        if(action.getBreakProgress()%10==5) w.playSound(null,root,net.minecraft.util.SoundEvents.WOOD_BREAK,
                net.minecraft.util.SoundCategory.BLOCKS,0.6F,0.8F+v.getRandom().nextFloat()*0.2F);
        v.getLookControl().setLookAt(root.getX()+0.5,root.getY()+0.5,root.getZ()+0.5);
        if(action.getBreakProgress()<action.getBreakTargetTicks()) return BlockInteractionAction.StepResult.IN_PROGRESS;
        TreeFelling current=scan(w,root);
        if(current==null||!current.logs.equals(logs)) return BlockInteractionAction.StepResult.ABANDONED;
        net.minecraftforge.common.util.FakePlayer actor=net.minecraftforge.common.util.FakePlayerFactory.get(w,
            new com.mojang.authlib.GameProfile(v.getUUID(),"[VillagerAgent]"));
        actor.setPos(v.getX(),v.getY(),v.getZ());actor.setItemSlot(net.minecraft.inventory.EquipmentSlotType.MAINHAND,tool);
        // Protection hooks for the complete snapshot run before any block is removed.
        for(BlockPos pos:logs.keySet())
            if(net.minecraftforge.common.ForgeHooks.onBlockBreakEvent(w,net.minecraft.world.GameType.SURVIVAL,actor,pos)<0)
                return BlockInteractionAction.StepResult.ABANDONED;
        for(Map.Entry<BlockPos,BlockState> e:logs.entrySet())
            if(!w.hasChunk(e.getKey().getX()>>4,e.getKey().getZ()>>4)||!e.getValue().equals(w.getBlockState(e.getKey())))
                return BlockInteractionAction.StepResult.ABANDONED;
        ItemStack lootTool=tool.copy();
        for(Map.Entry<BlockPos,BlockState> e:logs.entrySet()) {
            BlockPos pos=e.getKey();BlockState state=e.getValue();
            List<ItemStack> drops=Block.getDrops(state,w,pos,w.getBlockEntity(pos),actor,lootTool);
            // removeBlock avoids playing a separate break effect for every log.
            if(w.removeBlock(pos,false)) {
                for(ItemStack drop:drops) Block.popResource(w,pos,drop);
                if(!tool.isEmpty()) tool.mineBlock(w,state,pos,actor);
                com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex.instance(w).onBlockChanged(pos);
            }
        }
        w.levelEvent(2001,root,Block.getId(logs.get(root)));
        w.destroyBlockProgress(v.getId(),root,-1);
        replant(a,w,root,logs.get(root));
        return BlockInteractionAction.StepResult.DONE;
    }

    /**
     * Replant a sapling of the felled species at the base, if the lumberjack carries one and the
     * base is now air over soil. Replanting is best effort: no sapling or unsuitable ground simply
     * skips it, so regeneration failure surfaces later as a wood shortage rather than a free sapling.
     */
    private static void replant(VillagerAgentData a, ServerWorld w, BlockPos root, BlockState logState) {
        net.minecraft.util.ResourceLocation logId = logState.getBlock().getRegistryName();
        if (logId == null) return;
        net.minecraft.item.Item sapling = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.util.ResourceLocation(logId.toString().replace("_log", "_sapling")));
        if (sapling == null || sapling == net.minecraft.item.Items.AIR) return;
        ItemStack held = ItemStack.EMPTY;
        for (ItemStack st : a.getInventory().getItems())
            if (!st.isEmpty() && st.getItem() == sapling) { held = st; break; }
        if (held.isEmpty()) return;
        if (!w.isEmptyBlock(root)) return;
        BlockState below = w.getBlockState(root.below());
        if (below.getBlock() != Blocks.DIRT && below.getBlock() != Blocks.GRASS_BLOCK
                && below.getBlock() != Blocks.PODZOL && below.getBlock() != Blocks.COARSE_DIRT) return;
        Block saplingBlock = Block.byItem(sapling);
        if (saplingBlock == Blocks.AIR || !saplingBlock.defaultBlockState().canSurvive(w, root)) return;
        w.setBlock(root, saplingBlock.defaultBlockState(), 3);
        held.shrink(1);
        com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex.instance(w).onBlockChanged(root);
    }
}
