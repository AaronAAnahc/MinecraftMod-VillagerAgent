package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * Villager-to-villager invitations. The inviter (running an {@code invite} goal) proposes a plan
 * face to face; the invitee's own LLM decides yes or no in character. On yes, a copy of the plan
 * enters the invitee's brain through {@link LlmGoalIntake} — and the inviter's, once — tagged with
 * the shared {@link AgentGoal#getGroupId() group id} so nobody gets it twice.
 *
 * <p>Without a configured LLM (or when the call fails) the invitee decides by relationship.
 */
public final class Invitations {
    private static final Logger LOGGER = LogManager.getLogger("Invitations");
    /** Players this close hear what the villagers say (squared, 24 blocks). */
    private static final double HEAR_SQ = 24.0 * 24.0;
    private static final int RELATIONSHIP_ACCEPT_BONUS = 2;

    private Invitations() {}

    /** A timed plan whose start-by deadline has passed can no longer be joined. */
    public static boolean tooLate(AgentGoal plan, long now) {
        return plan != null && plan.isExpired(now);
    }

    /** Propose {@code invite}'s plan to {@code invitee}. Server thread; the answer is applied later. */
    public static void propose(VillagerAgentData inviter, VillagerEntity inviterEntity,
                               VillagerAgentData invitee, VillagerEntity inviteeEntity,
                               AgentGoal invite, ServerWorld world) {
        AgentGoal plan = invite.getInvitePlan();
        if (plan == null || LlmGoalIntake.hasGroupGoal(invitee, plan.getGroupId())) return;
        long now = world.getGameTime();
        String pitch = invite.getInviteMessage() != null && !invite.getInviteMessage().isEmpty()
                ? invite.getInviteMessage()
                : plan.isShared() ? "Would you like to join me: " + label(plan) + "?"
                                  : "Could you do something for me: " + label(plan) + "?";
        say(inviter, inviterEntity, world, invitee.getName() + ", " + pitch);
        inviter.addMemory((plan.isShared() ? "Invited " : "Asked ") + invitee.getName() + " to: " + label(plan));
        BrainLog.diagnostic(inviter, "invite-sent", "to=" + invitee.getName() + " plan=" + plan.getDescription());

        // Condition check before any LLM call: an invitee who cannot carry the plan out (not their trade,
        // nothing to give, ...) says so instead of agreeing to something that would be rejected afterwards.
        GoalValidator.Result able = GoalValidator.validate(plan, invitee, inviteeEntity, world);
        if (!able.ok) {
            BrainLog.diagnostic(invitee, "invite-unable", "from=" + inviter.getName() + " reason=" + able.reason);
            apply(inviter, invitee, plan, new Decision(false, "Sorry, I can't do that.", ""), world);
            return;
        }

        if (!LLMSetupReminder.isConfigured()) {
            apply(inviter, invitee, plan, ruleDecision(invitee, inviter), world);
            return;
        }
        String system = "You are " + invitee.getName() + ", a " + invitee.getProfession()
                + " in a medieval Minecraft village. Personality: " + invitee.getPersonality() + ".\n"
                + (plan.isShared() ? "Another villager has just invited you to do something together. "
                                   : "Another villager has just asked you to do something for them (they will not take part). ")
                + "Decide in character whether to accept, "
                + "considering your needs, your existing plans and how you feel about them.\n"
                + "Respond with ONLY a JSON object: {\"accept\": true or false, \"reply\": \"what you say to them (1 short sentence)\", "
                + "\"thought\": \"1 sentence, first person\"}";
        String user = buildDecisionContext(inviter, invitee, plan, pitch, world);
        LLMGuard.queryJson(invitee.getVillagerId(), now, system, user)
                .thenAccept(raw -> world.getServer().execute(() -> {
                    Decision d = raw == null || LLMService.isFailure(raw) ? null : Decision.parse(raw);
                    DecisionJournal.record(invitee.getVillagerId(), DecisionJournal.Kind.INVITE, now, user, raw,
                            d == null ? "fallback" : (d.accept ? "accepted" : "declined"));
                    apply(inviter, invitee, plan, d != null ? d : ruleDecision(invitee, inviter), world);
                }))
                .exceptionally(e -> {
                    LOGGER.warn("Invite decision failed for {}: {}", invitee.getName(), e.getMessage());
                    world.getServer().execute(() -> apply(inviter, invitee, plan, ruleDecision(invitee, inviter), world));
                    return null;
                });
    }

    private static String buildDecisionContext(VillagerAgentData inviter, VillagerAgentData invitee,
                                               AgentGoal plan, String pitch, ServerWorld world) {
        long now = world.getGameTime();
        StringBuilder sb = new StringBuilder();
        sb.append(inviter.getName()).append(" (").append(inviter.getProfession()).append(") says to you: \"")
          .append(pitch).append("\"\n");
        sb.append(plan.isShared() ? "Proposed activity: " : "What they ask you to do: ").append(label(plan));
        if (plan.getTitle() != null) sb.append(" (steps: ").append(plan.getDescription()).append(")");
        if (plan.getScheduledTick() != Long.MAX_VALUE) {
            sb.append(" (starts in about ").append(GameClock.duration(plan.getScheduledTick() - now)).append(")");
        }
        sb.append("\nCurrent time: ").append(GameClock.format(world.getDayTime())).append('\n');
        int rel = invitee.getRelationships().getOrDefault(inviter.getVillagerId().toString(), 0);
        sb.append("Your relationship with ").append(inviter.getName()).append(": ").append(rel).append(" (-100..100)\n");
        String needs = VillagerNeedsSystem.buildNeedsDescription(invitee);
        if (!needs.isEmpty()) sb.append("Your state: ").append(needs).append('\n');
        sb.append("Your current goals: ");
        int n = 0;
        for (AgentGoal g : invitee.getGoals()) {
            if (g.isCompleted() || g.getSource() == AgentGoal.Source.RULE) continue;
            sb.append(n++ == 0 ? "" : "; ").append(g.getDescription());
        }
        if (n == 0) sb.append("only your usual routine");
        return sb.append('\n').toString();
    }

    /** Apply a decision on the server thread. */
    private static void apply(VillagerAgentData inviter, VillagerAgentData invitee, AgentGoal plan,
                              Decision d, ServerWorld world) {
        VillagerEntity inviterEntity = villager(world, inviter);
        VillagerEntity inviteeEntity = villager(world, invitee);
        if (!d.reply.isEmpty()) say(invitee, inviteeEntity, world, d.reply);
        if (!d.thought.isEmpty()) invitee.addMemory("I thought about " + inviter.getName() + "'s invitation: " + d.thought);
        String what = label(plan);

        if (d.accept && tooLate(plan, world.getGameTime())) {
            invitee.addMemory("Wanted to join " + inviter.getName() + " for " + what + " but it is too late");
            return;
        }
        if (d.accept && !LlmGoalIntake.hasGroupGoal(invitee, plan.getGroupId())) {
            List<AgentGoal> added = LlmGoalIntake.enqueueGoals(invitee, Collections.singletonList(GoalNbt.copy(plan)),
                    inviteeEntity, world, "invitation from " + inviter.getName());
            if (added.isEmpty()) {
                invitee.addMemory("Couldn't fit " + inviter.getName() + "'s plan into my day: " + what);
                inviter.addMemory(invitee.getName() + " wanted to come but couldn't fit it in: " + what);
                return;
            }
            // Only a shared plan (invite) is the inviter's too; an ask is done by the others alone.
            if (plan.isShared() && !LlmGoalIntake.hasGroupGoal(inviter, plan.getGroupId())) {
                LlmGoalIntake.enqueueGoals(inviter, Collections.singletonList(GoalNbt.copy(plan)),
                        inviterEntity, world, "my invitation");
            }
            invitee.addMemory(plan.isShared() ? "Agreed to " + what + " with " + inviter.getName()
                                              : "Agreed to " + what + " for " + inviter.getName());
            inviter.addMemory(invitee.getName() + (plan.isShared() ? " accepted my invitation: " : " agreed to my request: ") + what);
            invitee.updateRelationship(inviter.getVillagerId().toString(), RELATIONSHIP_ACCEPT_BONUS);
            inviter.updateRelationship(invitee.getVillagerId().toString(), RELATIONSHIP_ACCEPT_BONUS);
            BrainLog.diagnostic(invitee, "invite-accepted", "from=" + inviter.getName() + " plan=" + what);
        } else if (!d.accept) {
            invitee.addMemory("Declined " + inviter.getName() + "'s invitation: " + what);
            inviter.addMemory(invitee.getName() + " declined my invitation: " + what
                    + (d.reply.isEmpty() ? "" : " (\"" + d.reply + "\")"));
            BrainLog.diagnostic(invitee, "invite-declined", "from=" + inviter.getName() + " plan=" + what);
        }
    }

    /** The plan's title when it has one, otherwise its step description. */
    private static String label(AgentGoal plan) {
        return plan.getTitle() != null ? plan.getTitle() : plan.getDescription();
    }

    /** No-LLM fallback: accept from anyone you do not dislike. */
    private static Decision ruleDecision(VillagerAgentData invitee, VillagerAgentData inviter) {
        int rel = invitee.getRelationships().getOrDefault(inviter.getVillagerId().toString(), 0);
        boolean yes = rel >= 0;
        return new Decision(yes, yes ? "Sure, I'll be there." : "Sorry, not this time.", "");
    }

    private static VillagerEntity villager(ServerWorld world, VillagerAgentData agent) {
        Entity e = world.getEntity(agent.getVillagerId());
        return e instanceof VillagerEntity ? (VillagerEntity) e : null;
    }

    /** Let nearby players overhear a villager line. */
    public static void say(VillagerAgentData speaker, VillagerEntity entity, ServerWorld world, String text) {
        if (entity == null || text == null || text.isEmpty()) return;
        StringTextComponent msg = new StringTextComponent("§e[" + speaker.getName() + "] §f" + text);
        for (ServerPlayerEntity p : world.getPlayers(pl -> true)) {
            if (p.distanceToSqr(entity) <= HEAR_SQ) p.sendMessage(msg, Util.NIL_UUID);
        }
    }

    /** The invitee's answer. */
    static final class Decision {
        final boolean accept; final String reply; final String thought;
        Decision(boolean accept, String reply, String thought) {
            this.accept = accept; this.reply = reply == null ? "" : reply; this.thought = thought == null ? "" : thought;
        }

        /** Parse {"accept":..,"reply":..,"thought":..}; tolerates code fences and "yes"/"no". Null if unreadable. */
        static Decision parse(String raw) {
            if (raw == null) return null;
            String s = raw.trim();
            int start = s.indexOf('{'), end = s.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            try {
                JsonObject o = new JsonParser().parse(s.substring(start, end + 1)).getAsJsonObject();
                JsonElement a = o.get("accept");
                if (a == null || a.isJsonNull()) return null;
                boolean accept = a.isJsonPrimitive() && a.getAsJsonPrimitive().isBoolean() ? a.getAsBoolean()
                        : a.getAsString().trim().toLowerCase(java.util.Locale.ROOT).startsWith("y");
                return new Decision(accept, LlmGoalCatalog.str(o, "reply"), LlmGoalCatalog.str(o, "thought"));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
