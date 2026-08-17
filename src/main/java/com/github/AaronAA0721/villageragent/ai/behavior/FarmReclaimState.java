package com.github.AaronAA0721.villageragent.ai.behavior;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-villager, NBT-serialisable state for the {@link FarmReclaimSkill} state machine.
 *
 * <p>Why it lives here and not on the (singleton) skill: skills are shared across every villager,
 * so any FSM progress must be stored on the {@code VillagerAgentData} (which is per-villager and
 * saved to disk). Without this, a chunk unload / world restart would erase the in-progress
 * reclaim plan and the villager would forget which blocks it had already marked.
 */
public class FarmReclaimState {
    /** N — how many blocks this reclaim run aims to open. Generated fresh each run. */
    private int targetCount;
    /** Immutable positions still waiting to be ploughed. Consumed FIFO as they are tilled. */
    private final List<BlockPos> pending = new ArrayList<>();
    /** How many have been ploughed so far this run (for logging / progress). */
    private int tilledCount;

    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int n) { this.targetCount = n; }
    public List<BlockPos> getPending() { return pending; }
    public int getTilledCount() { return tilledCount; }
    public void setTilledCount(int n) { this.tilledCount = n; }
    public boolean isEmpty() { return pending.isEmpty(); }

    public void addPending(BlockPos p) { pending.add(p.immutable()); }

    public CompoundNBT writeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        nbt.putInt("TargetCount", targetCount);
        nbt.putInt("TilledCount", tilledCount);
        long[] arr = new long[pending.size()];
        for (int i = 0; i < pending.size(); i++) arr[i] = pending.get(i).asLong();
        nbt.putLongArray("Pending", arr);
        return nbt;
    }

    public static FarmReclaimState readNBT(CompoundNBT nbt) {
        FarmReclaimState s = new FarmReclaimState();
        s.targetCount = nbt.getInt("TargetCount");
        s.tilledCount = nbt.getInt("TilledCount");
        if (nbt.contains("Pending")) {
            for (long l : nbt.getLongArray("Pending")) s.pending.add(BlockPos.of(l));
        }
        return s;
    }
}
