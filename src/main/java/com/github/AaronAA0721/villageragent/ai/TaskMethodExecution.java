package com.github.AaronAA0721.villageragent.ai;

import java.util.*;
import java.util.function.Supplier;

/** Selection, progress clock, hysteresis and attempt lifecycle shared by composite tasks. */
public final class TaskMethodExecution<C> {
    public static final class Policy {
        public final long reconsiderTicks, minimumRunTicks, graceTicks, stallTimeoutTicks, cooldownTicks;
        public final double timeWeight, switchMargin, failurePenalty;
        public Policy(long reconsider, long minimumRun, long grace, long timeout, long cooldown,
                      double timeWeight, double switchMargin, double failurePenalty) {
            if (reconsider <= 0 || minimumRun < 0 || grace < 0 || timeout <= grace || cooldown < 0
                    || !Double.isFinite(timeWeight) || timeWeight < 0 || !Double.isFinite(switchMargin)
                    || switchMargin < 0 || !Double.isFinite(failurePenalty) || failurePenalty < 0)
                throw new IllegalArgumentException("Invalid method policy");
            this.reconsiderTicks = reconsider; this.minimumRunTicks = minimumRun; this.graceTicks = grace;
            this.stallTimeoutTicks = timeout; this.cooldownTicks = cooldown;
            this.timeWeight = timeWeight; this.switchMargin = switchMargin; this.failurePenalty = failurePenalty;
        }
        public static Policy defaults() { return new Policy(100, 100, 200, 1200, 400, .0025, .5, 1); }
    }
    private final Policy policy;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private TaskMethod<C> active;
    private long lastTick, elapsed, progressAt, reconsiderAt;
    private TaskMethod.Status previous = TaskMethod.Status.RUNNING;
    private String lastTransition = "none";
    private String pendingExit;
    private int transitions;
    private double lastScore, lastRemainingCost;
    private long progressVersion;

    public TaskMethodExecution(Policy policy) { this.policy = policy; }
    public String activeId() { return active == null ? null : active.id(); }
    public String lastTransition() { return lastTransition; }
    public int transitions() { return transitions; }
    public double score() { return lastScore; }
    public double remainingCost() { return active == null ? 0 : lastRemainingCost; }
    public long progressVersion() { return progressVersion; }
    public long stalledTicks() { return elapsed - progressAt; }

    public TaskMethod.Status tick(C context, long now, Supplier<List<TaskMethod<C>>> methods) {
        TaskMethod.Update update = null;
        if (active != null) {
            long delta = Math.max(0, now - lastTick);
            // External waiting continues off-scheduler. A preempted body action does not age.
            elapsed += previous == TaskMethod.Status.WAITING ? delta : Math.min(1, delta);
            lastTick = now;
            update = active.tick(context);
            previous = update.status;
            if (update.progressed) { progressAt = elapsed; progressVersion++; }
            if (update.status == TaskMethod.Status.DONE) {
                failures.remove(active.id());
                finish(context, now, "done", false, false);
                return TaskMethod.Status.DONE; // The parent still checks its own completion predicate.
            }
            if (update.status == TaskMethod.Status.FAILED) {
                finish(context, now, "failed", true, true);
            } else if (update.safeToSwitch && stalledTicks() >= policy.stallTimeoutTicks) {
                finish(context, now, "stalled", true, true);
            }
        }
        if (active != null && (!update.safeToSwitch || now < reconsiderAt)) return update.status;
        if (active == null && now < reconsiderAt) return TaskMethod.Status.WAITING;
        reconsiderAt = now + policy.reconsiderTicks;
        TaskMethod<C> best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (TaskMethod<C> method : methods.get()) {
            if (active != null && method.id().equals(active.id())) continue;
            if (cooldowns.getOrDefault(method.id(), Long.MIN_VALUE) > now) continue;
            double cost = method.estimate(context);
            if (!Double.isFinite(cost) || cost < 0) continue;
            double score = cost + failures.getOrDefault(method.id(), 0) * policy.failurePenalty;
            if (score < bestScore) { best = method; bestScore = score; }
        }
        if (active != null) {
            double remaining = active.estimate(context);
            lastRemainingCost = remaining;
            lastScore = remaining + failures.getOrDefault(active.id(), 0) * policy.failurePenalty
                    + Math.max(0, stalledTicks() - policy.graceTicks) * policy.timeWeight;
            boolean invalid = !Double.isFinite(remaining) || remaining < 0;
            if (!invalid && (best == null || elapsed < policy.minimumRunTicks
                    || bestScore + policy.switchMargin >= lastScore)) return update.status;
            finish(context, now, invalid ? "unavailable" : "cheaper", invalid, true);
        }
        if (best == null) return TaskMethod.Status.WAITING;
        active = best;
        active.start(context, UUID.randomUUID());
        lastTick = now; elapsed = progressAt = 0; previous = TaskMethod.Status.RUNNING;
        lastScore = bestScore;
        lastRemainingCost = bestScore - failures.getOrDefault(best.id(), 0) * policy.failurePenalty;
        lastTransition = (pendingExit == null ? "" : pendingExit + " -> ") + "start:" + best.id();
        pendingExit = null; transitions++;
        return TaskMethod.Status.RUNNING;
    }
    public void close(C context) {
        if (active != null) active.close(context);
        active = null;
    }
    private void finish(C context, long now, String reason, boolean failed, boolean cooldown) {
        String id = active.id();
        active.close(context); active = null;
        if (failed) failures.put(id, Math.min(3, failures.getOrDefault(id, 0) + 1));
        if (cooldown) cooldowns.put(id, now + policy.cooldownTicks);
        reconsiderAt = now; lastTransition = reason + ":" + id; pendingExit = lastTransition; transitions++;
    }
}
