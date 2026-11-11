package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A goal as the LLM wrote it, before it is grounded in the world.
 *
 * <p>Parsing ({@link LlmGoalCatalog}) is pure and may run on any thread; it fills the
 * world-independent fields of {@link #goal} and keeps the parts that need the world as text:
 * villager names, clock times, nested steps and invitation plans. {@link #bind} resolves those on
 * the server thread and returns a ready {@link AgentGoal}, which then goes through
 * {@link GoalValidator} like any other goal (see {@link LlmGoalIntake}).
 */
public final class GoalDraft {
    /** Grace for "at" times that just passed: start now instead of tomorrow (30 s). */
    private static final long JUST_PASSED_GRACE = 600;
    /** An appointment that has not started this long after its time is dropped (2 min). */
    public static final long START_WINDOW = 2_400;
    /** Priority a due appointment runs at (below rest 8 and emergencies 9). */
    public static final int APPOINTMENT_PRIORITY = 7;
    private static final long MAX_DELAY = GameClock.DAY;
    /** Longest activity window ("until"/"for_minutes"): six in-game hours. */
    public static final long MAX_ACTIVITY = 6_000;

    public final AgentGoal goal;
    /** converse: villager name / uuid, or a profession for legacy {@code target_id}. */
    String targetName;
    /** True when {@link #targetName} came from the "villager" field and must name a villager. */
    boolean targetMustBeVillager;
    /** sequence: ordered steps. */
    final List<GoalDraft> steps = new ArrayList<>();
    /** invite: who to invite (names or ids) and what to do together. */
    final List<String> inviteeNames = new ArrayList<>();
    GoalDraft plan;
    /** Timing: in-game clock time ("12:00") or a delay in seconds. */
    String at;
    long inSeconds = -1;
    /** Human-readable name of the plan ("Dinner"). */
    String title;
    /** invite (true): the inviter takes part; ask (false): only the invitees do it. */
    boolean together = true;
    /** Activity end: clock time ("19:00") or a length in in-game minutes. */
    String until;
    long forMinutes = -1;

    GoalDraft(AgentGoal goal) { this.goal = goal; }

    /** Wrap an already-complete goal (legacy TODO: protocol) so it uses the same intake. */
    public static GoalDraft of(AgentGoal goal) { return new GoalDraft(goal); }

    public String type() { return goal.getGoalType(); }

    /** Thrown when a draft cannot be grounded (unknown villager, bad time, ...). */
    public static final class Unbindable extends Exception {
        Unbindable(String reason) { super(reason, null, false, false); }
    }

    /**
     * Ground this draft for {@code agent}. Server thread only.
     *
     * @param timed whether "at"/"in_seconds" apply here (top-level goals and invitation plans;
     *              steps of a sequence run back-to-back and ignore their own times)
     */
    public AgentGoal bind(VillagerAgentData agent, VillagerEntity self, ServerWorld world, boolean timed)
            throws Unbindable {
        AgentGoal g = goal;
        g.setTitle(title);

        if (targetName != null) {
            UUID id = KnownVillagers.resolve(targetName, agent, self, world);
            if (id != null) {
                g.setTargetKind("villager");
                g.setTargetEntityId(id);
                g.setDescription("gift".equals(g.getGoalType())
                        ? "give " + g.getTargetItem() + (g.getTargetQuantity() > 1 ? " x" + g.getTargetQuantity() : "")
                          + " to " + KnownVillagers.nameOf(id)
                        : g.getGoalType() + " with " + KnownVillagers.nameOf(id));
            } else if (targetMustBeVillager) {
                throw new Unbindable("unknown-villager:" + targetName);
            } else {
                g.setTargetProfession(targetName); // legacy: target_id held a profession
            }
        }

        if (!steps.isEmpty()) {
            g.getSteps().clear();
            for (GoalDraft step : steps) {
                AgentGoal s = step.bind(agent, self, world, false);
                s.setSource(g.getSource());
                g.getSteps().add(s);
            }
            g.setStepIndex(0);
            g.setDescription(describeSteps(g.getSteps()));
        }

        if ("invite".equals(g.getGoalType())) {
            for (String name : inviteeNames) {
                UUID id = KnownVillagers.resolve(name, agent, self, world);
                if (id == null) {
                    BrainLog.diagnostic(agent, "invite-unknown", "name=" + name);
                } else if (!g.getInvitees().contains(id)) {
                    g.getInvitees().add(id);
                }
            }
            if (g.getInvitees().isEmpty()) throw new Unbindable("invite:no-known-invitee:" + inviteeNames);
            if (plan == null) throw new Unbindable("invite:no-activity");
            AgentGoal p = plan.bind(agent, self, world, true);
            p.setSource(g.getSource());
            p.setGroupId(UUID.randomUUID());
            p.setLeaderId(agent.getVillagerId());
            p.setShared(together);
            if (p.getTitle() == null) p.setTitle(title);
            g.setInvitePlan(p);
            List<String> names = new ArrayList<>();
            for (UUID id : g.getInvitees()) names.add(KnownVillagers.nameOf(id));
            g.setDescription((together ? "invite " : "ask ") + String.join(", ", names) + " to: " + p.getDescription());
        }

        if (timed && (at != null || inSeconds >= 0 || until != null || forMinutes >= 0)) applyTiming(g, world);
        return g;
    }

    /**
     * Reuse the existing scheduled-priority fields as an appointment: held until T, start-by T+window.
     * An end ("until"/"for_minutes") makes it an activity that lasts until that tick; without a start
     * time it starts now.
     */
    private void applyTiming(AgentGoal g, ServerWorld world) throws Unbindable {
        if (world == null) throw new Unbindable("timing:no-world");
        long delay = at != null ? GameClock.ticksUntil(at, world.getDayTime(), JUST_PASSED_GRACE)
                : inSeconds >= 0 ? inSeconds * 20 : 0;
        if (delay < 0 || delay > MAX_DELAY) throw new Unbindable("timing:bad-time:" + (at != null ? at : inSeconds + "s"));
        long start = world.getGameTime() + delay;
        long startDay = world.getDayTime() + delay;
        String label = GameClock.format(startDay);
        if (until != null || forMinutes >= 0) {
            long length = until != null ? GameClock.ticksUntil(until, startDay, 0) : forMinutes * 1000 / 60;
            if (length <= 0 || length > MAX_ACTIVITY) {
                throw new Unbindable("timing:bad-end:" + (until != null ? until : forMinutes + "min")
                        + " (must be after the start, at most 6 in-game hours)");
            }
            g.setEndTick(start + length);
            label += "-" + GameClock.format(startDay + length);
        }
        g.setScheduledTick(start);
        g.setScheduledPriority(Math.max(APPOINTMENT_PRIORITY, g.getPriority()));
        g.setHoldUntilScheduled(true);
        g.setLifecycle(AgentGoal.Lifecycle.EXPIRING);
        g.setDeadlineTick(g.hasEndTime() ? Math.min(start + START_WINDOW, g.getEndTick()) : start + START_WINDOW);
        g.setDescription(g.getDescription() + " @" + label);
    }

    static String describeSteps(List<AgentGoal> steps) {
        StringBuilder sb = new StringBuilder();
        for (AgentGoal s : steps) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(s.getDescription());
        }
        return sb.toString();
    }
}
