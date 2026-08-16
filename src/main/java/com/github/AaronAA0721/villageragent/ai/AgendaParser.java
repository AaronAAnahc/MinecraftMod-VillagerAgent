package com.github.AaronAA0721.villageragent.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses structured REMEMBER: directives out of an LLM reply so a villager can turn
 * conversation (or reflection) into persistent long-term intentions.
 *
 * <p>Directive format (one per line, case-insensitive keyword):
 * <pre>
 *   REMEMBER: debt player=&lt;uuid&gt; name=Steve item=minecraft:emerald qty=64 reason=for pickaxes
 *   REMEMBER: acquire item=minecraft:iron_ingot qty=3 source=craft
 *   REMEMBER: deal with=&lt;uuid&gt; name=Blacksmith want=minecraft:bread wantqty=5 offer=minecraft:emerald offerqty=2
 *   REMEMBER: generic title=watch for raiders notes=they came from the west
 *   REMEMBER: update &lt;id&gt; notes=... key=value ...
 *   REMEMBER: done &lt;id&gt;
 * </pre>
 * Unknown kinds fall back to {@link GenericAgenda}; leftover key=value pairs are stored as
 * free-form fields, so the LLM can record arbitrary long-term things without a new subclass.
 */
public final class AgendaParser {

    private AgendaParser() {}

    /**
     * Parse REMEMBER: lines into new {@link LongTermAgenda}s. {@code update} / {@code done}
     * operate on the agent's existing agendas in place (not returned).
     */
    public static List<LongTermAgenda> parse(String text, VillagerAgentData agent, long gameTick) {
        List<LongTermAgenda> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.toUpperCase().startsWith("REMEMBER:")) continue;
            String body = line.substring("REMEMBER:".length()).trim();
            if (body.isEmpty()) continue;

            String[] tok = body.split("\\s+");
            String first = tok[0].toLowerCase();

            if (first.equals("done") && tok.length >= 2) {
                resolveById(agent, tok[1], gameTick);
                continue;
            }
            if (first.equals("update") && tok.length >= 3) {
                updateById(agent, tok[1],
                        parseKeyValues(Arrays.copyOfRange(tok, 2, tok.length)), gameTick);
                continue;
            }

            // Otherwise: first token is the kind, the rest are key=value / loose words.
            Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(tok, 1, tok.length));
            LongTermAgenda agenda = buildAgenda(first, kv, gameTick);
            if (agenda != null) out.add(agenda);
        }
        return out;
    }

    /** Remove REMEMBER: lines so the player only sees the spoken reply. */
    public static String stripRememberLines(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.trim().toUpperCase().startsWith("REMEMBER:")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }

    // ── Internals ──

    private static void resolveById(VillagerAgentData agent, String id, long tick) {
        for (LongTermAgenda a : agent.getAgendas()) {
            if (a.getId().equals(id)) {
                a.setResolved(true);
                a.touch(tick);
                return;
            }
        }
    }

    private static void updateById(VillagerAgentData agent, String id, Map<String, String> kv, long tick) {
        for (LongTermAgenda a : agent.getAgendas()) {
            if (a.getId().equals(id)) {
                a.applyUpdate(kv, tick);
                return;
            }
        }
    }

    private static Map<String, String> parseKeyValues(String[] tokens) {
        Map<String, String> kv = new LinkedHashMap<>();
        StringBuilder loose = new StringBuilder();
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                kv.put(t.substring(0, eq).toLowerCase(), t.substring(eq + 1));
            } else {
                if (loose.length() > 0) loose.append(' ');
                loose.append(t);
            }
        }
        if (loose.length() > 0) kv.put("__loose__", loose.toString());
        return kv;
    }

    private static LongTermAgenda buildAgenda(String kind, Map<String, String> kv, long tick) {
        switch (kind) {
            case "debt": {
                DebtAgenda d = new DebtAgenda("", tick);
                d.setDebtorId(kv.get("player"));
                d.setDebtorName(kv.get("name"));
                d.setItemId(kv.get("item"));
                d.setQuantity(parseInt(kv.get("qty"), 1));
                d.setReason(kv.get("reason"));
                String who = d.getDebtorName() != null ? d.getDebtorName()
                        : (d.getDebtorId() != null ? d.getDebtorId() : "someone");
                d.setTitle(titleOf(kv, "Collect " + d.getQuantity() + "x "
                        + LongTermAgenda.shortName(d.getItemId()) + " from " + who));
                return d;
            }
            case "acquire": {
                AcquireItemAgenda a = new AcquireItemAgenda("", tick);
                a.setItemId(kv.get("item"));
                a.setQuantity(parseInt(kv.get("qty"), 1));
                a.setSource(kv.get("source"));
                a.setTitle(titleOf(kv, "Acquire " + a.getQuantity() + "x "
                        + LongTermAgenda.shortName(a.getItemId())));
                return a;
            }
            case "deal": {
                DealAgenda d = new DealAgenda("", tick);
                d.setCounterpartyId(kv.get("with"));
                d.setCounterpartyName(kv.get("name"));
                d.setWantedItem(kv.get("want"));
                d.setWantedQuantity(parseInt(kv.get("wantqty"), 1));
                d.setOfferedItem(kv.get("offer"));
                d.setOfferedQuantity(parseInt(kv.get("offerqty"), 1));
                String who = d.getCounterpartyName() != null ? d.getCounterpartyName() : "a villager";
                d.setTitle(titleOf(kv, "Trade with " + who));
                return d;
            }
            default: {
                GenericAgenda g = new GenericAgenda("", tick);
                String loose = kv.get("__loose__");
                String title = kv.containsKey("title") ? kv.get("title")
                        : (loose != null ? loose : kind);
                g.setTitle(title);
                g.setNotes(kv.get("notes"));
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    String k = e.getKey();
                    if (k.equals("title") || k.equals("notes") || k.equals("__loose__")) continue;
                    g.putField(k, e.getValue());
                }
                return g;
            }
        }
    }

    private static String titleOf(Map<String, String> kv, String fallback) {
        if (kv.containsKey("title")) return kv.get("title");
        String loose = kv.get("__loose__");
        return (loose != null && !loose.isEmpty()) ? loose : fallback;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
