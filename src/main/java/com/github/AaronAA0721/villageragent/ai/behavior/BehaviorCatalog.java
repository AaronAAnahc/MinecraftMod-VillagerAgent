package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityPool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static catalog of <em>what a behaviour needs</em>: which capability and minimum level unlock
 * it, how heavy it is, and its default ("amateur") weight.
 *
 * <p>This is the "behavior catalog" half of the three-layer design (profession pool / capability pool /
 * behaviour catalog). It is static metadata keyed by a stable behaviour id that matches the goal
 * types used across the mod ({@code "farm_maintain"}, {@code "farm_reclaim"}, {@code "socialize"},
 * …). Capability gating and weighted planning both read from here, so "what does doing X require"
 * lives in exactly one place.
 */
public final class BehaviorCatalog {

    /** Coarse category, used by weighted planning (work vs leisure vs social …). */
    public enum Category { WORK, SOCIAL, SELF_CARE, LEISURE, COMBAT }

    /** One behaviour's static descriptor. */
    public static final class Entry {
        public final String id;
        public final CapabilityId requiredCapability; // null = universal (no gate)
        public final int minLevel;                     // capability level to unlock (0 = none)
        public final int baseWeight;                   // default/amateur scheduling weight
        public final Category category;

        Entry(String id, CapabilityId cap, int minLevel, int baseWeight, Category category) {
            this.id = id;
            this.requiredCapability = cap;
            this.minLevel = minLevel;
            this.baseWeight = baseWeight;
            this.category = category;
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        // Universal behaviours — no capability gate, everyone can do them.
        add("sleep",        null,                    0, 1, Category.SELF_CARE);
        add("rest",         null,                    0, 1, Category.SELF_CARE);
        add("explore",      null,                    0, 2, Category.LEISURE);
        add("move",         null,                    0, 1, Category.LEISURE);
        add("drop_item",    null,                    0, 1, Category.WORK);
        add("pickup_item",  null,                    0, 1, Category.WORK);
        add("goto",         null,                    0, 1, Category.LEISURE);
        add("wander",       null,                    0, 2, Category.LEISURE);

        // Gated behaviours — the capability ladder (§3 of capability_pool_plan.md).
        add("farm",         CapabilityId.FARMING,    1, 4, Category.WORK);
        add("farm_maintain",CapabilityId.FARMING,    1, 4, Category.WORK);
        add("farm_reclaim", CapabilityId.FARMING,    2, 4, Category.WORK);
        add("gather",       CapabilityId.GATHERING,  1, 3, Category.WORK);
        add("craft",        CapabilityId.CRAFTING,   1, 3, Category.WORK);
        add("socialize",    CapabilityId.SOCIAL,     0, 3, Category.SOCIAL); // ungated — chat trains SOCIAL from 0
        add("trade",        CapabilityId.SOCIAL,     2, 3, Category.SOCIAL);
        add("combat",       CapabilityId.COMBAT,     1, 4, Category.COMBAT);
    }

    private BehaviorCatalog() {}

    private static void add(String id, CapabilityId cap, int minLevel, int baseWeight, Category cat) {
        ENTRIES.put(id, new Entry(id, cap, minLevel, baseWeight, cat));
    }

    /** Descriptor for a behaviour id, or {@code null} if unknown. */
    public static Entry get(String id) {
        return ENTRIES.get(id);
    }

    /** Every catalogued behaviour id (for planning candidate enumeration). */
    public static Map<String, Entry> all() {
        return Collections.unmodifiableMap(ENTRIES);
    }

    /**
     * Whether a villager's capability pool unlocks this behaviour (the gate used by
     * {@code VillagerSkill.canHandle} and the weighted plan generator).
     */
    public static boolean isUnlocked(String id, CapabilityPool pool) {
        Entry e = ENTRIES.get(id);
        if (e == null || e.requiredCapability == null || e.minLevel <= 0) return true;
        return pool.levelOf(e.requiredCapability) >= e.minLevel;
    }
}
