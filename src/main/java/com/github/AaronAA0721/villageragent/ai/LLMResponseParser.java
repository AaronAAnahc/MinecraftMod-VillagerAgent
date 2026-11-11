package com.github.AaronAA0721.villageragent.ai;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses structured JSON responses from the LLM. Goal objects are read by {@link LlmGoalCatalog}
 * (the same table that documents them in the prompt) into {@link GoalDraft}s, which callers ground
 * and enqueue through {@link LlmGoalIntake}. The expected schema is:
 * <pre>{@code
 * {
 *   "reply": "Spoken text the player sees",
 *   "goals": [
 *     { "type": "craft", "item": "minecraft:iron_pickaxe", "qty": 2 },
 *     { "type": "move", "x": 120, "y": 64, "z": 200 },
 *     { "type": "goto", "building": "house" },
 *     { "type": "socialize" },
 *     { "type": "trade", "target_kind": "villager", "target_profession": "farmer" },
 *     { "type": "converse", "target_kind": "player", "target_id": "<uuid>" }
 *   ],
 *   "memories": [
 *     { "kind": "debt", "player": "<uuid>", "name": "Steve", "item": "minecraft:emerald", "qty": 64, "reason": "for pickaxes" },
 *     { "kind": "generic", "title": "watch for raiders", "notes": "they came from the west" }
 *   ]
 * }
 * }</pre>
 *
 * <p>JSON is the normal format. The plain-text TODO: / REMEMBER: line protocol is only requested when the
 * configured model can't produce JSON ({@link LLMService#jsonOutputSupported()}); it is still parsed here as
 * a fallback. ({@link LLMService} itself checks JSON-requested replies to detect models that ignore JSON.)
 */
public final class LLMResponseParser {

    private static final Logger LOGGER = LogManager.getLogger("LLMResponseParser");
    private static final Gson GSON = new GsonBuilder().create();

    private LLMResponseParser() {}

    /**
     * Parsed result of an LLM response.
     */
    public static final class ParsedResponse {
        /** The spoken reply visible to the player. Never null (may be empty). */
        public final String reply;
        /** Internal thought (first-person, 1 sentence). Never null (may be empty). */
        public final String thought;
        /** Goals extracted from the response (parsed, not yet bound to the world or validated). */
        public final List<GoalDraft> goals;
        /** Long-term agendas extracted from the response. */
        public final List<LongTermAgenda> agendas;
        /** True if the response was parsed as JSON; false if fallback to legacy prefix lines. */
        public final boolean jsonParsed;
        /** Chat only: a trade the villager offers the player ({@code "trade"} field), unchecked; null if none. */
        public TradeProposal.Draft trade;
        /** Chat only: candidate trades the villager asks the system to evaluate before replying ({@code "check_trade"}). */
        public List<TradeProposal.Draft> checks = Collections.emptyList();

        ParsedResponse(String reply, String thought, List<GoalDraft> goals,
                        List<LongTermAgenda> agendas, boolean jsonParsed) {
            this.reply = reply != null ? reply : "";
            this.thought = thought != null ? thought : "";
            this.goals = goals != null ? goals : Collections.emptyList();
            this.agendas = agendas != null ? agendas : Collections.emptyList();
            this.jsonParsed = jsonParsed;
        }
    }

    /**
     * Parse an LLM response string. Tries JSON first; a malformed/truncated JSON reply keeps only its text
     * fields; plain text is parsed with the TODO:/REMEMBER: line protocol.
     */
    public static ParsedResponse parse(String response, VillagerAgentData agent, long gameTick) {
        if (response == null || response.isEmpty()) {
            return new ParsedResponse("", "", Collections.emptyList(), Collections.emptyList(), false);
        }

        // Try JSON. Models may wrap it in code fences or add a sentence around it, so take the
        // outermost {...} object rather than requiring the reply to start with '{'.
        JsonObject root = extractJsonObject(response);
        if (root != null) return parseJson(root, agent, gameTick);

        // Truncated / malformed JSON: never show raw JSON to the player. Salvage the text fields.
        if (looksLikeJson(response)) {
            LOGGER.warn("LLM returned malformed JSON (likely cut off by llm_max_tokens); salvaged text only");
            return new ParsedResponse(salvageField(response, "reply"), salvageField(response, "thought"),
                    Collections.emptyList(), Collections.emptyList(), false);
        }

        // Plain text: the TODO:/REMEMBER: line protocol (the format for models without JSON output).
        return parseLegacy(response, agent, gameTick);
    }

