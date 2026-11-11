package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.content.Workstations;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.merchant.villager.VillagerProfession;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * Keeps at least one farmer, lumberjack and miner in every loaded village.
 *
 * <p>A missing role goes to, in order: an unemployed resident, a novice holding a duplicated
 * non-core profession, any novice non-core resident, a novice duplicate of another core role.
 * Masters (level &gt; 1) are never re-trained. A free matching workstation inside the village is
 * reused; otherwise one is placed on open ground beside the notice board (or the village centre).
 * The POI ticket, JOB_SITE memory, profession and agent role are set together so neither vanilla
 * nor {@code FindJobSiteSkill} has to rediscover the site.
 */
public final class VillageJobGuarantee {
    private static final Logger LOGGER = LogManager.getLogger("VillageJobGuarantee");
    private static final long CHECK_INTERVAL = 600;
    private static final Map<Long, Long> NEXT_CHECK = new HashMap<>();

    private VillageJobGuarantee() {}

    public static final class Role {
        public final String name;
        public final VillagerProfession profession;
        public final BlockState workstation;
        Role(String name, VillagerProfession profession, BlockState workstation) {
            this.name = name; this.profession = profession; this.workstation = workstation;
        }
    }

    public static List<Role> coreRoles() {
        return Arrays.asList(
                new Role("Farmer", VillagerProfession.FARMER, Blocks.COMPOSTER.defaultBlockState()),
                new Role("Lumberjack", Workstations.LUMBERJACK.get(), Workstations.CARPENTER_TABLE.get().defaultBlockState()),
                new Role("Miner", Workstations.MINER.get(), Workstations.MINER_WORKBENCH.get().defaultBlockState()));
    }

    /** Server tick hook; each village is examined at most every 30 s. */
    public static void tick(ServerWorld world) {
        if (!ModConfig.ENSURE_CORE_JOBS.get()) return;
        long now = world.getGameTime();
        for (VillageMarkets.Market market : VillageMarkets.loaded(world)) {
            Long next = NEXT_CHECK.get(market.id);
            if (next != null && now < next) continue;
            NEXT_CHECK.put(market.id, now + CHECK_INTERVAL);
            ensure(world, market);
            shareWorkKnowledge(world, market, false);
        }
    }

    /** Fill every missing core role in {@code market}. Returns a human-readable line per action. */
    public static List<String> ensure(ServerWorld world, VillageMarkets.Market market) {
        List<String> report = new ArrayList<>();
        List<VillagerEntity> adults = new ArrayList<>();
        for (VillagerAgentData agent : market.members()) {
            if (agent == null || TradeDemo.isActive(agent.getVillagerId())) continue;
            Entity entity = world.getEntity(agent.getVillagerId());
            if (entity instanceof VillagerEntity && entity.isAlive() && !((VillagerEntity) entity).isBaby())
                adults.add((VillagerEntity) entity);
        }
        if (adults.isEmpty()) return report;
        adults.sort(Comparator.comparing(v -> v.getUUID().toString()));
        List<Role> roles = coreRoles();
        for (Role role : roles) {
            if (count(adults, role.profession) > 0) continue;
            VillagerEntity chosen = choose(adults, roles);
            if (chosen == null) {
                report.add("No eligible resident left for " + role.name);
                continue;
            }
            String previous = profName(chosen.getVillagerData().getProfession());
            if (assign(world, market, chosen, role)) {
                VillagerAgentData agent = VillagerAgentManager.getAgent(chosen.getUUID());
                report.add((agent != null ? agent.getName() : chosen.getUUID().toString()) + ": " + previous
                        + " -> " + role.name);
            } else {
                report.add("Could not find room for a " + role.name + " workstation");
            }
        }
        return report;
    }

    private static int count(List<VillagerEntity> adults, VillagerProfession profession) {
        int n = 0;
        for (VillagerEntity v : adults) if (v.getVillagerData().getProfession() == profession) n++;
        return n;
    }

    private static boolean isCore(VillagerProfession p, List<Role> roles) {
        for (Role r : roles) if (r.profession == p) return true;
        return false;
    }

