package com.github.AaronAA0721.villageragent.ai.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * The structured decision the LLM must emit (plan: 技术力提升计划书 §1.1 — {@code DecisionSchema}).
 *
 * <pre>{@code {"action":"CRAFT", "target":"minecraft:iron_pickaxe", "rationale":"I need a pickaxe to mine"} }</pre>
 *
 * <p>The vocabulary is the harness's OWN high-level decision set, deliberately decoupled from
 * {@code VillagerAction.ActionType} — the LLM only picks the brain-level decision; the existing
 * state machine still owns the details of execution. {@link #parse(String)} is lenient: it strips
 * markdown code fences and extracts the first JSON object, so a chatty model can't break it.
 */
public class DecisionSchema {

    /** High-level decisions the harness allows the LLM to make. */
    public enum DecisionAction {
        CRAFT, GATHER, MOVE, SOCIALIZE, TRADE, REST, IDLE, FLEE, DEFEND, HARVEST, GROW, BUILD, UNKNOWN
    }

    public String action;     // raw action token from the LLM
    public String target;     // optional parameter (item id, "x y z", ...)
    public String rationale;  // optional one-line reason

    /** Parse a lenient JSON decision from arbitrary LLM text. Returns null if unparseable. */
    public static DecisionSchema parse(String text) {
        if (text == null) return null;
        String json = extractJson(text);
        if (json == null) return null;
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            DecisionSchema s = new DecisionSchema();
            s.action = o.has("action") ? o.get("action").getAsString() : null;
            s.target = o.has("target") ? o.get("target").getAsString() : "";
            if (s.target == null) s.target = "";
            s.rationale = o.has("rationale") ? o.get("rationale").getAsString() : "";
            if (s.rationale == null) s.rationale = "";
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJson(String text) {
        String t = text.trim();
        // Strip a ```json ... ``` fence if present.
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
            t = t.trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return t.substring(start, end + 1);
    }

    /** Upper-cased, trimmed action token (empty string if absent). */
    public String normalizedAction() {
        return action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
    }

    /** The canonical decision, or {@link DecisionAction#UNKNOWN} if not recognised. */
    public DecisionAction decisionAction() {
        try {
            return DecisionAction.valueOf(normalizedAction());
        } catch (Exception e) {
            return DecisionAction.UNKNOWN;
        }
    }

    /** Whether the action is one the harness understands (anything other than UNKNOWN). */
    public boolean isKnown() {
        return decisionAction() != DecisionAction.UNKNOWN;
    }

    @Override
    public String toString() {
        return "Decision(action=" + action + ", target=" + target + ", rationale=" + rationale + ")";
    }
}
