package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.housing.ConstructionSites;
import com.github.AaronAA0721.villageragent.ai.housing.HouseBlueprints;
import com.github.AaronAA0721.villageragent.ai.housing.VillageHousing;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SoundType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.BedItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.state.properties.BedPart;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.state.properties.DoubleBlockHalf;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Builder routine ({@code builder_work}). Each session the builder looks at its village and does the most useful
 * of:
 * <ol>
 *   <li><b>finish the house under construction</b> (any builder of the village continues it);</li>
 *   <li><b>put a bed in a house</b> when there are fewer beds than residents (+ {@code builder_spare_beds}) and a
 *       free bed spot exists — an empty slot of a house it built, or a spare corner of an existing house. No bed in
 *       the inventory → it asks for {@code minecraft:white_bed} through the normal acquisition (it is unlocked for
 *       builders, so it crafts it from wool and planks, asking the shepherd/lumberjack when short);</li>
 *   <li><b>start a new house</b> when beds are needed and no spot is left: it picks a vanilla house of the village's
 *       style ({@link HouseBlueprints}) and searches a flat, open spot inside the village ({@link VillageHousing}).</li>
 * </ol>
 * Building places one block every {@code builder_block_interval} ticks, layer by layer. Missing materials are
 * requested ({@link FailureRecoveryPlanner#requireItem}: craft / buy / village request) and the session pauses
 * until they arrive. The plan is kept in {@link ConstructionSites}; progress is read from the world.
 */
public final class BuilderWorkSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("BuilderWorkSkill");
    /** How far a builder reaches (it carries a ladder; a player reaches 4.5). */
    private static final double REACH = 6.0;
    private static final int BLOCKS_PER_SESSION = 64;
    private static final int SEARCH_BUDGET = 160;
    /** A cell that cannot be reached or placed this many times is left out of the house. */
    private static final int MAX_CELL_FAILS = 3;
    private static final int OBSTRUCTED_WAIT_TICKS = 60;
    private static final String BED_ITEM = "minecraft:white_bed";

    private static final Map<UUID, Map<BlockPos, Integer>> FAILS = new HashMap<>();

    private enum Phase { DECIDE, SEARCH, BUILD, BED }

    private static final class State {
        Phase phase = Phase.DECIDE;
        long village;
        VillageHousing.SiteSearch search;
        UUID siteId;
        VillageHousing.BedSpot bed;
        /** Cell being worked on and the standing cell chosen for it. */
        BlockPos target, stand;
        final Set<BlockPos> deferred = new HashSet<>();
        int placed;
        long nextPlaceTick;
        long obstructedSince = Long.MIN_VALUE;
        boolean askedForMaterials;
    }

    @Override public String id() { return "builder_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.CRAFTING; }
    @Override public int minLevel() { return 1; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "builder_work".equals(g.getGoalType()) && isBuilder(a) && passesCapabilityGate(a);
    }

    public static boolean isBuilder(VillagerAgentData a) {
        return a != null && "builder".equalsIgnoreCase(a.getProfession());
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        VillageMarkets.Market market = VillageMarkets.market(w, v.getUUID());
        if (market == null || VillageHousing.bounds(w, market.id) == null)
            return idle(a, g, v, s, 2400, "I'm a builder, but I don't belong to a village to build for");
        s.village = market.id;
        switch (s.phase) {
            case SEARCH: return search(a, g, v, w, s);
            case BUILD: return build(a, g, v, w, s);
            case BED: return placeBed(a, g, v, w, s);
            case DECIDE:
            default: return decide(a, g, v, w, s);
        }
    }

    // ── What to do this session ─────────────────────────────────────────────

    private static SkillResult decide(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s) {
        a.brain().goalStartedTick = w.getGameTime();
        ConstructionSites sites = ConstructionSites.get(w);
        ConstructionSites.Site active = sites.active(s.village);
        if (active != null) {
            s.siteId = active.id;
            s.phase = Phase.BUILD;
            a.setCurrentActivity("building");
            return SkillResult.RUNNING;
        }
        if (VillageHousing.demand(w, s.village) <= 0)
            return idle(a, g, v, s, 2400, null); // everyone has a bed (and a spare): nothing to build
        VillageHousing.BedSpot spot = VillageHousing.freeBedSpot(w, s.village);
        if (spot != null) {
            s.bed = spot;
            s.phase = Phase.BED;
            return SkillResult.RUNNING;
        }
        if (sites.finishedCount(s.village) >= ModConfig.BUILDER_MAX_HOUSES.get())
            return idle(a, g, v, s, 6000, "The village needs beds, but I've built as many houses as I'm allowed to");
        s.search = VillageHousing.newSearch(w, s.village, new Random(v.getRandom().nextLong()));
        if (s.search == null) return idle(a, g, v, s, 6000, "I have no house plans for this kind of village");
        s.phase = Phase.SEARCH;
        a.setCurrentActivity("surveying");
        return SkillResult.RUNNING;
    }

    private static SkillResult search(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s) {
        a.brain().goalStartedTick = w.getGameTime();
        if (!s.search.step(w, SEARCH_BUDGET)) return SkillResult.RUNNING;
        VillageHousing.Candidate c = s.search.result();
        s.search = null;
        if (c == null) {
            s.phase = Phase.DECIDE;
            return idle(a, g, v, s, 6000, "We need more beds, but there's no flat, open spot left in the village for a house");
        }
        ConstructionSites.Site site = ConstructionSites.get(w).add(s.village, c.template, c.rotation, c.origin, w.getGameTime());
        HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, site.template, site.rotation);
        if (bp != null) VillageHousing.clearSite(w, site, bp);
        a.addMemory("Marked out a new house (" + (bp != null ? bp.shortName() : c.template) + ") at "
                + c.origin.getX() + "," + c.origin.getZ() + " because the village is short of beds");
        LOG.info("{} started a house {} {} at {}", a.getName(), c.template, c.rotation, c.origin);
        s.siteId = site.id;
        s.phase = Phase.BUILD;
        a.setCurrentActivity("building");
        return SkillResult.RUNNING;
    }

    // ── Building ────────────────────────────────────────────────────────────

    private static SkillResult build(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s) {
        long now = w.getGameTime();
        ConstructionSites sites = ConstructionSites.get(w);
        ConstructionSites.Site site = sites.byId(s.siteId);
        if (site == null || site.finished) { s.phase = Phase.DECIDE; return SkillResult.RUNNING; }
        HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, site.template, site.rotation);
        if (bp == null) {
            sites.remove(site);
            a.addMemory("Lost the plan for the house at " + site.origin.getX() + "," + site.origin.getZ());
            s.phase = Phase.DECIDE;
            return SkillResult.RUNNING;
        }
        if (now < s.nextPlaceTick) return SkillResult.RUNNING;
        Map<BlockPos, Integer> fails = FAILS.computeIfAbsent(site.id, k -> new HashMap<>());

        // ── Pick the cell to work on: the current group (phase + layer), something we carry, nearest first ──
        HouseBlueprints.Step step = s.target == null ? null : stepAt(bp, site, s.target);
        if (step != null && (done(w, site, step) || !hasMaterial(a, step))) step = null;
        if (step == null) {
            if (s.target != null && a.brain().tasks.current().child() != null) {
                // Re-targeting: stop walking to the old cell's standing spot.
                ActionLifecycle.clearBody(a, v, w);
                a.brain().tasks.discardChild();
            }
            s.target = null; s.stand = null;
            List<HouseBlueprints.Step> pending = new ArrayList<>();
            boolean deferred = false;
            for (HouseBlueprints.Step st : bp.steps) {
                BlockPos p = site.world(st.rel);
                if (done(w, site, st) || fails.getOrDefault(p, 0) >= MAX_CELL_FAILS) continue;
                if (s.deferred.contains(p)) { deferred = true; continue; }
                pending.add(st);
            }
            if (pending.isEmpty()) {
                if (deferred) return endSession(a, g, v, s); // retry the hard-to-reach cells next session
                return finishHouse(a, v, w, s, site, bp);
            }
            int group = pending.get(0).group();
            double best = Double.MAX_VALUE;
            List<HouseBlueprints.Step> short_ = new ArrayList<>();
            for (HouseBlueprints.Step st : pending) {
                if (st.group() != group) break;
                if (!hasMaterial(a, st)) { short_.add(st); continue; }
                double d = v.blockPosition().distSqr(site.world(st.rel));
                if (d < best) { best = d; step = st; }
            }
            if (step == null) return askForMaterials(a, g, v, w, s, bp, site, short_);
            s.target = site.world(step.rel);
            s.obstructedSince = Long.MIN_VALUE;
        }
        BlockPos target = s.target;
        a.setCurrentActivity("building");

        // ── Get within reach, out of the cell ──
        boolean tall = step.state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
        if (!inReach(v, target) || occupies(v, target, tall)) {
            if (s.stand == null) {
                s.stand = findStand(w, v, target, tall);
                if (s.stand == null) return defer(a, s, fails, target, "no standing spot");
            }
            final BlockPos stand = s.stand;
            SkillResult walk = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to the building site", g.getPriority());
                move.setTargetLocation(stand);
                move.setArrivalDistanceSq(1.0);
                move.setSource(g.getSource());
                return move;
            });
            if (walk == SkillResult.RUNNING) return SkillResult.RUNNING;
            s.stand = null;
            if (walk != SkillResult.SUCCESS || !inReach(v, target) || occupies(v, target, tall))
                return defer(a, s, fails, target, "walk " + walk);
        }

        // ── Someone standing in the cell: wait a little ──
        v.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (!w.isUnobstructed(step.state, target, ISelectionContext.empty())) {
            if (s.obstructedSince == Long.MIN_VALUE) s.obstructedSince = now;
            if (now - s.obstructedSince < OBSTRUCTED_WAIT_TICKS) return SkillResult.RUNNING;
            return defer(a, s, fails, target, "obstructed");
        }

        // ── Place ──
        if (!place(a, v, w, site, step, target)) {
            fails.put(target, MAX_CELL_FAILS); // cannot stand there (e.g. a torch with nothing to hang on)
        } else {
            s.placed++;
            g.markProgress();
            a.brain().goalStartedTick = now;
            if (s.placed % 16 == 0) {
                a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
                GoalCommitment.workUnitDone(a, now); // a natural pause: eating or a meeting may take over
            }
        }
        s.target = null;
        s.nextPlaceTick = now + ModConfig.BUILDER_BLOCK_INTERVAL.get();
        if (s.placed >= BLOCKS_PER_SESSION) return endSession(a, g, v, s);
        return SkillResult.RUNNING;
    }

    private static HouseBlueprints.Step stepAt(HouseBlueprints.Blueprint bp, ConstructionSites.Site site, BlockPos pos) {
        BlockPos rel = pos.subtract(site.origin);
        for (HouseBlueprints.Step st : bp.steps) if (st.rel.equals(rel)) return st;
        return null;
    }

    /** The cell already holds the planned block (any wood of the same kind counts). */
    private static boolean done(ServerWorld w, ConstructionSites.Site site, HouseBlueprints.Step st) {
        BlockState cur = w.getBlockState(site.world(st.rel));
        Block want = st.state.getBlock();
        if (cur.getBlock() == want) return true;
        // Terrain is terrain: grass that turned to dirt under the floor still counts.
        if (st.free()) return !cur.isAir() && cur.getMaterial().isSolid();
        String category = st.free() ? null : SupplyPolicy.materialCategory(st.item);
        return category != null && cur.getBlock().asItem() != net.minecraft.item.Items.AIR
                && SupplyPolicy.categoryMatches(category, cur.getBlock().asItem());
    }

    private static boolean hasMaterial(VillagerAgentData a, HouseBlueprints.Step st) {
        return st.free() || !material(a, st).isEmpty();
    }

    /** The exact item, or for wood any planks / logs the builder carries. */
    private static ItemStack material(VillagerAgentData a, HouseBlueprints.Step st) {
        String category = SupplyPolicy.materialCategory(st.item);
        ItemStack fallback = ItemStack.EMPTY;
        for (ItemStack stack : a.getInventory().getItems()) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() == st.item) return stack;
            if (fallback.isEmpty() && category != null && stack.getItem() instanceof BlockItem
                    && SupplyPolicy.categoryMatches(category, stack.getItem())) fallback = stack;
        }
        return fallback;
    }

    /**
     * Nothing in the current layer can be placed with what the builder carries: ask for the missing items (up to a
     * stack each, sized to what the rest of the house still needs) and pause the session while they are acquired.
     */
    private static SkillResult askForMaterials(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s,
                                               HouseBlueprints.Blueprint bp, ConstructionSites.Site site,
                                               List<HouseBlueprints.Step> shortSteps) {
        Map<Item, Integer> remaining = new LinkedHashMap<>();
        for (HouseBlueprints.Step st : bp.steps)
            if (!st.free() && !done(w, site, st)) remaining.merge(st.item, 1, Integer::sum);
        Set<Item> asked = new LinkedHashSet<>();
        for (HouseBlueprints.Step st : shortSteps) {
            if (asked.size() >= 3) break;
            if (!asked.add(st.item)) continue;
            int want = Math.min(64, remaining.getOrDefault(st.item, 1));
            FailureRecoveryPlanner.requireItem(a, st.item.getRegistryName().toString(), want);
        }
        if (!asked.isEmpty() && !s.askedForMaterials) {
            StringBuilder sb = new StringBuilder("Need materials for the house:");
            for (Item item : asked) sb.append(' ').append(Math.min(64, remaining.getOrDefault(item, 1))).append("x ")
                    .append(item.getRegistryName().getPath());
            a.addMemory(sb.toString());
            s.askedForMaterials = true;
        }
        BrainLog.diagnostic(a, "builder-waiting-materials", "site=" + site.origin + " items=" + asked);
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        g.setLastFailure(null);
        g.setMinimumExitCooldownTicks(300);
        s.target = null;
        return SkillResult.WAITING;
    }

    private static boolean place(VillagerAgentData a, VillagerEntity v, ServerWorld w, ConstructionSites.Site site,
                                 HouseBlueprints.Step step, BlockPos pos) {
        BlockState state = step.state;
        ItemStack stack = step.free() ? ItemStack.EMPTY : material(a, step);
        if (!step.free() && stack.isEmpty()) return true; // spent meanwhile: re-plan next tick
        if (!stack.isEmpty() && stack.getItem() != step.item)
            state = HouseBlueprints.copyProperties(step.state, ((BlockItem) stack.getItem()).getBlock());
        boolean tall = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
        if (!tall) {
            // Let fences, panes and stairs join their neighbours; a torch with no wall comes back as air.
            state = Block.updateFromNeighbourShapes(state, w, pos);
            if (state.isAir()) return false;
        }
        if (step.rel.getY() == 0 && w.isEmptyBlock(pos.below()))
            w.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), 3); // fill a dip under the floor
        w.setBlock(pos, state, 3);
        if (tall) w.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
        SoundType sound = state.getSoundType();
        w.playSound(null, pos, sound.getPlaceSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        v.setItemSlot(EquipmentSlotType.MAINHAND, stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem()));
        v.swing(Hand.MAIN_HAND);
        if (!stack.isEmpty()) stack.shrink(1);
        return true;
    }

    private static SkillResult finishHouse(VillagerAgentData a, VillagerEntity v, ServerWorld w, State s,
                                           ConstructionSites.Site site, HouseBlueprints.Blueprint bp) {
        ConstructionSites.get(w).finish(site, w.getGameTime());
        FAILS.remove(site.id);
        a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get() * 4, v, w.getGameTime());
        a.addMemory("Finished building a house at " + site.origin.getX() + "," + site.origin.getZ()
                + " with room for " + bp.beds.size() + " bed(s)");
        LOG.info("{} finished house {} at {}", a.getName(), bp.shortName(), site.origin);
        s.phase = Phase.DECIDE; // furnish it with beds right away
        s.siteId = null;
        return SkillResult.RUNNING;
    }

    // ── Beds ────────────────────────────────────────────────────────────────

    private static SkillResult placeBed(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s) {
        VillageHousing.BedSpot spot = s.bed;
        if (spot == null || !VillageHousing.spotFree(w, spot.foot, spot.facing)) {
            s.bed = null; s.phase = Phase.DECIDE; s.stand = null;
            return SkillResult.RUNNING;
        }
        ItemStack bed = findBed(a);
        if (bed.isEmpty()) {
            FailureRecoveryPlanner.requireItem(a, BED_ITEM, 1);
            if (!s.askedForMaterials) {
                a.addMemory("The village is short of beds — making one for the house at "
                        + spot.foot.getX() + "," + spot.foot.getZ());
                s.askedForMaterials = true;
            }
            s.phase = Phase.DECIDE;
            g.setLastFailure(null);
            g.setMinimumExitCooldownTicks(300);
            return SkillResult.WAITING;
        }
        BlockPos head = spot.head();
        if (!inReach(v, spot.foot) || !inReach(v, head) || occupies(v, spot.foot, false) || occupies(v, head, false)) {
            if (s.stand == null) {
                s.stand = findStand(w, v, spot.foot, false);
                if (s.stand == null || s.stand.equals(head)) {
                    s.bed = null; s.phase = Phase.DECIDE; s.stand = null;
                    return idle(a, g, v, s, 1200, "Couldn't get to the bed spot at " + spot.foot.getX() + "," + spot.foot.getZ());
                }
            }
            final BlockPos stand = s.stand;
            SkillResult walk = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Carry a bed into a house", g.getPriority());
                move.setTargetLocation(stand);
                move.setArrivalDistanceSq(1.0);
                move.setSource(g.getSource());
                return move;
            });
            if (walk == SkillResult.RUNNING) return SkillResult.RUNNING;
            s.stand = null;
            if (walk != SkillResult.SUCCESS || occupies(v, spot.foot, false) || occupies(v, head, false)) {
                s.bed = null; s.phase = Phase.DECIDE;
                return idle(a, g, v, s, 1200, null);
            }
        }
        Block block = ((BlockItem) bed.getItem()).getBlock();
        BlockState foot = block.defaultBlockState().setValue(BedBlock.FACING, spot.facing).setValue(BedBlock.PART, BedPart.FOOT);
        w.setBlock(spot.foot, foot, 3);
        w.setBlock(head, foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
        SoundType sound = foot.getSoundType();
        w.playSound(null, spot.foot, sound.getPlaceSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        v.setItemSlot(EquipmentSlotType.MAINHAND, new ItemStack(bed.getItem()));
        v.swing(Hand.MAIN_HAND);
        bed.shrink(1);
        a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, w.getGameTime());
        a.addMemory("Put a new bed in " + (spot.planned ? "the new house" : "a house") + " at "
                + spot.foot.getX() + "," + spot.foot.getY() + "," + spot.foot.getZ());
        LOG.info("{} placed a bed at {}", a.getName(), spot.foot);
        g.markProgress();
        a.brain().goalStartedTick = w.getGameTime();
        s.placed++;
        s.bed = null;
        s.phase = Phase.DECIDE; // maybe another bed is needed
        return SkillResult.RUNNING;
    }

    private static ItemStack findBed(VillagerAgentData a) {
        for (ItemStack stack : a.getInventory().getItems())
            if (!stack.isEmpty() && stack.getItem() instanceof BedItem) return stack;
        return ItemStack.EMPTY;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static SkillResult defer(VillagerAgentData a, State s, Map<BlockPos, Integer> fails, BlockPos target, String why) {
        fails.merge(target, 1, Integer::sum);
        s.deferred.add(target);
        s.target = null;
        s.stand = null;
        BrainLog.diagnostic(a, "builder-cell-deferred", "cell=" + target + " reason=" + why
                + " fails=" + fails.get(target));
        return SkillResult.RUNNING;
    }

    private static SkillResult endSession(VillagerAgentData a, AgentGoal g, VillagerEntity v, State s) {
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        a.setCurrentActivity("idle");
        g.setMinimumExitCooldownTicks(0);
        return SkillResult.SUCCESS;
    }

    /** Nothing (more) to do: end the session and wait {@code cooldown} ticks before looking again. */
    private static SkillResult idle(VillagerAgentData a, AgentGoal g, VillagerEntity v, State s, int cooldown, String memory) {
        if (memory != null) a.addMemory(memory);
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        a.setCurrentActivity("idle");
        g.setMinimumExitCooldownTicks(s.placed > 0 ? 0 : cooldown);
        return SkillResult.SUCCESS;
    }

    private static boolean inReach(VillagerEntity v, BlockPos target) {
        double dx = v.getX() - (target.getX() + 0.5);
        double dy = v.getEyeY() - (target.getY() + 0.5);
        double dz = v.getZ() - (target.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private static boolean occupies(VillagerEntity v, BlockPos target, boolean tall) {
        AxisAlignedBB cell = tall ? new AxisAlignedBB(target).expandTowards(0, 1, 0) : new AxisAlignedBB(target);
        return v.getBoundingBox().intersects(cell);
    }

    /**
     * A standing cell within reach of {@code target} with a complete path from the villager. Nearest candidates are
     * probed first and only a handful of paths are computed.
     */
    private static BlockPos findStand(ServerWorld w, VillagerEntity v, BlockPos target, boolean tall) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -5; dy <= 1; dy++) {
            BlockPos c = target.offset(dx, dy, dz);
            if (c.equals(target) || c.above().equals(target) || (tall && c.equals(target.above()))) continue;
            if (!w.isLoaded(c) || !BlockInteractionAction.isStandable(w, c)) continue;
            double ex = dx, ey = c.getY() + 1.62 - (target.getY() + 0.5), ez = dz;
            if (ex * ex + ey * ey + ez * ez > (REACH - 0.5) * (REACH - 0.5)) continue;
            candidates.add(c.immutable());
        }
        BlockPos here = v.blockPosition();
        candidates.sort(Comparator.comparingDouble(c -> c.distSqr(here)));
        int probes = 0;
        for (BlockPos c : candidates) {
            if (probes++ >= 6) break;
            if (c.equals(here) || Double.isFinite(ReachablePaths.distance(v, c))) return c;
        }
        return null;
    }
}
