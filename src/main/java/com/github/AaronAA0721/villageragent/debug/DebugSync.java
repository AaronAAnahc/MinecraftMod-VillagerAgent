package com.github.AaronAA0721.villageragent.debug;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerVisionSystem;
import com.github.AaronAA0721.villageragent.ai.memory.BlockObservation;
import com.github.AaronAA0721.villageragent.ai.memory.EntityObservation;
import com.github.AaronAA0721.villageragent.ai.vision.DetailedViewRecorder;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.network.DebugDataPacket;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.PacketDistributor;

import java.util.List;

/**
 * Server-side producer of the debug overlay snapshot.
 *
 * <p>Runs every few ticks (gated by {@link ModConfig#ENABLE_DEBUG_OVERLAY}) and,
 * for each player, builds a {@link DebugDataPacket} from the live world state:
 * the villager the player is looking at (with a fresh environment summary +
 * live vision scan), and the buildings the {@link WorldStructureIndex} has
 * detected near the player.
 */
public final class DebugSync {

    /** Send a snapshot to every player roughly twice per second. */
    private static final int INTERVAL = 10; // ticks

    private DebugSync() {}

    public static void tick(ServerWorld world) {
        if (!ModConfig.ENABLE_DEBUG_OVERLAY.get()) return;
        if (world.getGameTime() % INTERVAL != 0) return;

        int range = ModConfig.DEBUG_RENDER_RANGE.get();
        for (ServerPlayerEntity player : world.getServer().getPlayerList().getPlayers()) {
            if (player.level != world) continue;
            DebugDataPacket pkt = build(world, player, range);
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pkt);
        }
    }

    private static DebugDataPacket build(ServerWorld world, ServerPlayerEntity player, int range) {
        DebugDataPacket pkt = new DebugDataPacket();
        pkt.agentCount = VillagerAgentManager.getAgentCount();

        BlockPos playerPos = player.blockPosition();

        // ── Buildings near the player (from the shared, persisted index) ──
        int rChunks = (range + 15) / 16;
        List<BuildingRecord> recs = WorldStructureIndex.instance(world).queryNear(playerPos, rChunks);
        for (BuildingRecord r : recs) {
            BlockPos center = new BlockPos(
                    (r.boundsMin.getX() + r.boundsMax.getX()) / 2,
                    (r.boundsMin.getY() + r.boundsMax.getY()) / 2,
                    (r.boundsMin.getZ() + r.boundsMax.getZ()) / 2);
            if (center.distSqr(playerPos) <= (long) range * range) {
                pkt.buildings.add(new DebugDataPacket.BuildingBox(
                        r.id, r.boundsMin, r.boundsMax, r.coarseType));
                for (BuildingRecord.DebugSeed s : r.debugSeeds) {
                    pkt.seeds.add(new DebugDataPacket.SeedPoint(s.pos, s.interior));
                }
                for (BuildingRecord.DebugFieldCell fc : r.debugField) {
                    int flags = (fc.atmospheric ? 1 : 0) | (fc.boundary ? 2 : 0) | ((fc.setId & 0xFFFFFF) << 8);
                    pkt.field.add(new DebugDataPacket.FieldCell(fc.wx, fc.wy, fc.wz, flags));
                }
            }
        }
        pkt.nearbyBuildings = pkt.buildings.size();

        // ── The villager the player is looking at ──
        VillagerEntity target = findLookedAtVillager(world, player, 6.0);
        if (target != null) {
            VillagerAgentData agent = VillagerAgentManager.getAgent(target.getUUID());
            if (agent != null) buildTarget(pkt, world, target, agent);
        }

        return pkt;
    }

    private static void buildTarget(DebugDataPacket pkt, ServerWorld world,
                                    VillagerEntity villager, VillagerAgentData agent) {
        pkt.hasTarget = true;
        pkt.targetName = agent.getName();
        pkt.targetProfession = agent.getProfession();
        pkt.targetPersonality = agent.getPersonality();
        pkt.targetMood = agent.getMood().name().toLowerCase();
        pkt.targetActivity = agent.getCurrentActivity() != null ? agent.getCurrentActivity() : "idle";
        pkt.targetScheduled = agent.getScheduledActivity() != null ? agent.getScheduledActivity() : "-";
        pkt.targetAction = agent.getCurrentAction() != null ? agent.getCurrentAction().getDescription() : "-";
        pkt.hunger = agent.getHunger();
        pkt.fatigue = agent.getFatigue();

        // Goals (cap to 8 for the packet)
        pkt.goalsCount = agent.getGoals().size();
        int g = 0;
        for (com.github.AaronAA0721.villageragent.ai.AgentGoal goal : agent.getGoals()) {
            if (g++ >= 8) break;
            pkt.goals.add(goal.getGoalType() + ": " + goal.getDescription() + " (prio " + goal.getPriority() + ")");
        }

        // Memories (last 10, truncated)
        pkt.memoriesCount = agent.getMemories().size();
        int m = 0;
        for (int i = Math.max(0, agent.getMemories().size() - 10); i < agent.getMemories().size(); i++) {
            if (m++ >= 10) break;
            pkt.memories.add(trunc(agent.getMemories().get(i), 120));
        }

        // Chunk memory summary (unique tags + count)
        pkt.chunkMemoryCount = agent.getAllChunkMemories().size();
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        for (com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm : agent.getAllChunkMemories()) {
            for (String t : cm.tagLabels()) tags.add(t);
        }
        int ti = 0;
        for (String t : tags) { if (ti++ >= 16) break; pkt.chunkTags.add(t); }

        // Live environment summary (this is what the LLM actually sees)
        String env = VillagerVisionSystem.buildEnvironmentSummary(villager, world, agent);
        pkt.targetEnv = env != null ? trunc(env, 600) : "";

        // Live frustum scan (DetailedViewRecorder)
        DetailedViewRecorder.ViewSnapshot snap = DetailedViewRecorder.record(villager, world);
        pkt.frustumBlocks = snap.blocks.size();
        pkt.frustumEntities = snap.entities.size();
        int s = 0;
        for (BlockObservation bo : snap.blocks) {
            if (s++ >= 6) break;
            String n = bo.blockId.replace("minecraft:", "").replace('_', ' ');
            pkt.frustumSamples.add(n + (bo.note != null && !bo.note.isEmpty() ? " (" + bo.note + ")" : ""));
        }
        // entity counts
        int hostiles = 0, animals = 0, villagers = 0, players = 0, items = 0;
        for (EntityObservation eo : snap.entities) {
            switch (eo.category) {
                case HOSTILE:  hostiles++;  break;
                case ANIMAL:   animals++;   break;
                case VILLAGER: villagers++; break;
                case PLAYER:   players++;   break;
                case ITEM:     items++;     break;
                default: break;
            }
        }
        if (hostiles > 0)   pkt.frustumSamples.add("hostile x" + hostiles);
        if (animals > 0)    pkt.frustumSamples.add("animal x" + animals);
        if (villagers > 0)  pkt.frustumSamples.add("villager x" + villagers);
        if (players > 0)    pkt.frustumSamples.add("player x" + players);
        if (items > 0)      pkt.frustumSamples.add("item x" + items);
    }

    /** Ray-ish test: nearest villager whose body is closest to the player's look ray. */
    private static VillagerEntity findLookedAtVillager(ServerWorld world, ServerPlayerEntity player, double maxDist) {
        Vector3d eye = player.position().add(0, player.getEyeHeight(), 0);
        Vector3d look = player.getLookAngle();
        AxisAlignedBB box = new AxisAlignedBB(player.blockPosition()).inflate(maxDist);

        VillagerEntity best = null;
        double bestT = Double.MAX_VALUE;
        for (VillagerEntity v : world.getEntitiesOfClass(VillagerEntity.class, box)) {
            Vector3d to = v.position().add(0, v.getBbHeight() * 0.5, 0).subtract(eye);
            double t = to.dot(look);
            if (t < 0 || t > maxDist) continue;
            Vector3d perp = to.subtract(look.scale(t));
            if (perp.length() > 1.5) continue;
            if (t < bestT) { bestT = t; best = v; }
        }
        return best;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
