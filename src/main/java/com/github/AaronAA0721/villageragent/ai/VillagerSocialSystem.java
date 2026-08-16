package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Manages villager-to-villager social interactions.
 *
 * <p>Every {@link #SCAN_INTERVAL} ticks (≈10 seconds), idle villagers scan for
 * nearby partners.  If two villagers are within {@link #CONVERSE_RANGE_SQ} blocks
 * and both are free, an LLM-generated dialogue is queued and broadcast to
 * nearby players.  A per-villager cooldown prevents constant chattering.
 *
 * <p>Socialising is gated by the villager's {@code scheduledActivity}: a villager
 * only initiates a chat when their plan says "socializing" (or when they have no plan).
 */
public class VillagerSocialSystem {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Squared block range within which two villagers can converse (~5 blocks). */
    private static final double CONVERSE_RANGE_SQ  = 25.0;
    /** Squared block range within which players receive the broadcast (~20 blocks). */
    private static final double BROADCAST_RANGE_SQ = 400.0;
    /** Ticks between social scans (200 ticks ≈ 10 seconds). */
    private static final int    SCAN_INTERVAL       = 200;
    /** Minimum ticks between two conversations for the same villager (~5 min). */
    private static final long   SOCIAL_COOLDOWN     = 6_000L;

    // ── Main tick entry point ─────────────────────────────────────────────────

    public static void tickSocial(ServerWorld world) {
        long currentTick = world.getGameTime();
        if (currentTick % SCAN_INTERVAL != 0) return;

        // No socialising at night (dayTime 13000-23000)
        long dayTime = world.getDayTime() % 24_000L;
        if (dayTime > 13_000 && dayTime < 23_000) return;

        Collection<VillagerAgentData> allAgents = VillagerAgentManager.getAllAgents();
        Set<UUID> paired = new HashSet<>();

        for (VillagerAgentData agentA : allAgents) {
            if (paired.contains(agentA.getVillagerId())) continue;
            if (!canSocialize(agentA, currentTick)) continue;

            VillagerEntity villagerA = findVillager(world, agentA.getVillagerId());
            if (villagerA == null) continue;

            VillagerAgentData agentB = findPartner(world, villagerA, agentA, paired, currentTick);
            if (agentB == null) continue;

            VillagerEntity villagerB = findVillager(world, agentB.getVillagerId());
            if (villagerB == null) continue;

            paired.add(agentA.getVillagerId());
            paired.add(agentB.getVillagerId());
            startConversation(world, villagerA, agentA, villagerB, agentB, currentTick);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean canSocialize(VillagerAgentData agent, long tick) {
        if (agent.isSocializing()) return false;
        if (agent.getCurrentAction() != null) return false;
        if (tick - agent.getLastSocialTick() < SOCIAL_COOLDOWN) return false;
        // Only if schedule is empty or says "socializing"
        String sched = agent.getScheduledActivity();
        return sched == null || sched.isEmpty() || "socializing".equals(sched);
    }

    private static VillagerEntity findVillager(ServerWorld world, UUID id) {
        Entity e = world.getEntity(id);
        return e instanceof VillagerEntity ? (VillagerEntity) e : null;
    }

    private static VillagerAgentData findPartner(ServerWorld world, VillagerEntity villagerA,
                                                  VillagerAgentData agentA, Set<UUID> paired, long tick) {
        for (VillagerAgentData agentB : VillagerAgentManager.getAllAgents()) {
            if (agentB.getVillagerId().equals(agentA.getVillagerId())) continue;
            if (paired.contains(agentB.getVillagerId())) continue;
            if (!canSocialize(agentB, tick)) continue;
            VillagerEntity vB = findVillager(world, agentB.getVillagerId());
            if (vB != null && villagerA.distanceToSqr(vB) <= CONVERSE_RANGE_SQ) return agentB;
        }
        return null;
    }

    // ── Conversation logic ───────────────────────────────────────────────────

    private static void startConversation(ServerWorld world,
                                           VillagerEntity villagerA, VillagerAgentData agentA,
                                           VillagerEntity villagerB, VillagerAgentData agentB,
                                           long currentTick) {
        agentA.setSocializing(true);
        agentB.setSocializing(true);
        agentA.setLastSocialTick(currentTick);
        agentB.setLastSocialTick(currentTick);

        String env  = agentA.getEnvironmentSummary();
        String memA = recentMemories(agentA, 2);
        String memB = recentMemories(agentB, 2);

        // ── Relationship tone ──
        int relAtoB = agentA.getRelationships().getOrDefault(agentB.getVillagerId().toString(), 0);
        int relBtoA = agentB.getRelationships().getOrDefault(agentA.getVillagerId().toString(), 0);
        int rel     = (relAtoB + relBtoA) / 2; // average mutual score
        String relTone;
        if (rel >= 60) {
            relTone = "They are close friends — warm, familiar, and happy to see each other.";
        } else if (rel >= 20) {
            relTone = "They are friendly acquaintances — polite and pleasant.";
        } else if (rel >= -20) {
            relTone = "They barely know each other — somewhat neutral and slightly awkward.";
        } else if (rel >= -60) {
            relTone = "They have had disagreements before — a bit cold and guarded.";
        } else {
            relTone = "They strongly dislike each other — tense, curt, and barely civil.";
        }

        String sysPrompt  = "Generate a short, natural conversation between two Minecraft villagers. "
                + "Format EACH line as 'Name: message'. Write exactly 4 exchanges (2 per villager). "
                + "Keep it in-character and brief. No stage directions or extra text.";
        String moodA = VillagerNeedsSystem.buildNeedsDescription(agentA);
        String moodB = VillagerNeedsSystem.buildNeedsDescription(agentB);

        String userPrompt = "Villager 1: " + agentA.getName() + " (" + agentA.getProfession()
                + "), personality: " + agentA.getPersonality()
                + (moodA.isEmpty() ? "" : ", feeling: " + moodA) + "\n"
                + "Villager 2: " + agentB.getName() + " (" + agentB.getProfession()
                + "), personality: " + agentB.getPersonality()
                + (moodB.isEmpty() ? "" : ", feeling: " + moodB) + "\n"
                + "Relationship: " + relTone + " (score " + rel + "/100)\n"
                + (env != null ? "Setting: " + env + "\n" : "")
                + agentA.getName() + "'s recent thoughts: " + memA + "\n"
                + agentB.getName() + "'s recent thoughts: " + memB + "\n"
                + "Let their mood and relationship colour the conversation. Generate a 4-line conversation:";

        LLMGuard.query(agentA.getVillagerId(), world.getGameTime(), sysPrompt, userPrompt).thenAccept(response -> {
            DecisionJournal.record(agentA.getVillagerId(), DecisionJournal.Kind.SOCIAL,
                    world.getGameTime(), userPrompt, response, DecisionJournal.outcome(response));
            if (response == null || response.trim().isEmpty() || LLMService.isFailure(response)) {
                agentA.setSocializing(false); agentB.setSocializing(false); return;
            }
            world.getServer().execute(() ->
                    broadcastDialogue(world, villagerA, agentA, villagerB, agentB, response));
        }).exceptionally(e -> {
            LOGGER.debug("Social conversation failed: " + e.getMessage());
            agentA.setSocializing(false); agentB.setSocializing(false);
            return null;
        });
    }

    private static void broadcastDialogue(ServerWorld world,
                                           VillagerEntity villagerA, VillagerAgentData agentA,
                                           VillagerEntity villagerB, VillagerAgentData agentB,
                                           String response) {
        List<String> lines = new ArrayList<>();
        for (String raw : response.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && line.contains(":")) lines.add(line);
        }

        List<ServerPlayerEntity> players = world.getServer().getPlayerList().getPlayers();
        StringBuilder convA = new StringBuilder("Talked with " + agentB.getName() + ": ");
        StringBuilder convB = new StringBuilder("Talked with " + agentA.getName() + ": ");

        for (String line : lines) {
            StringTextComponent msg = new StringTextComponent("§e[" + line.substring(0, line.indexOf(":")) + "]:§f "
                    + line.substring(line.indexOf(":") + 1).trim());
            for (ServerPlayerEntity player : players) {
                if (player.distanceToSqr(villagerA) <= BROADCAST_RANGE_SQ
                        || player.distanceToSqr(villagerB) <= BROADCAST_RANGE_SQ) {
                    player.sendMessage(msg, Util.NIL_UUID);
                }
            }
            convA.append(line).append(" | ");
            convB.append(line).append(" | ");
        }

        if (!lines.isEmpty()) {
            agentA.addMemory(convA.toString());
            agentB.addMemory(convB.toString());
            // Relationship gain scales with existing score: friends gain less (already close),
            // rivals gain a small amount (every interaction nudges toward neutral)
            int relAtoB = agentA.getRelationships().getOrDefault(agentB.getVillagerId().toString(), 0);
            int relGain = (relAtoB >= 60) ? 2 : (relAtoB >= 0) ? 4 : 1;
            agentA.updateRelationship(agentB.getVillagerId().toString(), relGain);
            agentB.updateRelationship(agentA.getVillagerId().toString(), relGain);
            LOGGER.info("{} and {} had a conversation (rel delta +{})", agentA.getName(), agentB.getName(), relGain);

            // ── Gossip propagation (30% chance) ───────────────────────────────
            // One of the two speakers mentions a third villager they both know,
            // nudging the listener's opinion of that third party.
            if (Math.random() < 0.30) {
                propagateGossip(agentA, agentB, world);
            }
        }

        agentA.setSocializing(false);
        agentB.setSocializing(false);
    }

    /**
     * Simple gossip: the gossiper picks a third-party villager they have an opinion about.
     * The listener's relationship with that third party is nudged by ±2 in the gossiper's
     * direction (positive gossip → +2, negative gossip → -2, neutral → no change).
     *
     * <p>Both parties receive a memory entry describing the gossip.
     */
    private static void propagateGossip(VillagerAgentData gossiper, VillagerAgentData listener,
                                         ServerWorld world) {
        Map<String, Integer> rels = gossiper.getRelationships();
        // Exclude listener from candidates
        String listenerKey = listener.getVillagerId().toString();
        String gossiperKey = gossiper.getVillagerId().toString();

        // Find a third-party villager the gossiper has an opinion on
        VillagerAgentData target = null;
        int targetRel = 0;
        for (Map.Entry<String, Integer> entry : rels.entrySet()) {
            if (entry.getKey().equals(listenerKey)) continue;
            // Pick first non-neutral entry (|score| > 10) to make gossip meaningful
            if (Math.abs(entry.getValue()) > 10) {
                VillagerAgentData candidate = VillagerAgentManager.getAgent(
                        UUID.fromString(entry.getKey()));
                if (candidate != null) { target = candidate; targetRel = entry.getValue(); break; }
            }
        }
        if (target == null) return;

        int nudge = (targetRel > 0) ? 2 : -2;
        listener.updateRelationship(target.getVillagerId().toString(), nudge);

        String sentiment = (targetRel > 0) ? "spoke well of" : "spoke poorly of";
        String memGossiper = "Told " + listener.getName() + " about " + target.getName() + ".";
        String memListener = gossiper.getName() + " " + sentiment + " " + target.getName()
                + " (my opinion of them shifted by " + nudge + ").";
        gossiper.addMemory(memGossiper);
        listener.addMemory(memListener);
        LOGGER.debug("{} gossiped about {} to {} (nudge {})", gossiper.getName(), target.getName(),
                listener.getName(), nudge);
    }

    private static String recentMemories(VillagerAgentData agent, int count) {
        List<String> memories = agent.getMemories();
        if (memories.isEmpty()) return "nothing in particular";
        int start = Math.max(0, memories.size() - count);
        StringJoiner sj = new StringJoiner("; ");
        for (int i = start; i < memories.size(); i++) sj.add(memories.get(i));
        return sj.toString();
    }
}

