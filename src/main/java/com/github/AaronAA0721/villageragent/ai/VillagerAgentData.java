package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkTag;
import com.github.AaronAA0721.villageragent.ai.memory.EntityCategory;
import com.github.AaronAA0721.villageragent.ai.vision.ChunkContentSampler;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Stores AI agent data for each villager including personality, memory, goals, and inventory
 */
public class VillagerAgentData {
    private static final Logger LOGGER = LogManager.getLogger();

    /** One Minecraft day = 24 000 ticks.  Conversations older than this are forgotten. */
    private static final long CONVERSATION_EXPIRY_TICKS = 24_000L;

    /** Maximum number of conversation entries kept (safety cap). */
    private static final int MAX_CONVERSATION_ENTRIES = 40;

    // ── Inner class: a single chat line with its game-time stamp ──
    public static class TimestampedMessage {
        private final String text;
        private final long gameTick;

        public TimestampedMessage(String text, long gameTick) {
            this.text = text;
            this.gameTick = gameTick;
        }

        public String getText()    { return text; }
        public long   getGameTick() { return gameTick; }

        @Override public String toString() { return text; }
    }

    private UUID villagerId;
    private volatile String name;
    private String profession;  // The villager's actual Minecraft profession (Farmer, Librarian, etc.)
    private volatile String personality;
    private List<TimestampedMessage> conversationHistory;
    private List<String> memories;
    private Map<String, Integer> relationships; // villager UUID -> relationship score
    private List<AgentGoal> goals;
    private List<LongTermAgenda> agendas;
    /** Player UUID -> reputation with this villager (-100..100). Drives debt/duty accountability. */
    private final Map<UUID, Integer> playerReputation = new HashMap<>();
    private AgentInventory inventory;
    private Map<String, Object> preferences; // trading preferences, item values, etc.
    private long lastThinkTime;
    private String currentActivity;
    private VillagerAction currentAction;  // Current action being executed
    private long actionStartTime;  // When the current action started
    private BuildJob currentBuildJob = null; // Active multi-block building task (null if none)
    private volatile boolean llmGenerationFailed = false;
    private volatile String llmErrorMessage = null;
    private long lastRestockTime = 0;  // Track when villager last restocked at job block
    private boolean inFarmingState = false;       // true while the villager is actively farming an area
    private int farmingCooldownTicks = 0;          // >0 means the villager is resting after a farming session
    private int attackCooldownTicks = 0;           // >0 means the villager can't attack yet

    /** Latest environment snapshot, built by VillagerVisionSystem before each chat response. */
    private String lastEnvironmentSummary = null;

    // ── Daily schedule / autonomous lifecycle ──
    /** The villager's plan for today, generated once per dawn by VillagerSchedulePlanner. */
    private DailySchedule dailySchedule = null;
    /** The Minecraft day number (gameTime / 24000) when the plan was last generated. */
    private long lastDayPlanned = -1L;
    /** The Minecraft day number when an evening reflection was last generated. */
    private long lastDayReflected = -1L;
    /** True while this villager is mid-way through an LLM-generated social conversation. */
    private volatile boolean socializing = false;
    /** Game tick of the last social interaction (used for cooldown). */
    private long lastSocialTick = -6001L;
    /** The activity the daily schedule intends this villager to do right now. */
    private String scheduledActivity = null;

    // ── Goals lifecycle ──
    /** The Minecraft day (gameTime / 24000) when goals were last pruned / fallback-generated. */
    private long lastGoalPruneDay = -1L;

    // ── Needs system ──
    /** Current hunger level (0 = starving, 100 = full). Starts full. */
    private float hunger = 100f;
    /** Current fatigue level (0 = rested, 100 = exhausted). Increases at night, resets at dawn. */
    private float fatigue = 0f;
    /** Game tick when the environment summary was last refreshed autonomously. */
    private long lastEnvRefreshTick = -1L;

    // ── Mood system ──
    /**
     * Five-tier mood derived by {@link VillagerNeedsSystem#deriveMood(VillagerAgentData)}.
     * Re-computed every time needs are ticked. Injected into all LLM prompts.
     */
    public enum Mood { HAPPY, CONTENT, NEUTRAL, ANXIOUS, DISTRESSED }
    private Mood mood = Mood.NEUTRAL;

    public Mood getMood() { return mood; }
    public void  setMood(Mood mood) { this.mood = mood; }

    // ── Player greeting ──
    /** Maps player UUID → last game tick this villager greeted that player. */
    private final Map<UUID, Long> lastGreetedPlayer = new HashMap<>();

    public Map<UUID, Long> getLastGreetedPlayer() { return lastGreetedPlayer; }

    /** Maps player UUID → how many times this villager has greeted that player. */
    private final Map<UUID, Integer> greetCount = new HashMap<>();

    public Map<UUID, Integer> getGreetCount() { return greetCount; }

    // ── Spatial memory ──
    /**
     * Per-chunk memory the villager has built up. Keyed by {@code ChunkPos.asLong}.
     * When a new chunk is entered, that chunk is sampled (see {@link ChunkContentSampler})
     * and the 8 surrounding chunks are marked "known" (lightweight, no sampling).
     * Capped at MAX_KNOWN_CHUNKS entries; lowest-saliency entry is evicted first.
     */
    private final LinkedHashMap<Long, ChunkMemory> chunkMemories = new LinkedHashMap<>();
    /** Maximum number of chunks to remember. */
    private static final int MAX_KNOWN_CHUNKS = 512;
    /** The chunk the villager was in last tick — used to detect chunk transitions. */
    private long lastChunkKey = Long.MIN_VALUE;

    // ── Memory summarization ──
    /** True while an async LLM memory-compression call is in flight. */
    private volatile boolean summarizingMemories = false;
    /** Number of memories that triggers LLM compression. */
    private static final int MEMORY_SUMMARIZE_THRESHOLD = 40;
    /** Oldest memories consumed per summarization pass. */
    private static final int MEMORY_SUMMARIZE_COUNT = 20;
    /** Game tick of the last thought-bubble broadcast (for cooldown). */
    private long lastThoughtTick = -7_200L;

    public VillagerAgentData(UUID villagerId) {
        this(villagerId, true);
    }

