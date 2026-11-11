package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Applies a villager's reply to a player message: parse it ({@link LLMResponseParser}), put its goals
 * through {@link LlmGoalIntake}, record agendas and the inner thought. Shared by the chat packet and
 * the {@code /va ask|preview} commands so both exercise exactly the same path. Server thread only.
 */
public final class ChatReplyProcessor {
    private static final Logger LOGGER = LogManager.getLogger("ChatReplyProcessor");

    private ChatReplyProcessor() {}

    public static final class Result {
        public final LLMResponseParser.ParsedResponse parsed;
        public final List<LlmGoalIntake.Outcome> goals;
        Result(LLMResponseParser.ParsedResponse parsed, List<LlmGoalIntake.Outcome> goals) {
            this.parsed = parsed; this.goals = goals;
        }
    }

    /**
     * @param apply false = dry run: report what would happen without touching goals, agendas or memories
     */
    public static Result process(VillagerAgentData agent, String response, long gameTick,
                                 VillagerEntity villager, ServerWorld world, String source, boolean apply) {
        // JSON normally; TODO:/REMEMBER: lines only for models without JSON output.
        LLMResponseParser.ParsedResponse parsed = LLMResponseParser.parse(response, agent, gameTick);
        List<LlmGoalIntake.Outcome> outcomes = LlmGoalIntake.process(agent, parsed.goals, villager, world, source, apply);
        if (!parsed.goals.isEmpty()) {
            long added = outcomes.stream().filter(o -> o.accepted).count();
            LOGGER.info("{} parsed {} goal(s) from {}, {} accepted (json={}, applied={})",
                    agent.getName(), parsed.goals.size(), source, added, parsed.jsonParsed, apply);
        }
        if (apply) {
            for (LongTermAgenda a : parsed.agendas) {
                agent.getAgendas().add(a);
                agent.addMemory("Long-term commitment recorded: " + a.getTitle());
            }
            if (!parsed.thought.isEmpty()) agent.addMemory("I thought during chat: " + parsed.thought);
        }
        return new Result(parsed, outcomes);
    }
}
