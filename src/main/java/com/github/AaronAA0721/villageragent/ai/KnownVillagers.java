package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The villagers one villager "knows": the loaded neighbours in its world, nearest first. Used to
 * show the LLM who exists (name, profession, id) and to resolve the names the LLM writes back
 * into UUIDs. Server thread only.
 */
public final class KnownVillagers {
    public static final int PROMPT_LIMIT = 12;
    private static final double RANGE_SQ = 128.0 * 128.0;

    private KnownVillagers() {}

    public static final class Entry {
        public final VillagerAgentData agent;
        public final double distance;
        Entry(VillagerAgentData agent, double distance) { this.agent = agent; this.distance = distance; }
    }

    /** Neighbours of {@code self} within range in {@code world}, nearest first. */
    public static List<Entry> near(VillagerAgentData self, VillagerEntity selfEntity, ServerWorld world) {
        List<Entry> out = new ArrayList<>();
        if (world == null) return out;
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (other == self || other.getVillagerId().equals(self.getVillagerId())) continue;
            Entity e = world.getEntity(other.getVillagerId());
            if (!(e instanceof VillagerEntity) || !e.isAlive()) continue;
            double d2 = selfEntity == null ? 0 : selfEntity.distanceToSqr(e);
            if (d2 > RANGE_SQ) continue;
            out.add(new Entry(other, Math.sqrt(d2)));
        }
        out.sort(Comparator.comparingDouble(en -> en.distance));
        return out;
    }

    /** Prompt block listing known villagers, or "" when there are none. */
    public static String promptBlock(VillagerAgentData self, VillagerEntity selfEntity, ServerWorld world) {
        List<Entry> known = near(self, selfEntity, world);
        if (known.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Villagers you know (refer to them by name in goals; friendship -100..100): ");
        int n = Math.min(PROMPT_LIMIT, known.size());
        for (int i = 0; i < n; i++) {
            Entry e = known.get(i);
            if (i > 0) sb.append("; ");
            int friendship = self.getRelationships().getOrDefault(e.agent.getVillagerId().toString(), 0);
            sb.append(e.agent.getName()).append(" (").append(e.agent.getProfession())
              .append(", ").append(Math.round(e.distance)).append(" blocks away, friendship ").append(friendship)
              .append(", id ").append(e.agent.getVillagerId()).append(")");
        }
        return sb.append(". ").toString();
    }

    /**
     * Resolve a name ("Bob Smith", "bob", case-insensitive) or a UUID string to a villager other
     * than {@code self}. Full-name match wins over first-name match; ties go to the nearest.
     * Returns null when nothing matches or a first name is ambiguous at equal distance.
     */
    public static UUID resolve(String nameOrId, VillagerAgentData self, VillagerEntity selfEntity, ServerWorld world) {
        if (nameOrId == null) return null;
        String key = nameOrId.trim().replace("\"", "");
        if (key.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(key);
            VillagerAgentData a = VillagerAgentManager.getAgent(id);
            return a != null && !id.equals(self.getVillagerId()) ? id : null;
        } catch (IllegalArgumentException notUuid) { /* fall through to names */ }
        String k = key.toLowerCase(Locale.ROOT);
        VillagerAgentData best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (other.getVillagerId().equals(self.getVillagerId()) || other.getName() == null) continue;
            String name = other.getName().toLowerCase(Locale.ROOT);
            int rank = name.equals(k) ? 0 : name.startsWith(k + " ") ? 1 : -1;
            if (rank < 0) continue;
            double d = distance(other, selfEntity, world);
            if (rank < bestRank || (rank == bestRank && d < bestDist)) { best = other; bestRank = rank; bestDist = d; }
        }
        return best == null ? null : best.getVillagerId();
    }

    private static double distance(VillagerAgentData other, VillagerEntity selfEntity, ServerWorld world) {
        if (world == null || selfEntity == null) return Double.MAX_VALUE / 2;
        Entity e = world.getEntity(other.getVillagerId());
        return e == null ? Double.MAX_VALUE / 2 : selfEntity.distanceToSqr(e);
    }

    /** Display name for a villager id (falls back to a short id). */
    public static String nameOf(UUID id) {
        VillagerAgentData a = id == null ? null : VillagerAgentManager.getAgent(id);
        return a != null ? a.getName() : (id == null ? "?" : id.toString().substring(0, 8));
    }
}
