package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.*;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Rarity-first expeditions, followed by local ore clearing and finally stone. */
public final class MinerWorkSkill implements VillagerSkill {
    private static final class State {
        ResourceCatalog.Source source;
        ResourceSensor.MiningScan scan;
        MiningPlanner.Search search;
        final Map<BlockPos, Long> rejected = new HashMap<>();
        MovementProgress movement = new MovementProgress();
        BlockPos stand;
        long nextNavigation, nextOreCheck, knownOreFacts;
        /** Connected exposed ore still to mine before the next survey. */
        final Deque<BlockPos> vein = new ArrayDeque<>();
        List<ResourceCatalog.Source> scanSources = Collections.emptyList(), veinSources = Collections.emptyList();
        DropCollector collector;
        /** Dark work spot to light with a torch on arrival. */
        BlockPos lightAt;
        int mined, quota;
        boolean surveyed, initialized;
    }
    @Override public String id() { return "miner_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.MINING; }
    @Override public int minLevel() { return 1; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        return Math.max(.5, 1 + (a.getCapabilityPool().levelOf(CapabilityId.MINING) - 1) * .3);
    }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "miner_work".equals(g.getGoalType()) && "miner".equalsIgnoreCase(a.getProfession()) && passesCapabilityGate(a);
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        if (!canHandle(a, g, v, w)) return SkillResult.DELETE;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return SkillResult.WAITING;
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        MiningWorldData data = MiningWorldData.get(w);
        MiningProgress p = data.progress(v.getUUID());
        List<ResourceCatalog.Source> ores = MiningPlanner.eligible(a, null);
        int eligibility = 1;
        for (ResourceCatalog.Source source : ores) eligibility = 31 * eligibility + source.output.getRegistryName().hashCode();
        if (p.refresh(now, eligibility)) { s.scan = null; s.search = null; s.surveyed = false; }
        data.setDirty();
        if (!s.initialized) {
            s.initialized = true;
            if (p.active() && p.destination == null && p.lastMine != null) p.destination = p.lastMine;
        }
        boolean pick = false;
        for (ItemStack stack : a.getInventory().getItems())
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem) { pick = true; break; }
        if (!pick) { g.setLastFailure(FailureReason.MISSING_ITEM.with("pickaxe")); return SkillResult.BLOCKED; }
        int level = a.getCapabilityPool().levelOf(CapabilityId.MINING);
        if (s.quota == 0) { int base = 6 + level / 2; s.quota = base + v.getRandom().nextInt(Math.max(1, base / 2)); }

