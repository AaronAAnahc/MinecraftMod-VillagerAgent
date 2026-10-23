package com.github.AaronAA0721.villageragent.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // LLM Settings
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_TYPE;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_MODEL;
    public static final ForgeConfigSpec.IntValue LLM_MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue LLM_TEMPERATURE;
    /** Verbose LLM I/O debug logging (request/response dumps, prompts). Off by default. */
    public static final ForgeConfigSpec.BooleanValue LLM_DEBUG;

    // Agent Behavior Settings
    public static final ForgeConfigSpec.BooleanValue ENABLE_AI_AGENTS;
    public static final ForgeConfigSpec.IntValue AGENT_THINK_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VILLAGER_CHAT;
    public static final ForgeConfigSpec.IntValue GREETING_SCAN_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue GREETING_BASE_PROBABILITY;
    public static final ForgeConfigSpec.DoubleValue GREETING_FAMILIARITY_DECAY;
    public static final ForgeConfigSpec.DoubleValue GREETING_TARGET_DILUTION;
    public static final ForgeConfigSpec.BooleanValue ENABLE_WORLD_INTERACTION;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BUILDING;
    public static final ForgeConfigSpec.DoubleValue BUILD_INTERACT_RANGE_SQ;
    public static final ForgeConfigSpec.IntValue BUILD_BLOCK_INTERVAL;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue BUILD_STUCK_TIMEOUT;
    public static final ForgeConfigSpec.BooleanValue ENABLE_AUTO_PICKUP;
    public static final ForgeConfigSpec.IntValue VILLAGER_PICKUP_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DAILY_SCHEDULE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VILLAGER_SOCIAL;
    /** Ambient inner-thought bubbles ('[Name thinks]') broadcast to nearby players. OFF by default. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_VILLAGER_THOUGHTS;

    // ── Decision Harness (plan: 技术力提升计划书 Module 1) ──
    /** Master switch for the whole harness (resilience + structured LLM decisions). */
    public static final ForgeConfigSpec.BooleanValue HARNESS_ENABLED;
    /** Per-LLM-call timeout in ms. On timeout the villager falls back to rule-based behaviour. */
    public static final ForgeConfigSpec.IntValue HARNESS_TIMEOUT_MS;
    /** Consecutive LLM failures before a villager enters rule-only mode for a cooldown. */
    public static final ForgeConfigSpec.IntValue HARNESS_CIRCUIT_FAILURES;
    /** Ticks a villager stays in rule-only mode after the circuit breaker trips. */
    public static final ForgeConfigSpec.IntValue HARNESS_CIRCUIT_COOLDOWN_TICKS;
    /** Write a JSONL decision journal (replay / eval / debugging). */
    public static final ForgeConfigSpec.BooleanValue HARNESS_JOURNAL_ENABLED;
    /** When true the harness also decides one structured daily goal per villager at dawn (P1). */
    public static final ForgeConfigSpec.BooleanValue HARNESS_DRIVE_DAILY_GOAL;

    // ── Debug overlay (visualization debugging) ──
    /** Master switch: when true the server streams perception/agent debug snapshots to clients. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_OVERLAY;
    /** Radius (blocks) around the player in which building boxes are drawn. */
    public static final ForgeConfigSpec.IntValue DEBUG_RENDER_RANGE;
    /** Client render toggles (also effective client-side). */
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_HUD;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_BUILDINGS;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_SEEDS;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_FIELD;

    // ── Proficiency (villager experience / level system) ──
    /** Mirror proficiency level onto vanilla VillagerData.level so trade UI/pools follow it. */
    public static final ForgeConfigSpec.BooleanValue PROFICIENCY_SYNC_TO_VANILLA;
    /** Chunks radius for the sleep skill's bed search (HOME refill). Behavioural switch, not game data. */
    public static final ForgeConfigSpec.IntValue HOME_SEARCH_RADIUS_CHUNKS;
    /** Cadence (ticks) of the autonomous behavior executor that runs goals via skills. */
    public static final ForgeConfigSpec.IntValue BEHAVIOR_EXECUTOR_INTERVAL_TICKS;

    // ── Combat (aggro thresholds) ──
    /** Require a real weapon to attack on one's own; bare hands only pin/distract. */
    public static final ForgeConfigSpec.BooleanValue COMBAT_REQUIRE_WEAPON;
    /** Solo-attack TTK gate: villager kills threat at most this × as slowly as it is killed. */
    public static final ForgeConfigSpec.DoubleValue COMBAT_SOLO_WIN_FACTOR;
    /** Group-charge gate: summed responder combat power must reach this to charge. */
    public static final ForgeConfigSpec.DoubleValue COMBAT_CHARGE_TOTAL_POWER;
    /** Per-villager attack gate: must have at least this much health to swing. */
    public static final ForgeConfigSpec.DoubleValue COMBAT_CHARGE_MIN_HEALTH;

    // ── Economy (LLM-free village supply/demand) ──
    /** Master switch for the autonomous village economy (trade messages + item exchange). */
    public static final ForgeConfigSpec.BooleanValue ENABLE_ECONOMY;
    /** Cadence (ticks) of the economy scan (post wants/haves, match, navigate, exchange). */
    public static final ForgeConfigSpec.IntValue ECONOMY_TICK_INTERVAL;
    /** Player barter fairness: accepted when offered value ≥ requested value × this. */
    public static final ForgeConfigSpec.DoubleValue BARTER_FAIRNESS_TOLERANCE;
    /** Distance (blocks) within which two matched traders count as "met" and exchange. */
    public static final ForgeConfigSpec.DoubleValue TRADE_MEET_RADIUS;
    /** Lifetime (ticks) of a trade/have/want message before it expires from the board. */
    public static final ForgeConfigSpec.IntValue TRADE_MSG_TTL;

    // ── Brain logging (decision-brain trace) ──
    /** Trace every todo/goal lifecycle (add / end / switch / suspend-resume) to the VillagerBrain logger. */
    public static final ForgeConfigSpec.BooleanValue BRAIN_LOG_ENABLED;

    // ── Capability pool (what a villager knows, grows, and forgets) ──
    /** Days (after a 2-day grace) over which an unpractised capability rusts away (square curve). */
    public static final ForgeConfigSpec.IntValue CAPABILITY_RUST_DAYS;
    /** Capability XP gained per "practice" event (harvest, craft, combat, …). */
    public static final ForgeConfigSpec.IntValue CAPABILITY_PRACTICE_XP;
    /** Capability XP gained per "chat with an expert" event (slower than doing). */
    public static final ForgeConfigSpec.IntValue CAPABILITY_CHAT_XP;

    // ── Perception / event bus / gossip / debug dump ──
    /** Master switch: write a human-readable JSON snapshot per villager on world save. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_DUMP;
    /** Manhattan chunk-distance radius within which a villager perceives environment events. */
    public static final ForgeConfigSpec.IntValue PERCEPTION_RADIUS_CHUNKS;
    /** Max events a villager consumes per slow think tick. */
    public static final ForgeConfigSpec.IntValue EVENTS_PER_THINK;
    /** Probability a social encounter transfers one resource fact (0..1). */
    public static final ForgeConfigSpec.DoubleValue GOSSIP_CHANCE;
    /** Ticks before a second-hand ore fact stops being re-told (3 MC days = 72000). */
    public static final ForgeConfigSpec.IntValue GOSSIP_ORE_EXPIRY_TICKS;

    static {
        BUILDER.push("LLM Settings");
        
        LLM_API_TYPE = BUILDER
                .comment("LLM API type to use (openai, anthropic, ollama, gemini)")
                .define("llm_api_type", "openai");
        
        LLM_API_KEY = BUILDER
                .comment("Your LLM API key")
                .define("llm_api_key", "");
        
        LLM_API_URL = BUILDER
                .comment("LLM API endpoint URL. Default points at DeepSeek (OpenAI-compatible, China-direct, free tier available). Swap for any OpenAI-compatible endpoint.")
                .define("llm_api_url", "https://api.deepseek.com/v1/chat/completions");
        
        LLM_MODEL = BUILDER
                .comment("LLM model to use. Default: deepseek-chat (DeepSeek V3). Also works with any OpenAI-compatible model name, e.g. glm-4-flash, Qwen/Qwen2.5-7B-Instruct, moonshot-v1-8k.")
                .define("llm_model", "deepseek-chat");
        
        LLM_MAX_TOKENS = BUILDER
                .comment("Maximum tokens for LLM responses")
                .defineInRange("llm_max_tokens", 150, 50, 1000);
        
        LLM_TEMPERATURE = BUILDER
                .comment("LLM temperature (creativity) - 0.0 to 2.0")
                .defineInRange("llm_temperature", 0.7, 0.0, 2.0);

        LLM_DEBUG = BUILDER
                .comment("Verbose LLM I/O debug logging: prints full request/response bodies, prompts, and "
                        + "HTTP details at INFO level. Off by default to avoid log spam; flip to true when "
                        + "diagnosing LLM/API problems. (Always-on failure diagnostics are logged separately "
                        + "at WARN and do NOT depend on this flag.)")
                .define("llm_debug", false);

        BUILDER.pop();
        
        BUILDER.push("Agent Behavior");
        
        ENABLE_AI_AGENTS = BUILDER
                .comment("Enable AI agents for villagers")
                .define("enable_ai_agents", true);
        
        AGENT_THINK_INTERVAL = BUILDER
                .comment("Ticks between AI agent updates (20 ticks = 1 second)")
                .defineInRange("agent_think_interval", 100, 20, 1200);
        
        ENABLE_VILLAGER_CHAT = BUILDER
                .comment("Enable villager-to-villager chat")
                .define("enable_villager_chat", true);

        GREETING_SCAN_INTERVAL = BUILDER
                .comment("Ticks between player-proximity greeting scans (20 ticks = 1s). "
                        + "Lower = villagers notice you faster but cost more; raise it to make "
                        + "greetings feel less frequent/robotic.")
                .defineInRange("greeting_scan_interval", 60, 20, 600);

        GREETING_BASE_PROBABILITY = BUILDER
                .comment("Base chance (0-1) a villager greets you on first proximity when few other "
                        + "conversable targets are around. Not 1.0 on purpose — avoids 100% robotic greetings.")
                .defineInRange("greeting_base_probability", 0.7, 0.0, 1.0);

        GREETING_FAMILIARITY_DECAY = BUILDER
                .comment("Per-meeting probability multiplier (0-1). Each time you've already greeted "
                        + "this villager, the next greet chance is multiplied by this. 0.6 → after 2 "
                        + "meetings the chance is ~0.36x. Makes repeat greetings rarer.")
                .defineInRange("greeting_familiarity_decay", 0.6, 0.05, 0.95);

        GREETING_TARGET_DILUTION = BUILDER
                .comment("How much other nearby conversable targets (villagers + players) dilute the "
                        + "greet chance. chance = base / (1 + (targets-1) * dilution). 1.0 → 1/N split; "
                        + "0.0 → targets don't matter. If you're the only one around, you get the full base.")
                .defineInRange("greeting_target_dilution", 1.0, 0.0, 5.0);
        
        ENABLE_WORLD_INTERACTION = BUILDER
                .comment("Enable villagers to interact with the world (farming, crafting, etc.)")
                .define("enable_world_interaction", true);

        ENABLE_BUILDING = BUILDER
                .comment("Enable villagers to place/break blocks and build structures designed by the LLM")
                .define("enable_building", true);

        BUILD_INTERACT_RANGE_SQ = BUILDER
                .comment("Interaction distance squared for block place/break (2.0 = within 1 block, Chebyshev).")
                .defineInRange("build_interact_range_sq", 2.0, 1.0, 16.0);

        BUILD_BLOCK_INTERVAL = BUILDER
                .comment("Ticks between placing consecutive blocks in a large build (throttles server load).")
                .defineInRange("build_block_interval", 3, 1, 200);

        BUILD_MAX_RETRIES = BUILDER
                .comment("Max times the LLM may revise an impossible structure before the villager gives up.")
                .defineInRange("build_max_retries", 3, 0, 20);

        BUILD_STUCK_TIMEOUT = BUILDER
                .comment("Ticks a villager may spend stuck walking to a block before giving up on it.")
                .defineInRange("build_stuck_timeout", 100, 10, 1200);

        ENABLE_AUTO_PICKUP = BUILDER
                .comment("Enable villagers to automatically pick up nearby items")
                .define("enable_auto_pickup", true);

        VILLAGER_PICKUP_INTERVAL = BUILDER
                .comment("Ticks between villager item pickup attempts (20 ticks = 1 second)")
                .defineInRange("villager_pickup_interval", 10, 1, 200);

        ENABLE_DAILY_SCHEDULE = BUILDER
                .comment("Enable LLM-powered daily schedule system (villagers plan their day at dawn)")
                .define("enable_daily_schedule", true);

        ENABLE_VILLAGER_SOCIAL = BUILDER
                .comment("Enable villager-to-villager social conversations (LLM-powered)")
                .define("enable_villager_social", true);

        ENABLE_VILLAGER_THOUGHTS = BUILDER
                .comment("Enable ambient inner-thought bubbles ('[Name thinks]') broadcast to nearby players. "
                        + "Default OFF. The thought-generation API stays available for other features "
                        + "(e.g. a future 'mind-reader' item that reveals a single villager's thoughts "
                        + "to the holder only).")
                .define("enable_villager_thoughts", false);

        ENABLE_DEBUG_OVERLAY = BUILDER
                .comment("Enable the in-game debug overlay (building boxes, agent HUD). "
                        + "Server streams perception/agent debug snapshots to clients when true.")
                .define("enable_debug_overlay", false);

        DEBUG_RENDER_RANGE = BUILDER
                .comment("Radius (blocks) around the player for debug building boxes")
                .defineInRange("debug_render_range", 64, 8, 256);

        DEBUG_SHOW_HUD = BUILDER
                .comment("Draw the agent-state HUD panel (client render toggle)")
                .define("debug_show_hud", true);

        DEBUG_SHOW_BUILDINGS = BUILDER
                .comment("Draw wireframe boxes around detected buildings (client render toggle)")
                .define("debug_show_buildings", true);

        DEBUG_SHOW_SEEDS = BUILDER
                .comment("Draw a wireframe marker at every distance-field seed (regional-maximum "
                        + "plateau) of each detected building. Interior (room-candidate) seeds are "
                        + "yellow; atmosphere seeds are magenta. Client render toggle.")
                .define("debug_show_seeds", true);

        DEBUG_SHOW_FIELD = BUILDER
                .comment("Draw the full watershed collection field of each detected building: every "
                        + "BFS collection in its own colour, the atmospheric collection in a uniform "
                        + "cyan, and the watershed boundary (equal-geodesic-distance surface) in white. "
                        + "Seeds are drawn as a cross at the centre of their block. Client render toggle.")
                .define("debug_show_field", true);

        BUILDER.pop();

        BUILDER.push("Decision Harness");

        HARNESS_ENABLED = BUILDER
                .comment("Master switch for the decision harness (resilience + structured LLM decisions). "
                        + "When false, LLM calls bypass the guard and behave as before.")
                .define("harness_enabled", true);

        HARNESS_TIMEOUT_MS = BUILDER
                .comment("Per-LLM-call timeout in milliseconds. On timeout the villager falls back to "
                        + "rule-based behaviour instead of stalling the decision pipeline. "
                        + "Tune via /va config settimeout <ms>; default raised to 15000 to cover real "
                        + "OpenAI round-trips (~12-13s observed).")
                .defineInRange("harness_timeout_ms", 15000, 1000, 120000);

        HARNESS_CIRCUIT_FAILURES = BUILDER
                .comment("Consecutive LLM failures before a villager is put into rule-only mode for a cooldown "
                        + "(prevents an API outage from cascading into a village-wide stall).")
                .defineInRange("harness_circuit_failures", 3, 1, 20);

        HARNESS_CIRCUIT_COOLDOWN_TICKS = BUILDER
                .comment("How many ticks a villager stays in rule-only mode after the circuit breaker trips.")
                .defineInRange("harness_circuit_cooldown_ticks", 1200, 100, 24000);

        HARNESS_JOURNAL_ENABLED = BUILDER
                .comment("Write a JSONL decision journal to villageragent_decision_journal.jsonl "
                        + "(for replay / eval / debugging).")
                .define("harness_journal_enabled", true);

        HARNESS_DRIVE_DAILY_GOAL = BUILDER
                .comment("When true, the harness also decides one structured daily goal for each villager at dawn "
                        + "(P1 integration). Enabled by default so the LLM acts as a decision engine, not just a "
                        + "dialogue generator; set false (or /va config set harnessdaily false) to restore the old "
                        + "rule-only daily goal behaviour.")
                .define("harness_drive_daily_goal", true);

        BUILDER.pop();

        BUILDER.push("Combat");

        COMBAT_REQUIRE_WEAPON = BUILDER
                .comment("Require a villager to hold a real weapon (sword/axe/tool with damage above the "
                        + "bare-hand base) before it will attack on its own. Bare-handed villagers never "
                        + "solo-fight and contribute 0 to a group charge's total power — they only run to "
                        + "the rally point to pin/distract. Set false to let fists count as 'armed' "
                        + "(legacy behaviour).")
                .define("combat_require_weapon", false);

        COMBAT_SOLO_WIN_FACTOR = BUILDER
                .comment("Solo-attack gate (armed villagers only): a villager engages a threat directly only "
                        + "if its time-to-kill is at most this multiple of the threat's time-to-kill "
                        + "(lower = pickier). 0.7 means it must kill ~43% faster than it gets killed.")
                .defineInRange("combat_solo_win_factor", 0.7, 0.0, 5.0);

        COMBAT_CHARGE_TOTAL_POWER = BUILDER
                .comment("Group-charge gate: villagers may charge a threat together only once the summed "
                        + "combat power of all responders reaches this value (replaces the old head-count + "
                        + "DPS check). Iron sword ≈6-7 can charge alone; stone ≈5 or wood ≈4 need 2 responders.")
                .defineInRange("combat_charge_total_power", 5.0, 0.0, 1000.0);

        COMBAT_CHARGE_MIN_HEALTH = BUILDER
                .comment("Per-villager attack gate: a villager only actively attacks (solo or in a group "
                        + "charge) when its current health is at least this value. Below it, even in a "
                        + "charged group, it only runs to the rally point to pin/distract.")
                .defineInRange("combat_charge_min_health", 10.0, 0.0, 20.0);

        BUILDER.pop();

        BUILDER.push("Economy");

        ENABLE_ECONOMY = BUILDER
                .comment("Master switch for the LLM-free village economy: villagers broadcast supply/demand "
                        + "messages and walk to each other to trade. Works with no API key.")
                .define("economy_enabled", true);

        ECONOMY_TICK_INTERVAL = BUILDER
                .comment("Cadence (ticks) of the economy scan that posts wants/haves, matches traders, and "
                        + "drives the walk-to-trade. 20 = once per second.")
                .defineInRange("economy_tick_interval", 20, 1, 200);

        BARTER_FAIRNESS_TOLERANCE = BUILDER
                .comment("Player barter acceptance: a villager accepts when the value of what the player gives "
                        + "is at least this multiple of the value it must give back. 0.95 = must be ~fair.")
                .defineInRange("barter_fairness_tolerance", 0.95, 0.0, 5.0);

        TRADE_MEET_RADIUS = BUILDER
                .comment("Distance (blocks) within which two matched traders count as 'met' and actually "
                        + "exchange items.")
                .defineInRange("trade_meet_radius", 2.5, 1.0, 16.0);

        TRADE_MSG_TTL = BUILDER
                .comment("Lifetime (ticks) of a trade/want message on the board before it expires.")
                .defineInRange("trade_msg_ttl", 1200, 20, 24000);

        BUILDER.pop();

        BUILDER.push("Brain Logging");

        BRAIN_LOG_ENABLED = BUILDER
                .comment("Trace every todo/goal lifecycle event (added, ended, switched, suspended/resumed) "
                        + "to the dedicated 'VillagerBrain' logger. Useful to watch a villager's decision brain "
                        + "in the log: why a todo appeared, when/why it finished, and how the running goal changes. "
                        + "Goal transitions are infrequent, so this is low-volume even with many villagers.")
                .define("brain_log_enabled", true);

        BUILDER.pop();

        BUILDER.push("Proficiency");

        PROFICIENCY_SYNC_TO_VANILLA = BUILDER
                .comment("Mirror the proficiency level onto the vanilla VillagerData level so the vanilla trading UI "
                        + "and trade offers unlock in step with proficiency. Set false to keep the two fully independent.")
                .define("proficiency_sync_to_vanilla", true);

        HOME_SEARCH_RADIUS_CHUNKS = BUILDER
                .comment("How many chunks around a villager to search the structure index for a bed when it has no "
                        + "vanilla HOME (sleep skill / HOME refill). Larger = finds more distant homes but costs more.")
                .defineInRange("home_search_radius_chunks", 8, 1, 64);

        BEHAVIOR_EXECUTOR_INTERVAL_TICKS = BUILDER
                .comment("How often (in ticks) the autonomous behavior executor re-scans the goal list and dispatches "
                        + "to a skill. It is cheap, so 20 (=1s) is a sane default; lower = more responsive.")
                .defineInRange("behavior_executor_interval_ticks", 20, 1, 1200);

        CAPABILITY_RUST_DAYS = BUILDER
                .comment("Days (after a 2-day grace period) over which an unpractised capability rusts away, on a "
                        + "slow-start / fast-finish square curve. 14 = a skill left alone for ~2 weeks decays to 0.")
                .defineInRange("capability_rust_days", 14, 1, 365);

        CAPABILITY_PRACTICE_XP = BUILDER
                .comment("Capability XP gained per 'practice' event (harvest a crop, smelt/forge, craft an item, "
                        + "win a fight, close a trade, absorb a drop). Doing is the main way to level.")
                .defineInRange("capability_practice_xp", 10, 1, 1000);

        CAPABILITY_CHAT_XP = BUILDER
                .comment("Capability XP gained per 'chat with an expert' event (talking to a villager of the matching "
                        + "profession). Much slower than doing, by design — practice first, mentorship second.")
                .defineInRange("capability_chat_xp", 1, 0, 100);

        BUILDER.pop();

        BUILDER.push("Perception / Event Bus / Gossip");

        ENABLE_DEBUG_DUMP = BUILDER
                .comment("When true, every world save also writes a human-readable JSON snapshot of each villager "
                        + "(chunk memory, value table, inventory, goals, capabilities) to <world>/villageragent/debug/<uuid>.json. "
                        + "Debug-only mirror; never read back by the mod.")
                .define("debug_dump_enabled", true);

        PERCEPTION_RADIUS_CHUNKS = BUILDER
                .comment("Manhattan chunk-distance radius within which a villager perceives environment events "
                        + "(anti-telepathy). 4 chunks = 64 blocks.")
                .defineInRange("perception_radius_chunks", 4, 1, 16);

        EVENTS_PER_THINK = BUILDER
                .comment("Max environment events a villager consumes per slow think tick (budget throttle).")
                .defineInRange("events_per_think", 8, 1, 64);

        GOSSIP_CHANCE = BUILDER
                .comment("Probability (0..1) that a social encounter transfers one resource fact between villagers.")
                .defineInRange("gossip_chance", 0.30, 0.0, 1.0);

        GOSSIP_ORE_EXPIRY_TICKS = BUILDER
                .comment("Ticks before a second-hand ore fact stops being re-told (depletable resource). "
                        + "3 Minecraft days = 72000 ticks.")
                .defineInRange("gossip_ore_expiry_ticks", 72000, 24000, 480000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}

