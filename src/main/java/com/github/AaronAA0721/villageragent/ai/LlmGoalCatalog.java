package com.github.AaronAA0721.villageragent.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single vocabulary of goals the LLM may author. Each entry pairs the line the LLM sees in
 * its prompt ({@link #promptDocs()}) with the code that reads that JSON shape ({@link #parse}), so
 * the two can never drift apart. Adding an LLM goal type = one {@code add(...)} here plus a
 * {@code VillagerSkill} that executes it.
 *
 * <p>Routine-only types ({@code rest}, {@code wander}, ...) are deliberately absent: the routine
 * scheduler owns them and an LLM request for them is rejected.
 */
public final class LlmGoalCatalog {
    private static final Logger LOGGER = LogManager.getLogger("LlmGoalCatalog");
    public static final int MAX_STEPS = 8;
    /** Seeded by routines/needs only — never accepted from the LLM. */
    private static final Set<String> RULE_ONLY = new HashSet<>(Arrays.asList("rest", "sleep", "wander"));

    @FunctionalInterface
    private interface Parser { GoalDraft parse(JsonObject o); }

    private static final class Entry {
        final String doc; final boolean composite; final Parser parser;
        Entry(String doc, boolean composite, Parser parser) { this.doc = doc; this.composite = composite; this.parser = parser; }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    /** Spellings the LLM may use for a documented type (not shown in the prompt). */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        add("craft", "{\"type\":\"craft\",\"item\":\"minecraft:<id>\",\"qty\":1} - craft an item you are able to craft",
                o -> item(o, "craft"));
        add("acquire_item", "{\"type\":\"acquire_item\",\"item\":\"minecraft:<id>\",\"qty\":1} - get an item by any means (craft it, buy it, or ask the village)",
                o -> item(o, "acquire_item"));
        add("drop_item", "{\"type\":\"drop_item\",\"item\":\"minecraft:<id>\",\"qty\":1} - drop an item from your inventory",
                o -> item(o, "drop_item"));
        add("pickup_item", "{\"type\":\"pickup_item\",\"item\":\"minecraft:<id>\",\"qty\":1} - pick up a nearby dropped item",
                o -> item(o, "pickup_item"));
        add("move", "{\"type\":\"move\",\"x\":120,\"y\":64,\"z\":200} - walk to coordinates",
                o -> position(o, "move"));
        add("goto", "{\"type\":\"goto\",\"building\":\"house\"} - walk to the nearest building of a type (house/cave_house/any)",
                o -> {
                    String b = str(o, "building");
                    if (b == null) b = "any";
                    AgentGoal g = new AgentGoal("goto", "goto " + b, 5);
                    g.setTargetBuildingType(b.toLowerCase(Locale.ROOT));
                    return new GoalDraft(g);
                });
        add("place_block", "{\"type\":\"place_block\",\"item\":\"minecraft:<block id>\",\"x\":10,\"y\":64,\"z\":20} - walk there and place a block"
                        + " (omit x/y/z to place it right next to you; if you don't have the block you will get one first)",
                LlmGoalCatalog::place);
        ALIASES.put("place", "place_block");
        ALIASES.put("place_near_self", "place_block");
        add("cook", "{\"type\":\"cook\"} - cook raw meat at a smoker or lit campfire (needs raw meat; butchers)",
                o -> new GoalDraft(new AgentGoal("butcher_cook", "cook meat", 5)));
        add("eat", "{\"type\":\"eat\"} - eat food you carry (fetches food first if you have none)",
                o -> new GoalDraft(new AgentGoal("eat", "eat", 5)));
        add("stroll", "{\"type\":\"stroll\"} - walk around for a while; inside an invite's activity the guests walk along with the host",
                o -> new GoalDraft(new AgentGoal("stroll", "stroll", 5)));
        add("socialize", "{\"type\":\"socialize\"} - chat with someone nearby (inside an invite's activity: with the people you meet)",
                o -> new GoalDraft(new AgentGoal("socialize", "socialize", 5)));
        add("converse", "{\"type\":\"converse\",\"villager\":\"<name>\"} or {\"type\":\"converse\",\"target_kind\":\"player\"} - walk to that villager (or the nearest player) and talk",
                LlmGoalCatalog::converse);
        add("trade", "{\"type\":\"trade\"} - visit the village notice board to buy, sell or post requests",
                o -> new GoalDraft(new AgentGoal("trade", "trade", 5)));
        add("work", "{\"type\":\"work\"} - do a session of your own trade now; add \"job\":\"<"
                        + String.join("|", RoutineScheduler.WORK_TYPES) + ">\" for one particular part of it"
                        + " (only your own profession's jobs work; smelt also takes \"item\":\"minecraft:iron_ingot\")",
                LlmGoalCatalog::work);
        add("gift", "{\"type\":\"gift\",\"villager\":\"<name>\",\"item\":\"minecraft:<id>\",\"qty\":1} - walk to that villager and give them"
                        + " something you can spare (a present, or to help them out)",
                LlmGoalCatalog::gift);
        for (String t : new String[]{"harvest", "plant", "till", "collect_crops"}) {
            add(t, "{\"type\":\"" + t + "\",\"x\":10,\"y\":64,\"z\":20} - " + t.replace('_', ' ') + " at a farm block",
                    o -> position(o, t));
        }
        addComposite("sequence", "{\"type\":\"sequence\",\"steps\":[<goal>,<goal>,...]} - do the steps in order, without switching to other work in between (max "
                        + MAX_STEPS + " steps; a step cannot be sequence or invite)",
                LlmGoalCatalog::sequence);
        addComposite("invite", "{\"type\":\"invite\",\"villagers\":[\"<name>\"],\"title\":\"<short>\",\"message\":\"what you tell them\",\"activity\":<goal or sequence, usually with \"at\">}"
                        + " - walk to each villager and invite them; everyone who accepts and you do the activity together",
                o -> invite(o, true));
        addComposite("ask", "{\"type\":\"ask\",\"villagers\":[\"<name>\"],\"title\":\"<short>\",\"message\":\"what you ask\",\"activity\":<goal or sequence>}"
                        + " - walk to each villager and ask them to do something; only those who agree do it, not you",
                o -> invite(o, false));
    }

    private LlmGoalCatalog() {}

    private static void add(String type, String doc, Parser p) { ENTRIES.put(type, new Entry(doc, false, p)); }
    private static void addComposite(String type, String doc, Parser p) { ENTRIES.put(type, new Entry(doc, true, p)); }

    /** True if the LLM may author this goal type. */
    public static boolean isLlmGoalType(String type) { return type != null && ENTRIES.containsKey(type); }

    /** Routine-only types ({@code rest}, {@code sleep}, {@code wander}) that no LLM path may enqueue. */
    public static boolean isRuleOnly(String type) { return type != null && RULE_ONLY.contains(type); }

    /** The goal vocabulary for prompts, generated from the same table the parser uses. */
    public static String promptDocs() {
        StringBuilder sb = new StringBuilder("GOAL TYPES (each is a JSON object in the \"goals\" array):\n");
        for (Entry e : ENTRIES.values()) sb.append("  ").append(e.doc).append('\n');
        sb.append("TITLE: give every planned or timed goal (and every invite/ask) a short \"title\" a person can read, e.g."
                + " \"Dinner\", \"Evening stroll\", \"Cook meat\". Leave names out - \"with Bob\" / \"asked by Ann\" is added for you.\n");
        sb.append("TIMING (optional, on any top-level goal or on an invite/ask activity): \"at\":\"HH:MM\" (in-game clock, 24h)"
                + " or \"in_seconds\":N sets the start; the goal waits and starts at that time, ahead of ordinary work."
                + " A plan normally ends when its steps are done. Only when the point is to stay together for a while (a long"
                + " chat, a party) add an end: \"until\":\"HH:MM\" or \"for_minutes\":N (in-game minutes, max 6 hours) - you then"
                + " stay at the place until that time.\n");
        sb.append("WITH OTHERS: \"invite\" = do it together (you take part too, e.g. a meal or a walk); \"ask\" = ask someone to"
                + " do something for you (only they do it, e.g. cook or craft). The activity is whatever steps make sense: meeting"
                + " up only happens if it starts by walking to a place (\"move\" to coordinates you know, e.g. your location);"
                + " then whoever arrives first waits for the others. For a meal use \"eat\" (not \"socialize\"); for a walk"
                + " use \"stroll\".\n");
        sb.append("Example - dinner with Bob: {\"type\":\"invite\",\"villagers\":[\"Bob\"],\"title\":\"Dinner\",\"message\":\"Join me for dinner at six?\","
                + "\"activity\":{\"type\":\"sequence\",\"at\":\"18:00\",\"steps\":[{\"type\":\"move\",\"x\":10,\"y\":64,\"z\":20},"
                + "{\"type\":\"eat\"},{\"type\":\"socialize\"}]}}\n");
        sb.append("Example - ask the butcher Ann to cook in an hour: {\"type\":\"ask\",\"villagers\":[\"Ann\"],\"title\":\"Cook meat\","
                + "\"message\":\"Could you cook the meat in an hour?\",\"activity\":{\"type\":\"cook\",\"in_seconds\":50}}\n");
        sb.append("Sleeping and idle wandering are automatic - never add goals for them.\n");
        sb.append("Use exact minecraft:<id> for items; \"qty\" defaults to 1. Only add goals you genuinely want to pursue. Empty array [] is fine.\n\n");
        return sb.toString();
    }

    /** Parse one top-level goal object. Returns null (and logs) for anything not in the catalog. */
    public static GoalDraft parse(JsonObject o) { return parse(o, true, true); }

    private static GoalDraft parse(JsonObject o, boolean allowSequence, boolean allowInvite) {
        String type = str(o, "type");
        if (type == null) return null;
        type = type.toLowerCase(Locale.ROOT).trim();
        if (RULE_ONLY.contains(type)) {
            LOGGER.debug("LLM asked for routine-only goal '{}' - ignored", type);
            return null;
        }
        type = ALIASES.getOrDefault(type, type);
        if (RoutineScheduler.isWork(type)) type = "work"; // {"type":"fish"} = {"type":"work","job":"fish"}
        Entry e = ENTRIES.get(type);
        if (e == null) { LOGGER.debug("Unknown LLM goal type '{}'", type); return null; }
        boolean inviting = "invite".equals(type) || "ask".equals(type);
        if (("sequence".equals(type) && !allowSequence) || (inviting && !allowInvite)) {
            LOGGER.debug("Goal type '{}' not allowed at this nesting level", type);
            return null;
        }
        GoalDraft d = e.parser.parse(o);
        if (d == null) return null;
        d.goal.setImportance(getInt(o, "importance", 6));
        d.goal.setSource(AgentGoal.Source.LLM);
        d.at = str(o, "at");
        d.inSeconds = getInt(o, "in_seconds", -1);
        d.until = str(o, "until");
        d.forMinutes = getInt(o, "for_minutes", -1);
        d.title = str(o, "title");
        if (allowSequence && (d.until != null || d.forMinutes >= 0) && !e.composite) return asActivity(d);
        return d;
    }

    /**
     * A single goal with an end time becomes a one-step sequence carrying the timing, so it gets the
     * activity behaviour (hold together, linger until the end) that {@code SequenceSkill} provides.
     */
    private static GoalDraft asActivity(GoalDraft single) {
        GoalDraft seq = new GoalDraft(new AgentGoal("sequence", "sequence", 5));
        seq.steps.add(single);
        seq.at = single.at; seq.inSeconds = single.inSeconds; seq.until = single.until; seq.forMinutes = single.forMinutes;
        single.at = null; single.inSeconds = -1; single.until = null; single.forMinutes = -1;
        seq.goal.setImportance(single.goal.getImportance());
        seq.title = single.title;
        single.title = null;
        seq.goal.setSource(AgentGoal.Source.LLM);
        return seq;
    }

    // ── Entry parsers ───────────────────────────────────────────────────────

    private static GoalDraft item(JsonObject o, String type) {
        String item = str(o, "item");
        if (item == null) return null;
        int qty = getInt(o, "qty", 1);
        if (qty <= 0) return null;
        AgentGoal g = new AgentGoal(type, type + " " + item + (qty > 1 ? " x" + qty : ""), 5);
        g.setTargetItem(item);
        g.setTargetQuantity(qty);
        if ("pickup_item".equals(type)) {
            String entityId = str(o, "entity_id");
            if (entityId != null) {
                try { g.setTargetEntityId(UUID.fromString(entityId)); } catch (IllegalArgumentException ignored) {}
            }
        }
        return new GoalDraft(g);
    }

    /** work: the villager's own trade ("work"), or one named job of it; the validator checks it is theirs. */
    private static GoalDraft work(JsonObject o) {
        String job = str(o, "job");
        String type = str(o, "type");
        if (job == null && type != null && !"work".equalsIgnoreCase(type.trim())) job = type;
        if (job == null) return new GoalDraft(new AgentGoal("work", "work at my trade", 5));
        job = job.toLowerCase(Locale.ROOT).trim();
        if ("cook".equals(job)) job = "butcher_cook";
        if (!RoutineScheduler.isWork(job)) return null;
        AgentGoal g = new AgentGoal(job, job.replace('_', ' '), 5);
        String item = str(o, "item");
        if ("smelt".equals(job) && item != null) {
            g.setTargetItem(item);
            g.setTargetQuantity(Math.max(1, getInt(o, "qty", 1)));
            g.setDescription("smelt " + item);
        }
        return new GoalDraft(g);
    }

    /** gift: an item goal whose target must resolve to a known villager. */
    private static GoalDraft gift(JsonObject o) {
        String villager = str(o, "villager");
        GoalDraft d = villager == null ? null : item(o, "gift");
        if (d == null) return null;
        d.targetName = villager;
        d.targetMustBeVillager = true;
        return d;
    }

    private static GoalDraft position(JsonObject o, String type) {
        int x = getInt(o, "x", Integer.MIN_VALUE), y = getInt(o, "y", Integer.MIN_VALUE), z = getInt(o, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) return null;
        AgentGoal g = new AgentGoal(type, type + " to " + x + "," + y + "," + z, 5);
        g.setTargetLocation(new BlockPos(x, y, z));
        return new GoalDraft(g);
    }

    /** place_block / place_near_self, built by the same constructor the TODO: protocol uses. */
    private static GoalDraft place(JsonObject o) {
        boolean hasPos = o.has("x") && o.has("y") && o.has("z");
        AgentGoal g = GoalParser.placeGoal(str(o, "item"),
                hasPos ? String.valueOf(getInt(o, "x", 0)) : null,
                hasPos ? String.valueOf(getInt(o, "y", 0)) : null,
                hasPos ? String.valueOf(getInt(o, "z", 0)) : null);
        return g == null ? null : new GoalDraft(g);
    }

    private static GoalDraft converse(JsonObject o) {
        String villager = str(o, "villager");
        AgentGoal g;
        if (villager != null) {
            g = new AgentGoal("converse", "converse with " + villager, 5);
            g.setTargetKind("villager");
            GoalDraft d = new GoalDraft(g);
            d.targetName = villager;
            d.targetMustBeVillager = true;
            return d;
        }
        String kind = str(o, "target_kind");
        if (kind == null) return null;
        kind = kind.toLowerCase(Locale.ROOT);
        if (!kind.equals("player") && !kind.equals("villager")) return null;
        g = new AgentGoal("converse", "converse with a " + kind, 5);
        g.setTargetKind(kind);
        GoalDraft d = new GoalDraft(g);
        String tid = str(o, "target_id");
        if (tid != null && !tid.equalsIgnoreCase("any")) {
            try { g.setTargetEntityId(UUID.fromString(tid)); }
            catch (IllegalArgumentException notUuid) { if (kind.equals("villager")) d.targetName = tid; }
        }
        return d;
    }

    private static GoalDraft sequence(JsonObject o) {
        JsonArray arr = o.has("steps") && o.get("steps").isJsonArray() ? o.getAsJsonArray("steps") : null;
        if (arr == null || arr.size() == 0) return null;
        GoalDraft d = new GoalDraft(new AgentGoal("sequence", "sequence", 5));
        for (JsonElement el : arr) {
            if (d.steps.size() >= MAX_STEPS) break;
            if (!el.isJsonObject()) continue;
            GoalDraft step = parse(el.getAsJsonObject(), false, false);
            if (step == null) return null; // a plan with an unreadable step is not the plan the LLM meant
            d.steps.add(step);
        }
        return d.steps.isEmpty() ? null : d;
    }

    /** invite (together) and ask (only they do it) share one shape and one skill; {@code together} tells them apart. */
    private static GoalDraft invite(JsonObject o, boolean together) {
        GoalDraft d = new GoalDraft(new AgentGoal("invite", together ? "invite" : "ask", 5));
        d.together = together;
        if (o.has("villagers") && o.get("villagers").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("villagers")) {
                if (el.isJsonPrimitive()) d.inviteeNames.add(el.getAsString());
            }
        }
        String single = str(o, "villager");
        if (single != null) d.inviteeNames.add(single);
        if (d.inviteeNames.isEmpty()) return null;
        if (!o.has("activity") || !o.get("activity").isJsonObject()) return null;
        d.plan = parse(o.getAsJsonObject("activity"), true, false);
        if (d.plan == null) return null;
        d.goal.setInviteMessage(str(o, "message"));
        return d;
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    static String str(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    static int getInt(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return def; }
    }
}
