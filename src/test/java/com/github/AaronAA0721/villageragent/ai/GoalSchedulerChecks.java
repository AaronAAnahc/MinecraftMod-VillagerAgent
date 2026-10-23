package com.github.AaronAA0721.villageragent.ai;

import java.util.Arrays;
import java.util.Collections;

/** Deterministic checks of the same scored selection used by the live dispatcher. */
public final class GoalSchedulerChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        java.util.List<AgentGoal> items = GoalParser.parse("TODO: drop_item minecraft:stone x3\n"
                + "TODO: pickup_item minecraft:stone x2 00000000-0000-0000-0000-000000000001", null);
        check(items.size() == 2 && items.get(0).getTargetQuantity() == 3, "drop quantity parsed");
        check(items.get(1).getTargetEntityId() != null, "specific pickup UUID parsed");
        check(GoalParser.parse("TODO: drop_item minecraft:stone x0\n"
                + "TODO: pickup_item minecraft:stone xbad\n"
                + "TODO: pickup_item minecraft:stone x2 invalid", null).isEmpty(), "invalid item directives rejected");
        AgentGoal wander = new AgentGoal("wander", "idle", 2);
        AgentGoal work = new AgentGoal("fulfill_order", "delivery", 5);
        AgentGoal farm = new AgentGoal("farm", "fields", 5);
        AgentGoal emergency = new AgentGoal("move", "emergency", 9);
        for (int i = 0; i < 100; i++) {
            double draw = i / 100.0;
            check(GoalScheduler.pickScored(Arrays.asList(wander, work), g -> g == wander ? 100 : 1, draw) == work,
                    "available work must beat wandering even at much lower weight");
            check(GoalScheduler.pickScored(Arrays.asList(wander, work), g -> g == wander ? 2 : 0, draw) == wander,
                    "unavailable/cooling work must still allow exploration");
        }
        check(GoalScheduler.pickScored(Arrays.asList(wander, work), g -> 0, 0.5) == null, "no positive score");
        check(GoalScheduler.pickScored(Collections.emptyList(), g -> 1, 0.5) == null, "empty candidate set");
        check(GoalScheduler.pickScored(Arrays.asList(wander, work, emergency), g -> 1, 0.99) == emergency,
                "emergencies retain precedence");
        check(GoalScheduler.pickScored(Arrays.asList(wander, work, farm), g -> g == farm ? 3 : 1, 0.1) == work,
                "purposeful goals still use weighted choice");
        check(GoalScheduler.pickScored(Arrays.asList(wander, work, farm), g -> g == farm ? 3 : 1, 0.8) == farm,
                "purposeful goals still use weighted choice");
        check(GoalScheduler.shouldPreempt(wander, work), "work becoming ready interrupts wander");
        check(!GoalScheduler.shouldPreempt(work, wander), "wander cannot interrupt work");
        check(!GoalScheduler.shouldPreempt(work, farm), "normal work is not constantly interrupted");
        check(GoalScheduler.shouldPreempt(work, emergency), "emergency interrupts normal work");
        com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory memory =
                new com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory(net.minecraft.util.math.ChunkPos.asLong(-4, 9));
        com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature field =
                com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.FARMLAND;
        memory.recordFeature(field, 3, 65, 7);
        net.minecraft.util.math.BlockPos anchor = new net.minecraft.util.math.BlockPos(-61, 65, 151);
        check(anchor.equals(memory.anchorOf(field)), "field anchor uses correct negative chunk coordinates");
        com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory restored =
                com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory.readNBT(memory.writeNBT());
        check(anchor.equals(restored.anchorOf(field)), "remembered field survives save/load");
        restored.forgetFeature(field);
        check(restored.anchorOf(field) == null, "removed field loses stale anchor");
        restored.recordFeature(field, 4, 66, 8);
        check(new net.minecraft.util.math.BlockPos(-60, 66, 152).equals(restored.anchorOf(field)),
                "a new field can replace a forgotten anchor");
        AgentGoal oldJob = new AgentGoal("find_job_site", "saved job search", 4);
        AgentGoal oldCraft = new AgentGoal("craft", "saved work", 5);
        GoalScheduler.expireGoals(Arrays.asList(oldJob, oldCraft), 0);
        check(oldJob.getPriority() == 8, "saved job search adopts priority 8");
        check(oldCraft.getPriority() == 7, "saved production adopts priority 7");
        GoalScheduler.applyWorkPriority(oldCraft);
        check(oldCraft.getPriority() == 7, "priority migration is idempotent");
        oldCraft.setPriority(10);
        GoalScheduler.applyWorkPriority(oldCraft);
        check(oldCraft.getPriority() == 10, "explicit urgent work keeps its priority");
        check(!GoalScheduler.shouldPreempt(work, oldJob), "job search does not turn into an emergency");
        check(!GoalScheduler.canYieldBetweenActions(farm), "farm keeps ownership between harvesting and collecting");
        check(!GoalScheduler.shouldPreempt(farm, work), "ordinary order cannot interrupt field session");
        check(GoalScheduler.shouldPreempt(farm, emergency), "emergency can interrupt field session");
        check(GoalScheduler.canYieldBetweenActions(work), "other goals retain normal boundaries");
        System.out.println("GoalSchedulerChecks: " + assertions + " assertions passed");
    }
}
