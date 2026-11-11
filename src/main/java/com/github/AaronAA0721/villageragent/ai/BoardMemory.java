package com.github.AaronAA0721.villageragent.ai;

import java.util.*;

/**
 * A villager's own snapshot of its village board, refreshed only by physically reading the board.
 * Supply decisions (keep, drop or add responses) are made from this snapshot, never from live board state.
 */
public final class BoardMemory {
    /** One request as last seen. */
    public static final class Entry {
        public final UUID id, author, claimer;
        public final String spec;
        public final int quantity;
        public final VillageSignalBoard.RequestState state;
        Entry(VillageSignalBoard.VillageMessage m, long now) {
            id = m.id; author = m.author; spec = m.spec.key(); quantity = m.quantity;
            state = m.state(now);
            claimer = m.claim() == null ? null : m.claim().provider;
        }
        public boolean open() { return state == VillageSignalBoard.RequestState.OPEN; }
    }
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private VillageSignalBoard board;
    private long readTick = Long.MIN_VALUE;

    /** Replace the snapshot with what is on {@code board} now; requests not listed are gone. */
    public void observe(VillageSignalBoard board, long now) {
        entries.clear();
        this.board = board;
        readTick = now;
        if (board == null) return;
        for (VillageSignalBoard.VillageMessage m : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT))
            if (m.isRequest() && !m.resolved) entries.put(m.id, new Entry(m, now));
    }
    public Entry get(UUID id) { return entries.get(id); }
    public Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }
    public VillageSignalBoard board() { return board; }
    public long readTick() { return readTick; }
}