    private static VillagerEntity choose(List<VillagerEntity> adults, List<Role> roles) {
        Comparator<VillagerEntity> novice = Comparator.comparingInt((VillagerEntity v) -> v.getVillagerData().getLevel())
                .thenComparingInt(VillagerEntity::getVillagerXp);
        // 1. Unemployed.
        for (VillagerEntity v : adults)
            if (v.getVillagerData().getProfession() == VillagerProfession.NONE) return v;
        // 2/3. Non-core novices, duplicated professions first.
        VillagerEntity best = null;
        boolean bestDuplicated = false;
        for (VillagerEntity v : adults) {
            VillagerProfession p = v.getVillagerData().getProfession();
            if (p == VillagerProfession.NITWIT || isCore(p, roles) || v.getVillagerData().getLevel() > 1) continue;
            boolean duplicated = count(adults, p) > 1;
            if (best == null || (duplicated && !bestDuplicated)
                    || (duplicated == bestDuplicated && novice.compare(v, best) < 0)) {
                best = v; bestDuplicated = duplicated;
            }
        }
        if (best != null) return best;
        // 4. A duplicated core role (e.g. a second farmer).
        for (VillagerEntity v : adults) {
            VillagerProfession p = v.getVillagerData().getProfession();
            if (isCore(p, roles) && count(adults, p) > 1 && v.getVillagerData().getLevel() <= 1
                    && (best == null || novice.compare(v, best) < 0)) best = v;
        }
        return best;
    }

    /** Assign {@code role} to {@code villager}: workstation, POI ticket, memory, profession, agent role. */
    public static boolean assign(ServerWorld world, VillageMarkets.Market market, VillagerEntity villager, Role role) {
        VillageRegistry registry = VillageRegistry.get(world);
        MutableBoundingBox bounds = registry.bounds(market.id);
        BlockPos board = registry.noticeBoard(market.id);
        BlockPos anchor = board != null ? board : market.center;
        PointOfInterestType poi = role.profession.getJobPoiType();
        Predicate<PointOfInterestType> matches = poi.getPredicate();

        BlockPos site = world.getPoiManager().findAllClosestFirst(matches,
                p -> world.isLoaded(p) && (bounds == null || bounds.isInside(p)), anchor, 96,
                PointOfInterestManager.Status.HAS_SPACE).findFirst().orElse(null);
        if (site == null) site = placeWorkstation(world, bounds, anchor, role.workstation);
        if (site == null) site = placeWorkstation(world, null, villager.blockPosition(), role.workstation);
        if (site == null) return false;

        VillagerAgentData agent = VillagerAgentManager.getAgent(villager.getUUID());
        if (agent != null) {
            ActionLifecycle.cancel(agent, villager, world);
            agent.setRunningGoal(null);
        }
        villager.releasePoi(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);

        // Block placement registers the POI on a deferred server task; register now (idempotent for the
        // same type) so the ticket can be taken in this tick.
        if (!world.getPoiManager().getType(site).isPresent()) world.getPoiManager().add(site, poi);
        final BlockPos target = site;
        world.getPoiManager().take(matches, target::equals, target, 1);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(world.dimension(), site));
        villager.setVillagerData(villager.getVillagerData().setProfession(role.profession));
        // Non-zero XP keeps vanilla's novice reset task from undoing the assignment if the vanilla brain runs.
        if (villager.getVillagerXp() <= 0) villager.setVillagerXp(1);

