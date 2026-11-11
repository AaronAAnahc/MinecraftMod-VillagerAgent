package com.github.AaronAA0721.villageragent.ai;

/**
 * Shared prompt fragments for all LLM call sites. Every path (chat, thought, dawn reflection)
 * uses the same JSON response schema and goal-type documentation so there is exactly one
 * place to add a new goal type or tweak wording.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    // ── Goal type documentation ─────────────────────────────────────────────

    /**
     * Complete documentation of every goal type and its parameters, generated from
     * {@link LlmGoalCatalog} (the same table the parser reads), shared across all prompt paths.
     */
    public static final String GOAL_TYPE_DOCS = LlmGoalCatalog.promptDocs();

    // ── Memory type documentation ───────────────────────────────────────────

    public static final String MEMORY_TYPE_DOCS =
            "MEMORY TYPES (each is a JSON object in the \"memories\" array):\n"
            + "  {\"kind\":\"debt\", \"player\":\"<uuid>\", \"name\":\"Steve\", \"item\":\"minecraft:emerald\", \"qty\":64, \"reason\":\"for pickaxes\"}\n"
            + "  {\"kind\":\"acquire\", \"item\":\"minecraft:iron_ingot\", \"qty\":3, \"source\":\"craft\"}\n"
            + "  {\"kind\":\"deal\", \"with\":\"<uuid>\", \"name\":\"Blacksmith\", \"want\":\"minecraft:bread\", \"want_qty\":5, \"offer\":\"minecraft:emerald\", \"offer_qty\":2}\n"
            + "    (deal: \"want\" = what THEY get from you, \"offer\" = what YOU get; exactly ONE item id in each)\n"
            + "  {\"kind\":\"generic\", \"title\":\"watch for raiders\", \"notes\":\"they came from the west\"}\n"
            + "Only add memories for commitments or important facts worth remembering across conversations. Empty array [] is fine.\n\n";

    // ── Unified JSON response format ────────────────────────────────────────

    /**
     * The unified JSON response format instruction. All LLM calls must include this.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code reply} — spoken text the player sees (chat only; null/omit for non-chat)</li>
     *   <li>{@code thought} — a short first-person internal thought (always present)</li>
     *   <li>{@code goals} — array of actions to perform (may be empty)</li>
     *   <li>{@code memories} — array of things to remember long-term (may be empty)</li>
     * </ul>
     */
    public static final String JSON_SCHEMA_INSTRUCTION =
            "\n\nYou MUST respond with ONLY a JSON object in this exact format (no extra text):\n"
            + "{\n"
            + "  \"reply\": \"Spoken response the player sees (ONLY when replying to a player; omit or set null otherwise)\",\n"
            + "  \"thought\": \"A short first-person internal thought reflecting what you are thinking right now (always required, 1 sentence)\",\n"
            + "  \"goals\": [],\n"
            + "  \"memories\": []\n"
            + "}\n\n"
            + GOAL_TYPE_DOCS
            + MEMORY_TYPE_DOCS
            + "Output ONLY the JSON object. No markdown fences, no explanation, no extra text.\n";

    // ── Plain-text fallback (models without JSON output) ────────────────────

    /**
     * Line protocol understood by {@code GoalParser} (TODO:) and {@code AgendaParser} (REMEMBER:). Used only
     * when the configured model can't produce JSON (see {@link LLMService#jsonOutputSupported()}).
     */
    public static final String TEXT_PROTOCOL_INSTRUCTION =
            "\n\nRESPONSE FORMAT (plain text, NOT JSON):\n"
            + "First write what you say (or, when you are not talking to anyone, your one-sentence first-person thought).\n"
            + "Then, only if you want to act, add one directive per line, each on its own line:\n"
            + "  TODO: craft minecraft:<id> x1\n"
            + "  TODO: move <x> <y> <z>\n"
            + "  TODO: goto <house|cave_house|building|any>\n"
            + "  TODO: socialize\n"
            + "  TODO: trade villager <profession|any>\n"
            + "  TODO: converse <player|villager> [<uuid>|<profession>]\n"
            + "  TODO: harvest|plant|till|collect_crops <x> <y> <z>\n"
            + "  TODO: place minecraft:<block id> [<x> <y> <z>]   (no coordinates = right next to you)\n"
            + "  TODO: drop_item minecraft:<id> x1\n"
            + "  TODO: pickup_item minecraft:<id> x1\n"
            + "To remember a commitment long-term:\n"
            + "  REMEMBER: debt player=<uuid> name=<name> item=minecraft:<id> qty=<n> reason=<words>\n"
            + "  REMEMBER: acquire item=minecraft:<id> qty=<n> source=<craft|trade>\n"
            + "  REMEMBER: deal with=<uuid> name=<name> want=minecraft:<id> wantqty=<n> offer=minecraft:<id> offerqty=<n>\n"
            + "  REMEMBER: generic title=<words> notes=<words>\n"
            + "Use exact minecraft:<id> for items. No directives is fine. Never put directives inside your spoken words.\n";

    /** The response-format block every structured prompt ends with: JSON when supported, else the text protocol. */
    public static String responseFormatInstruction() {
        return LLMService.jsonOutputSupported() ? JSON_SCHEMA_INSTRUCTION : TEXT_PROTOCOL_INSTRUCTION;
    }

    /**
     * Remove legacy directive markers ("TODO:", "REMEMBER:", "GOAL:") from text that is fed back into a
     * prompt (memories, goal descriptions, chat history from older saves). Models copy whatever format
     * they see in their context, so stale directive lines pull them away from the JSON format.
     */
    public static String scrubDirectives(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?i)\\b(TODO|REMEMBER|GOAL):\\s*", "").trim();
    }
}
