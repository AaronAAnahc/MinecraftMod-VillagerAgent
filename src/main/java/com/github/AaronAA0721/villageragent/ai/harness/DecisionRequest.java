package com.github.AaronAA0721.villageragent.ai.harness;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured context handed to the LLM for a decision (plan: 技术力提升计划书 §1.1 —
 * {@code DecisionRequest}).
 *
 * <p>Replaces "dump the whole world into a prompt" with a small, schema-shaped context:
 * the villager's perception, a shared blackboard of context signals, the actions it may
 * pick from, and its current goals. {@link #toPrompt()} renders this as the instruction half
 * that asks for a {@link DecisionSchema} JSON reply.
 */
public class DecisionRequest {

    /** Perception: who/where/how the villager is (env snapshot, mood, activity). */
    public String perception;
    /** Shared / context signals (isNight, threat level, ...). */
    public final Map<String, String> blackboard = new HashMap<>();
    /** The high-level actions the LLM may choose from (rendered as a menu). */
    public final List<String> availableActions = new ArrayList<>();
    /** The villager's current goals (for prioritisation). */
    public final List<String> goals = new ArrayList<>();

    /** Assemble a request from a villager's live state. */
    public static DecisionRequest from(VillagerAgentData agent, String envSummary,
                                       boolean isNight, String threatLevel) {
        DecisionRequest r = new DecisionRequest();

        StringBuilder perc = new StringBuilder();
        perc.append("Profession: ").append(agent.getProfession()).append("\n");
        perc.append("Personality: ").append(agent.getPersonality()).append("\n");
        perc.append("Mood: ").append(agent.getMood().name()).append("\n");
        perc.append("Activity: ").append(agent.getCurrentActivity() == null ? "idle" : agent.getCurrentActivity()).append("\n");
        if (envSummary != null && !envSummary.isEmpty()) {
            perc.append("Environment: ").append(envSummary).append("\n");
        }
        r.perception = perc.toString();

        r.blackboard.put("isNight", Boolean.toString(isNight));
        r.blackboard.put("threat", threatLevel == null ? "none" : threatLevel);

        r.availableActions.add("CRAFT <minecraft:item_id> - make an item your profession can make");
        r.availableActions.add("GATHER <minecraft:item_id> - collect an item from the ground / nearby");
        r.availableActions.add("MOVE <x> <y> <z> - walk to a coordinate");
        r.availableActions.add("SOCIALIZE - talk with a nearby villager or player");
        r.availableActions.add("TRADE - trade with a nearby player or villager");
        r.availableActions.add("REST - go home / sleep");
        r.availableActions.add("IDLE - do nothing for a while");
        r.availableActions.add("FLEE - retreat to safety (threat present)");
        r.availableActions.add("DEFEND - stand and fight a hostile mob");

        for (AgentGoal g : agent.getGoals()) {
            r.goals.add(g.toString());
        }
        return r;
    }

    /** Render the request as the user-prompt half that asks for a JSON decision. */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the decision-making brain of a Minecraft villager. ")
          .append("Given the perception and current goals, decide the single best HIGH-LEVEL ")
          .append("action to take right now.\n\n");
        sb.append("PERCEPTION:\n").append(perception).append("\n");
        sb.append("BLACKBOARD: ").append(blackboard.toString()).append("\n");
        sb.append("CURRENT GOALS:\n");
        if (goals.isEmpty()) sb.append("- (none)\n");
        else for (String g : goals) sb.append("- ").append(g).append("\n");
        sb.append("\nAVAILABLE ACTIONS (choose exactly one):\n");
        for (String a : availableActions) sb.append("- ").append(a).append("\n");
        sb.append("\nRespond with EXACTLY one JSON object and nothing else:\n");
        sb.append("{\"action\":\"<one of the action names above>\", ")
          .append("\"target\":\"<parameter or empty string>\", ")
          .append("\"rationale\":\"<one short sentence why>\"}\n");
        sb.append("Example: {\"action\":\"CRAFT\", \"target\":\"minecraft:iron_pickaxe\", ")
          .append("\"rationale\":\"I need a pickaxe to gather iron\"}\n");
        return sb.toString();
    }
}
