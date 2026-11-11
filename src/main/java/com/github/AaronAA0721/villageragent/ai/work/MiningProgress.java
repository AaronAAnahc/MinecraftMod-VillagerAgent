package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Persistent intent, independent of an execution's paths, animation and quota. */
public final class MiningProgress {
    public enum Phase { SELECT, PRIMARY, CLEAR, STONE }
    public Phase phase = Phase.SELECT;
    public int chunkX, chunkZ;
    public String primary = "";
    public BlockPos lastMine;
    public BlockPos destination;
    public String anchorKey = "";
    private long day = -1;
    private int eligibility;
    private final Map<String, Long> retryAfter = new HashMap<>();

    public boolean inArea(BlockPos pos) {
        return Math.abs((pos.getX() >> 4) - chunkX) <= 1
                && Math.abs((pos.getZ() >> 4) - chunkZ) <= 1;
    }
    public boolean active() { return phase == Phase.PRIMARY || phase == Phase.CLEAR; }
    public void exhausted() {
        if (phase == Phase.PRIMARY) phase = Phase.CLEAR;
        else if (phase == Phase.CLEAR) phase = Phase.SELECT;
        else phase = Phase.STONE;
    }
    public void begin(BlockPos anchor, String item) {
        chunkX = anchor.getX() >> 4; chunkZ = anchor.getZ() >> 4;
        primary = item; lastMine = anchor.immutable(); phase = Phase.PRIMARY;
    }
    public boolean refresh(long now, int eligible) {
        boolean changed = day != now / 24000L || eligibility != eligible;
        if (changed) {
            day = now / 24000L; eligibility = eligible; retryAfter.clear();
            if (phase == Phase.STONE) phase = Phase.SELECT;
        }
        retryAfter.values().removeIf(t -> t <= now);
        return changed;
    }
    public boolean ready(String key, long now) { return retryAfter.getOrDefault(key, 0L) <= now; }
    public void defer(String key, long until) { retryAfter.put(key, until); }
    public CompoundNBT save() {
        CompoundNBT tag = new CompoundNBT();
        tag.putString("Phase", phase.name()); tag.putInt("X", chunkX); tag.putInt("Z", chunkZ);
        tag.putString("Primary", primary); tag.putLong("Day", day); tag.putInt("Eligibility", eligibility);
        if (lastMine != null) tag.putLong("LastMine", lastMine.asLong());
        if (destination != null) tag.putLong("Destination", destination.asLong());
        tag.putString("AnchorKey", anchorKey);
        ListNBT retries = new ListNBT();
        retryAfter.forEach((key, until) -> { CompoundNBT t = new CompoundNBT();
            t.putString("Key", key); t.putLong("Until", until); retries.add(t); });
        tag.put("Retries", retries); return tag;
    }
    public static MiningProgress load(CompoundNBT tag) {
        MiningProgress p = new MiningProgress();
        try { p.phase = Phase.valueOf(tag.getString("Phase")); } catch (IllegalArgumentException ignored) {}
        p.chunkX = tag.getInt("X"); p.chunkZ = tag.getInt("Z"); p.primary = tag.getString("Primary");
        p.day = tag.getLong("Day"); p.eligibility = tag.getInt("Eligibility");
        if (tag.contains("LastMine")) p.lastMine = BlockPos.of(tag.getLong("LastMine"));
        if (tag.contains("Destination")) p.destination = BlockPos.of(tag.getLong("Destination"));
        p.anchorKey = tag.getString("AnchorKey");
        ListNBT retries = tag.getList("Retries", 10);
        for (int i = 0; i < retries.size(); i++) {
            CompoundNBT t = retries.getCompound(i); p.defer(t.getString("Key"), t.getLong("Until"));
        }
        return p;
    }
    public static double coalChance(int level) { return Math.min(.03, .01 + Math.max(0, level) * .002); }
    public static double ironChance(int level) { return Math.min(.01, Math.max(0, level - 2) * .001); }
    /** 0 = none, 1 = coal, 2 = iron; one draw, disjoint intervals. */
    public static int bonus(int level, double draw) {
        double iron = ironChance(level);
        return draw < iron ? 2 : draw < iron + coalChance(level) ? 1 : 0;
    }
}
