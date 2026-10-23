package com.github.AaronAA0721.villageragent.ai.proficiency;

import java.util.Locale;

/**
 * Hard-coded capability ids — the typed keys of a villager's {@link CapabilityPool}.
 *
 * <p>These are deliberately an enum (not data-driven): a capability is always tied to a
 * hard-coded {@code *Action} class in code, so the id that gates it must be type-safe and
 * compiler-checked. The stable {@link #name()} string goes into NBT, so <b>never rename</b> an
 * existing constant (same "stable id" rule as {@code BehaviorKey}).
 */
public enum CapabilityId {
    FARMING, FISHING, SMITHING, BREWING, ENCHANTING,
    CRAFTING, GATHERING, SOCIAL, COMBAT;

    /**
     * The "main" capability for a villager profession, i.e. the capability that profession
     * trains by doing its job. {@code null} means "no main capability" (unemployed / nitwit).
     *
     * <p>Professions here are the humanised strings used across this mod (e.g. "Farmer",
     * "Weaponsmith" — as produced by {@code VillagerEventHandler#getVillagerProfessionName}).
     */
    public static CapabilityId mainForProfession(String profession) {
        if (profession == null || profession.isEmpty()) return null;
        switch (profession.toLowerCase(Locale.ROOT)) {
            case "farmer":      return FARMING;
            case "fisherman":   return FISHING;
            case "weaponsmith":
            case "toolsmith":
            case "armorer":     return SMITHING;
            case "cleric":      return BREWING;
            case "librarian":   return ENCHANTING;
            case "fletcher":
            case "leatherworker":
            case "mason":
            case "butcher":
            case "shepherd":
            case "cartographer": return CRAFTING;
            default:            return null; // unemployed villager, nitwit, unknown
        }
    }

    /** Parse a stable id back to a capability, ignoring case. {@code null} if unknown. */
    public static CapabilityId fromId(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
