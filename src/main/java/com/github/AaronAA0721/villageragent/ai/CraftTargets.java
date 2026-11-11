package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Per-profession stock table for the routine "craft goods" goal ({@code config/villageragent/craft_targets.json}).
 * A profession crafts only what is listed here, up to the listed count; a profession with no entry has no
 * craft routine at all. Requested items and missing tools are crafted through their own goals (village
 * request responses and profession-tool acquisition), not through this table.
 */
public final class CraftTargets {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final String FILE = "craft_targets.json";
    private static Map<String, LinkedHashMap<String, Integer>> table;

    private CraftTargets() {}

    /** item id -> stock target, in preference order; empty when the profession stocks nothing. */
    public static synchronized Map<String, Integer> forProfession(String profession) {
        load();
        Map<String, Integer> m = table.get(norm(profession));
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
    }

    /** Stock target for one item, 0 when not listed. */
    public static int target(String profession, String item) {
        Integer n = forProfession(profession).get(item);
        return n == null ? 0 : n;
    }

    public static synchronized void reload() { table = null; }

    private static void load() {
        if (table != null) return;
        table = new HashMap<>();
        try (InputStream in = VillagerAgentConfigDir.open(FILE)) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject professions = root != null && root.has("professions") && root.get("professions").isJsonObject()
                    ? root.getAsJsonObject("professions") : root;
            if (professions == null) return;
            for (Map.Entry<String, JsonElement> p : professions.entrySet()) {
                if (p.getKey().startsWith("_") || !p.getValue().isJsonObject()) continue;
                LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : p.getValue().getAsJsonObject().entrySet()) {
                    try {
                        int n = e.getValue().getAsInt();
                        if (n > 0) items.put(e.getKey(), n);
                    } catch (RuntimeException ex) {
                        LOGGER.warn("[VillagerAgent] craft_targets.json: bad count for {} / {}", p.getKey(), e.getKey());
                    }
                }
                table.put(norm(p.getKey()), items);
            }
            LOGGER.info("[VillagerAgent] Loaded craft stock targets for {} profession(s)", table.size());
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] craft_targets.json load failed (no routine crafting): {}", ex.getMessage());
        }
    }

    /** "minecraft:Farmer" / "villageragent:miner" / "farmer" -> "farmer". */
    static String norm(String profession) {
        if (profession == null) return "";
        String p = profession.toLowerCase(Locale.ROOT).trim();
        int colon = p.indexOf(':');
        return colon >= 0 ? p.substring(colon + 1) : p;
    }
}
