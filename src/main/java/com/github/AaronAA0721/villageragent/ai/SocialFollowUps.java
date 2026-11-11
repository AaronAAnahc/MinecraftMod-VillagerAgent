package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import com.google.gson.JsonObject;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * After two villagers finish a conversation, the initiator's LLM may follow it up with one social action:
 * {@code invite} (do something together), {@code ask} (a favour that suits the other's trade) or {@code gift}
 * (hand over something spare). The decision is optional by design — "none" is presented as the normal answer —
 * so villages get livelier without every chat spawning a plan.
 *
 * <p>Condition checks happen <em>before</em> the LLM sees the options: inviting is only offered while
 * {@link LlmGoalIntake#mayStartGroupPlan} allows another plan, gifting only when
 * {@link CooperativeSupply#giftable(VillagerAgentData)} has stock to spare (never the working tool, reserves or
 * promised goods). Whatever comes back still goes through the one goal door ({@link LlmGoalIntake}), so the
 * same validation, caps and social limits apply as to any other LLM goal.
 */
public final class SocialFollowUps {
    private static final Logger LOGGER = LogManager.getLogger("SocialFollowUps");
    /** Only this share of conversations even considers a follow-up (keeps LLM calls and plans in proportion). */
    private static final double CONSIDER_CHANCE = 0.35;
    /** Cold or hostile pairs don't plan anything together. */
    private static final int MIN_RELATIONSHIP = 0;
    private static final int MAX_SPARE_LISTED = 6;

    private SocialFollowUps() {}

    /** Called on the server thread right after a conversation between {@code a} (initiator) and {@code b}. */
    public static void consider(ServerWorld world, VillagerEntity villagerA, VillagerAgentData a,
                                VillagerEntity villagerB, VillagerAgentData b) {
        if (!LLMSetupReminder.isConfigured()) return;
        if (world.random.nextDouble() > CONSIDER_CHANCE) return;
        int rel = a.getRelationships().getOrDefault(b.getVillagerId().toString(), 0);
        if (rel < MIN_RELATIONSHIP) return;

        List<String> options = new ArrayList<>();
        if (LlmGoalIntake.mayStartGroupPlan(a)) {
            options.add("{\"goal\":{\"type\":\"invite\",\"villagers\":[\"" + b.getName() + "\"],\"title\":\"<short>\","
                    + "\"message\":\"<what you say>\",\"activity\":{\"type\":\"sequence\",\"at\":\"HH:MM\",\"steps\":["
                    + "{\"type\":\"move\",\"x\":..,\"y\":..,\"z\":..},{\"type\":\"eat\"}]}}} - plan something together"
                    + " (a meal, a stroll); you take part too");
            options.add("{\"goal\":{\"type\":\"ask\",\"villagers\":[\"" + b.getName() + "\"],\"title\":\"<short>\","
                    + "\"message\":\"<what you ask>\",\"activity\":{\"type\":\"work\"}}} - ask a favour that suits"
                    + " their trade (work, cook, craft an item); only they do it");
        }
        String spare = spareList(a);
        if (!spare.isEmpty()) {
            options.add("{\"goal\":{\"type\":\"gift\",\"villager\":\"" + b.getName() + "\",\"item\":\"minecraft:<id>\","
                    + "\"qty\":1}} - give them one of your spare items right now");
        }
        if (options.isEmpty()) return;

        long now = world.getGameTime();
        StringBuilder user = new StringBuilder();
        user.append("You just finished a friendly chat with ").append(b.getName()).append(" (")
            .append(b.getProfession()).append("). Your friendship: ").append(rel).append(" (-100..100).\n")
            .append("Current time: ").append(GameClock.format(world.getDayTime())).append('\n')
            .append("Your position: ").append(villagerA.blockPosition().getX()).append(' ')
            .append(villagerA.blockPosition().getY()).append(' ').append(villagerA.blockPosition().getZ()).append('\n');
        if (!spare.isEmpty()) user.append("Items you could spare: ").append(spare).append('\n');
        user.append("If (and only if) it feels natural right now, you may follow the chat up with ONE of:\n");
        for (String option : options) user.append("  ").append(option).append('\n');
        user.append("Most of the time the right answer is none. Respond with ONLY a JSON object: ")
            .append("{\"goal\": null} to do nothing, or one of the shapes above.");

        String system = "You are " + a.getName() + ", a " + a.getProfession()
                + " in a medieval Minecraft village. Personality: " + a.getPersonality() + ".";
        LLMGuard.queryJson(a.getVillagerId(), now, system, user.toString())
                .thenAccept(raw -> world.getServer().execute(() -> apply(world, a, b, raw)))
                .exceptionally(e -> {
                    LOGGER.debug("Follow-up decision failed for {}: {}", a.getName(), e.getMessage());
                    return null;
                });
    }

    /** Server thread: route a non-null "goal" through the normal intake. */
    private static void apply(ServerWorld world, VillagerAgentData a, VillagerAgentData b, String raw) {
        if (raw == null || LLMService.isFailure(raw)) return;
        JsonObject root = LLMResponseParser.extractJsonObject(raw);
        if (root == null || !root.has("goal") || !root.get("goal").isJsonObject()) return;
        GoalDraft draft = LlmGoalCatalog.parse(root.getAsJsonObject("goal"));
        if (draft == null) return;
        String type = draft.type();
        if (!"invite".equals(type) && !"gift".equals(type)) {
            LOGGER.debug("{} follow-up ignored: type {} is not a social action", a.getName(), type);
            return;
        }
        net.minecraft.entity.Entity e = world.getEntity(a.getVillagerId());
        if (!(e instanceof VillagerEntity) || !e.isAlive()) return;
        List<LlmGoalIntake.Outcome> outcomes = LlmGoalIntake.process(a, Collections.singletonList(draft),
                (VillagerEntity) e, world, "chat-followup:" + b.getName(), true);
        for (LlmGoalIntake.Outcome o : outcomes) {
            BrainLog.diagnostic(a, "chat-followup", "type=" + o.type
                    + (o.accepted ? " accepted" : " rejected=" + o.reason));
        }
    }

    /** Up to {@value #MAX_SPARE_LISTED} item ids this villager could give away, with counts. */
    private static String spareList(VillagerAgentData a) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (ItemStack s : CooperativeSupply.giftable(a)) {
            if (listed++ >= MAX_SPARE_LISTED) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.getItem().getRegistryName()).append(" x").append(s.getCount());
        }
        return sb.toString();
    }
}
