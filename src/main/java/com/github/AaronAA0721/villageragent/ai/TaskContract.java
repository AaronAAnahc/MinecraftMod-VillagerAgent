package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import java.util.*;
import java.util.function.ToIntFunction;

/** Declarative requirements and effects of a bound task (quantities are inventory targets). */
public final class TaskContract {
    public static final class ItemNeed {
        public final String item;
        public final int quantity;
        public final boolean consumed;
        public ItemNeed(String item, int quantity, boolean consumed) {
            if (item == null || quantity < 1) throw new IllegalArgumentException("Invalid item requirement");
            this.item = item; this.quantity = quantity; this.consumed = consumed;
        }
    }
    public final List<ItemNeed> items;
    public final Map<CapabilityId, Integer> levels;
    public final Map<String, Integer> outputs;
    /** Practice events, multiplied by configured practice XP; never a promised absolute level. */
    public final Map<CapabilityId, Integer> practice;
    public TaskContract(List<ItemNeed> items, Map<CapabilityId, Integer> levels,
                        Map<String, Integer> outputs, Map<CapabilityId, Integer> practice) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.levels = Collections.unmodifiableMap(new LinkedHashMap<>(levels));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        this.practice = Collections.unmodifiableMap(new LinkedHashMap<>(practice));
    }
    public boolean levelsMet(ToIntFunction<CapabilityId> current) {
        for (Map.Entry<CapabilityId, Integer> e : levels.entrySet()) if (current.applyAsInt(e.getKey()) < e.getValue()) return false;
        return true;
    }
    public boolean produces(String item) { return outputs.getOrDefault(item, 0) > 0; }
}
