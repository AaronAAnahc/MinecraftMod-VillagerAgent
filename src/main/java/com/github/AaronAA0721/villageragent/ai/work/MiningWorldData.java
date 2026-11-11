package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import java.util.*;

/** Per-dimension storage. Yields are personal; only short-lived occupancy is shared. */
public final class MiningWorldData extends WorldSavedData {
    private static final String NAME = "villageragent_mining";
    private long day = -1;
    private final Map<UUID, Set<Long>> mined = new HashMap<>();
    private final Map<UUID, MiningProgress> progress = new HashMap<>();
    private final Map<Long, Lease> leases = new HashMap<>();
    private static final class Lease {
        final UUID owner; long until;
        Lease(UUID owner, long until) { this.owner = owner; this.until = until; }
    }
    public MiningWorldData() { super(NAME); }
    public static MiningWorldData get(ServerWorld world) {
        MiningWorldData data = world.getDataStorage().computeIfAbsent(MiningWorldData::new, NAME);
        data.advance(world.getGameTime());
        data.leases.values().removeIf(l -> {
            net.minecraft.entity.Entity owner = world.getEntity(l.owner);
            return owner == null || !owner.isAlive();
        });
        return data;
    }
    public void advance(long now) {
        if (day != now / 24000L) { day = now / 24000L; mined.clear(); setDirty(); }
        leases.values().removeIf(l -> l.until <= now);
    }
    public MiningProgress progress(UUID owner) {
        return progress.computeIfAbsent(owner, ignored -> { setDirty(); return new MiningProgress(); });
    }
    public boolean mined(UUID owner, BlockPos pos) {
        return mined.getOrDefault(owner, Collections.emptySet()).contains(pos.asLong());
    }
    public boolean occupied(UUID owner, BlockPos pos, long now) {
        Lease lease = leases.get(pos.asLong());
        return lease != null && lease.until > now && !lease.owner.equals(owner);
    }
    public boolean claim(UUID owner, BlockPos pos, long now) {
        if (mined(owner, pos) || occupied(owner, pos, now)) return false;
        leases.put(pos.asLong(), new Lease(owner, now + 100)); return true;
    }
    /** Called on the server thread immediately before the one physical yield. */
    public boolean complete(UUID owner, BlockPos pos, long now) {
        advance(now);
        if (!claim(owner, pos, now)) return false;
        mined.computeIfAbsent(owner, ignored -> new HashSet<>()).add(pos.asLong());
        leases.remove(pos.asLong()); setDirty(); return true;
    }
    public void release(UUID owner) { leases.values().removeIf(l -> l.owner.equals(owner)); }
    public void forget(UUID owner) { release(owner); mined.remove(owner); progress.remove(owner); setDirty(); }
    public void releaseChunk(int x, int z, ServerWorld world) {
        leases.entrySet().removeIf(e -> {
            BlockPos target = BlockPos.of(e.getKey());
            net.minecraft.entity.Entity owner = world.getEntity(e.getValue().owner);
            BlockPos at = owner == null ? target : owner.blockPosition();
            return (target.getX() >> 4) == x && (target.getZ() >> 4) == z
                    || (at.getX() >> 4) == x && (at.getZ() >> 4) == z;
        });
    }
    @Override public void load(CompoundNBT tag) {
        mined.clear(); progress.clear(); leases.clear(); day = tag.getLong("Day");
        ListNBT people = tag.getList("Miners", 10);
        for (int i = 0; i < people.size(); i++) {
            CompoundNBT t = people.getCompound(i); if (!t.hasUUID("Id")) continue;
            UUID id = t.getUUID("Id"); Set<Long> positions = new HashSet<>();
            for (long pos : t.getLongArray("Mined")) positions.add(pos);
            if (!positions.isEmpty()) mined.put(id, positions);
            if (t.contains("Progress")) progress.put(id, MiningProgress.load(t.getCompound("Progress")));
        }
    }
    @Override public CompoundNBT save(CompoundNBT tag) {
        tag.putLong("Day", day); ListNBT people = new ListNBT();
        Set<UUID> owners = new HashSet<>(mined.keySet()); owners.addAll(progress.keySet());
        for (UUID id : owners) {
            CompoundNBT t = new CompoundNBT(); t.putUUID("Id", id);
            Set<Long> positions = mined.getOrDefault(id, Collections.emptySet());
            t.putLongArray("Mined", positions.stream().mapToLong(Long::longValue).toArray());
            if (progress.containsKey(id)) t.put("Progress", progress.get(id).save());
            people.add(t);
        }
        tag.put("Miners", people); return tag;
    }
}
