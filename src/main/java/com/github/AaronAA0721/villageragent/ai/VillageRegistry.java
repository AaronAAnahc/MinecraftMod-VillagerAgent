package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.vision.ChunkContentSampler;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.nbt.*;
import net.minecraft.util.math.*;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraft.world.gen.feature.structure.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Persistent vanilla structure bounds, resident affiliation, well positions,
 * and shared chunk memories — scoped to one dimension.
 *
 * Well positions and shared chunk memories are village-level data that all
 * residents inherit, avoiding per-villager duplication.
 */
public final class VillageRegistry extends WorldSavedData {

    private static final Logger LOG = LogManager.getLogger("VillageRegistry");

    private final Map<Long, MutableBoundingBox> villages = new LinkedHashMap<>();
    private final Map<UUID, Long> residents = new HashMap<>();

    /** Mining well entrance position per village (village key → well entry pos). */
    private final Map<Long, BlockPos> noticeBoards = new HashMap<>();

    private final Map<Long, BlockPos> wellPositions = new HashMap<>();

    /**
     * Shared chunk memories per village. Seeded once when the village is first discovered;
     * covers the village AABB plus one chunk ring. New villagers copy these into their
     * personal memory as initial knowledge; personal memories diverge independently afterward.
     */
    private final Map<Long, List<ChunkMemory>> sharedChunkMemories = new HashMap<>();

    public VillageRegistry() { super("villageragent_villages"); }

    public static VillageRegistry get(ServerWorld world) {
        return world.getDataStorage().computeIfAbsent(VillageRegistry::new, "villageragent_villages");
    }

    // ── Village discovery ────────────────────────────────────────────────

    public Long discover(ServerWorld world, BlockPos pos) {
        for (Map.Entry<Long, MutableBoundingBox> e : villages.entrySet())
            if (e.getValue().isInside(pos)) return e.getKey();
        if (!world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        net.minecraft.world.chunk.Chunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        Set<Long> origins = new HashSet<>(chunk.getReferencesForFeature(Structure.VILLAGE));
        origins.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        for (long origin : origins) {
            net.minecraft.world.chunk.Chunk startChunk = world.getChunkSource().getChunkNow(ChunkPos.getX(origin), ChunkPos.getZ(origin));
            if (startChunk == null) continue;
            StructureStart<?> start = startChunk.getStartForFeature(Structure.VILLAGE);
            if (start == null || !start.isValid()) continue;
            long key = ChunkPos.asLong(start.getChunkX(), start.getChunkZ());
            if (!villages.containsKey(key)) {
                villages.put(key, start.getBoundingBox());
                setDirty();
                // Seed shared chunk memories for the newly discovered village.
                seedSharedMemories(world, key, start.getBoundingBox());
            }
        }
        for (Map.Entry<Long, MutableBoundingBox> e : villages.entrySet())
            if (e.getValue().isInside(pos)) return e.getKey();
        return null;
    }

    /** Unknown referenced structure bounds are protected until their start is observed. */
    public boolean contains(ServerWorld world, BlockPos pos) {
        if (discover(world, pos) != null) return true;
        net.minecraft.world.chunk.Chunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return true;
        for (long origin : chunk.getReferencesForFeature(Structure.VILLAGE))
            if (!villages.containsKey(origin)) return true;
        return false;
    }

    public boolean intersectsChunk(int x, int z) {
        for (MutableBoundingBox box : villages.values())
            if (box.x0 <= x * 16 + 15 && box.x1 >= x * 16 && box.z0 <= z * 16 + 15 && box.z1 >= z * 16) return true;
        return false;
    }

    public Long join(ServerWorld world, UUID resident, BlockPos pos) {
        Long here = discover(world, pos), old = residents.get(resident);
        if (old != null && villages.containsKey(old)) return old;
        if (here == null) {
            double nearest = Double.POSITIVE_INFINITY;
            for (Map.Entry<Long, MutableBoundingBox> e : villages.entrySet()) {
                double distance = e.getValue().getCenter().distSqr(pos);
                if (distance < nearest) { nearest = distance; here = e.getKey(); }
            }
        }
        if (here != null) { residents.put(resident, here); setDirty(); }
        return here;
    }

    public BlockPos noticeBoard(long key) { return noticeBoards.get(key); }
    public void setNoticeBoard(long key, BlockPos pos) { noticeBoards.put(key, pos.immutable()); setDirty(); }
    public void clearNoticeBoard(long key) { if (noticeBoards.remove(key) != null) setDirty(); }
    /** Village whose notice board is at {@code pos}, or null. */
    public Long villageWithBoard(BlockPos pos) {
        for (Map.Entry<Long, BlockPos> e : noticeBoards.entrySet()) if (e.getValue().equals(pos)) return e.getKey();
        return null;
    }
    /** Village containing {@code pos}, else the nearest village whose bounds grown by {@code margin} contain it. */
    public Long villageNear(ServerWorld world, BlockPos pos, int margin) {
        Long inside = discover(world, pos);
        if (inside != null) return inside;
        Long best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<Long, MutableBoundingBox> e : villages.entrySet()) {
            MutableBoundingBox b = e.getValue();
            if (pos.getX() < b.x0 - margin || pos.getX() > b.x1 + margin || pos.getZ() < b.z0 - margin || pos.getZ() > b.z1 + margin) continue;
            double d = b.getCenter().distSqr(pos);
            if (d < bestD) { bestD = d; best = e.getKey(); }
        }
        return best;
    }

