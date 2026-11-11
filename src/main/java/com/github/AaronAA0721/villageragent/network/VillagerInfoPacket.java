package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkTag;
import com.github.AaronAA0721.villageragent.ai.memory.EntityCategory;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityLevelTable;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityPool;
import com.github.AaronAA0721.villageragent.client.VillagerChatHandler;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> Client: a read-only snapshot of a villager's state (needs, skills, inventory,
 * relationships, chunk memory ...) for the chat panel's Info view.
 *
 * <p>Lines are pre-formatted on the server so the client needs no knowledge of the AI classes.
 * Besides plain text (with § colour codes) two structured line kinds are understood by
 * {@code VillagerChatScreen}:
 * <ul>
 *   <li>{@code #H|Title|right text} — a section header with a rule under it,</li>
 *   <li>{@code #B|label|value|max|RRGGBB|right text} — a labelled progress bar.</li>
 * </ul>
 */
public class VillagerInfoPacket {
    private static final int MAX_LINES = 320;
    private static final int MAX_LINE_LEN = 400;
    /** How many individual chunks are listed (nearest first); the rest only count in the summary. */
    private static final int CHUNK_ROWS = 24;

    private final UUID villagerId;
    private final List<String> lines;

    public VillagerInfoPacket(UUID villagerId, List<String> lines) {
        this.villagerId = villagerId;
        this.lines = lines;
    }

    public static void encode(VillagerInfoPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
        int n = Math.min(packet.lines.size(), MAX_LINES);
        buffer.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            String s = packet.lines.get(i);
            buffer.writeUtf(s.length() > MAX_LINE_LEN ? s.substring(0, MAX_LINE_LEN) : s, MAX_LINE_LEN * 4);
        }
    }

    public static VillagerInfoPacket decode(PacketBuffer buffer) {
        UUID id = buffer.readUUID();
        int n = Math.min(buffer.readVarInt(), MAX_LINES);
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(buffer.readUtf(MAX_LINE_LEN * 4));
        return new VillagerInfoPacket(id, lines);
    }

    public static void handle(VillagerInfoPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VillagerChatHandler.receiveInfo(packet.villagerId, packet.lines));
        ctx.get().setPacketHandled(true);
    }

    // ── Server-side formatting ─────────────────────────────────────────────

    /** Build the display lines for {@code agent}'s info view. Server thread only. */
    public static List<String> describeInfo(VillagerAgentData agent, ServerPlayerEntity viewer, long now) {
        List<String> out = new ArrayList<>();
        VillagerEntity self = VillagerAgentManager.findVillagerEntity(agent.getVillagerId());
        BlockPos here = self != null ? self.blockPosition() : null;
        long today = now / 24000L;

        // ── Overview ──
        out.add(header("Overview", ""));
        out.add(kv("Profession", safe(agent.getProfession())
                + (agent.getResourceProfession() != null
                    ? " §8(work role: " + lower(String.valueOf(agent.getResourceProfession())) + ")" : "")));
        out.add(kv("Personality", safe(agent.getPersonality())));
        String path = agent.brain().tasks.path();
        out.add(kv("Doing now", path.isEmpty() ? "§8(idle)" : path));
        if (agent.getScheduledActivity() != null) out.add(kv("Schedule says", agent.getScheduledActivity()));
        AgentGoal running = agent.brain().tasks.root() == null ? null : agent.brain().tasks.root().goal;
        if (running != null && running.getDescription() != null) out.add(kv("Current goal", running.getDescription()));
        out.add(kv("Goals queued", String.valueOf(agent.getGoals().size())));
        if (here != null) out.add(kv("Position", fmt(here)));
        BlockPos bed = agent.getHomeBedPos();
        out.add(kv("Home bed", bed == null ? "§8not chosen yet" : fmt(bed) + dist(here, bed)));

        // ── Needs ──
        out.add(header("Needs & mood", ""));
        if (self != null) {
            float hp = self.getHealth(), max = self.getMaxHealth();
            out.add(bar("Health", hp, max, hp / max > 0.5f ? 0x55FF55 : hp / max > 0.25f ? 0xFFFF55 : 0xFF5555,
                    String.format(Locale.ROOT, "%.0f / %.0f", hp, max)));
        }
        float hunger = agent.getHunger();
        out.add(bar("Hunger", hunger, 100, hunger > 50 ? 0x55FF55 : hunger > 20 ? 0xFFFF55 : 0xFF5555,
                Math.round(hunger) + " / 100  " + hungerWord(hunger)));
        float fatigue = agent.getFatigue();
        out.add(bar("Fatigue", fatigue, 100, fatigue < 50 ? 0x55FFFF : fatigue < 80 ? 0xFFAA00 : 0xFF5555,
                Math.round(fatigue) + " / 100  " + fatigueWord(fatigue)));
        out.add(kv("Mood", moodText(agent.getMood())));

        // ── Skills ──
        CapabilityPool pool = agent.getCapabilityPool();
        CapabilityLevelTable table = CapabilityLevelTable.instance();
        CapabilityId main = CapabilityId.mainForProfession(agent.getProfession());
        out.add(header("Skills", "level " + "·" + " progress to next"));
        List<String> untrained = new ArrayList<>();
        for (CapabilityId id : CapabilityId.values()) {
            int xp = pool.xpOf(id);
            if (xp <= 0 && id != main) { untrained.add(pretty(id.name())); continue; }
            int lvl = pool.levelOf(id);
            int maxLvl = table.getMaxLevel();
            String label = (id == main ? "§6★ " : "") + pretty(id.name());
            StringBuilder right = new StringBuilder();
            right.append("Lv ").append(lvl).append('/').append(maxLvl);
            float cur, need;
            if (lvl >= maxLvl) {
                cur = 1; need = 1;
                right.append("  §6MAX");
            } else {
                int base = table.xpForLevel(lvl), next = table.xpForLevel(lvl + 1);
                cur = xp - base; need = Math.max(1, next - base);
                right.append("  §7").append(xp - base).append('/').append(next - base).append(" xp");
            }
            long last = pool.lastPracticed(id);
            if (last >= 0) {
                long ago = today - last;
                right.append("  §8").append(ago <= 0 ? "practised today" : "practised " + ago + "d ago");
                if (ago > CapabilityPool.RUST_GRACE_DAYS) right.append(" §c(rusting)");
            }
            out.add(bar(label, cur, need, id == main ? 0xFFAA00 : 0x55AAFF, right.toString()));
        }
        if (!untrained.isEmpty()) out.add("§8Untrained: " + String.join(", ", untrained));
        if (main != null) out.add("§8★ = main skill for this profession");

        Collection<String> recipes = agent.getUnlockedRecipes().ids();
        if (!recipes.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String r : recipes) names.add(shortId(r));
            names.sort(String::compareTo);
            out.add("§7Can craft (" + names.size() + "): §f" + String.join(", ", names));
        }

        // ── Inventory ──
        Map<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack s : agent.getInventory().getItems()) {
            if (s == null || s.isEmpty()) continue;
            items.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
        }
        out.add(header("Inventory", items.isEmpty() ? "empty" : items.size() + " kinds"));
        if (items.isEmpty()) {
            out.add("§8(nothing)");
        } else {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : items.entrySet()) parts.add("§f" + e.getKey() + " §7×" + e.getValue());
            out.add(String.join("§8,  ", parts));
        }

        // ── Relationships ──
        out.add(header("Relationships", ""));
        Integer rep = agent.getPlayerReputation().get(viewer.getUUID());
        out.add(bar("You", (rep == null ? 0 : rep) + 100, 200, repColor(rep == null ? 0 : rep),
                (rep == null ? "0 (never met)" : signed(rep)) + "  §8-100..100"));
        List<Map.Entry<String, Integer>> rels = new ArrayList<>(agent.getRelationships().entrySet());
        rels.sort((a, b) -> Integer.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
        int shown = 0;
        for (Map.Entry<String, Integer> e : rels) {
            if (shown++ >= 10) { out.add("§8... and " + (rels.size() - 10) + " more"); break; }
            out.add(bar(villagerName(e.getKey()), e.getValue() + 100, 200, repColor(e.getValue()), signed(e.getValue())));
        }
        if (rels.isEmpty()) out.add("§8(knows no other villagers yet)");

        // ── Chunk memory ──
        describeChunks(agent, here, now, out);

        // ── Recent memories ──
        List<String> mem = agent.getMemories();
        out.add(header("Recent memories", mem.size() + " total"));
        if (mem.isEmpty()) out.add("§8(none)");
        for (int i = Math.max(0, mem.size() - 8); i < mem.size(); i++) out.add("§7• §f" + mem.get(i));
        return out;
    }

    private static void describeChunks(VillagerAgentData agent, BlockPos here, long now, List<String> out) {
        List<ChunkMemory> all = new ArrayList<>(agent.getAllChunkMemories());
        int visited = 0;
        int[] tagCount = new int[ChunkTag.values().length];
        for (ChunkMemory cm : all) {
            if (cm.lastVisitedTick > 0) visited++;
            for (ChunkTag t : ChunkTag.values()) if (cm.has(t)) tagCount[t.ordinal()]++;
        }
        out.add(header("Chunk memory", all.size() + " / " + VillagerAgentData.MAX_KNOWN_CHUNKS + " chunks"));
        out.add(bar("Capacity", all.size(), VillagerAgentData.MAX_KNOWN_CHUNKS, all.size() > VillagerAgentData.MAX_KNOWN_CHUNKS * 9 / 10 ? 0xFFAA00 : 0x55AAFF,
                visited + " walked through  ·  " + (all.size() - visited) + " only glimpsed"));

        List<String> tagParts = new ArrayList<>();
        for (ChunkTag t : ChunkTag.values()) {
            if (t == ChunkTag.KNOWN || tagCount[t.ordinal()] == 0) continue;
            tagParts.add(tagColor(t) + t.label() + " §7" + tagCount[t.ordinal()]);
        }
        out.add("§7Areas: " + (tagParts.isEmpty() ? "§8(nothing notable)" : String.join("§8,  ", tagParts)));

        // Known features: how many chunks have one, and the nearest remembered spot.
        out.add("§7Remembered places (nearest spot):");
        boolean anyFeature = false;
        for (ChunkFeature f : ChunkFeature.values()) {
            int n = 0;
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (ChunkMemory cm : all) {
                if (!cm.has(f)) continue;
                n++;
                BlockPos a = cm.anchorOf(f);
                if (a != null && here != null) {
                    double d = a.distSqr(here);
                    if (d < bestD) { bestD = d; best = a; }
                } else if (a != null && best == null) {
                    best = a;
                }
            }
            if (n == 0) continue;
            anyFeature = true;
            out.add("§8   • " + (f.isOre() ? "§b" : "§f") + f.display() + " §7in " + n + " chunk" + (n == 1 ? "" : "s")
                    + (best != null ? "§8  → " + fmt(best) + dist(here, best) : ""));
        }
        if (!anyFeature) out.add("§8   (none yet)");

        // Nearest individual chunks.
        if (here != null) {
            final int hx = here.getX(), hz = here.getZ();
            all.sort(Comparator.comparingDouble(cm -> sq(cm.getX() * 16 + 8 - hx) + sq(cm.getZ() * 16 + 8 - hz)));
        } else {
            all.sort(Comparator.comparingLong((ChunkMemory cm) -> cm.lastVisitedTick).reversed());
        }
        out.add("§7Nearest chunks §8(chunk x,z  ·  distance  ·  last visit  ·  what's there):");
        int rows = 0;
        for (ChunkMemory cm : all) {
            if (rows++ >= CHUNK_ROWS) {
                out.add("§8   ... " + (all.size() - CHUNK_ROWS) + " more chunks remembered");
                break;
            }
            StringBuilder sb = new StringBuilder("§8   ");
            sb.append("§f[").append(cm.getX()).append(", ").append(cm.getZ()).append("]");
            if (here != null) {
                int dx = cm.getX() * 16 + 8 - here.getX(), dz = cm.getZ() * 16 + 8 - here.getZ();
                sb.append(" §7").append((int) Math.sqrt(dx * dx + dz * dz)).append("m ").append(compass(dx, dz));
            }
            sb.append(" §8· ").append(cm.lastVisitedTick > 0 ? "§7" + ago(now - cm.lastVisitedTick) : "§8glimpsed");
            List<String> what = new ArrayList<>();
            for (ChunkTag t : ChunkTag.values()) if (t != ChunkTag.KNOWN && cm.has(t)) what.add(tagColor(t) + t.label());
            for (ChunkFeature f : ChunkFeature.values()) if (cm.has(f)) what.add((f.isOre() ? "§b" : "§f") + f.display());
            int hostiles = cm.entityCount(EntityCategory.HOSTILE);
            if (hostiles > 0) what.add("§c" + hostiles + " hostile");
            if (cm.getKnownBuildingIds().length > 0) what.add("§d" + cm.getKnownBuildingIds().length + " building"
                    + (cm.getKnownBuildingIds().length == 1 ? "" : "s"));
            sb.append(" §8· ").append(what.isEmpty() ? "§8-" : String.join("§8, ", what));
            out.add(sb.toString());
        }
        if (all.isEmpty()) out.add("§8   (no chunks remembered)");
    }

    // ── helpers ──

    private static String header(String title, String right) { return "#H|" + title + "|" + right; }

    private static String bar(String label, float value, float max, int rgb, String right) {
        return "#B|" + label.replace('|', '/') + "|" + value + "|" + max + "|"
                + String.format("%06X", rgb & 0xFFFFFF) + "|" + right.replace('|', '/');
    }

    private static String kv(String key, String value) { return "§7" + key + ": §f" + value; }

    private static String hungerWord(float h) {
        return h > 80 ? "§afull" : h > 50 ? "§asatisfied" : h > 30 ? "§epeckish" : h > 10 ? "§6hungry" : "§cstarving";
    }

    private static String fatigueWord(float f) {
        return f < 20 ? "§arested" : f < 50 ? "§aa little tired" : f < 80 ? "§6tired" : "§cexhausted";
    }

    private static String moodText(VillagerAgentData.Mood m) {
        if (m == null) return "§7unknown";
        switch (m) {
            case HAPPY: return "§a☺ happy";
            case CONTENT: return "§acontent";
            case NEUTRAL: return "§fneutral";
            case ANXIOUS: return "§6anxious";
            default: return "§cdistressed";
        }
    }

    private static int repColor(int v) { return v > 10 ? 0x55FF55 : v < -10 ? 0xFF5555 : 0xAAAAAA; }

    private static String tagColor(ChunkTag t) {
        switch (t) {
            case FOREST: return "§2";
            case FARMLAND: return "§e";
            case WATER: return "§9";
            case VILLAGE: return "§6";
            case ORE: return "§b";
            case DANGER_LAVA: case HOSTILES: return "§c";
            case ANIMALS: return "§a";
            default: return "§7";
        }
    }

    private static String villagerName(String uuid) {
        try {
            VillagerAgentData other = VillagerAgentManager.getAgent(UUID.fromString(uuid));
            if (other != null && other.getName() != null) return other.getName();
        } catch (IllegalArgumentException ignored) {}
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    private static String compass(int dx, int dz) {
        if (dx * dx + dz * dz < 64) return "here";
        double a = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-z), 90 = east (+x)
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(((a % 360) + 360) % 360 / 45.0) % 8];
    }

    private static String dist(BlockPos from, BlockPos to) {
        if (from == null || to == null) return "";
        return " §8(" + (int) Math.sqrt(from.distSqr(to)) + "m away)";
    }

    private static String ago(long ticks) {
        long s = Math.max(0, ticks) / 20;
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    private static double sq(double v) { return v * v; }
    private static String fmt(BlockPos p) { return p.getX() + ", " + p.getY() + ", " + p.getZ(); }
    private static String signed(int v) { return (v > 0 ? "+" : "") + v; }
    private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
    private static String pretty(String enumName) {
        String s = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
    private static String safe(String s) { return s == null || s.isEmpty() ? "§8-" : s; }
}