    /**
     * @param generateIdentity when true, generate a fresh identity for a NEW villager
     *                         (async LLM, with an instant local fallback). When false,
     *                         the identity is expected to be restored via
     *                         {@link #deserializeNBT(CompoundNBT)} — used during world
     *                         load so we never block the server thread on the network.
     */
    public VillagerAgentData(UUID villagerId, boolean generateIdentity) {
        this.villagerId = villagerId;
        this.profession = "Villager";  // Default, will be updated from actual villager
        this.conversationHistory = new ArrayList<>();
        this.memories = new ArrayList<>();
        this.relationships = new HashMap<>();
        this.goals = new ArrayList<>();
        this.agendas = new ArrayList<>();
        this.inventory = new AgentInventory();
        this.preferences = new HashMap<>();
        this.lastThinkTime = 0;
        this.currentActivity = "idle";

        // Brand-new villager: kick off async identity generation (never blocks).
        // Loading from saved data: skip — deserializeNBT restores the real identity.
        if (generateIdentity && shouldUseLLM()) {
            generateWithLLM();
        } else {
            // Local fallback so the villager always has a usable identity instantly.
            this.personality = generateRandomPersonality();
            this.name = generateRandomName();
        }
    }

    /**
     * Update the profession from the actual Minecraft villager entity
     * Only gives starter items when transitioning from no job to having a job
     */
    public void updateProfession(String newProfession) {
        if (newProfession != null && !newProfession.isEmpty()) {
            String oldProfession = this.profession;
            boolean hadNoJob = oldProfession == null || oldProfession.isEmpty() ||
                               oldProfession.equalsIgnoreCase("none") ||
                               oldProfession.equalsIgnoreCase("villager");
            boolean gettingJob = !newProfession.equalsIgnoreCase("none") &&
                                 !newProfession.equalsIgnoreCase("villager");

            this.profession = newProfession;

            // Generate profession-specific goals
            this.goals = ProfessionGoalGenerator.generateGoalsForProfession(newProfession);
            LOGGER.info("Generated " + this.goals.size() + " goals for profession: " + newProfession);

            // Only give starter items when getting a job from not having one
            if (hadNoJob && gettingJob) {
                JobStarterItems.giveStarterItems(this);
                LOGGER.info("Gave starter items to " + name + " for new profession: " + newProfession);
                addMemory("Received starter items for new profession: " + newProfession);
            }
        }
    }

    public String getProfession() {
        return profession;
    }

    /**
     * Restock items when villager visits their job block
     * Called when villager reaches their workstation
     */
    public void restockAtJobBlock() {
        if (profession == null || profession.isEmpty()) {
            return;
        }

        JobStarterItems.giveStarterItems(this);
        LOGGER.info(name + " restocked items at job block for profession: " + profession);
        addMemory("Restocked items at job block");
    }
    
    /**
     * Check if we should use LLM for generation
     */
    private boolean shouldUseLLM() {
        return ModConfig.ENABLE_AI_AGENTS.get();
    }

    /**
     * Generate name and personality using LLM. Runs ASYNCHRONOUSLY — it never blocks
     * the calling thread (the server thread), so world load and villager spawns stay
     * responsive even when the LLM endpoint is slow or unreachable. A local fallback
     * identity is applied immediately; the LLM result (if it ever arrives) replaces it.
     */
    private void generateWithLLM() {
        // Apply a local fallback right away so the villager is usable instantly and
        // we never have a null/blank identity while the LLM call is in flight.
        this.personality = generateRandomPersonality();
        this.name = generateRandomName();

        String apiKey = ModConfig.LLM_API_KEY.get();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            this.llmGenerationFailed = true;
            this.llmErrorMessage = "API key not configured. Using local fallback names.";
            return;
        }

        String systemPrompt = "You are a creative assistant that generates unique medieval villager characters. " +
                "Respond ONLY with a JSON object in this exact format: {\"name\":\"VillagerName\",\"personality\":\"personality description\"}. " +
                "The name should be a single medieval-style first name. " +
                "The personality should be a short phrase (3-6 words) describing their character traits.";

        String userPrompt = "Generate a unique villager character with a medieval name and interesting personality.";