    public BlockPos center(long key) { return new BlockPos(villages.get(key).getCenter()); }

    public MutableBoundingBox bounds(long key) { return villages.get(key); }

    // ── Mining well positions ────────────────────────────────────────────

    /** Record the mining well entrance position for a village. */
    public void setWellPos(long villageKey, BlockPos pos) {
        wellPositions.put(villageKey, pos.immutable());
        setDirty();
        LOG.info("Recorded mining well at {} for village {}", pos, villageKey);
    }

    /** Get the mining well entrance for a village, or null if none placed. */
    public BlockPos getWellPos(long villageKey) {
        return wellPositions.get(villageKey);
    }

    /** Find which village a position belongs to, then return its well position. */
    public BlockPos getWellPosNear(ServerWorld world, BlockPos pos) {
        Long key = discover(world, pos);
        return key == null ? null : wellPositions.get(key);
    }

    // ── Shared chunk memories ────────────────────────────────────────────

    /**
     * Get the shared chunk memories for a village. Returns empty list if not yet seeded.
     * Callers should deep-copy entries into the villager's personal memory.
     */
    public List<ChunkMemory> getSharedMemories(long villageKey) {
        return sharedChunkMemories.getOrDefault(villageKey, Collections.emptyList());
    }

    /**
     * Seed shared chunk memories by sampling chunks that cover the village AABB
     * plus one chunk ring outward. Only runs once per village (skipped if already seeded).
     */
    private void seedSharedMemories(ServerWorld world, long villageKey, MutableBoundingBox bounds) {
        if (sharedChunkMemories.containsKey(villageKey)) return;

        // Chunk range covering the AABB, expanded by 1 chunk in each direction.
        int cx0 = (bounds.x0 >> 4) - 1;
        int cz0 = (bounds.z0 >> 4) - 1;
        int cx1 = (bounds.x1 >> 4) + 1;
        int cz1 = (bounds.z1 >> 4) + 1;

        // Use the village center Y as the sampling reference height.
        int refY = (bounds.y0 + bounds.y1) / 2;

        List<ChunkMemory> memories = new ArrayList<>();
        // Create a fake position at the village center for sampling height reference.
        net.minecraft.entity.Entity fakeRef = null; // we'll use the static sampler overload

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!world.hasChunk(cx, cz)) continue;
                // Sample with a synthetic reference position at the village center height.
                ChunkMemory cm = ChunkContentSampler.sampleAt(world, cx, cz, refY);
                if (cm != null) memories.add(cm);
            }
        }

        sharedChunkMemories.put(villageKey, memories);
        setDirty();
        LOG.info("Seeded {} shared chunk memories for village {} ({}x{} chunks)",
                memories.size(), villageKey, cx1 - cx0 + 1, cz1 - cz0 + 1);
    }

    // ── NBT persistence ──────────────────────────────────────────────────

    @Override public void load(CompoundNBT nbt) {
        villages.clear(); residents.clear(); wellPositions.clear(); sharedChunkMemories.clear();

        ListNBT list = nbt.getList("Villages", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT n = list.getCompound(i); int[] b = n.getIntArray("Bounds");
            if (b.length == 6) villages.put(n.getLong("Origin"), new MutableBoundingBox(b));
        }
        list = nbt.getList("Residents", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT n = list.getCompound(i);
            if (n.hasUUID("Id") && villages.containsKey(n.getLong("Village")))
                residents.put(n.getUUID("Id"), n.getLong("Village"));
        }

        noticeBoards.clear();
        ListNBT boards = nbt.getList("NoticeBoards", 10);
        for (int i = 0; i < boards.size(); i++) {
            CompoundNBT entry = boards.getCompound(i);
            noticeBoards.put(entry.getLong("Village"), BlockPos.of(entry.getLong("Pos")));
        }
        // Load well positions
        list = nbt.getList("Wells", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT n = list.getCompound(i);
            wellPositions.put(n.getLong("Village"), new BlockPos(n.getInt("X"), n.getInt("Y"), n.getInt("Z")));
        }

        // Load shared chunk memories
        list = nbt.getList("SharedChunks", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT n = list.getCompound(i);
            long vk = n.getLong("Village");
            ListNBT chunks = n.getList("Chunks", 10);
            List<ChunkMemory> mems = new ArrayList<>(chunks.size());
            for (int j = 0; j < chunks.size(); j++) mems.add(ChunkMemory.readNBT(chunks.getCompound(j)));
            sharedChunkMemories.put(vk, mems);
        }
    }

    @Override public CompoundNBT save(CompoundNBT nbt) {
        // Villages
        ListNBT list = new ListNBT();
        villages.forEach((key, b) -> {
            CompoundNBT n = new CompoundNBT(); n.putLong("Origin", key);
            n.putIntArray("Bounds", new int[]{b.x0, b.y0, b.z0, b.x1, b.y1, b.z1}); list.add(n);
        });
        nbt.put("Villages", list);

        // Residents
        ListNBT members = new ListNBT();
        residents.forEach((id, key) -> {
            CompoundNBT n = new CompoundNBT(); n.putUUID("Id", id); n.putLong("Village", key); members.add(n);
        });
        nbt.put("Residents", members);

        ListNBT boards = new ListNBT();
        noticeBoards.forEach((key, pos) -> {
            CompoundNBT entry = new CompoundNBT(); entry.putLong("Village", key); entry.putLong("Pos", pos.asLong()); boards.add(entry);
        });
        nbt.put("NoticeBoards", boards);
        // Wells
        ListNBT wells = new ListNBT();
        wellPositions.forEach((vk, pos) -> {
            CompoundNBT n = new CompoundNBT(); n.putLong("Village", vk);
            n.putInt("X", pos.getX()); n.putInt("Y", pos.getY()); n.putInt("Z", pos.getZ());
            wells.add(n);
        });
        nbt.put("Wells", wells);

        // Shared chunk memories
        ListNBT sharedList = new ListNBT();
        sharedChunkMemories.forEach((vk, mems) -> {
            CompoundNBT n = new CompoundNBT(); n.putLong("Village", vk);
            ListNBT chunks = new ListNBT();
            for (ChunkMemory cm : mems) chunks.add(cm.writeNBT());
            n.put("Chunks", chunks);
            sharedList.add(n);
        });
        nbt.put("SharedChunks", sharedList);

        return nbt;
    }
}
