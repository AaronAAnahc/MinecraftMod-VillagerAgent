package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Deterministic, no-LLM knowledge propagation between villagers.
 *
 * <p>This is the "what I know, let me tell you" channel — distinct from the existing
 * {@code VillagerSocialSystem.propagateGossip}, which nudges <em>relationship opinions</em>
 * about a third party (LLM-social flavour). This class moves <em>resource knowledge</em>: a
 * villager who has personally seen e.g. iron ore in some chunk can, on a social encounter,
 * copy that single feature anchor into a neighbour's {@link ChunkMemory}.
 *
 * <p>Design rules (see {@code docs/world_event_bus_gossip_design.md} §四):
 * <ul>
 *   <li><b>One fact per encounter</b>, <b>unidirectional</b>, with probability
 *       {@link ModConfig#GOSSIP_CHANCE} — a symmetric exchange would diffuse too fast to debug.</li>
 *   <li><b>Only resource features</b> (farmland / crop / water / ores). Furniture and building
 *       anchors stay private; threat locations are out of v1 scope.</li>
 *   <li><b>Renewable vs depletable</b>: ores get a second-hand expiry ({@code oreExpiryTick}), so a
 *       borrowed "there is iron here" stops being re-told after a while and can be falsified on
 *       arrival. Water/farmland/crops decay through the chunk memory's existing saliency.</li>
 * </ul>
 *
 * <p>Propagation changes only <em>what the listener knows</em> — whether the listener acts on it
 * is left to the existing decision layer (e.g. the weaponsmith's "memory has ore → high craft
 * weight" behaviour pool).
 */
public final class GossipChannel {

    private static final Logger LOGGER = LogManager.getLogger();

    private GossipChannel() {}

    /**
     * Attempt a knowledge transfer from {@code teller} to {@code listener}. Called on a social
     * encounter (the two villagers are already known to be within conversation range). Transfers
     * at most one fact, and may do nothing.
     */
    public static void tryGossip(VillagerAgentData teller, VillagerAgentData listener, long gameTick) {
        if (teller == null || listener == null) return;
        if (Math.random() >= ModConfig.GOSSIP_CHANCE.get()) return;

        for (ChunkMemory cm : teller.getAllChunkMemories()) {
            for (ChunkFeature f : ChunkFeature.values()) {
                if (!isResourceFeature(f)) continue;
                if (!cm.has(f)) continue;

                BlockPos anchor = cm.anchorOf(f);
                if (anchor == null) continue;

                int cx = anchor.getX() >> 4;
                int cz = anchor.getZ() >> 4;
                ChunkMemory lm = listener.getOrCreateChunkMemory(cx, cz);
                if (lm.has(f)) continue; // the listener already knows this resource here

                // Copy the fact as second-hand: local coords + the teller's freshness as of visit.
                lm.recordFeature(f, anchor.getX() & 15, anchor.getY(), anchor.getZ() & 15);
                lm.lastVisitedTick = cm.lastVisitedTick;
                if (f.isOre()) {
                    lm.setOreExpiryTick(gameTick + ModConfig.GOSSIP_ORE_EXPIRY_TICKS.get());
                }

                listener.addMemory("Heard from " + teller.getName() + " about "
                        + f.display() + " near (" + anchor.getX() + ", " + anchor.getZ() + ")");
                LOGGER.info("[GOSSIP] {} -> {} : {} @ chunk ({},{})",
                        teller.getName(), listener.getName(), f.display(), cx, cz);
                return; // one fact per encounter
            }
        }
    }

    /** Resource-point features worth sharing; everything else stays private. */
    private static boolean isResourceFeature(ChunkFeature f) {
        if (f.isOre()) return true;
        switch (f) {
            case FARMLAND:
            case CROP:
            case WATER:
                return true;
            default:
                return false;
        }
    }
}
