package com.github.AaronAA0721.villageragent.ai.debug;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerEconomySystem;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exports a human-readable JSON snapshot of every villager agent on world save.
 *
 * <p>This is a <em>debug mirror</em>, deliberately kept out of the main NBT / hot path: the
 * primary storage stays the binary {@code VillagerAgentSavedData}. On every world save this dumps
 * one pretty-printed JSON per villager under {@code <world>/villageragent/debug/<uuid>.json} so a
 * person can read memory, value table, inventory, goals and capabilities with a text editor or
 * {@code grep}, without touching NBT tooling.
 *
 * <p>Gated by {@link ModConfig#ENABLE_DEBUG_DUMP}. Files are overwritten each save and are safe to
 * delete at any time — they are never read back by the mod.
 */
public final class AgentDebugExporter {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int RECENT_MEMORY_LIMIT = 20;

    private AgentDebugExporter() {}

    /** Dump every agent to {@code <gamedir>/villageragent/debug/}. Called from the world-save event. */
    public static void exportAll(ServerWorld world) {
        if (!ModConfig.ENABLE_DEBUG_DUMP.get()) return;

        Path dir;
        try {
            dir = FMLPaths.GAMEDIR.get().resolve("villageragent").resolve("debug");
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[DEBUG-DUMP] could not create debug dir: " + e.getMessage());
            return;
        }

        int count = 0;
        for (VillagerAgentData agent : VillagerAgentManager.getAllAgents()) {
            if (agent == null) continue;
            try {
                JsonObject obj = build(agent);
                Path out = dir.resolve(agent.getVillagerId() + ".json");
                try (OutputStreamWriter w = new OutputStreamWriter(
                        Files.newOutputStream(out), StandardCharsets.UTF_8)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(obj, w);
                }
                count++;
            } catch (Exception e) {
                LOGGER.error("[DEBUG-DUMP] failed to export " + agent.getVillagerId() + ": " + e.getMessage());
            }
        }
        LOGGER.info("[DEBUG-DUMP] exported {} villager(s) to {}", count, dir);
    }

    private static JsonObject build(VillagerAgentData agent) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", agent.getVillagerId().toString());
        o.addProperty("name", agent.getName());
        o.addProperty("profession", agent.getProfession());
        o.addProperty("resourceProfession", agent.getResourceProfession().name());
        o.addProperty("personality", agent.getPersonality());
        o.addProperty("mood", agent.getMood().name());
        o.addProperty("hunger", agent.getHunger());
        o.addProperty("fatigue", agent.getFatigue());

        // ── Chunk memories (labels + feature anchors, human-readable) ──
        JsonArray chunks = new JsonArray();
        for (ChunkMemory cm : agent.getAllChunkMemories()) {
            JsonObject c = new JsonObject();
            c.addProperty("chunk", "(" + cm.getX() + "," + cm.getZ() + ")");
            c.addProperty("visitedTick", cm.lastVisitedTick);
            c.addProperty("saliency", cm.saliency);

            JsonArray tags = new JsonArray();
            for (String t : cm.tagLabels()) tags.add(t);
            c.add("tags", tags);

            JsonArray feats = new JsonArray();
            for (ChunkFeature f : ChunkFeature.values()) {
                if (!cm.has(f)) continue;
                JsonObject ff = new JsonObject();
                ff.addProperty("type", f.name());
                BlockPos a = cm.anchorOf(f);
                if (a != null) ff.addProperty("pos", "(" + a.getX() + "," + a.getY() + "," + a.getZ() + ")");
                if (f.isOre() && cm.getOreExpiryTick() != 0) {
                    ff.addProperty("oreExpiryTick", cm.getOreExpiryTick());
                }
                feats.add(ff);
            }
            c.add("features", feats);
            chunks.add(c);
        }
        o.add("chunkMemories", chunks);

        // ── Inventory ──
        JsonArray inv = new JsonArray();
        for (ItemStack s : agent.getInventory().getItems()) {
            if (s.isEmpty()) continue;
            JsonObject i = new JsonObject();
            i.addProperty("id", String.valueOf(s.getItem().getRegistryName()));
            i.addProperty("count", s.getCount());
            inv.add(i);
        }
        o.add("inventory", inv);

        // ── Personal value table ──
        JsonArray vt = new JsonArray();
        Map<String, VillagerEconomySystem.Price> pv = agent.getPersonalValueTable();
        if (pv != null) {
            for (Map.Entry<String, VillagerEconomySystem.Price> e : pv.entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("item", e.getKey());
                p.addProperty("buy", e.getValue().buy);
                p.addProperty("sell", e.getValue().sell);
                vt.add(p);
            }
        }
        o.add("valueTable", vt);

        // ── Current goals ──
        JsonArray goals = new JsonArray();
        for (AgentGoal g : agent.getGoals()) {
            JsonObject gg = new JsonObject();
            gg.addProperty("type", g.getGoalType());
            if (g.getWorkOrderId() != null) gg.addProperty("workOrderId", g.getWorkOrderId().toString());
            if (g.getTradeMessageId() != null) gg.addProperty("tradeMessageId", g.getTradeMessageId().toString());
            gg.addProperty("priority", g.getPriority());
            gg.addProperty("completed", g.isCompleted());
            if (g.getTargetItem() != null) gg.addProperty("targetItem", g.getTargetItem());
            goals.add(gg);
        }
        o.add("goals", goals);

        // ── Capabilities (non-zero levels) ──
        JsonArray caps = new JsonArray();
        for (CapabilityId id : CapabilityId.values()) {
            int lvl = agent.getCapabilityPool().levelOf(id);
            if (lvl <= 0) continue;
            JsonObject cc = new JsonObject();
            cc.addProperty("id", id.name());
            cc.addProperty("level", lvl);
            caps.add(cc);
        }
        o.add("capabilities", caps);

        // ── Recent memories (tail) ──
        JsonArray mems = new JsonArray();
        java.util.List<String> all = agent.getMemories();
        int start = Math.max(0, all.size() - RECENT_MEMORY_LIMIT);
        for (int i = start; i < all.size(); i++) mems.add(all.get(i));
        o.add("recentMemories", mems);

        return o;
    }
}
