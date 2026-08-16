package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;

import java.util.Map;
import java.util.UUID;

/**
 * A persistent, long-lived intention that outlives any single {@link AgentGoal} (todo).
 *
 * <p>Unlike a todo — which is episodic, executed once, then deleted — a LongTermAgenda
 * stays in the villager's "ledger" until explicitly resolved, and is periodically
 * re-expressed as short-term todos by the LLM during reflection. Debts, item-acquisition
 * goals and inter-villager deals are specific kinds of agenda; unknown kinds fall back to
 * {@link GenericAgenda}, so the LLM can remember arbitrary long-term things without a new
 * Java subclass for each.
 */
public abstract class LongTermAgenda {

    private String id;
    private final String kind;
    private String title;
    private long createdAt;
    private long updatedAt;
    private boolean resolved;

    protected LongTermAgenda(String kind, String title, long createdAt) {
        this.id = UUID.randomUUID().toString();
        this.kind = kind;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.resolved = false;
    }

    public String getId()      { return id; }
    public String getKind()    { return kind; }
    public String getTitle()   { return title; }
    public void setTitle(String title) { this.title = title; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void touch(long tick) { this.updatedAt = tick; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    /** Human-readable one-liner for LLM prompt injection. */
    public abstract String describe();

    /**
     * Apply a key=value update produced by the LLM ("REMEMBER: update &lt;id&gt; ...").
     * Base implementation only accepts {@code title}; subclasses override to map their own
     * structured fields. {@link GenericAgenda} maps everything else into free-form fields.
     */
    public void applyUpdate(Map<String, String> kv, long tick) {
        if (kv.containsKey("title")) setTitle(kv.get("title"));
        touch(tick);
    }

    /** minecraft:emerald -> "emerald" (shared by agenda descriptions). */
    static String shortName(String registryName) {
        if (registryName == null) return "?";
        String s = registryName;
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        return s.replace('_', ' ');
    }

    // ── NBT round-trip ──

    public final CompoundNBT writeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        nbt.putString("Id", id);
        nbt.putString("Kind", kind);
        nbt.putString("Title", title);
        nbt.putLong("CreatedAt", createdAt);
        nbt.putLong("UpdatedAt", updatedAt);
        nbt.putBoolean("Resolved", resolved);
        writeExtra(nbt);
        return nbt;
    }

    protected abstract void writeExtra(CompoundNBT nbt);
    protected abstract void readExtra(CompoundNBT nbt);

    /** Factory: reconstruct the correct subclass from NBT, keyed on {@code kind}. */
    public static LongTermAgenda fromNBT(CompoundNBT nbt) {
        String kind = nbt.getString("Kind");
        String title = nbt.getString("Title");
        long createdAt = nbt.getLong("CreatedAt");

        LongTermAgenda a;
        switch (kind) {
            case "debt":    a = new DebtAgenda(title, createdAt); break;
            case "acquire": a = new AcquireItemAgenda(title, createdAt); break;
            case "deal":    a = new DealAgenda(title, createdAt); break;
            default:        a = new GenericAgenda(title, createdAt); break;
        }
        a.id = nbt.getString("Id");
        a.updatedAt = nbt.getLong("UpdatedAt");
        a.resolved = nbt.getBoolean("Resolved");
        a.readExtra(nbt);
        return a;
    }
}
