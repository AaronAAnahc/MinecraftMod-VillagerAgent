package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback agenda for anything the LLM wants to remember long-term that we didn't model as a
 * dedicated subclass. Holds free-form key=value fields plus a plain notes string, so no new
 * Java class is needed for ad-hoc long-term intentions.
 */
public class GenericAgenda extends LongTermAgenda {

    private final Map<String, String> fields = new LinkedHashMap<>();
    private String notes;

    public GenericAgenda(String title, long createdAt) {
        super("generic", title, createdAt);
    }

    public Map<String, String> getFields() { return fields; }
    public void putField(String key, String value) { fields.put(key, value); }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("[remember] ").append(getTitle());
        if (notes != null && !notes.isEmpty()) sb.append(" — ").append(notes);
        return sb.toString();
    }

    @Override
    public void applyUpdate(Map<String, String> kv, long tick) {
        super.applyUpdate(kv, tick);
        if (kv.containsKey("notes")) setNotes(kv.get("notes"));
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String k = e.getKey();
            if (k.equals("title") || k.equals("notes")) continue;
            putField(k, e.getValue());
        }
    }

    @Override
    protected void writeExtra(CompoundNBT nbt) {
        if (!fields.isEmpty()) {
            ListNBT fl = new ListNBT();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                CompoundNBT fn = new CompoundNBT();
                fn.putString("K", e.getKey());
                fn.putString("V", e.getValue());
                fl.add(fn);
            }
            nbt.put("Fields", fl);
        }
        if (notes != null) nbt.putString("Notes", notes);
    }

    @Override
    protected void readExtra(CompoundNBT nbt) {
        fields.clear();
        if (nbt.contains("Fields")) {
            ListNBT fl = nbt.getList("Fields", 10);
            for (int i = 0; i < fl.size(); i++) {
                CompoundNBT fn = fl.getCompound(i);
                fields.put(fn.getString("K"), fn.getString("V"));
            }
        }
        if (nbt.contains("Notes")) notes = nbt.getString("Notes");
    }
}
