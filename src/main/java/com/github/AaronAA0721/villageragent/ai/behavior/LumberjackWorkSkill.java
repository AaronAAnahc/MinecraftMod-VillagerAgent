package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.memory.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.DropCollector;
import com.github.AaronAA0721.villageragent.ai.work.TreeFelling;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Lumberjack routine: walk to the nearest non-village chunk with trees,
 * find a tree (log connected to leaves via BFS), compute break time
 * using the axe, play chopping animation, then fell the whole tree.
 *
 * Opportunity factor: influenced by remembered WOOD_LOG counts in chunk memory.
 * Requires an axe in inventory.
 */
public final class LumberjackWorkSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("LumberjackWorkSkill");

    private static final class State {
        ChunkMemory targetChunk;
        BlockPos treeRoot, treeStand, forestStand;
        final TargetAttempts attempts = new TargetAttempts(3);
        TreeFelling felling;
        Set<Long> rejectedChunks = new HashSet<>();
        VillagerAction breakAction;
        /** Trees felled this session, and how many this session aims for. */
        int felled, quota;
        /** Picking up the drops of the tree just felled. */
        boolean collecting;
        Set<BlockPos> rejectedTrees = new HashSet<>();
        DropCollector collector;
    }

    @Override public String id() { return "lumberjack_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.WOODCUTTING; }
    @Override public int minLevel() { return 1; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // More remembered trees in non-village chunks = higher motivation
        int woodLogs = 0;
        for (com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm : a.getAllChunkMemories())
            woodLogs += cm.count(com.github.AaronAA0721.villageragent.ai.memory.BlockCategory.WOOD_LOG);
        return Math.min(4, Math.max(0.2, woodLogs / 20.0));
    }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "lumberjack_work".equals(g.getGoalType()) && passesCapabilityGate(a);
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        if (s.attempts.exhausted()) {
            g.setLastFailure(FailureReason.UNREACHABLE.with("three failed logging targets"));
            g.setMinimumExitCooldownTicks(1200);
            return SkillResult.FAIL;
        }

        if (s.quota == 0) {
            // A session fells several trees: 3-4 for a novice, more with practice.
            int level = a.getCapabilityPool().levelOf(CapabilityId.WOODCUTTING);
            int base = Math.min(MAX_TREES_PER_SESSION, MIN_TREES_PER_SESSION + level / 2);
            s.quota = Math.min(MAX_TREES_PER_SESSION, base + v.getRandom().nextInt(2));
        }

        if (s.collecting) {
            // Walk round the felled tree until every drop (logs, saplings, apples) has been picked up.
            if (s.collector == null) {
                ActionLifecycle.clearBody(a, v, w);
                a.brain().tasks.discardChild();
                s.collector = new DropCollector(s.treeRoot, 12);
            }
            g.markProgress();
            if (!s.collector.tick(a, v, w)) { a.brain().goalStartedTick = now; return SkillResult.RUNNING; }
            s.collecting = false; s.collector = null;
            if (s.felled >= s.quota || !hasRoom(a)) return SkillResult.SUCCESS;
            // Next tree in the same forest. Between two trees the lumberjack may switch to another task.
            s.rejectedTrees.add(s.treeRoot);
            s.treeRoot = null; s.treeStand = null; s.felling = null; s.breakAction = null;
            GoalCommitment.workUnitDone(a, now);
            return SkillResult.RUNNING;
        }

        // ── Need an axe ──
        ItemStack axe = findAxe(a);
        if (axe.isEmpty()) {
            if (s.felled > 0) return SkillResult.SUCCESS; // Axe broke mid-session: keep what was felled.
            LOG.debug("{} wants to chop but has no axe", a.getName());
            g.setLastFailure(FailureReason.MISSING_ITEM.with("axe"));
            return SkillResult.BLOCKED;
        }

        // Keep one route stable. Only a failed/exhausted target triggers reselection.
        if (s.targetChunk == null && s.treeRoot == null) {
            s.targetChunk = TreeFelling.remembered(a, v, w, s.rejectedChunks);
            if (s.targetChunk == null) {
                if (s.felled > 0) return SkillResult.SUCCESS;
                g.setLastFailure(FailureReason.UNREACHABLE.with("no reachable forest chunk"));
                return SkillResult.FAIL;
            }
            s.forestStand = ReachablePaths.forestDestination(w,v,s.targetChunk.getX(),s.targetChunk.getZ());
        }
        if (s.treeRoot == null && ((v.blockPosition().getX()>>4) != s.targetChunk.getX()
                || (v.blockPosition().getZ()>>4) != s.targetChunk.getZ())) {
            if (s.forestStand == null) return rejectChunk(a,g,v,w,s);
            SkillResult r = BehaviorExecutor.executeChild(a,v,w,() -> {
                AgentGoal move=new AgentGoal("move","Walk to forest",g.getPriority());
                move.setTargetLocation(s.forestStand); move.setArrivalDistanceSq(0.25); return move;
            });
            if (r == SkillResult.RUNNING) return r;
            if (r != SkillResult.SUCCESS) return rejectChunk(a,g,v,w,s);
        }

        // Entering the destination chunk ends its movement child before selecting a tree.
        if (s.treeRoot == null && a.brain().tasks.current().child() != null) {
            ActionLifecycle.clearBody(a,v,w); a.brain().tasks.discardChild();
        }
        // ── Find a tree nearby ──
        if (s.treeRoot == null) {
            s.treeRoot = findNearbyTree(a, v, w, s);
            if (s.treeRoot == null) {
                LOG.debug("{} no tree found in chunk ({},{})", a.getName(),
                        s.targetChunk.getX(), s.targetChunk.getZ());
                if (s.felled > 0) {
                    // This patch is cleared after a productive start: try the next remembered forest
                    // (not a failed target); with none left the session simply ends.
                    s.rejectedChunks.add(s.targetChunk.chunkKey);
                    s.targetChunk = null; s.forestStand = null;
                    return SkillResult.RUNNING;
                }
                return rejectChunk(a,g,v,w,s);
            }
        }

        // ── Scan the tree (BFS logs connected to leaves) ──
        if (s.felling == null) {
            s.felling = TreeFelling.scan(w, s.treeRoot);
            if (s.felling == null) {
                LOG.debug("{} tree at {} is invalid (no connected logs+leaves)", a.getName(), s.treeRoot);
                return rejectTree(a,g,v,w,s);
            }
        }

        // ── Walk to the tree root ──
        if (!BlockInteractionAction.withinReach(v.blockPosition(), s.treeRoot)) {
            SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to tree", g.getPriority());
                move.setTargetLocation(s.treeStand);
                move.setArrivalDistanceSq(0.25);
                return move;
            });
            if (r == SkillResult.RUNNING) return r;
            if (r != SkillResult.SUCCESS || !BlockInteractionAction.withinReach(v.blockPosition(),s.treeRoot))
                return rejectTree(a,g,v,w,s);
        }

        // ── Chop: delegate to TreeFelling.tick for animation + break logic ──
        if (s.breakAction == null) {
            s.breakAction = new VillagerAction(VillagerAction.ActionType.BREAK,
                    "Chopping tree at " + s.treeRoot);
            s.breakAction.setGoalDriven(true);
            s.breakAction.setTargetBlockPos(s.treeRoot);
            s.breakAction.setPhase(VillagerAction.ActionPhase.ACTING);
            // Find a standing position adjacent to the root
            BlockPos stand = s.treeStand;
            s.breakAction.setStandCell(stand);
            s.breakAction.setBreakTool(axe);
            a.setCurrentAction(s.breakAction);
        }

        // Hold axe visually
        v.setItemSlot(EquipmentSlotType.MAINHAND, axe.copy());

        BlockInteractionAction.StepResult result = s.felling.tick(a, v, w, s.breakAction);

        switch (result) {
            case DONE:
                s.felled++;
                LOG.info("{} felled tree #{} at {}", a.getName(), s.felled, s.treeRoot);
                a.gainCapabilityXp(CapabilityId.WOODCUTTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
                a.addMemory("Felled a tree near (" + s.treeRoot.getX() + ", " + s.treeRoot.getZ() + ")");
                g.markProgress();
                v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
                a.setCurrentAction(null);
                s.breakAction = null;
                s.collecting = true;
                return SkillResult.RUNNING;

            case ABANDONED:
                LOG.debug("{} abandoned felling at {}", a.getName(), s.treeRoot);
                v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
                return rejectTree(a,g,v,w,s);

            case IN_PROGRESS:
            default:
                a.brain().goalStartedTick = now; // prevent stuck timeout
                return SkillResult.RUNNING;
        }
    }

    private static final int MIN_TREES_PER_SESSION = 3;
    private static final int MAX_TREES_PER_SESSION = 6;

    /** At least one free slot left for the next tree's logs. */
    private static boolean hasRoom(VillagerAgentData a) {
        for (ItemStack st : a.getInventory().getItems()) if (st.isEmpty()) return true;
        return false;
    }

    /** Find an axe in the villager's inventory. */
    private static ItemStack findAxe(VillagerAgentData a) {
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() instanceof AxeItem) return s;
        return ItemStack.EMPTY;
    }

    private static SkillResult rejectTree(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w,State s) {
        s.attempts.reject(s.treeRoot); s.rejectedTrees.add(s.treeRoot);
        BrainLog.diagnostic(a,"logging-target-rejected","tree="+s.treeRoot+" failures="+s.attempts.failures());
        s.treeRoot=null; s.treeStand=null; s.felling=null; s.breakAction=null;
        return afterRejection(a,g,v,w,s);
    }
    private static SkillResult rejectChunk(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w,State s) {
        s.attempts.reject(s.targetChunk.chunkKey); s.rejectedChunks.add(s.targetChunk.chunkKey);
        BrainLog.diagnostic(a,"logging-target-rejected","chunk="+s.targetChunk.chunkKey+" failures="+s.attempts.failures());
        s.targetChunk=null; s.forestStand=null;
        return afterRejection(a,g,v,w,s);
    }
    private static SkillResult afterRejection(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w,State s) {
        ActionLifecycle.clearBody(a,v,w); a.brain().tasks.discardChild(); ReachablePaths.invalidate(v);
        if (!s.attempts.exhausted()) return SkillResult.RUNNING;
        if (s.felled > 0) return SkillResult.SUCCESS; // Keep the trees already felled this session.
        g.setLastFailure(FailureReason.UNREACHABLE.with("three failed logging targets"));
        g.setMinimumExitCooldownTicks(1200); return SkillResult.FAIL;
    }
    /** Probe a bounded set of trunk bases and compare complete routes to adjacent standing cells. */
    private static BlockPos findNearbyTree(VillagerAgentData a,VillagerEntity v,ServerWorld w,State s) {
        ChunkMemory cm=s.targetChunk;
        if(cm==null || !w.hasChunk(cm.getX(),cm.getZ())) return null;
        List<BlockPos> roots=new ArrayList<>(); Map<BlockPos,BlockPos> stands=new HashMap<>();
        int minX=cm.getX()*16,minZ=cm.getZ()*16;
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(minX,0,minZ),new BlockPos(minX+15,255,minZ+15))) {
            if(s.rejectedTrees.contains(p) || !w.getBlockState(p).is(BlockTags.LOGS)
                    || w.getBlockState(p.below()).is(BlockTags.LOGS) || TreeFelling.scan(w,p)==null) continue;
            roots.add(p.immutable()); if(roots.size()>=16) break;
        }
        BlockPos best=s.attempts.select(roots,root -> {
            BlockPos stand=ReachablePaths.standingBeside(w,v,root); stands.put(root,stand);
            return ReachablePaths.distance(v,stand);
        });
        s.treeStand=best==null?null:stands.get(best);
        return best;
    }
}