        if (agent != null) {
            agent.updateProfession(role.name);
            agent.getGoals().removeIf(g -> "find_job_site".equals(g.getGoalType()));
            agent.addMemory("The village needed a " + role.name.toLowerCase(Locale.ROOT)
                    + ", so I took the job at " + site.getX() + "," + site.getZ());
            BrainLog.diagnostic(agent, "core-job-assigned", "role=" + role.name + " site=" + site
                    + " village=" + market.id);
        }
        LOGGER.info("Village {} lacked a {}; assigned {} (workstation {})", market.id, role.name,
                villager.getUUID(), site);
        return true;
    }

    private static BlockPos placeWorkstation(ServerWorld world, MutableBoundingBox bounds, BlockPos anchor, BlockState block) {
        for (int radius = 3; radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos column = anchor.offset(dx, 0, dz);
                if (!world.hasChunk(column.getX() >> 4, column.getZ() >> 4)) continue;
                BlockPos base = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
                if (bounds != null && !bounds.isInside(base)) continue;
                if (!world.isEmptyBlock(base) || !world.isEmptyBlock(base.above())
                        || !world.getFluidState(base.below()).isEmpty()
                        || !world.getBlockState(base.below()).isFaceSturdy(world, base.below(), Direction.UP)) continue;
                // Flat, open ground: every neighbour (incl. diagonals) is free at foot and head height and
                // standable on all four sides, so stations never touch each other, the board or a wall and
                // a villager can always stand right next to one.
                if (!clearAround(world, base)) continue;
                if (!world.setBlock(base, block, 3)) continue;
                return base.immutable();
            }
        }
        return null;
    }

    private static boolean clearAround(ServerWorld world, BlockPos base) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos n = base.offset(dx, 0, dz);
            if (!world.isLoaded(n) || !world.isEmptyBlock(n) || !world.isEmptyBlock(n.above())) return false;
            if (PointOfInterestType.forState(world.getBlockState(n.below())).isPresent()) return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) if (!standable(world, base.relative(d))) return false;
        return true;
    }

    private static boolean standable(ServerWorld world, BlockPos pos) {
        return world.isLoaded(pos) && world.isEmptyBlock(pos) && world.isEmptyBlock(pos.above())
                && world.getBlockState(pos.below()).isFaceSturdy(world, pos.below(), Direction.UP);
    }

    public static String profName(VillagerProfession p) {
        if (p == null || p.getRegistryName() == null) return "none";
        String path = p.getRegistryName().getPath();
        return path.isEmpty() ? path : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }


    // ── Work knowledge: where the fields and the ore are ──────────────────

    private static final class Knowledge {
        long scannedAt = Long.MIN_VALUE;
        final List<BlockPos> farmland = new ArrayList<>();
        final Map<BlockPos, ChunkFeature> ores = new LinkedHashMap<>();
    }
    private static final Map<Long, Knowledge> KNOWLEDGE = new HashMap<>();
    private static final long RESCAN_TICKS = 6000;
    private static final int ORE_RADIUS = 64;

    private static ChunkFeature oreFeature(net.minecraft.block.Block b) {
        if (b == Blocks.COAL_ORE) return ChunkFeature.ORE_COAL;
        if (b == Blocks.IRON_ORE) return ChunkFeature.ORE_IRON;
        if (b == Blocks.LAPIS_ORE) return ChunkFeature.ORE_LAPIS;
        if (b == Blocks.REDSTONE_ORE) return ChunkFeature.ORE_REDSTONE;
        if (b == Blocks.GOLD_ORE) return ChunkFeature.ORE_GOLD;
        if (b == Blocks.DIAMOND_ORE) return ChunkFeature.ORE_DIAMOND;
        return null;
    }

    private static boolean exposed(ServerWorld world, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (world.isLoaded(n) && world.isEmptyBlock(n)) return true;
        }
        return false;
    }

    /** Surface scan of the village (farmland) and its surroundings (exposed ore). Loaded chunks only. */
    private static Knowledge scan(ServerWorld world, VillageMarkets.Market market, boolean force) {
        Knowledge k = KNOWLEDGE.computeIfAbsent(market.id, id -> new Knowledge());
        long now = world.getGameTime();
        if (!force && k.scannedAt != Long.MIN_VALUE && now - k.scannedAt < RESCAN_TICKS) return k;
        k.scannedAt = now;
        k.farmland.clear();
        k.ores.clear();
        MutableBoundingBox box = VillageRegistry.get(world).bounds(market.id);
        BlockPos c = market.center;
        int x0 = c.getX() - ORE_RADIUS, x1 = c.getX() + ORE_RADIUS, z0 = c.getZ() - ORE_RADIUS, z1 = c.getZ() + ORE_RADIUS;
        Set<Long> farmChunks = new HashSet<>();
        Map<Long, Integer> oreChunkRank = new HashMap<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!world.hasChunk(x >> 4, z >> 4)) continue;
            int top = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            boolean inVillage = box == null || (x >= box.x0 - 16 && x <= box.x1 + 16 && z >= box.z0 - 16 && z <= box.z1 + 16);
            for (int y = top + 2; y >= Math.max(1, top - 12); y--) {
                cursor.set(x, y, z);
                net.minecraft.block.Block b = world.getBlockState(cursor).getBlock();
                if (inVillage && b == Blocks.FARMLAND && y >= top - 3) {
                    long key = net.minecraft.util.math.ChunkPos.asLong(x >> 4, z >> 4);
                    if (farmChunks.add(key)) k.farmland.add(cursor.immutable());
                    continue;
                }
                ChunkFeature f = oreFeature(b);
                if (f == null || !exposed(world, cursor)) continue;
                long key = net.minecraft.util.math.ChunkPos.asLong(x >> 4, z >> 4) * 8 + f.ordinal();
                if (!oreChunkRank.containsKey(key)) { oreChunkRank.put(key, 1); k.ores.put(cursor.immutable(), f); }
            }
        }
        return k;
    }

    /**
     * Teach the village's farmers its fields and its miners the exposed ore around it, the way residents
     * would know their own village. Builds a small ore outcrop when nothing is exposed nearby.
     * @return report lines
     */
    public static List<String> shareWorkKnowledge(ServerWorld world, VillageMarkets.Market market, boolean force) {
        List<String> report = new ArrayList<>();
        boolean anyFarmer = false, anyMiner = false;
        for (VillagerAgentData a : market.members()) {
            if (a == null) continue;
            String p = String.valueOf(a.getProfession()).toLowerCase(Locale.ROOT);
            anyFarmer |= p.equals("farmer");
            anyMiner |= p.equals("miner");
        }
        if (!anyFarmer && !anyMiner) return report;
        Knowledge k = scan(world, market, force);
        if (anyMiner && k.ores.isEmpty() && ModConfig.ENSURE_CORE_JOBS.get()) {
            BlockPos outcrop = buildOutcrop(world, market);
            if (outcrop != null) {
                k.scannedAt = Long.MIN_VALUE;
                k = scan(world, market, true);
                report.add("No exposed ore near the village; built an ore outcrop at "
                        + outcrop.getX() + "," + outcrop.getY() + "," + outcrop.getZ());
            }
        }
        int farmers = 0, miners = 0;
        for (VillagerAgentData a : market.members()) {
            if (a == null) continue;
            String p = String.valueOf(a.getProfession()).toLowerCase(Locale.ROOT);
            if (p.equals("farmer")) {
                for (BlockPos f : k.farmland) teach(a, f, ChunkFeature.FARMLAND);
                if (!k.farmland.isEmpty()) farmers++;
            } else if (p.equals("miner")) {
                for (Map.Entry<BlockPos, ChunkFeature> e : k.ores.entrySet()) teach(a, e.getKey(), e.getValue());
                if (!k.ores.isEmpty()) miners++;
            }
        }
        report.add("Fields known: " + k.farmland.size() + " (taught to " + farmers + " farmer(s)); exposed ore spots: "
                + k.ores.size() + " (taught to " + miners + " miner(s))");
        return report;
    }

    private static void teach(VillagerAgentData agent, BlockPos p, ChunkFeature feature) {
        com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory memory =
                agent.getOrCreateChunkMemory(p.getX() >> 4, p.getZ() >> 4);
        if (!memory.has(feature)) memory.recordFeature(feature, p.getX() & 15, p.getY(), p.getZ() & 15);
    }

    /**
     * A 3 wide x 3 high x 2 deep stone outcrop with coal and iron on its open faces, on flat ground
     * 18-40 blocks from the village centre (outside houses, inside the villagers' path range).
     */
    public static BlockPos buildOutcrop(ServerWorld world, VillageMarkets.Market market) {
        BlockPos c = market.center;
        java.util.Random r = world.getRandom();
        for (int attempt = 0; attempt < 80; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2, dist = 18 + r.nextDouble() * 22;
            int x = c.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = c.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!world.hasChunk(x >> 4, z >> 4) || !world.hasChunk((x + 4) >> 4, (z + 4) >> 4)) continue;
            BlockPos base = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            boolean flat = true;
            for (int dx = -1; dx <= 3 && flat; dx++) for (int dz = -1; dz <= 2 && flat; dz++) {
                BlockPos g = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base.offset(dx, 0, dz));
                if (g.getY() != base.getY() || !world.getFluidState(g.below()).isEmpty()
                        || !world.getBlockState(g.below()).isFaceSturdy(world, g.below(), Direction.UP)
                        || PointOfInterestType.forState(world.getBlockState(g)).isPresent()
                        || !world.isEmptyBlock(g) || !world.isEmptyBlock(g.above(3))) flat = false;
            }
            if (!flat) continue;
            for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 2; dz++)
                world.setBlock(base.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
            // Ore on the front face (z = 0) and the sides; mined in place, so it lasts.
            world.setBlock(base.offset(0, 1, 0), Blocks.COAL_ORE.defaultBlockState(), 3);
            world.setBlock(base.offset(2, 0, 0), Blocks.COAL_ORE.defaultBlockState(), 3);
            world.setBlock(base.offset(1, 1, 0), Blocks.IRON_ORE.defaultBlockState(), 3);
            world.setBlock(base.offset(2, 1, 1), Blocks.IRON_ORE.defaultBlockState(), 3);
            world.setBlock(base.offset(0, 0, 1), Blocks.COAL_ORE.defaultBlockState(), 3);
            LOGGER.info("Built showcase ore outcrop for village {} at {}", market.id, base);
            return base.immutable();
        }
        return null;
    }

    /**
     * A 9x9 irrigated field (water in the centre hydrates all of it) of wheat, carrots and potatoes at mixed
     * growth stages, on flat ground 10-30 blocks from the village centre. Gives the farmer real work and the
     * village a food source. Returns the centre (water) position, or null if no spot was found.
     */
    public static BlockPos buildField(ServerWorld world, VillageMarkets.Market market) {
        BlockPos c = market.center;
        java.util.Random r = world.getRandom();
        net.minecraft.block.Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES};
        for (int attempt = 0; attempt < 120; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2, dist = 10 + r.nextDouble() * 20;
            int x = c.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = c.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!world.hasChunk((x - 5) >> 4, (z - 5) >> 4) || !world.hasChunk((x + 5) >> 4, (z + 5) >> 4)) continue;
            BlockPos center = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            boolean flat = true;
            for (int dx = -5; dx <= 5 && flat; dx++) for (int dz = -5; dz <= 5 && flat; dz++) {
                BlockPos g = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.offset(dx, 0, dz));
                net.minecraft.block.Block ground = world.getBlockState(g.below()).getBlock();
                if (g.getY() != center.getY() || !world.getFluidState(g.below()).isEmpty()
                        || !(ground == Blocks.GRASS_BLOCK || ground == Blocks.DIRT || ground == Blocks.COARSE_DIRT)
                        || PointOfInterestType.forState(world.getBlockState(g)).isPresent()
                        || !world.isEmptyBlock(g.above()) || !world.isEmptyBlock(g.above(2))) flat = false;
            }
            if (!flat) continue;
            BlockPos soilLevel = center.below();
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                BlockPos soil = soilLevel.offset(dx, 0, dz);
                world.setBlock(soil.above(), Blocks.AIR.defaultBlockState(), 3); // clear grass/flowers
                if (dx == 0 && dz == 0) { world.setBlock(soil, Blocks.WATER.defaultBlockState(), 3); continue; }
                world.setBlock(soil, Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.block.FarmlandBlock.MOISTURE, 7), 3);
                net.minecraft.block.CropsBlock crop = (net.minecraft.block.CropsBlock) crops[Math.floorMod(dx + 4, 9) / 3];
                // Two thirds ready to harvest now, the rest still growing.
                int age = r.nextInt(3) == 0 ? r.nextInt(crop.getMaxAge()) : crop.getMaxAge();
                world.setBlock(soil.above(), crop.getStateForAge(age), 3);
            }
            LOGGER.info("Built showcase field for village {} at {}", market.id, soilLevel);
            return soilLevel.immutable();
        }
        return null;
    }

    /** Force the next tick to re-check every village (used by the command). */
    public static void resetSchedule() { NEXT_CHECK.clear(); KNOWLEDGE.clear(); }
}