    // ── JSON parsing ────────────────────────────────────────────────────────

    private static ParsedResponse parseJson(JsonObject root, VillagerAgentData agent, long gameTick) {
        // Reply text
        String reply = "";
        if (root.has("reply") && root.get("reply").isJsonPrimitive()) {
            reply = root.get("reply").getAsString();
        }

        // Thought text
        String thought = "";
        if (root.has("thought") && root.get("thought").isJsonPrimitive()) {
            thought = root.get("thought").getAsString();
        }

        // Goals array
        List<GoalDraft> goals = new ArrayList<>();
        if (root.has("goals") && root.get("goals").isJsonArray()) {
            for (JsonElement elem : root.getAsJsonArray("goals")) {
                if (!elem.isJsonObject()) continue;
                GoalDraft g = LlmGoalCatalog.parse(elem.getAsJsonObject());
                if (g != null) goals.add(g);
            }
        }

        // Memories array
        List<LongTermAgenda> agendas = new ArrayList<>();
        if (root.has("memories") && root.get("memories").isJsonArray()) {
            for (JsonElement elem : root.getAsJsonArray("memories")) {
                if (!elem.isJsonObject()) continue;
                LongTermAgenda a = parseMemoryObject(elem.getAsJsonObject(), gameTick);
                if (a != null) agendas.add(a);
            }
        }

        LOGGER.debug("JSON parse: reply={} chars, thought={} chars, {} goals, {} memories",
                reply.length(), thought.length(), goals.size(), agendas.size());
        ParsedResponse parsed = new ParsedResponse(reply, thought, goals, agendas, true);
        if (root.has("trade") && root.get("trade").isJsonObject()) parsed.trade = parseTrade(root.getAsJsonObject("trade"));
        if (root.has("check_trade")) {
            List<TradeProposal.Draft> checks = new ArrayList<>();
            JsonElement c = root.get("check_trade");
            List<JsonElement> items = new ArrayList<>();
            if (c.isJsonArray()) for (JsonElement e : c.getAsJsonArray()) items.add(e); else items.add(c);
            for (JsonElement e : items) {
                if (!e.isJsonObject() || checks.size() >= 4) continue;
                TradeProposal.Draft d = parseTrade(e.getAsJsonObject());
                if (d != null) checks.add(d);
            }
            parsed.checks = checks;
        }
        return parsed;
    }

    /**
     * {@code "trade": {"player_gives":[{"item":"minecraft:iron_ingot","qty":2}], "player_gets":[{"item":"minecraft:chicken","qty":1}]}}.
     * Also accepts a single object instead of an array, and "count" for "qty". Null when both sides are empty.
     */
    static TradeProposal.Draft parseTrade(JsonObject obj) {
        TradeProposal.Draft d = new TradeProposal.Draft();
        readLines(obj.get("player_gives"), d.playerGives);
        readLines(obj.get("player_gets"), d.playerGets);
        return d.isEmpty() ? null : d;
    }

