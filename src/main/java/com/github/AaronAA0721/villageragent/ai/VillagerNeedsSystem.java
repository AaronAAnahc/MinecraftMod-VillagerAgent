package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;

import net.minecraft.world.server.ServerWorld;

import java.util.Map;

/**
 * Simulates basic needs (hunger and fatigue) for villager agents.
 *
 * <h3>Hunger (0–100, starts full at 100)</h3>
 * <ul>
 *   <li>Decays by the configured {@code hunger_decay_per_minute} every {@link #DECAY_INTERVAL} ticks.</li>
 *   <li>When hunger falls below {@link #EAT_THRESHOLD}, a goal-driven {@code eat} task is seeded;
 *       {@link com.github.AaronAA0721.villageragent.ai.behavior.EatSkill} performs the eating
 *       (chewing animation, sounds, acquisition when no food is carried). Starvation raises the
 *       goal into the emergency priority band.</li>
 * </ul>
 *
 * <h3>Fatigue (0–100, starts at 0)</h3>
 * <ul>
 *   <li>Accumulates while awake: 3 points/1200 ticks idle or moving, 12 during other activities.</li>
 *   <li>Sleep restores fatigue; dawn does not reset it.</li>
 * </ul>
 *
 * <p>Both values are persisted via {@link VillagerAgentData}'s NBT methods.
 */
public class VillagerNeedsSystem {

    /** Ticks between each hunger-decay step (~1 Minecraft minute = 1200 ticks). */
    private static final int DECAY_INTERVAL = 1200;


    /** Hunger below this level triggers automatic eating. */
    private static final float EAT_THRESHOLD = 60.0f;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Tick the needs system for a single villager.
     * Should be called from the slow-tick update loop (every {@code AGENT_THINK_INTERVAL} ticks).
     */
    public static void tick(ServerWorld world, VillagerAgentData agent) {
        long gameTime  = world.getGameTime();
        if (agent.brain().lastNeedsTick < 0) { agent.brain().lastNeedsTick = gameTime; return; }
        long elapsed = gameTime - agent.brain().lastNeedsTick;
        if (elapsed < DECAY_INTERVAL) return;
        agent.brain().lastNeedsTick = gameTime;
        float scale = Math.min(4, elapsed / (float) DECAY_INTERVAL);

        // ── Hunger ──
        float hunger = agent.getHunger();
        float decay = ModConfig.HUNGER_DECAY_PER_MINUTE.get().floatValue();
        hunger = Math.max(0f, hunger - decay * scale);
        agent.setHunger(hunger);

        if (hunger < EAT_THRESHOLD) {
            ensureEatGoal(agent, hunger);
        }

        // ── Mood (re-derived every needs tick) ──
        agent.setMood(deriveMood(agent));
    }

    /**
     * Seed a goal-driven {@code eat} task instead of silently consuming food in the background.
     * {@link com.github.AaronAA0721.villageragent.ai.behavior.EatSkill} handles selection, the
     * chewing animation and sounds, and acquisition when no food is carried. Starvation raises the
     * priority into the emergency band so eating can preempt ordinary work.
     */
    private static void ensureEatGoal(VillagerAgentData agent, float hunger) {
        for (AgentGoal g : agent.getGoals())
            if (!g.isCompleted() && "eat".equals(g.getGoalType())) return;
        if (agent.getGoals().size() >= 10) return;
        int priority = hunger < 15f ? 9 : 6;
        AgentGoal eat = new AgentGoal("eat", "Eat to satisfy hunger", priority);
        eat.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        eat.setImportance(priority);
        agent.getGoals().add(eat);
    }

    /** Per-tick sampling avoids charging a whole slow-update interval as work after a walk. */
    public static void tickFatigue(ServerWorld world, net.minecraft.entity.merchant.villager.VillagerEntity self, VillagerAgentData agent) {
        long now = world.getGameTime(), previous = agent.brain().lastFatigueTick;
        agent.brain().lastFatigueTick = now;
        if (previous < 0 || previous == now || self.isSleeping()) return;
        VillagerAction action = agent.getCurrentAction();
        AgentGoal goal = agent.brain().tasks.activeGoal();
        if (goal == null) goal = agent.getRunningGoal();
        String type = goal == null ? "" : goal.getGoalType();
        boolean moving = !self.getNavigation().isDone()
                || (action != null && (action.getActionType() == VillagerAction.ActionType.MOVE
                || action.getPhase() == VillagerAction.ActionPhase.WALKING));
        boolean working = !moving && (goal != null || action != null)
                && !"move".equals(type) && !"goto".equals(type) && !"wander".equals(type)
                && !"pickup_item".equals(type) && !"collect_crops".equals(type)
                && !"rest".equals(type) && !"sleep".equals(type);
        // 6000 ticks working + 6000 idle/moving = 60 + 15 = 75, starting at zero.
        agent.setFatigue(agent.getFatigue() + (working ? 12f : 3f) * Math.min(1, now - previous) / 1200f);
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

