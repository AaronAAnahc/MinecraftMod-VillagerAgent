package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.DropCollector;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.content.VillagerBobberEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.*;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


/**
 * Fisherman routine: walk to the water's edge and fish a session of several catches the way a player does.
 *
 * <ol>
 *   <li><b>Cast</b> — a {@link VillagerBobberEntity} arcs from the rod tip onto a visible water block 3–8 blocks out;
 *       the client draws the hook and the line to the villager's rod.</li>
 *   <li><b>Wait</b> — vanilla timings: 100–600 ticks until a fish notices the hook, then 20–80 ticks while its
 *       trail ({@code FISHING} particles) swims toward the float.</li>
 *   <li><b>Bite</b> — the float is pulled under with a splash; the villager reacts after 6–14 ticks.</li>
 *   <li><b>Reel in</b> — the vanilla fishing loot table is rolled at the hook and every item is launched toward the
 *       villager with the vanilla retrieve velocity, so it flies through the air to him; he then picks it up.</li>
 * </ol>
 * One session lands 3–5 catches (each a yield point), with short pauses between casts.
 */
public final class FishSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("FishSkill");
    private static final int MAX_CAST_FAILURES = 4;

    private enum Phase { FIND, SEEK, WALK, CAST, LURE, APPROACH, BITE, LANDING, PAUSE }

    /** Open water (8 water neighbours), remembered in chunk memory as WATER. */
    private static final BlockSeek.Kind WATER = new BlockSeek.Kind("open water", 6,
            st -> st.getBlock() == Blocks.WATER, com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.WATER);

    private static final class State {
        Phase phase = Phase.FIND;
        BlockPos water;
        /** Shore cell he fishes from: always at least one block above {@link #water}. */
        BlockPos stand;
        final java.util.Set<BlockPos> rejectedStands = new java.util.HashSet<>();
        /** Remembered water being walked to when no spot is in view; null when not seeking. */
        BlockPos seeking;
        final java.util.Set<BlockPos> rejectedWater = new java.util.HashSet<>();
        final BlockSeek.Tracker seek = new BlockSeek.Tracker();
        VillagerBobberEntity bobber;
        long until;
        float fishAngle;
        int catches, sessionCatches, castFailures, walkFailures;
        DropCollector drops;
    }

    @Override public String id() { return "fish"; }
    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // Boost if we know water chunks; slight boost from fishing level
        int waterChunks = 0;
        for (com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm : a.getAllChunkMemories())
            if (cm.has(com.github.AaronAA0721.villageragent.ai.memory.ChunkTag.WATER)) waterChunks++;
        return Math.max(0.5, Math.min(3, waterChunks * 0.5 + 0.5));
    }
    @Override public CapabilityId requiredCapability() { return CapabilityId.FISHING; }
    @Override public int minLevel() { return 1; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "fish".equals(g.getGoalType()) && passesCapabilityGate(a);
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        Random random = v.getRandom();
        if (s.sessionCatches == 0) s.sessionCatches = 3 + random.nextInt(3);

        // Need a fishing rod.
        if (!hasFishingRod(a)) {
            removeBobber(s);
            g.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:fishing_rod"));
            LOG.debug("{} wants to fish but has no rod", a.getName());
            return SkillResult.BLOCKED;
        }

        switch (s.phase) {
            case FIND: {
                BlockPos[] spot = findSpot(v, w, s.rejectedStands);
                if (spot == null && s.rejectedWater.size() < 3) {
                    // Nothing in view: head for the nearest open water chunk memory remembers.
                    s.seeking = BlockSeek.nearest(a, v, w, WATER, (ww, p) -> !s.rejectedWater.contains(p)
                            && p.distSqr(v.blockPosition()) > 20 * 20 && isOpenWater(ww, p), 0);
                    if (s.seeking != null) { s.seek.reset(); s.phase = Phase.SEEK; return SkillResult.RUNNING; }
                }
                if (spot == null) {
                    LOG.debug("{} found no open water with a raised bank nearby to fish", a.getName());
                    g.setLastFailure(FailureReason.UNREACHABLE.with("no open water with a raised bank"));
                    return SkillResult.FAIL;
                }
                s.water = spot[0];
                s.stand = spot[1];
                BrainLog.diagnostic(a, "fish-spot", "water=" + s.water + " stand=" + s.stand);
                a.setCurrentActivity("fishing");
                s.phase = Phase.WALK;
                return SkillResult.RUNNING;
            }
            case SEEK: {
                // Walking to remembered water; each newly entered chunk with open water triggers a spot search.
                BlockPos wet = BlockSeek.closerOnTheWay(a, v, w, WATER, (ww, p) -> isOpenWater(ww, p), null, s.seek);
                BlockPos[] spot = wet != null ? findSpot(v, w, s.rejectedStands) : null;
                if (spot == null) {
                    final BlockPos far = s.seeking;
                    SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                        AgentGoal move = new AgentGoal("move", "Walk to remembered water", g.getPriority());
                        move.setTargetLocation(far);
                        move.setArrivalDistanceSq(36);
                        return move;
                    });
                    if (r == SkillResult.RUNNING) return r;
                    s.rejectedWater.add(s.seeking); // arrived or gave up: look again from here
                    s.seeking = null;
                    s.phase = Phase.FIND;
                    return SkillResult.RUNNING;
                }
                BlockSeek.abortWalk(a, v, w);
                s.seeking = null;
                s.water = spot[0];
                s.stand = spot[1];
                BrainLog.diagnostic(a, "fish-spot", "water=" + s.water + " stand=" + s.stand + " (found on the way)");
                a.setCurrentActivity("fishing");
                s.phase = Phase.WALK;
                return SkillResult.RUNNING;
            }
            case WALK: {
                if (!atStand(v, s)) {
                    final BlockPos target = s.stand;
                    SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                        AgentGoal move = new AgentGoal("move", "Walk to fishing spot", g.getPriority());
                        move.setTargetLocation(target);
                        move.setArrivalDistanceSq(1);
                        return move;
                    });
                    if (r == SkillResult.RUNNING) return r;
                    if (r != SkillResult.SUCCESS || !atStand(v, s)) {
                        s.rejectedStands.add(s.stand);
                        if (++s.walkFailures >= 3) {
                            g.setLastFailure(FailureReason.UNREACHABLE.with("fishing spot " + s.stand));
                            return SkillResult.FAIL;
                        }
                        s.phase = Phase.FIND;
                        return SkillResult.RUNNING;
                    }
                }
                // Never fish standing level with (or below) the water surface.
                if (v.blockPosition().getY() <= s.water.getY()) {
                    s.rejectedStands.add(s.stand);
                    s.phase = Phase.FIND;
                    return SkillResult.RUNNING;
                }
                s.phase = Phase.CAST;
                return SkillResult.RUNNING;
            }
            default:
                break;
        }

        // Everything below happens at the water's edge, rod in hand.
        a.brain().goalStartedTick = now; // productive waiting; do not trip the no-progress watchdog
        v.getNavigation().stop();
        ItemStack rod = findRod(a);
        if (!ItemStack.isSame(v.getMainHandItem(), rod)) v.setItemSlot(EquipmentSlotType.MAINHAND, rod.copy());

        if (s.phase == Phase.CAST) {
            if (!atStand(v, s) || v.blockPosition().getY() <= s.water.getY()) { s.phase = Phase.WALK; return SkillResult.RUNNING; }
            BlockPos target = pickCastTarget(v, w, s.water, random);
            lookAt(v, Vector3d.atCenterOf(target));
            v.swing(Hand.MAIN_HAND);
            w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundCategory.NEUTRAL,
                    0.5F, 0.4F / (random.nextFloat() * 0.4F + 0.8F));
            s.bobber = VillagerBobberEntity.cast(w, v, new Vector3d(target.getX() + 0.5, target.getY() + 0.9, target.getZ() + 0.5));
            // Vanilla lure time (no Lure enchantment): 100–600 ticks.
            s.until = now + MathHelper.nextInt(random, 100, 600);
            s.phase = Phase.LURE;
            LOG.debug("{} cast at {}", a.getName(), target);
            return SkillResult.RUNNING;
        }

        if (s.phase == Phase.PAUSE) {
            if (now < s.until) return SkillResult.RUNNING;
            s.phase = Phase.CAST;
            return SkillResult.RUNNING;
        }

        if (s.phase == Phase.LANDING) {
            if (now < s.until) return SkillResult.RUNNING;
            if (s.drops != null && !s.drops.tick(a, v, w)) return SkillResult.RUNNING;
            s.drops = null;
            if (s.catches >= s.sessionCatches) {
                v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
                return SkillResult.SUCCESS;
            }
            // Walking after the catch may have moved him away from the edge.
            s.phase = atStand(v, s) ? Phase.PAUSE : Phase.WALK;
            s.until = now + 20 + random.nextInt(30);
            return SkillResult.RUNNING;
        }

        // LURE / APPROACH / BITE: the bobber must still be out there.
        VillagerBobberEntity bobber = s.bobber;
        if (bobber == null || !bobber.isAlive()) return recast(s, g, now);
        bobber.keepAlive(now);
        lookAt(v, bobber.position());
        if (bobber.hasLanded() && bobber.landedOnGround()) {
            reelEmpty(v, w, s);
            return recast(s, g, now);
        }
        double surface = bobber.waterSurface();

        if (s.phase == Phase.LURE) {
            if (surface >= 0 && random.nextInt(60) == 0)
                w.sendParticles(ParticleTypes.BUBBLE, bobber.getX(), surface, bobber.getZ(), 1, 0.1, 0.0, 0.1, 0.0);
            if (now < s.until) return SkillResult.RUNNING;
            if (surface < 0) { reelEmpty(v, w, s); return recast(s, g, now); }
            // A fish has noticed the hook: vanilla 20–80 ticks of approach.
            s.fishAngle = MathHelper.nextFloat(random, 0.0F, 360.0F) * ((float) Math.PI / 180F);
            s.until = now + MathHelper.nextInt(random, 20, 80);
            s.phase = Phase.APPROACH;
            return SkillResult.RUNNING;
        }

        if (s.phase == Phase.APPROACH) {
            if (surface < 0) { reelEmpty(v, w, s); return recast(s, g, now); }
            long remaining = s.until - now;
            if (remaining > 0) {
                // The fish's wake: particles moving across the surface toward the float (vanilla style).
                s.fishAngle += (float) random.nextGaussian() * 0.06F;
                double dist = remaining * 0.1;
                double px = bobber.getX() + MathHelper.sin(s.fishAngle) * dist;
                double pz = bobber.getZ() + MathHelper.cos(s.fishAngle) * dist;
                w.sendParticles(ParticleTypes.FISHING, px, surface + 0.1, pz, 0,
                        -MathHelper.sin(s.fishAngle) * 0.04, 0.01, -MathHelper.cos(s.fishAngle) * 0.04, 1.0);
                if (random.nextFloat() < 0.15F)
                    w.sendParticles(ParticleTypes.BUBBLE, px, surface - 0.1, pz, 1, 0.1, 0.0, 0.1, 0.0);
                return SkillResult.RUNNING;
            }
            // Bite: the float is pulled under with a splash.
            bobber.setBiting(true);
            w.playSound(null, bobber.getX(), bobber.getY(), bobber.getZ(), SoundEvents.FISHING_BOBBER_SPLASH,
                    SoundCategory.NEUTRAL, 0.25F, 1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
            w.sendParticles(ParticleTypes.BUBBLE, bobber.getX(), surface, bobber.getZ(), 6, 0.15, 0.0, 0.15, 0.2);
            w.sendParticles(ParticleTypes.FISHING, bobber.getX(), surface, bobber.getZ(), 6, 0.15, 0.0, 0.15, 0.2);
            s.until = now + MathHelper.nextInt(random, 6, 14); // the villager's reaction time
            s.phase = Phase.BITE;
            return SkillResult.RUNNING;
        }

        // BITE: reel in.
        if (now < s.until) return SkillResult.RUNNING;
        reelCatch(a, v, w, bobber, rod);
        removeBobber(s);
        damageRod(a);
        s.catches++;
        a.gainCapabilityXp(CapabilityId.FISHING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
        a.addMemory("Went fishing and caught something");
        g.markProgress();
        GoalCommitment.workUnitDone(a, now);
        // Let the catch fly in, then pick up anything that fell short.
        s.until = now + 25;
        s.drops = new DropCollector(v.blockPosition(), 6);
        s.phase = Phase.LANDING;
        return SkillResult.RUNNING;
    }

    private static SkillResult recast(State s, AgentGoal g, long now) {
        removeBobber(s);
        if (++s.castFailures >= MAX_CAST_FAILURES) {
            g.setLastFailure(FailureReason.UNREACHABLE.with("no open water to cast into"));
            return s.catches > 0 ? SkillResult.SUCCESS : SkillResult.FAIL;
        }
        s.phase = Phase.CAST;
        return SkillResult.RUNNING;
    }

    /** Pull the line back without a catch. */
    private static void reelEmpty(VillagerEntity v, ServerWorld w, State s) {
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundCategory.NEUTRAL, 1.0F,
                0.4F / (v.getRandom().nextFloat() * 0.4F + 0.8F));
        v.swing(Hand.MAIN_HAND);
        removeBobber(s);
    }

    /** Vanilla retrieve: roll the fishing loot table at the hook and throw every item toward the fisherman. */
    private static void reelCatch(VillagerAgentData a, VillagerEntity v, ServerWorld w, VillagerBobberEntity bobber, ItemStack rod) {
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundCategory.NEUTRAL, 1.0F,
                0.4F / (v.getRandom().nextFloat() * 0.4F + 0.8F));
        v.swing(Hand.MAIN_HAND);
        w.sendParticles(ParticleTypes.SPLASH, bobber.getX(), bobber.getY() + 0.3, bobber.getZ(), 8, 0.3, 0.1, 0.3, 0.0);
        LootContext ctx = new LootContext.Builder(w)
                .withRandom(v.getRandom())
                .withParameter(LootParameters.ORIGIN, bobber.position())
                .withParameter(LootParameters.TOOL, rod)
                .withParameter(LootParameters.THIS_ENTITY, bobber)
                .create(LootParameterSets.FISHING);
        List<ItemStack> loot = new ArrayList<>(w.getServer().getLootTables().get(LootTables.FISHING).getRandomItems(ctx));
        for (ItemStack drop : loot) {
            ItemEntity item = new ItemEntity(w, bobber.getX(), bobber.getY(), bobber.getZ(), drop.copy());
            double dx = v.getX() - bobber.getX();
            double dy = v.getY() - bobber.getY();
            double dz = v.getZ() - bobber.getZ();
            item.setDeltaMovement(dx * 0.1D, dy * 0.1D + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08D, dz * 0.1D);
            // Only the fisherman picks up his own catch while it is in flight.
            item.setOwner(v.getUUID());
            w.addFreshEntity(item);
            LOG.debug("{} caught {}", a.getName(), drop);
        }
    }

    private static void removeBobber(State s) {
        if (s.bobber != null && s.bobber.isAlive()) s.bobber.remove();
        s.bobber = null;
    }

    private static void lookAt(VillagerEntity v, Vector3d p) {
        v.getLookControl().setLookAt(p.x, p.y, p.z, 20f, 40f);
    }

    private static boolean hasFishingRod(VillagerAgentData a) { return !findRod(a).isEmpty(); }

    private static ItemStack findRod(VillagerAgentData a) {
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() == Items.FISHING_ROD) return s;
        return ItemStack.EMPTY;
    }

    private static void damageRod(VillagerAgentData a) {
        List<ItemStack> items = a.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || s.getItem() != Items.FISHING_ROD) continue;
            s.setDamageValue(s.getDamageValue() + 1);
            if (s.getDamageValue() >= s.getMaxDamage()) items.set(i, ItemStack.EMPTY);
            return;
        }
    }

    private static boolean atStand(VillagerEntity v, State s) {
        return s.stand != null && v.blockPosition().distSqr(s.stand) <= 2;
    }

    /**
     * Open water: a water block with air above whose 8 horizontal neighbours are all water too
     * (no single-block puddles, no water tucked against a wall or shore).
     */
    static boolean isOpenWater(ServerWorld w, BlockPos p) {
        if (!isSurfaceWater(w, p)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (w.getBlockState(p.offset(dx, 0, dz)).getBlock() != Blocks.WATER) return false;
        }
        return true;
    }

    private static boolean isSurfaceWater(ServerWorld w, BlockPos p) {
        return w.isLoaded(p) && w.getBlockState(p).getBlock() == Blocks.WATER && w.isEmptyBlock(p.above());
    }

    /** A dry cell a villager can stand in: solid floor, two blocks of air. */
    private static boolean standable(ServerWorld w, BlockPos p) {
        if (!w.isLoaded(p)) return false;
        net.minecraft.block.BlockState floor = w.getBlockState(p.below());
        if (floor.getBlock() == Blocks.WATER || !floor.isFaceSturdy(w, p.below(), net.minecraft.util.Direction.UP)) return false;
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty() && w.getFluidState(p).isEmpty()
                && w.getBlockState(p.above()).getCollisionShape(w, p.above()).isEmpty();
    }

    /**
     * The nearest fishing spot within 24 blocks: {open water block, shore cell to stand on}. The shore cell is
     * 1–3 blocks higher than the water surface, 3–7 blocks away horizontally, with a clear line of sight to it.
     */
    private static BlockPos[] findSpot(VillagerEntity v, ServerWorld w, java.util.Set<BlockPos> rejected) {
        BlockPos origin = v.blockPosition();
        List<BlockPos> waters = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-24, -6, -24), origin.offset(24, 3, 24)))
            if (isOpenWater(w, p)) waters.add(p.immutable());
        waters.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(origin)));
        BlockPos[] best = null;
        double bestD = Double.MAX_VALUE;
        int checked = 0;
        for (BlockPos water : waters) {
            if (++checked > 48) break;
            Vector3d top = new Vector3d(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
            for (int dy = 1; dy <= 3; dy++) for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
                double horiz = Math.sqrt(dx * dx + dz * dz);
                if (horiz < 3 || horiz > 7) continue;
                BlockPos stand = water.offset(dx, dy, dz);
                if (rejected.contains(stand) || !standable(w, stand)) continue;
                double d = stand.distSqr(origin);
                if (d >= bestD) continue;
                Vector3d eye = new Vector3d(stand.getX() + 0.5, stand.getY() + v.getEyeHeight(), stand.getZ() + 0.5);
                RayTraceResult hit = w.clip(new RayTraceContext(eye, top, RayTraceContext.BlockMode.COLLIDER,
                        RayTraceContext.FluidMode.NONE, v));
                if (hit.getType() != RayTraceResult.Type.MISS) continue;
                bestD = d;
                best = new BlockPos[]{water, stand.immutable()};
            }
            if (best != null && checked >= 8) break; // good enough: nearest few water blocks examined
        }
        return best;
    }

    /**
     * A water surface 3–8 blocks out that the villager can see from where he stands, preferring 4–7 blocks
     * (a natural cast). Falls back to the water block he walked to.
     */
    private static BlockPos pickCastTarget(VillagerEntity v, ServerWorld w, BlockPos fallback, Random random) {
        BlockPos origin = v.blockPosition();
        Vector3d eye = v.getEyePosition(1.0f);
        List<BlockPos> good = new ArrayList<>(), ok = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -4, -8), origin.offset(8, 1, 8))) {
            double dx = p.getX() + 0.5 - v.getX(), dz = p.getZ() + 0.5 - v.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 3 || d > 8) continue;
            // Only open water, and only water lower than where he stands.
            if (p.getY() >= origin.getY() || !isOpenWater(w, p)) continue;
            Vector3d top = new Vector3d(p.getX() + 0.5, p.getY() + 0.9, p.getZ() + 0.5);
            RayTraceResult hit = w.clip(new RayTraceContext(eye, top, RayTraceContext.BlockMode.COLLIDER,
                    RayTraceContext.FluidMode.NONE, v));
            if (hit.getType() != RayTraceResult.Type.MISS) continue;
            (d >= 4 && d <= 7 ? good : ok).add(p.immutable());
        }
        if (!good.isEmpty()) return good.get(random.nextInt(good.size()));
        if (!ok.isEmpty()) return ok.get(random.nextInt(ok.size()));
        return fallback;
    }
}
