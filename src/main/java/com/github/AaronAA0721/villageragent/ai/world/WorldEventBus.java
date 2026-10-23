package com.github.AaronAA0721.villageragent.ai.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Global, append-only environment event log for the server simulation.
 *
 * <p>Modeled after Kafka's log-and-offsets pattern (same source as the Settlements reference):
 * <ul>
 *   <li><b>O(1) append</b> — producers publish and never wait for consumers.</li>
 *   <li><b>Per-consumer cursor</b> — each villager stores its own {@code eventCursor} (the index
 *       of the last event it read) and consumes only the delta since its last think tick. Offline
 *       villagers (combat-occupied / unloaded) don't miss events: they catch up on resume.</li>
 *   <li><b>TTL = ring overwrite</b> — the ring is {@link #EVENT_CAPACITY} slots; when the write
 *       index wraps past a slot, that old event is implicitly evicted. No reaper timer needed.</li>
 *   <li><b>Perception gate (anti-telepathy)</b> — {@link #admits} only lets a villager consume an
 *       event whose chunk is within {@code perceptionRadiusChunks} (Manhattan chunk distance) of
 *       the villager. Far events are skipped (cursor still advances: "not perceived" ≠ "not read").</li>
 * </ul>
 *
 * <p>Single-threaded (server tick) means no locking is needed for append or scan; {@code
 * synchronized} is kept only as a cheap defence against any future async emission path.
 *
 * <p>Discipline: this bus only ever <em>informs</em>. It never drives movement or world edits
 * directly — consumers translate admitted events into {@code AgentGoal}s (or memory/refresh hints)
 * that flow through the existing unified goal funnel.
 */
public final class WorldEventBus {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Ring buffer capacity. Old events are evicted by overwrite once the write index wraps. */
    public static final int EVENT_CAPACITY = 256;

    /** Event kinds. Append new kinds at the end — ordinals are not persisted (events are not saved). */
    public enum Type { BLOCK_CHANGED, ITEM_DROPPED, NEW_VILLAGER }

    /** A single immutable environment event. */
    public static final class Event {
        public final Type type;
        public final long seq;
        public final long tick;
        public final BlockPos pos;

        Event(Type type, long seq, long tick, BlockPos pos) {
            this.type = type;
            this.seq = seq;
            this.tick = tick;
            this.pos = pos;
        }
    }

    private final Event[] ring = new Event[EVENT_CAPACITY];
    /** Monotonic sequence, starts at 1 so a cursor initialised to 0 picks up the first event. */
    private long sequence = 1L;
    /** Total number of events ever appended (== next slot's virtual index). */
    private long writeIndex = 0L;

    private static final Map<World, WorldEventBus> INSTANCES = new WeakHashMap<>();

    private WorldEventBus() {}

    /** Per-dimension singleton (mirrors {@code WorldStructureIndex.instance}). */
    public static WorldEventBus instance(ServerWorld world) {
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(world, w -> new WorldEventBus());
        }
    }

    /** Append an event. Producers are all on the server thread. */
    public synchronized void publish(Type type, BlockPos pos, long gameTick) {
        int slot = (int) (writeIndex % EVENT_CAPACITY);
        ring[slot] = new Event(type, sequence++, gameTick, pos);
        writeIndex++;
    }

    /**
     * Anti-telepathy gate: is this event within the villager's perception radius?
     *
     * @param radiusChunks Manhattan chunk-distance radius (4 = 64 blocks).
     */
    public static boolean admits(Event e, int villagerChunkX, int villagerChunkZ, int radiusChunks) {
        int ex = e.pos.getX() >> 4;
        int ez = e.pos.getZ() >> 4;
        return (Math.abs(ex - villagerChunkX) + Math.abs(ez - villagerChunkZ)) <= radiusChunks;
    }

    /**
     * Consume up to {@code budget} events after {@code cursor}, feeding admitted events to
     * {@code sink}. Returns the new cursor value.
     *
     * <p>Events older than {@code writeIndex - EVENT_CAPACITY} have been overwritten; the cursor is
     * clamped forward past them so a long-absent villager does not re-read evicted slots.
     */
    public synchronized long consume(long cursor, int budget, int villagerChunkX, int villagerChunkZ,
                                     int radiusChunks, Consumer<Event> sink) {
        long oldest = Math.max(0L, writeIndex - EVENT_CAPACITY);
        long start = Math.max(cursor, oldest);
        long head = Math.min(writeIndex, start + budget);

        for (long i = start; i < head; i++) {
            Event e = ring[(int) (i % EVENT_CAPACITY)];
            if (e == null) continue;
            if (admits(e, villagerChunkX, villagerChunkZ, radiusChunks)) sink.accept(e);
        }
        return head;
    }

    /** Diagnostic: how many unconsumed events remain (debug / balancing). */
    public synchronized int pending(long cursor) {
        long oldest = Math.max(0L, writeIndex - EVENT_CAPACITY);
        return (int) Math.max(0L, writeIndex - Math.max(cursor, oldest));
    }
}
