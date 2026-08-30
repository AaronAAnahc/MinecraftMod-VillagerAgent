package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Food;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Simulates basic needs (hunger and fatigue) for villager agents.
 *
 * <h3>Hunger (0–100, starts full at 100)</h3>
 * <ul>
 *   <li>Decays by {@link #HUNGER_DECAY_RATE} every {@link #DECAY_INTERVAL} ticks.</li>
 *   <li>When hunger falls below {@link #EAT_THRESHOLD}, the villager automatically
 *       consumes one food item from their {@link AgentInventory} and gains back
 *       hunger proportional to the item's nutrition value.</li>
 *   <li>If no food is available at starvation level ({@code < 10}), a memory is added.</li>
 * </ul>
 *
 * <h3>Fatigue (0–100, starts at 0)</h3>
 * <ul>
 *   <li>Increases during nighttime (dayTime 13000–23000) at {@link #FATIGUE_GAIN_RATE}.</li>
 *   <li>Resets to 0 at dawn (dayTime 0–500).</li>
 * </ul>
 *
 * <p>Both values are persisted via {@link VillagerAgentData}'s NBT methods.
 */
public class VillagerNeedsSystem {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Ticks between each hunger-decay step (~1 Minecraft minute = 1200 ticks). */
    private static final int DECAY_INTERVAL = 1200;

    /** Hunger points lost per decay step. */
    private static final float HUNGER_DECAY_RATE = 5.0f;

    /** Hunger below this level triggers automatic eating. */
    private static final float EAT_THRESHOLD = 40.0f;

    /** Hunger gained per food nutrition point (vanilla nutrition * this factor). */
    private static final float NUTRITION_FACTOR = 8.0f;

    /** Fatigue points gained per decay step during nighttime. */
    private static final float FATIGUE_GAIN_RATE = 4.0f;

    private static final long DAY_LENGTH = 24_000L;
    private static final long NIGHT_START = 13_000L;
    private static final long DAWN_END    =    500L;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Tick the needs system for a single villager.
     * Should be called from the slow-tick update loop (every {@code AGENT_THINK_INTERVAL} ticks).
     */
    public static void tick(ServerWorld world, VillagerAgentData agent) {
        long gameTime  = world.getGameTime();
        long dayTime   = world.getDayTime() % DAY_LENGTH;

        if (agent.getLastNeedsTick() <= 0L) {
            agent.setLastNeedsTick(gameTime);
            return;
        }

        if (gameTime - agent.getLastNeedsTick() < DECAY_INTERVAL) return;
        agent.setLastNeedsTick(gameTime);

        // ── Hunger ──
        float hunger = agent.getHunger();
        hunger = Math.max(0f, hunger - HUNGER_DECAY_RATE);
        agent.setHunger(hunger);

        if (hunger < EAT_THRESHOLD) {
            tryEat(agent, gameTime);
        }

        // ── Fatigue ──
        if (dayTime >= NIGHT_START || dayTime < DAWN_END) {
            float fatigue = Math.min(100f, agent.getFatigue() + FATIGUE_GAIN_RATE);
            agent.setFatigue(fatigue);
        } else if (dayTime >= DAWN_END && dayTime < NIGHT_START) {
            // Gradually recover during the day
            float fatigue = Math.max(0f, agent.getFatigue() - FATIGUE_GAIN_RATE * 0.5f);
            agent.setFatigue(fatigue);
        }

        // ── Mood (re-derived every needs tick) ──
        agent.setMood(deriveMood(agent));
    }

    // ── Eating ───────────────────────────────────────────────────────────────

    private static void tryEat(VillagerAgentData agent, long gameTime) {
        AgentInventory inv = agent.getInventory();
        List<ItemStack> items = inv.getItems();

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!item.isEdible()) continue;

            Food food = item.getFoodProperties();
            if (food == null) continue;

            // Consume one food item
            stack.shrink(1);
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);

            float restored = food.getNutrition() * NUTRITION_FACTOR;
            float newHunger = Math.min(100f, agent.getHunger() + restored);
            agent.setHunger(newHunger);

            String foodName = item.getRegistryName() != null
                    ? item.getRegistryName().getPath().replace("_", " ")
                    : "some food";
            agent.addMemory("Ate " + foodName + " — hunger satisfied");
            LOGGER.debug("{} ate {} (hunger: {} -> {})",
                    agent.getName(), foodName, (int) (agent.getHunger() - restored), (int) newHunger);
            return;
        }

        // No food found
        if (agent.getHunger() < 10f) {
            agent.addMemory("I'm very hungry but have nothing to eat!");
            LOGGER.debug("{} is starving with no food", agent.getName());
        }
    }

    // ── Mood derivation ───────────────────────────────────────────────────────

    /**
     * Derives a {@link VillagerAgentData.Mood} from hunger, fatigue, and the
     * average relationship score.  Call this after updating needs each tick and
     * store the result with {@code agent.setMood(...)}.
     *
     * <p>Score weights (lower is worse):
     * <ul>
     *   <li>-2 pts if hunger &lt; 20  (starving)</li>
     *   <li>-1 pt  if hunger &lt; 50  (hungry)</li>
     *   <li>-2 pts if fatigue &gt; 80 (exhausted)</li>
     *   <li>-1 pt  if fatigue &gt; 50 (tired)</li>
     *   <li>+2 pts if avgRel &gt; 40  (well-liked)</li>
     *   <li>+1 pt  if avgRel &gt; 10  (accepted)</li>
     *   <li>-1 pt  if avgRel &lt; -10 (somewhat disliked)</li>
     *   <li>-2 pts if avgRel &lt; -40 (strongly disliked)</li>
     * </ul>
     * Total score → HAPPY (≥3), CONTENT (1–2), NEUTRAL (0), ANXIOUS (−1–−2), DISTRESSED (≤−3).
     */
    public static VillagerAgentData.Mood deriveMood(VillagerAgentData agent) {
        int score = 0;

        float hunger  = agent.getHunger();
        float fatigue = agent.getFatigue();

        if      (hunger < 20f) score -= 2;
        else if (hunger < 50f) score -= 1;

        if      (fatigue > 80f) score -= 2;
        else if (fatigue > 50f) score -= 1;

        // Average relationship score
        Map<String, Integer> rels = agent.getRelationships();
        if (!rels.isEmpty()) {
            int sum = 0;
            for (int v : rels.values()) sum += v;
            int avgRel = sum / rels.size();
            if      (avgRel >  40) score += 2;
            else if (avgRel >  10) score += 1;
            else if (avgRel < -40) score -= 2;
            else if (avgRel < -10) score -= 1;
        }

        if      (score >=  3) return VillagerAgentData.Mood.HAPPY;
        else if (score >=  1) return VillagerAgentData.Mood.CONTENT;
        else if (score >=  0) return VillagerAgentData.Mood.NEUTRAL;
        else if (score >= -2) return VillagerAgentData.Mood.ANXIOUS;
        else                  return VillagerAgentData.Mood.DISTRESSED;
    }

    // ── Combined LLM description (mood + needs) ───────────────────────────────

    /**
     * Returns a brief description of the villager's current needs AND mood,
     * suitable for injection into an LLM system prompt.
     */
    public static String buildNeedsDescription(VillagerAgentData agent) {
        StringBuilder sb = new StringBuilder();

        float hunger  = agent.getHunger();
        float fatigue = agent.getFatigue();

        if      (hunger < 10f)  sb.append("You are starving and feel very weak. ");
        else if (hunger < 30f)  sb.append("You are quite hungry and distracted. ");
        else if (hunger < 50f)  sb.append("You feel a bit hungry. ");

        if      (fatigue > 80f) sb.append("You are exhausted and desperately need rest. ");
        else if (fatigue > 50f) sb.append("You feel quite tired. ");
        else if (fatigue > 20f) sb.append("You feel a little tired. ");

        // Mood description
        switch (agent.getMood()) {
            case HAPPY:      sb.append("You are in a great mood today — cheerful and energetic. "); break;
            case CONTENT:    sb.append("You feel generally content and at ease. "); break;
            case NEUTRAL:    /* nothing extra */ break;
            case ANXIOUS:    sb.append("You feel anxious and unsettled. "); break;
            case DISTRESSED: sb.append("You feel deeply distressed and unhappy. "); break;
        }

        return sb.toString().trim();
    }
}