        // Fire-and-forget: the network call already runs on LLMService's own pool,
        // so .thenAccept / .exceptionally execute off the server thread.
        try {
            LLMService.queryLLM(systemPrompt, userPrompt)
                    .thenAccept(this::applyLLMResponse)
                    .exceptionally(ex -> {
                        LOGGER.error("Error generating villager with LLM: " + ex.getMessage());
                        this.llmGenerationFailed = true;
                        this.llmErrorMessage = "LLM request failed: " + ex.getMessage();
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.error("Failed to start LLM request: " + e.getMessage());
            this.llmGenerationFailed = true;
            this.llmErrorMessage = "Failed to start LLM request: " + e.getMessage();
        }
    }

    /** Applies a completed LLM response (runs on the LLMService worker thread). */
    private void applyLLMResponse(String response) {
        if (response == null) {
            this.llmGenerationFailed = true;
            this.llmErrorMessage = "Empty LLM response; using local fallback.";
            return;
        }
        if (response.contains("{") && response.contains("}")) {
            String json = response.substring(response.indexOf("{"), response.lastIndexOf("}") + 1);
            String nameValue = extractJsonValue(json, "name");
            String personalityValue = extractJsonValue(json, "personality");
            if (nameValue != null && personalityValue != null && !nameValue.isEmpty() && !personalityValue.isEmpty()) {
                this.name = nameValue;
                this.personality = personalityValue;
                LOGGER.info("Generated villager via LLM: " + name + " - " + personality);
                return;
            }
        }
        this.llmGenerationFailed = true;
        this.llmErrorMessage = "Invalid/unexpected LLM response; using local fallback.";
    }

    /**
     * Simple JSON value extractor
     */
    private String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\"";
            int keyIndex = json.indexOf(searchKey);
            if (keyIndex == -1) return null;

            int colonIndex = json.indexOf(":", keyIndex);
            if (colonIndex == -1) return null;

            int startQuote = json.indexOf("\"", colonIndex);
            if (startQuote == -1) return null;

            int endQuote = json.indexOf("\"", startQuote + 1);
            if (endQuote == -1) return null;

            return json.substring(startQuote + 1, endQuote);
        } catch (Exception e) {
            return null;
        }
    }

    private String generateRandomPersonality() {
        String[] traits = {
            "friendly and generous",
            "shrewd and business-minded",
            "cautious and reserved",
            "adventurous and bold",
            "wise and thoughtful",
            "cheerful and optimistic",
            "grumpy but fair",
            "curious and inquisitive"
        };
        return traits[new Random().nextInt(traits.length)];
    }

    private String generateRandomName() {
        String[] names = {
            "Aldric", "Beatrice", "Cedric", "Diana", "Edmund", "Fiona",
            "Gregory", "Helena", "Isaac", "Juliana", "Kenneth", "Lydia",
            "Marcus", "Natalia", "Oliver", "Penelope", "Quentin", "Rosalind"
        };
        return names[new Random().nextInt(names.length)];
    }
    
    // Getters and setters
    public UUID getVillagerId() { return villagerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public List<TimestampedMessage> getConversationHistory() { return conversationHistory; }
    public List<String> getMemories() { return memories; }
    public Map<String, Integer> getRelationships() { return relationships; }
    public List<AgentGoal> getGoals() { return goals; }
    public List<LongTermAgenda> getAgendas() { return agendas; }
    public Map<UUID, Integer> getPlayerReputation() { return playerReputation; }
    public AgentInventory getInventory() { return inventory; }
    public Map<String, Object> getPreferences() { return preferences; }
    public long getLastThinkTime() { return lastThinkTime; }
    public void setLastThinkTime(long time) { this.lastThinkTime = time; }
    public String getCurrentActivity() { return currentActivity; }
    public void setCurrentActivity(String activity) { this.currentActivity = activity; }
    public VillagerAction getCurrentAction() { return currentAction; }
    public void setCurrentAction(VillagerAction action) {
        this.currentAction = action;
        this.actionStartTime = System.currentTimeMillis();
    }
    public long getActionStartTime() { return actionStartTime; }
    public BuildJob getBuildJob() { return currentBuildJob; }
    public void setBuildJob(BuildJob job) { this.currentBuildJob = job; }
    public boolean hasLLMGenerationFailed() { return llmGenerationFailed; }
    public String getLLMErrorMessage() { return llmErrorMessage; }
    public long getLastRestockTime() { return lastRestockTime; }
    public void setLastRestockTime(long time) { this.lastRestockTime = time; }
    public boolean isInFarmingState() { return inFarmingState; }
    public void setInFarmingState(boolean farming) { this.inFarmingState = farming; }
    public int getFarmingCooldownTicks() { return farmingCooldownTicks; }
    public void setFarmingCooldownTicks(int ticks) { this.farmingCooldownTicks = ticks; }
    public boolean isOnFarmingCooldown() { return farmingCooldownTicks > 0; }
    public void tickFarmingCooldown() { if (farmingCooldownTicks > 0) farmingCooldownTicks--; }
    public void tickFarmingCooldown(int ticks) { farmingCooldownTicks = Math.max(0, farmingCooldownTicks - ticks); }

    public int getAttackCooldownTicks() { return attackCooldownTicks; }
    public void setAttackCooldownTicks(int ticks) { this.attackCooldownTicks = ticks; }
    public boolean isOnAttackCooldown() { return attackCooldownTicks > 0; }
    public void tickAttackCooldown() { if (attackCooldownTicks > 0) attackCooldownTicks--; }

    /** Store the latest environment snapshot produced by {@link VillagerVisionSystem}. */
    public void setEnvironmentSummary(String summary) { this.lastEnvironmentSummary = summary; }
    public String getEnvironmentSummary() { return lastEnvironmentSummary; }

    // ── Daily schedule accessors ──
    public DailySchedule getDailySchedule() { return dailySchedule; }
    public void setDailySchedule(DailySchedule schedule) { this.dailySchedule = schedule; }
    public long getLastDayPlanned() { return lastDayPlanned; }
    public void setLastDayPlanned(long day) { this.lastDayPlanned = day; }
    public long getLastDayReflected() { return lastDayReflected; }
    public void setLastDayReflected(long day) { this.lastDayReflected = day; }
    public boolean isSocializing() { return socializing; }
    public void setSocializing(boolean socializing) { this.socializing = socializing; }
    public long getLastSocialTick() { return lastSocialTick; }
    public void setLastSocialTick(long tick) { this.lastSocialTick = tick; }
    public String getScheduledActivity() { return scheduledActivity; }
    public void setScheduledActivity(String activity) { this.scheduledActivity = activity; }

    public long getLastGoalPruneDay() { return lastGoalPruneDay; }
    public void setLastGoalPruneDay(long day) { this.lastGoalPruneDay = day; }

    // ── Needs system accessors ──
    public float getHunger() { return hunger; }
    public void setHunger(float hunger) { this.hunger = Math.max(0f, Math.min(100f, hunger)); }
    public float getFatigue() { return fatigue; }
    public void setFatigue(float fatigue) { this.fatigue = Math.max(0f, Math.min(100f, fatigue)); }
    public long getLastEnvRefreshTick() { return lastEnvRefreshTick; }
    public void setLastEnvRefreshTick(long tick) { this.lastEnvRefreshTick = tick; }

    // ── Spatial memory accessors ──

    /** Returns the packed chunk keys of all known (visited / nearby-seen) chunks. */
    public Set<Long> getVisitedChunks() { return chunkMemories.keySet(); }

    public ChunkMemory getChunkMemory(int cx, int cz) {
        return chunkMemories.get(ChunkPos.asLong(cx, cz));
    }

    public java.util.Collection<ChunkMemory> getAllChunkMemories() { return chunkMemories.values(); }

    /**
     * Called when the villager enters a new chunk.
     * Samples the entered chunk the first time it is seen (via {@link ChunkContentSampler}),
     * and marks the 8 surrounding chunks as merely "known" (cheap placeholder, no sampling).
     * Does nothing if the villager is still in the same chunk as the previous tick.
     */
    public void updateChunkMemory(LivingEntity villager, World world, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (key == lastChunkKey) return; // same chunk as last tick — nothing to do
        lastChunkKey = key;

        // Sample the entered chunk the first time we encounter it
        if (!chunkMemories.containsKey(key)) {
            if (chunkMemories.size() >= MAX_KNOWN_CHUNKS) evictLowestSaliency();
            chunkMemories.put(key, ChunkContentSampler.sample(villager, world, chunkX, chunkZ));
        }

        // Mark the 8 neighbours as known (no sampling — just remember we've seen them)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long nk = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                if (!chunkMemories.containsKey(nk)) {
                    if (chunkMemories.size() >= MAX_KNOWN_CHUNKS) evictLowestSaliency();
                    ChunkMemory m = new ChunkMemory(nk);
                    m.add(ChunkTag.KNOWN);
                    chunkMemories.put(nk, m);
                }
            }
        }
    }

    /** Evict the lowest-saliency chunk to make room (keeps important chunks longer). */
    private void evictLowestSaliency() {
        long worstKey = -1;
        float worstSal = Float.MAX_VALUE;
        for (java.util.Map.Entry<Long, ChunkMemory> e : chunkMemories.entrySet()) {
            if (e.getValue().saliency < worstSal) {
                worstSal = e.getValue().saliency;
                worstKey = e.getKey();
            }
        }
        if (worstKey != -1L) chunkMemories.remove(worstKey);
    }

    // ── Memory-query helpers ("recall without re-scanning the world") ──

    /** Recall all memorised workbenches (crafting tables) as global BlockPos. */
    public java.util.List<BlockPos> findWorkbenches() {
        return findFeature(ChunkFeature.CRAFTING_TABLE);
    }

    /**
     * Recall where a feature was seen — one representative position per chunk.
     * Chunk memory intentionally keeps a single anchor per feature per chunk (see
     * {@link ChunkMemory}); the exact layout is re-acquired on arrival by the frustum scan.
     */
    public java.util.List<BlockPos> findFeature(ChunkFeature feature) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (ChunkMemory cm : chunkMemories.values()) {
            BlockPos p = cm.anchorOf(feature);
            if (p != null) out.add(p);
        }
        return out;
    }

    public java.util.List<BlockPos> findOre(ChunkFeature ore) { return findFeature(ore); }

    /** Any remembered ore of any kind, one position per chunk. */
    public java.util.List<BlockPos> findAnyOre() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (ChunkMemory cm : chunkMemories.values()) {
            for (ChunkFeature f : ChunkFeature.values()) {
                if (!f.isOre()) continue;
                BlockPos p = cm.anchorOf(f);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    /**
     * Recall buildings near a position. Combines (1) personal chunk-memory "village" tags
     * and (2) the shared WorldStructureIndex (which also covers player-built and cave houses).
     */
    public java.util.List<String> rememberNearbyHouses(World world, int radiusChunks, BlockPos here) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int hx = here.getX() >> 4, hz = here.getZ() >> 4;
        // 1) personal memory
        for (ChunkMemory cm : chunkMemories.values()) {
            if (!cm.has(ChunkTag.VILLAGE)) continue;
            if (Math.abs(cm.getX() - hx) <= radiusChunks && Math.abs(cm.getZ() - hz) <= radiusChunks) {
                out.add("a building cluster around chunk (" + cm.getX() + "," + cm.getZ() + ")");
            }
        }
        // 2) shared index (includes cave houses)
        if (world != null) {
            for (BuildingRecord r : WorldStructureIndex.instance(world).queryNear(here, radiusChunks)) {
                out.add("a " + r.coarseType + " near (" + r.boundsMin.getX() + "," + r.boundsMin.getZ()
                        + "), with a bed at " + r.seedBed.getX() + "," + r.seedBed.getZ());
            }
        }
        return out;
    }

    public boolean remembersWaterNearby() {
        for (ChunkMemory cm : chunkMemories.values()) if (cm.has(ChunkTag.WATER)) return true;
        return false;
    }

    public boolean remembersLavaDanger() {
        for (ChunkMemory cm : chunkMemories.values()) if (cm.has(ChunkTag.DANGER_LAVA)) return true;
        return false;
    }

    /**
     * How many entities of a category were around the last time each remembered chunk was
     * visited. Positions are deliberately not stored (they are stale within seconds) — use
     * the live frustum scan when an actual position is needed.
     */
    public int countRememberedEntities(EntityCategory cat) {
        int n = 0;
        for (ChunkMemory cm : chunkMemories.values()) n += cm.entityCount(cat);
        return n;
    }

    public void addMemory(String memory) {
        memories.add(memory);
        // Trigger LLM compression if we have too many memories and none is in flight
        if (memories.size() >= MEMORY_SUMMARIZE_THRESHOLD && !summarizingMemories) {
            summarizeOldMemories();
        }
    }

    /**
     * Asynchronously compress the oldest {@link #MEMORY_SUMMARIZE_COUNT} memories
     * into 2-3 condensed sentences and replace them in-place.
     * Guards against re-entry with {@link #summarizingMemories}.
     */
    private void summarizeOldMemories() {
        summarizingMemories = true;

        // Snapshot the oldest entries to summarize
        int count = Math.min(MEMORY_SUMMARIZE_COUNT, memories.size());
        List<String> toSummarize = new ArrayList<>(memories.subList(0, count));

        StringBuilder memSb = new StringBuilder();
        for (String m : toSummarize) memSb.append("- ").append(m).append("\n");

        String sysPrompt = "Summarize the following Minecraft villager memories into 2-3 concise sentences "
                + "written in first person. Preserve the most important facts. "
                + "Output only the sentences — no labels or extra text.";
        String userPrompt = "Villager: " + name + " (" + profession + ")\nMemories to summarize:\n" + memSb;

        LLMService.queryLLM(sysPrompt, userPrompt).thenAccept(summary -> {
            synchronized (memories) {
                // Remove the entries we summarized (they may have shifted — remove up to count)
                int removeCount = Math.min(count, memories.size());
                memories.subList(0, removeCount).clear();
                // Prepend the summary so it reads as "old context" at the front
                if (summary != null && !summary.trim().isEmpty() && !LLMService.isFailure(summary)) {
                    memories.add(0, "[Summary of earlier memories] " + summary.trim());
                }
            }
            summarizingMemories = false;
            LOGGER.debug("{} memory compressed ({} → 1 summary)", name, count);
        }).exceptionally(e -> {
            // On failure just hard-cap — remove oldest to stay under threshold
            synchronized (memories) {
                int excess = memories.size() - MEMORY_SUMMARIZE_THRESHOLD + 5;
                if (excess > 0) memories.subList(0, excess).clear();
            }
            summarizingMemories = false;
            return null;
        });
    }

    public long getLastThoughtTick() { return lastThoughtTick; }
    public void setLastThoughtTick(long tick) { this.lastThoughtTick = tick; }
    
    /**
     * Record a conversation line with the current game tick.
     * Entries older than 1 Minecraft day (24 000 ticks) are pruned automatically.
     */
    public void addConversation(String conversation, long gameTick) {
        conversationHistory.add(new TimestampedMessage(conversation, gameTick));
        pruneExpiredConversations(gameTick);
    }

    /**
     * Remove conversation entries older than {@link #CONVERSATION_EXPIRY_TICKS}
     * and enforce the hard cap {@link #MAX_CONVERSATION_ENTRIES}.
     */
    public void pruneExpiredConversations(long currentTick) {
        conversationHistory.removeIf(msg ->
                currentTick - msg.getGameTick() > CONVERSATION_EXPIRY_TICKS);
        while (conversationHistory.size() > MAX_CONVERSATION_ENTRIES) {
            conversationHistory.remove(0);
        }
    }

    public void recordObservation(VillagerObservation observation) {
        addMemory("Observed: " + observation.toString());
    }

    /**
     * Build an action request for the LLM
     * Called after each task is completed to decide what to do next
     */
    public ActionRequest buildActionRequest() {
        ActionRequest request = new ActionRequest(villagerId, name, profession, personality);

        // Set current inventory
        java.util.List<String> inventoryItems = new java.util.ArrayList<>();
        for (net.minecraft.item.ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                inventoryItems.add(stack.getCount() + "x " + stack.getItem().getRegistryName());
            }
        }
        request.setInventoryItems(inventoryItems);

        // Set recent observations (last 10)
        java.util.List<String> recentObs = new java.util.ArrayList<>();
        int start = Math.max(0, memories.size() - 10);
        for (int i = start; i < memories.size(); i++) {
            if (memories.get(i).startsWith("Observed:")) {
                recentObs.add(memories.get(i));
            }
        }
        request.setRecentObservations(recentObs);

        // Set goals
        java.util.List<String> goalStrings = new java.util.ArrayList<>();
        for (AgentGoal goal : goals) {
            goalStrings.add(goal.toString());
        }
        request.setGoals(goalStrings);

        // Set memories
        request.setMemories(new java.util.ArrayList<>(memories));

        // Set craftable items for this profession (goal-driven catalogue)
        request.setAvailableRecipes(new java.util.ArrayList<>(ProfessionCraftCatalog.getAllIds(profession)));

        // Set available actions
        java.util.List<String> actions = new java.util.ArrayList<>();
        actions.add("CRAFT - Make an item at a workstation");
        actions.add("HARVEST - Gather crops or resources");
        actions.add("GROW - Plant seeds or grow crops");
        actions.add("ATTACK - Attack a hostile entity");
        actions.add("IDLE - Rest for a while");
        actions.add("MOVE - Go to a location");
        actions.add("GATHER - Pick up items from ground");
        actions.add("PLACE - Place a block within 1 block of you (the block must be in your inventory)");
        actions.add("BREAK - Break a block within 1 block of you");
        actions.add("BUILD - Design and build a structure (the LLM provides the layout)");
        request.setAvailableActions(actions);

        return request;
    }

    public void updateRelationship(String otherVillagerId, int change) {
        int current = relationships.getOrDefault(otherVillagerId, 0);
        relationships.put(otherVillagerId, Math.max(-100, Math.min(100, current + change)));
    }

    /**
     * Generate a chat response using LLM.
     * Includes the full recent conversation history (within 1 game-day) so the
     * villager can reference earlier exchanges when replying.
     *
     * @param playerName    The name of the player talking to the villager
     * @param playerMessage The message from the player (null for greeting)
     * @param gameTick      The current world game tick (used for timestamping)
     * @return CompletableFuture with the villager's response
     */
    public CompletableFuture<String> generateChatResponse(String playerName, String playerMessage, long gameTick,
                                                          VillagerEntity entity, UUID playerUuid) {
        // Prune stale entries before building the prompt
        pruneExpiredConversations(gameTick);

        // ── System prompt ──
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("You are ").append(name).append(", a ").append(profession)
                .append(" in a medieval Minecraft village. ")
                .append("Your job/profession is: ").append(profession).append(". ")
                .append("Your personality: ").append(personality).append(". ")
                .append("Respond in character as this villager. Keep responses short (1-2 sentences). ")
                .append("Be friendly but stay in character. Don't break the fourth wall. ")
                .append("Your profession shapes your skills and interests, but it does NOT determine what you own or trade. ")
                .append("Never claim you lack an item just because of your profession — always check your inventory. ")
                .append("You remember conversations from today. If the player refers to something said earlier, ")
                .append("use the conversation history below to give a consistent, contextual reply. ")
                .append("If the player asks whether you have an item, or wants to buy or trade something, ")
                .append("answer strictly from your ACTUAL inventory: if it is there, say so and discuss a deal; ")
                .append("if it is not, say you do not have it. Never guess from your profession. ");

        // Inject the craftable catalog + the TODO directive protocol so the villager can turn
        // conversation into executable goals (craft / move / gather / socialize / trade).
        java.util.List<String> craftable = ProfessionCraftCatalog.getAllIds(profession);
        if (!craftable.isEmpty()) {
            systemPromptBuilder.append(" As a ").append(profession)
                    .append(", you are ABLE TO CRAFT the following (this is a CRAFTING CAPABILITY only, ")
                    .append("NOT what you currently hold): ").append(String.join(", ", craftable)).append(". ");
            systemPromptBuilder.append("If you decide to act — now, or as a task to remember — append one or more "
                    + "lines starting with TODO: (each on its own line): ")
                    .append("TODO: craft minecraft:<item_id> x<qty>  |  TODO: move <x> <y> <z>  |  "
                            + "TODO: gather minecraft:<item> x<n>  |  TODO: goto house  |  TODO: goto cave_house  |  "
                            + "TODO: socialize  |  TODO: trade villager <profession-or-any>  |  "
                            + "TODO: converse player <uuid>  |  TODO: converse villager <profession-or-any> . ")
                    .append("Use the exact minecraft:<id> form for items you can make. ")
                    .append("To go to a nearby building, use TODO: goto <type> where <type> is one of the building "
                            + "types you see in your environment (e.g. house, cave_house) or just 'building'. ")
                    .append("These TODO lines are hidden from the player, so write your spoken reply first, "
                            + "then the TODO lines.");
        }

        // Centralised, ground-truth snapshot of the villager's full current state.
        // One method = one source of truth, so future state fields can't accidentally
        // be left out of the prompt the way the backpack once was.
        systemPromptBuilder.append(" ").append(buildVillagerStateBlock(entity, playerName, playerUuid));

        String systemPrompt = systemPromptBuilder.toString();

        // ── Build user prompt with context ──
        StringBuilder context = new StringBuilder();

        // Recent memories (non-conversation observations)
        if (!memories.isEmpty()) {
            context.append("Recent memories: ");
            int start = Math.max(0, memories.size() - 5);
            for (int i = start; i < memories.size(); i++) {
                context.append(memories.get(i)).append(". ");
            }
            context.append("\n");
        }

        // Conversation history from today
        if (!conversationHistory.isEmpty()) {
            context.append("Conversation history from today:\n");
            for (TimestampedMessage msg : conversationHistory) {
                context.append("- ").append(msg.getText()).append("\n");
            }
            context.append("\n");
        }

        String userPrompt;
        if (playerMessage == null || playerMessage.isEmpty()) {
            userPrompt = context + "A player named " + playerName + " approaches you. Greet them as a " + profession + ".";
        } else {
            userPrompt = context + "Now, player " + playerName + " says: \"" + playerMessage + "\". " +
                    "Respond in character. You may reference earlier parts of the conversation if relevant.";
        }

        final long tick = gameTick; // capture for lambda
        UUID id = getVillagerId();
        return LLMGuard.query(id, tick, systemPrompt, userPrompt)
                .thenApply(response -> {
                    // P0 fix: a failure sentinel is never the villager's actual speech.
                    if (LLMService.isFailure(response)) {
                        DecisionJournal.record(id, DecisionJournal.Kind.CHAT, tick, userPrompt, response, "failure");
                        return "Hmm... I seem to have lost my train of thought.";
                    }
                    DecisionJournal.record(id, DecisionJournal.Kind.CHAT, tick, userPrompt, response, "ok");
                    // Store both sides of the exchange with the current game tick
                    addConversation(playerName + ": " + (playerMessage != null ? playerMessage : "[greeting]"), tick);
                    addConversation(name + ": " + response, tick);
                    return response;
                })
                .exceptionally(e -> {
                    LOGGER.error("Error generating chat response: " + e.getMessage());
                    return "Hmm... I seem to have lost my train of thought.";
                });
    }

    /**
     * Render the villager's ACTUAL inventory (what it truly holds right now) for the chat
     * prompt. Mirrors {@code TradeRequestPacket.buildInventoryDescription} but items-only,
     * since the chat path has no {@link VillagerEntity} reference for equipped armor.
     * This is the ground-truth the LLM must use instead of guessing from its profession.
     */
    private String buildInventoryDescription() {
        java.util.Map<String, Integer> itemCounts = new java.util.HashMap<>();
        for (net.minecraft.item.ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                String regName = stack.getItem().getRegistryName().toString();
                if (regName.contains(":")) regName = regName.substring(regName.indexOf(":") + 1);
                regName = regName.replace("_", " ");
                itemCounts.merge(regName, stack.getCount(), Integer::sum);
            }
        }
        if (itemCounts.isEmpty()) {
            return "Your inventory is currently EMPTY - you are not holding any items right now.";
        }
        StringBuilder sb = new StringBuilder(
                "Your ACTUAL inventory (GROUND TRUTH — the ONLY items you physically hold, regardless of your profession): ");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Single source of truth for "what is the villager's current state" — rendered once and
     * injected into the chat system prompt. Every piece of villager-related context the LLM
     * should know lives here, so adding new state means editing ONE method instead of hunting
     * through scattered append() calls (the gap that let the backpack go untransmitted before).
     *
     * @param entity     the live entity (may be null); supplies armor, profession level, health, location
     * @param playerName the player currently talking (for the familiarity line)
     * @param playerUuid the player's UUID (for the greet-count lookup)
     */
    private String buildVillagerStateBlock(VillagerEntity entity, String playerName, UUID playerUuid) {
        StringBuilder sb = new StringBuilder();

        // Profession level (lives on the entity, not the data bag)
        if (entity != null) {
            int lvl = entity.getVillagerData().getLevel();
            sb.append("Profession level: ").append(lvl).append(" (").append(levelName(lvl)).append("). ");
        }

        // Mood + hunger/fatigue (buildNeedsDescription already folds mood in)
        String needs = VillagerNeedsSystem.buildNeedsDescription(this);
        if (!needs.isEmpty()) sb.append(needs).append(" ");

        // What the villager is doing right now
        if (currentActivity != null && !currentActivity.isEmpty()) {
            sb.append("Right now you are ").append(currentActivity.toLowerCase()).append(". ");
        }
        if (currentAction != null && currentAction.getActionType() != VillagerAction.ActionType.UNKNOWN) {
            sb.append("Your current action: ").append(currentAction.getActionType().name().toLowerCase()).append(". ");
        }

        // ACTUAL inventory (ground truth, not profession stereotype)
        sb.append(buildInventoryDescription()).append(" ");

        // Equipped armor / gear (lives on the entity, NOT in AgentInventory)
        if (entity != null) {
            sb.append(describeEquipment(entity)).append(" ");
        }

        // Current goals / objectives (by importance, capped)
        if (goals != null && !goals.isEmpty()) {
            List<AgentGoal> active = new ArrayList<>();
            for (AgentGoal g : goals) if (!g.isCompleted()) active.add(g);
            active.sort((a, b) -> Integer.compare(b.getImportance(), a.getImportance()));
            if (!active.isEmpty()) {
                sb.append("Your current goals (by importance): ");
                int n = Math.min(active.size(), 6);
                for (int i = 0; i < n; i++) {
                    AgentGoal g = active.get(i);
                    sb.append(g.getDescription());
                    if (g.getTargetItem() != null) sb.append(" (target: ").append(g.getTargetItem()).append(")");
                    sb.append("; ");
                }
                sb.append(" ");
            }
        }

        // Long-term commitments (persistent intentions: debts, deals, acquisitions).
        // Unlike goals, these stay until resolved and are re-expressed as todos by reflection.
        if (agendas != null && !agendas.isEmpty()) {
            List<LongTermAgenda> active = new ArrayList<>();
            for (LongTermAgenda a : agendas) if (!a.isResolved()) active.add(a);
            if (!active.isEmpty()) {
                sb.append("Your long-term commitments (persistent, keep these in mind): ");
                int n = Math.min(active.size(), 8);
                for (int i = 0; i < n; i++) {
                    sb.append(active.get(i).describe()).append("; ");
                }
                sb.append(" ");
            }
        }

        // Today's plan
        if (scheduledActivity != null && !scheduledActivity.isEmpty()) {
            sb.append("Your scheduled activity right now: ").append(scheduledActivity).append(". ");
        } else if (dailySchedule != null) {
            sb.append("You have a daily schedule planned. ");
        }

        // Active build job
        if (currentBuildJob != null && !currentBuildJob.isComplete()) {
            sb.append("You are building project '").append(currentBuildJob.getName())
              .append("' (").append(currentBuildJob.getCursor()).append("/")
              .append(currentBuildJob.getTotal()).append(" blocks placed). ");
        }

        // Familiarity with THIS player
        if (playerUuid != null) {
            Integer seen = greetCount.get(playerUuid);
            int n = (seen == null) ? 0 : seen;
            String fam = n == 0 ? "you have never met before"
                        : n <= 2 ? "you have met a few times"
                        : "you know them fairly well";
            sb.append("Your relationship with this player (").append(playerName)
              .append("): ").append(fam).append(". ");
        }

        // Location (cheap grounding)
        if (entity != null) {
            BlockPos p = entity.blockPosition();
            sb.append("Your location: x=").append(p.getX()).append(", y=").append(p.getY())
              .append(", z=").append(p.getZ()).append(". ");
        }

        // Environment snapshot (refreshed each chat by ChatMessagePacket)
        if (lastEnvironmentSummary != null && !lastEnvironmentSummary.isEmpty()) {
            sb.append("Environment around you: ").append(lastEnvironmentSummary).append(" ");
        }

        return sb.toString().trim();
    }

    private static String levelName(int level) {
        switch (level) {
            case 1: return "Novice";
            case 2: return "Apprentice";
            case 3: return "Journeyman";
            case 4: return "Expert";
            case 5: return "Master";
            default: return "Novice";
        }
    }

    private static String describeEquipment(VillagerEntity entity) {
        EquipmentSlotType[] slots = {
            EquipmentSlotType.HEAD, EquipmentSlotType.CHEST, EquipmentSlotType.LEGS,
            EquipmentSlotType.FEET, EquipmentSlotType.MAINHAND, EquipmentSlotType.OFFHAND
        };
        List<String> parts = new ArrayList<>();
        for (EquipmentSlotType slot : slots) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack != null && !stack.isEmpty()) {
                String reg = stack.getItem().getRegistryName().toString();
                if (reg.contains(":")) reg = reg.substring(reg.indexOf(":") + 1);
                reg = reg.replace("_", " ");
                parts.add(reg + " (" + slot.name().toLowerCase() + ")");
            }
        }
        if (parts.isEmpty()) return "You are not wearing any armor or holding any gear.";
        return "You are equipped with: " + String.join(", ", parts) + ".";
    }

    // NBT serialization for saving/loading
    public CompoundNBT serializeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        nbt.putUUID("VillagerId", villagerId);
        nbt.putString("Name", name);
        nbt.putString("Profession", profession);
        nbt.putString("Personality", personality);
        nbt.putString("CurrentActivity", currentActivity);
        nbt.putLong("LastThinkTime", lastThinkTime);
        nbt.putLong("LastRestockTime", lastRestockTime);  // Save restock time
        nbt.putLong("LastDayPlanned", lastDayPlanned);
        nbt.putLong("LastDayReflected", lastDayReflected);
        nbt.putFloat("Hunger", hunger);
        nbt.putFloat("Fatigue", fatigue);

        // Save chunk memories (each chunk's sampled content + entities)
        if (!chunkMemories.isEmpty()) {
            ListNBT memList = new ListNBT();
            for (ChunkMemory cm : chunkMemories.values()) memList.add(cm.writeNBT());
            nbt.put("ChunkMemories", memList);
        }

        // Save memories
        ListNBT memoriesNBT = new ListNBT();
        for (String memory : memories) {
            CompoundNBT memNBT = new CompoundNBT();
            memNBT.putString("Memory", memory);
            memoriesNBT.add(memNBT);
        }
        nbt.put("Memories", memoriesNBT);

        // Save conversation history (with timestamps)
        ListNBT conversationsNBT = new ListNBT();
        for (TimestampedMessage msg : conversationHistory) {
            CompoundNBT convNBT = new CompoundNBT();
            convNBT.putString("Text", msg.getText());
            convNBT.putLong("Tick", msg.getGameTick());
            conversationsNBT.add(convNBT);
        }
        nbt.put("Conversations", conversationsNBT);

        // Save inventory
        nbt.put("Inventory", inventory.serializeNBT());

        // Save active build job (if any)
        if (currentBuildJob != null) {
            nbt.put("BuildJob", currentBuildJob.writeNBT());
        }

        // ── Relationship graph: other villager UUID (string) -> affinity score ──
        // Without this, every restart wipes inter-villager relationships (gossip ±2, etc.).
        if (!relationships.isEmpty()) {
            ListNBT relList = new ListNBT();
            for (Map.Entry<String, Integer> e : relationships.entrySet()) {
                CompoundNBT relNBT = new CompoundNBT();
                relNBT.putString("Id", e.getKey());
                relNBT.putInt("Score", e.getValue());
                relList.add(relNBT);
            }
            nbt.put("Relationships", relList);
        }

        // ── Greeting familiarity per player: UUID -> last greeted tick / greet count ──
        // Without this, villagers "forget" they already greeted a player after a restart.
        if (!lastGreetedPlayer.isEmpty()) {
            ListNBT greetList = new ListNBT();
            for (Map.Entry<UUID, Long> e : lastGreetedPlayer.entrySet()) {
                CompoundNBT gNBT = new CompoundNBT();
                gNBT.putUUID("Player", e.getKey());
                gNBT.putLong("Tick", e.getValue());
                greetList.add(gNBT);
            }
            nbt.put("LastGreetedPlayer", greetList);
        }
        if (!greetCount.isEmpty()) {
            ListNBT countList = new ListNBT();
            for (Map.Entry<UUID, Integer> e : greetCount.entrySet()) {
                CompoundNBT cNBT = new CompoundNBT();
                cNBT.putUUID("Player", e.getKey());
                cNBT.putInt("Count", e.getValue());
                countList.add(cNBT);
            }
            nbt.put("GreetCount", countList);
        }

        // ── Long-term agendas (persistent intentions: debts, deals, acquisitions) ──
        if (!agendas.isEmpty()) {
            ListNBT agendaList = new ListNBT();
            for (LongTermAgenda a : agendas) agendaList.add(a.writeNBT());
            nbt.put("Agendas", agendaList);
        }

        // ── Player reputation: UUID -> affinity score ──
        if (!playerReputation.isEmpty()) {
            ListNBT repList = new ListNBT();
            for (Map.Entry<UUID, Integer> e : playerReputation.entrySet()) {
                CompoundNBT repNBT = new CompoundNBT();
                repNBT.putUUID("Player", e.getKey());
                repNBT.putInt("Score", e.getValue());
                repList.add(repNBT);
            }
            nbt.put("PlayerReputation", repList);
        }

        // ── Current mood (derived from needs; cheap to restore, avoids a NEUTRAL flicker) ──
        nbt.putInt("Mood", mood.ordinal());

        return nbt;
    }

    public void deserializeNBT(CompoundNBT nbt) {
        this.villagerId = nbt.getUUID("VillagerId");
        this.name = nbt.getString("Name");
        this.profession = nbt.contains("Profession") ? nbt.getString("Profession") : "Villager";
        this.personality = nbt.getString("Personality");
        this.currentActivity = nbt.getString("CurrentActivity");
        this.lastThinkTime = nbt.getLong("LastThinkTime");
        this.lastRestockTime = nbt.contains("LastRestockTime") ? nbt.getLong("LastRestockTime") : 0;  // Load restock time
        this.lastDayPlanned = nbt.contains("LastDayPlanned") ? nbt.getLong("LastDayPlanned") : -1L;
        this.lastDayReflected = nbt.contains("LastDayReflected") ? nbt.getLong("LastDayReflected") : -1L;
        this.hunger  = nbt.contains("Hunger")  ? nbt.getFloat("Hunger")  : 100f;
        this.fatigue = nbt.contains("Fatigue") ? nbt.getFloat("Fatigue") : 0f;

        // Load chunk memories (old "VisitedChunks" long-array saves are ignored — no crash)
        chunkMemories.clear();
        if (nbt.contains("ChunkMemories")) {
            ListNBT memList = nbt.getList("ChunkMemories", 10);
            for (int i = 0; i < memList.size(); i++) {
                ChunkMemory cm = ChunkMemory.readNBT(memList.getCompound(i));
                chunkMemories.put(cm.chunkKey, cm);
            }
            // Reset lastChunkKey so the next chunk entry triggers a fresh update
            lastChunkKey = Long.MIN_VALUE;
        }

        // Load memories
        ListNBT memoriesNBT = nbt.getList("Memories", 10);
        memories.clear();
        for (int i = 0; i < memoriesNBT.size(); i++) {
            CompoundNBT memNBT = memoriesNBT.getCompound(i);
            memories.add(memNBT.getString("Memory"));
        }

        // Load conversations (supports both old plain-string and new timestamped format)
        ListNBT conversationsNBT = nbt.getList("Conversations", 10);
        conversationHistory.clear();
        for (int i = 0; i < conversationsNBT.size(); i++) {
            CompoundNBT convNBT = conversationsNBT.getCompound(i);
            if (convNBT.contains("Text")) {
                // New timestamped format
                conversationHistory.add(new TimestampedMessage(
                        convNBT.getString("Text"),
                        convNBT.getLong("Tick")));
            } else if (convNBT.contains("Conversation")) {
                // Legacy format — assign tick 0 (will be pruned on first access)
                conversationHistory.add(new TimestampedMessage(
                        convNBT.getString("Conversation"), 0L));
            }
        }

        // Load inventory
        if (nbt.contains("Inventory")) {
            inventory.deserializeNBT(nbt.getCompound("Inventory"));
        }

        // Load active build job (reconciled against the real world later, in tickBuilding)
        if (nbt.contains("BuildJob")) {
            try {
                currentBuildJob = BuildJob.readNBT(nbt.getCompound("BuildJob"));
            } catch (Exception e) {
                LOGGER.warn("Failed to load BuildJob from NBT: " + e.getMessage());
                currentBuildJob = null;
            }
        }

        // ── Relationship graph ──
        relationships.clear();
        if (nbt.contains("Relationships")) {
            ListNBT relList = nbt.getList("Relationships", 10);
            for (int i = 0; i < relList.size(); i++) {
                CompoundNBT relNBT = relList.getCompound(i);
                relationships.put(relNBT.getString("Id"), relNBT.getInt("Score"));
            }
        }

        // ── Greeting familiarity per player ──
        lastGreetedPlayer.clear();
        if (nbt.contains("LastGreetedPlayer")) {
            ListNBT greetList = nbt.getList("LastGreetedPlayer", 10);
            for (int i = 0; i < greetList.size(); i++) {
                CompoundNBT gNBT = greetList.getCompound(i);
                lastGreetedPlayer.put(gNBT.getUUID("Player"), gNBT.getLong("Tick"));
            }
        }
        greetCount.clear();
        if (nbt.contains("GreetCount")) {
            ListNBT countList = nbt.getList("GreetCount", 10);
            for (int i = 0; i < countList.size(); i++) {
                CompoundNBT cNBT = countList.getCompound(i);
                greetCount.put(cNBT.getUUID("Player"), cNBT.getInt("Count"));
            }
        }

        // ── Mood ──
        if (nbt.contains("Mood")) {
            int ord = nbt.getInt("Mood");
            Mood[] vals = Mood.values();
            mood = (ord >= 0 && ord < vals.length) ? vals[ord] : Mood.NEUTRAL;
        }

        // ── Long-term agendas ──
        agendas.clear();
        if (nbt.contains("Agendas")) {
            ListNBT agendaList = nbt.getList("Agendas", 10);
            for (int i = 0; i < agendaList.size(); i++) {
                try {
                    agendas.add(LongTermAgenda.fromNBT(agendaList.getCompound(i)));
                } catch (Exception e) {
                    LOGGER.warn("Failed to load LongTermAgenda from NBT: " + e.getMessage());
                }
            }
        }

        // ── Player reputation ──
        playerReputation.clear();
        if (nbt.contains("PlayerReputation")) {
            ListNBT repList = nbt.getList("PlayerReputation", 10);
            for (int i = 0; i < repList.size(); i++) {
                CompoundNBT repNBT = repList.getCompound(i);
                playerReputation.put(repNBT.getUUID("Player"), repNBT.getInt("Score"));
            }
        }
    }
}