    private static void readLines(JsonElement el, List<TradeProposal.Line> out) {
        if (el == null || el.isJsonNull()) return;
        List<JsonElement> items = new ArrayList<>();
        if (el.isJsonArray()) for (JsonElement e : el.getAsJsonArray()) items.add(e);
        else items.add(el);
        for (JsonElement e : items) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String item = getStr(o, "item");
            int qty = getInt(o, "qty", getInt(o, "count", 1));
            if (item != null) out.add(new TradeProposal.Line(item, qty));
        }
    }

    private static LongTermAgenda parseMemoryObject(JsonObject obj, long gameTick) {
        String kind = getStr(obj, "kind");
        if (kind == null) kind = "generic";
        kind = kind.toLowerCase();

        switch (kind) {
            case "debt": {
                DebtAgenda d = new DebtAgenda("", gameTick);
                d.setDebtorId(getStr(obj, "player"));
                d.setDebtorName(getStr(obj, "name"));
                d.setItemId(getStr(obj, "item"));
                d.setQuantity(getInt(obj, "qty", 1));
                d.setReason(getStr(obj, "reason"));
                String who = d.getDebtorName() != null ? d.getDebtorName() : "someone";
                d.setTitle("Collect " + d.getQuantity() + "x " + LongTermAgenda.shortName(d.getItemId()) + " from " + who);
                return d;
            }
            case "acquire": {
                AcquireItemAgenda a = new AcquireItemAgenda("", gameTick);
                a.setItemId(getStr(obj, "item"));
                a.setQuantity(getInt(obj, "qty", 1));
                a.setSource(getStr(obj, "source"));
                a.setTitle("Acquire " + a.getQuantity() + "x " + LongTermAgenda.shortName(a.getItemId()));
                return a;
            }
            case "deal": {
                DealAgenda d = new DealAgenda("", gameTick);
                d.setCounterpartyId(getStr(obj, "with"));
                d.setCounterpartyName(getStr(obj, "name"));
                d.setWantedItem(getStr(obj, "want"));
                d.setWantedQuantity(getInt(obj, "want_qty", 1));
                d.setOfferedItem(getStr(obj, "offer"));
                d.setOfferedQuantity(getInt(obj, "offer_qty", 1));
                String who = d.getCounterpartyName() != null ? d.getCounterpartyName() : "a villager";
                d.setTitle("Trade with " + who);
                return d;
            }
            default: {
                GenericAgenda g = new GenericAgenda("", gameTick);
                g.setTitle(getStr(obj, "title") != null ? getStr(obj, "title") : kind);
                g.setNotes(getStr(obj, "notes"));
                return g;
            }
        }
    }

    // ── Legacy fallback ─────────────────────────────────────────────────────

    private static ParsedResponse parseLegacy(String response, VillagerAgentData agent, long gameTick) {
        List<GoalDraft> goals = new ArrayList<>();
        for (AgentGoal g : GoalParser.parse(response, agent)) goals.add(GoalDraft.of(g));
        List<LongTermAgenda> agendas = AgendaParser.parse(response, agent, gameTick);
        String cleaned = GoalParser.stripTodoLines(response);
        cleaned = AgendaParser.stripRememberLines(cleaned);
        // In legacy mode, the cleaned text serves as both reply and thought.
        return new ParsedResponse(cleaned, cleaned, goals, agendas, false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Side-effect-free: the words a villager actually said in {@code response} (JSON {@code reply}, or the
     * text with directive lines removed). Used to store chat history without raw JSON / directives.
     */
    public static String spokenText(String response) {
        if (response == null) return "";
        JsonObject root = extractJsonObject(response);
        if (root != null) {
            if (root.has("reply") && root.get("reply").isJsonPrimitive()) return root.get("reply").getAsString();
            return "";
        }
        if (looksLikeJson(response)) return salvageField(response, "reply");
        return AgendaParser.stripRememberLines(GoalParser.stripTodoLines(response));
    }

    /** The outermost JSON object in {@code text} (code fences / surrounding prose tolerated), or null. */
    static JsonObject extractJsonObject(String text) {
        if (text == null) return null;
        String t = stripCodeFences(text.trim());
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonElement e = new JsonParser().parse(t.substring(start, end + 1));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static boolean looksLikeJson(String text) {
        String t = stripCodeFences(text.trim());
        return t.startsWith("{") || t.contains("\"reply\"") || t.contains("\"thought\"");
    }

    private static final java.util.regex.Pattern FIELD_HEAD = java.util.regex.Pattern.compile("\"(reply|thought)\"\\s*:\\s*\"");

    /** Best-effort read of a string field from broken JSON: {@code "key": "value...} up to the closing quote. */
    private static String salvageField(String text, String key) {
        java.util.regex.Matcher m = FIELD_HEAD.matcher(text);
        while (m.find()) {
            if (!m.group(1).equals(key)) continue;
            StringBuilder out = new StringBuilder();
            for (int i = m.end(); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    char n = text.charAt(++i);
                    out.append(n == 'n' ? ' ' : n == 't' ? ' ' : n);
                } else if (c == '"') {
                    break;
                } else {
                    out.append(c);
                }
            }
            return out.toString().trim();
        }
        return "";
    }

    /** Strip markdown code fences (```json ... ```) that models often wrap around JSON. */
    private static String stripCodeFences(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3).trim();
        }
        return s.trim();
    }

    private static String getStr(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return def; }
    }
}