        VillagerAction action = a.getCurrentAction();
        if (action != null && action.getActionType() == VillagerAction.ActionType.BREAK) {
            BlockPos target = action.getTargetBlockPos();
            if (s.lightAt != null && v.blockPosition().distSqr(s.lightAt) <= 4) {
                placeTorch(a, v, w);
                s.lightAt = null;
            }
            if (s.source == null || !s.source.skilled(a) || !s.source.hasTool(a)
                    || !w.isLoaded(target) || !s.source.matches(w.getBlockState(target))) {
                ActionLifecycle.clearBody(a, v, w); s.source = null; return SkillResult.RUNNING;
            }
            BlockInteractionAction.StepResult result = BlockInteractionAction.tickMine(v, w, a, action, s.source.output, 1);
            if (result == BlockInteractionAction.StepResult.IN_PROGRESS) {
                a.brain().goalStartedTick = now; return SkillResult.RUNNING;
            }
            if (result == BlockInteractionAction.StepResult.DONE) {
                s.mined++; p.lastMine = target.immutable(); g.markProgress();
                // The block is broken: between this block and the next the miner may switch task.
                GoalCommitment.workUnitDone(a, now);
                a.gainCapabilityXp(s.source.capability, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
                if (s.source.capability == CapabilityId.QUARRYING) {
                    int bonus = MiningProgress.bonus(level, v.getRandom().nextDouble());
                    if (bonus != 0) Block.popResource(w, target, new ItemStack(bonus == 2 ? Items.IRON_ORE : Items.COAL));
                }
                BrainLog.diagnostic(a, "mining-yield", "source=" + s.source.output.getRegistryName()
                        + " pos=" + target + " phase=" + p.phase + " count=" + s.mined + "/" + s.quota);
            } else {
                s.rejected.put(target.immutable(), now + 1200); ReachablePaths.invalidate(v);
            }
            ActionLifecycle.clearBody(a, v, w); s.source = null; s.surveyed = false;
            // Keep swinging through the connected vein; walk round for the drops only once it is done.
            if (startNextInVein(s, a, v, w, data, now)) return SkillResult.RUNNING;
            s.collector = new DropCollector(target, 10);
            return SkillResult.RUNNING;
        }
        if (s.collector != null) {
            if (!s.collector.tick(a, v, w)) { a.brain().goalStartedTick = now; return SkillResult.RUNNING; }
            s.collector = null;
        }
        if (s.mined >= s.quota) return SkillResult.SUCCESS;
        s.rejected.values().removeIf(t -> t <= now);

        if (p.destination != null) {
            if (!w.isLoaded(p.destination)) {
                abandonVisit(p, s, now); ActionLifecycle.clearBody(a, v, w); return SkillResult.RUNNING;
            }
            if (lightNear(w, p.destination) < MIN_LIGHT && !hasTorch(a)) {
                // Never walk into the dark without a torch; the craft routine keeps torches stocked.
                BrainLog.diagnostic(a, "mining-dark", "anchor=" + p.destination + " light=" + lightNear(w, p.destination)
                        + " torches=0");
                abandonVisit(p, s, now); ActionLifecycle.clearBody(a, v, w); return SkillResult.RUNNING;
            }
            if (BlockInteractionAction.withinReach(v.blockPosition(), p.destination)) {
                v.getNavigation().stop(); p.destination = null; s.stand = null; s.surveyed = false;
            } else {
                if (s.stand == null) s.stand = ReachablePaths.standingBeside(w, v, p.destination);
                if (s.stand == null || s.movement.stationaryTicks(now, v.getX(), v.getY(), v.getZ()) > 200) {
                    abandonVisit(p, s, now); ActionLifecycle.clearBody(a, v, w); return SkillResult.RUNNING;
                }
                if (now >= s.nextNavigation) {
                    s.nextNavigation = now + 20;
                    Path path = v.getNavigation().createPath(s.stand, 0);
                    if (path == null || !path.canReach() || !v.getNavigation().moveTo(path, VillagerNavigationSupport.WALK_SPEED)) {
                        abandonVisit(p, s, now); ActionLifecycle.clearBody(a, v, w); return SkillResult.RUNNING;
                    }
                }
                a.brain().goalStartedTick = now; return SkillResult.RUNNING;
            }
        }
        if (p.phase == MiningProgress.Phase.STONE && s.scan == null
                && (now >= s.nextOreCheck || s.knownOreFacts != MiningPlanner.knowledgeFingerprint(a))) {
            p.phase = MiningProgress.Phase.SELECT; s.search = null;
        }
        if (p.active() || p.phase == MiningProgress.Phase.STONE) {
            if (!s.surveyed) {
                if (s.scan == null) {
                    List<ResourceCatalog.Source> sources;
                    if (p.phase == MiningProgress.Phase.STONE) {
                        ResourceCatalog.Source stone = ResourceCatalog.get("minecraft:cobblestone");
                        if (!stone.skilled(a) || !stone.hasTool(a)) return SkillResult.WAITING;
                        sources = Collections.singletonList(stone);
                    } else sources = MiningPlanner.eligible(a, p.phase == MiningProgress.Phase.PRIMARY ? p.primary : null);
                    boolean district = p.active();
                    s.scanSources = sources;
                    s.scan = new ResourceSensor.MiningScan(v.blockPosition(), sources, pos -> !district || p.inArea(pos));
                }
                if (!s.scan.tick(w, v, s.rejected::containsKey)) { a.brain().goalStartedTick = now; return SkillResult.RUNNING; }
                ResourceSensor.Target target = s.scan.best;
                ResourceCatalog.Source source = s.scan.source;
                boolean deferred = s.scan.deferred; s.scan = null;
                if (target != null && !lit(s, a, w, target.block, target.stand, now)) return SkillResult.RUNNING;
                if (target != null && source.skilled(a) && source.hasTool(a)
                        && data.claim(v.getUUID(), target.block, now)) {
                    begin(s, a, source, target.block, target.stand);
                    buildVein(s, v, w, data, target.block, now);
                    BrainLog.diagnostic(a, "mining-vein", "seed=" + target.block + " source="
                            + source.output.getRegistryName() + " queued=" + s.vein.size());
                    return SkillResult.RUNNING;
                }
                if (target != null) return SkillResult.RUNNING;
                if (p.phase == MiningProgress.Phase.STONE) return s.mined > 0 ? SkillResult.SUCCESS : SkillResult.WAITING;
                if (!p.anchorKey.isEmpty()) p.defer(p.anchorKey, deferred ? now + 1200 : (now / 24000L + 1) * 24000L);
                p.anchorKey = ""; s.surveyed = true;
            }
        }
        boolean global = !p.active();
        if (s.search == null) {
            if (global) s.knownOreFacts = MiningPlanner.knowledgeFingerprint(a);
            s.search = new MiningPlanner.Search(a, p, now, global);
        }
        if (!s.search.tick(w, v, p)) { a.brain().goalStartedTick = now; return SkillResult.RUNNING; }
        MiningPlanner.Visit visit = s.search.best;
        BlockPos stand = s.search.stand; s.search = null;
        if (visit != null) {
            if (global) p.begin(visit.anchor, visit.item);
            p.destination = visit.anchor; p.anchorKey = visit.key;
            s.stand = stand; s.movement = new MovementProgress(); s.nextNavigation = 0; s.surveyed = false;
            BrainLog.diagnostic(a, "mining-visit", "phase=" + p.phase + " district=" + p.chunkX + "," + p.chunkZ
                    + " source=" + visit.item + " anchor=" + visit.anchor);
            return SkillResult.RUNNING;
        }
        p.exhausted();
        if (p.phase == MiningProgress.Phase.STONE) s.nextOreCheck = now + 100;
        s.surveyed = false; p.anchorKey = "";
        BrainLog.diagnostic(a, "mining-phase", "phase=" + p.phase);
        return SkillResult.RUNNING;
    }
    private static void begin(State s, VillagerAgentData a, ResourceCatalog.Source source, BlockPos block, BlockPos stand) {
        s.source = source;
        VillagerAction mining = new VillagerAction(VillagerAction.ActionType.BREAK, source.methodId());
        mining.setTargetBlockPos(block); mining.setStandCell(stand);
        mining.setBreakTool(source.availableTool(a)); mining.setGoalDriven(true);
        mining.setPhase(VillagerAction.ActionPhase.WALKING);
        a.setCurrentAction(mining); a.setCurrentActivity(source.methodId());
    }
    private static ResourceCatalog.Source veinSource(State s, VillagerAgentData a, ServerWorld w, BlockPos pos) {
        net.minecraft.block.BlockState state = w.getBlockState(pos);
        for (ResourceCatalog.Source source : s.veinSources)
            if (source.matches(state) && (a == null || source.skilled(a) && source.hasTool(a))) return source;
        return null;
    }
    /** Flood-fill the blocks connected to the seed (26-neighbourhood) and queue the exposed ones. */
    private static void buildVein(State s, VillagerEntity v, ServerWorld w, MiningWorldData data, BlockPos seed, long now) {
        s.vein.clear(); s.veinSources = s.scanSources;
        if (s.veinSources.isEmpty()) return;
        boolean stone = s.veinSources.size() == 1 && "minecraft:cobblestone".equals(String.valueOf(s.veinSources.get(0).output.getRegistryName()));
        int cap = stone ? 8 : 24;
        Set<BlockPos> seen = new HashSet<>(); Deque<BlockPos> open = new ArrayDeque<>();
        seen.add(seed); open.add(seed);
        while (!open.isEmpty() && s.vein.size() < cap && seen.size() < 256) {
            BlockPos at = open.poll();
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                BlockPos next = at.offset(x, y, z);
                if (Math.abs(next.getX() - seed.getX()) > 6 || Math.abs(next.getY() - seed.getY()) > 6
                        || Math.abs(next.getZ() - seed.getZ()) > 6 || !w.isLoaded(next) || !seen.add(next)) continue;
                if (veinSource(s, null, w, next) == null) continue;
                open.add(next);
                if (s.vein.size() < cap && ResourceSensor.isExposed(w, next) && !s.rejected.containsKey(next)
                        && !data.mined(v.getUUID(), next) && !data.occupied(v.getUUID(), next, now)) s.vein.add(next);
            }
        }
    }
    /** Starts the nearest still-valid vein block; false once the vein is used up. */
    private static boolean startNextInVein(State s, VillagerAgentData a, VillagerEntity v, ServerWorld w,
                                           MiningWorldData data, long now) {
        while (!s.vein.isEmpty()) {
            BlockPos here = v.blockPosition(), next = null;
            double best = Double.POSITIVE_INFINITY;
            for (BlockPos pos : s.vein) { double d = pos.distSqr(here); if (d < best) { best = d; next = pos; } }
            s.vein.remove(next);
            if (!w.isLoaded(next) || s.rejected.containsKey(next) || data.mined(v.getUUID(), next)
                    || !ResourceSensor.isExposed(w, next)) continue;
            ResourceCatalog.Source source = veinSource(s, a, w, next);
            if (source == null) continue;
            BlockPos stand = BlockInteractionAction.withinReach(here, next) ? here.immutable()
                    : ReachablePaths.standingBeside(w, v, next);
            if (stand == null || !lit(s, a, w, next, stand, now) || !data.claim(v.getUUID(), next, now)) {
                s.rejected.put(next.immutable(), now + 1200); continue;
            }
            begin(s, a, source, next, stand);
            return true;
        }
        return false;
    }
    /** Minimum brightness (block or sky light, ignoring time of day) to mine without a torch. */
    public static final int MIN_LIGHT = 10;

    /** Brightest of the spot and its six neighbours (an ore block itself is opaque and always 0). */
    static int lightNear(ServerWorld w, BlockPos pos) {
        int best = w.getRawBrightness(pos, 0);
        for (net.minecraft.util.Direction d : net.minecraft.util.Direction.values())
            best = Math.max(best, w.getRawBrightness(pos.relative(d), 0));
        return best;
    }

    static boolean hasTorch(VillagerAgentData a) {
        return a.getInventory().countItem(new ItemStack(Items.TORCH)) > 0;
    }

    /**
     * Dark work spots need a torch: with one, remember to light the stand on arrival; without one, skip the
     * block for a minute (false).
     */
    private static boolean lit(State s, VillagerAgentData a, ServerWorld w, BlockPos block, BlockPos stand, long now) {
        BlockPos at = stand != null ? stand : block;
        int light = Math.max(lightNear(w, at), lightNear(w, block));
        if (light >= MIN_LIGHT) { s.lightAt = null; return true; }
        if (hasTorch(a)) { s.lightAt = at.immutable(); return true; }
        s.rejected.put(block.immutable(), now + 1200);
        BrainLog.diagnostic(a, "mining-dark", "block=" + block + " light=" + light + " torches=0");
        return false;
    }

    /** Set one carried torch on the floor at or beside the villager. */
    private static void placeTorch(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        if (!hasTorch(a) || lightNear(w, v.blockPosition()) >= MIN_LIGHT) return;
        BlockPos origin = v.blockPosition();
        List<BlockPos> spots = new ArrayList<>();
        spots.add(origin);
        for (net.minecraft.util.Direction d : net.minecraft.util.Direction.Plane.HORIZONTAL) spots.add(origin.relative(d));
        net.minecraft.block.BlockState torch = net.minecraft.block.Blocks.TORCH.defaultBlockState();
        for (BlockPos spot : spots) {
            if (!w.isEmptyBlock(spot) || !torch.canSurvive(w, spot)) continue;
            w.setBlock(spot, torch, 3);
            a.getInventory().removeItem(new ItemStack(Items.TORCH), 1);
            w.playSound(null, spot, net.minecraft.util.SoundEvents.WOOD_PLACE, net.minecraft.util.SoundCategory.BLOCKS, 1f, 1f);
            v.swing(net.minecraft.util.Hand.MAIN_HAND);
            BrainLog.diagnostic(a, "mining-torch", "pos=" + spot);
            return;
        }
    }

    private static void abandonVisit(MiningProgress p, State s, long now) {
        if (!p.anchorKey.isEmpty()) p.defer(p.anchorKey, now + 1200);
        p.destination = null; p.anchorKey = ""; s.stand = null;
        s.scan = null; s.search = null; s.surveyed = false;
    }
}
