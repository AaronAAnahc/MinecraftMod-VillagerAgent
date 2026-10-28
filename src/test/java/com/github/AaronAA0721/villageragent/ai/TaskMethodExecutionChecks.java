package com.github.AaronAA0721.villageragent.ai;

import java.util.*;

/** Exercise the same method interface as acquisition without Minecraft/world doubles. */
public final class TaskMethodExecutionChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static final class World {
        double a = 0, b = 2;
        boolean progress, safe = true;
        TaskMethod.Status status = TaskMethod.Status.WAITING;
        List<String> events = new ArrayList<>();
        Set<UUID> liveOwners = new HashSet<>();
    }
    private static final class Method implements TaskMethod<World> {
        final String id;
        UUID owner;
        Method(String id) { this.id = id; }
        public String id() { return id; }
        public double estimate(World w) { return "a".equals(id) ? w.a : w.b; }
        public void start(World w, UUID owner) {
            this.owner = owner; w.liveOwners.add(owner); w.events.add("start:" + id);
        }
        public Update tick(World w) { return new Update(w.status, w.progress, w.safe); }
        public void close(World w) { w.liveOwners.remove(owner); w.events.add("close:" + id); }
    }
    private static final class Fixture {
        final World world = new World();
        final TaskMethodExecution<World> execution = new TaskMethodExecution<>(
                new TaskMethodExecution.Policy(10, 20, 20, 100, 30, .1, .5, 1));
        TaskMethod.Status tick(long now) {
            return execution.tick(world, now, () -> Arrays.asList(new Method("a"), new Method("b")));
        }
    }
    public static void main(String[] args) {
        Fixture f = new Fixture();
        f.tick(10000);
        check("a".equals(f.execution.activeId()), "select cheapest feasible method without task-age bias");
        f.tick(10001); f.tick(10020); f.tick(10040);
        check("a".equals(f.execution.activeId()), "margin prevents switching at equal effective cost");
        f.tick(10050);
        check("b".equals(f.execution.activeId()), "stall time makes a costlier productive alternative preferable");
        check(f.world.events.equals(Arrays.asList("start:a", "close:a", "start:b")), "close old attempt before starting new one");
        check(f.world.liveOwners.size() == 1, "one live attempt after switching");
        f.tick(10051); f.tick(10060);
        check("b".equals(f.execution.activeId()), "cooldown and minimum run prevent immediate bounce");

        f = new Fixture(); f.tick(0); f.tick(1);
        f.world.a = 10; f.tick(10);
        check("a".equals(f.execution.activeId()), "minimum run protects new method even after repricing");
        f.tick(20);
        check("b".equals(f.execution.activeId()), "lower remaining cost wins after minimum run");

        f = new Fixture(); f.tick(0); f.tick(1);
        f.world.progress = true; f.tick(40); f.world.progress = false; f.tick(60);
        check("a".equals(f.execution.activeId()) && f.execution.stalledTicks() == 20, "actual progress resets only stall clock");
        check(f.execution.progressVersion() == 1, "nested parents can observe real child progress");
        f.tick(70);
        check("a".equals(f.execution.activeId()), "progress grants a new grace interval");

        f = new Fixture(); f.world.a = Double.POSITIVE_INFINITY; f.tick(0);
        check("b".equals(f.execution.activeId()), "unavailable method is excluded");
        f = new Fixture(); f.world.a = -1; f.world.b = Double.NaN;
        check(f.tick(0) == TaskMethod.Status.WAITING && f.world.liveOwners.isEmpty(), "invalid estimates never start work");

        f = new Fixture(); f.world.b = Double.POSITIVE_INFINITY; f.tick(0); f.tick(1); f.tick(100);
        check(f.execution.activeId() == null && f.world.liveOwners.isEmpty(), "hard stall timeout releases attempt even without alternatives");
        f.tick(120);
        check(f.execution.activeId() == null, "timed-out method stays cooling down");
        f.tick(130);
        check("a".equals(f.execution.activeId()), "retry after cooldown");
        check(f.execution.score() == 1, "bounded failure penalty survives attempt recreation");
        check(f.execution.remainingCost() == 0, "parent monetary estimate excludes child failure penalty");

        f = new Fixture(); f.tick(0); f.world.status = TaskMethod.Status.RUNNING; f.tick(1); f.tick(10000);
        check("a".equals(f.execution.activeId()) && f.execution.stalledTicks() == 2, "off-scheduler body time is not charged as stall");
        f = new Fixture(); f.tick(0); f.tick(1); f.world.safe = false; f.tick(200);
        check("a".equals(f.execution.activeId()), "unsafe transaction boundary postpones cancellation");
        f.world.safe = true; f.tick(201);
        check("b".equals(f.execution.activeId()), "switch resumes at a safe boundary");

        f = new Fixture(); f.tick(0); f.world.status = TaskMethod.Status.FAILED; f.tick(1);
        check("b".equals(f.execution.activeId()), "failure immediately tries another method despite minimum run");
        check(f.world.liveOwners.size() == 1, "failed attempt resources released");
        f.world.status = TaskMethod.Status.DONE;
        check(f.tick(2) == TaskMethod.Status.DONE && f.execution.activeId() == null, "method completion returns to parent predicate");
        check(f.world.liveOwners.isEmpty(), "completed method leaves no owned resources");
        int events = f.world.events.size(); f.execution.close(f.world); f.execution.close(f.world);
        check(f.world.events.size() == events, "close is idempotent");

        f = new Fixture(); f.tick(0); f.world.a = Double.POSITIVE_INFINITY; f.tick(1); f.tick(10);
        check("b".equals(f.execution.activeId()), "lost precondition bypasses minimum run at reevaluation");
        System.out.println("TaskMethodExecutionChecks: " + assertions + " assertions passed");
    }
}
