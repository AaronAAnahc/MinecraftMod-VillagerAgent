package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

public final class TaskExecutionChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static AgentGoal goal(String type) { return new AgentGoal(type, type, 5); }
    public static void main(String[] args) {
        TaskExecution tasks = new TaskExecution();
        AgentGoal root = goal("farm"), child = goal("harvest"), leaf = goal("move");
        SkillResult result = tasks.tick(root, g -> {
            tasks.current().state(StringBuilder.class, StringBuilder::new).append("root");
            return tasks.child(() -> child, c -> tasks.child(() -> leaf, l -> SkillResult.RUNNING));
        });
        check(tasks.activeGoal() == leaf, "active primitive visible outside tick");
        check(result == SkillResult.RUNNING && tasks.path().equals("farm/harvest/move"), "three levels retained");
        tasks.tick(root, g -> {
            check(tasks.current().state(StringBuilder.class, StringBuilder::new).toString().equals("root"), "root state retained");
            SkillResult cr = tasks.child(() -> { throw new AssertionError("child recreated"); }, c -> {
                check(c == child, "same child identity");
                SkillResult lr = tasks.child(() -> { throw new AssertionError("leaf recreated"); }, l -> {
                    l.setCompleted(true); return SkillResult.RUNNING;
                });
                check(lr == SkillResult.SUCCESS && !c.isCompleted(), "legacy completion stays at leaf");
                return SkillResult.FAIL;
            });
            check(cr == SkillResult.FAIL && !g.isCompleted(), "child failure stays at parent boundary");
            return SkillResult.RUNNING;
        });
        check(tasks.path().equals("farm"), "terminal child removed");
        tasks.tick(root, g -> tasks.child(() -> goal("plant"), c -> SkillResult.RUNNING));
        tasks.reset();
        check(tasks.root() == null && tasks.path().isEmpty(), "cancellation removes descendants and local state");
        tasks.tick(root, g -> {
            check(tasks.current().state(StringBuilder.class, StringBuilder::new).length() == 0, "restart has fresh state");
            return SkillResult.RUNNING;
        });
        try { tasks.tick(root, g -> { throw new IllegalStateException("test"); }); }
        catch (IllegalStateException expected) { }
        tasks.tick(root, g -> SkillResult.SUCCESS); // exception must restore the execution cursor
        for (SkillResult terminal : SkillResult.values()) {
            if (terminal == SkillResult.RUNNING) continue;
            tasks.tick(root, g -> {
                check(tasks.child(() -> goal("child"), c -> terminal) == terminal, "terminal result delivered: " + terminal);
                check(tasks.current().child() == null && !g.isCompleted(), "terminal child isolated: " + terminal);
                return SkillResult.RUNNING;
            });
        }
        for (String type : new String[]{"move", "harvest", "plant", "till", "collect_crops", "pickup_item", "drop_item"}) {
            AgentGoal action = goal(type);
            GoalQueue queue = new GoalQueue();
            check(!queue.add(action) && queue.isEmpty(), "rule primitive rejected: " + type);
            action.setSource(AgentGoal.Source.LLM);
            check(queue.add(action), "LLM primitive accepted: " + type);
            action.setSource(AgentGoal.Source.PLAYER);
            check(BehaviorExecutor.allowsRoot(action), "player primitive accepted: " + type);
        }
        check(new GoalQueue().add(root), "rule composite accepted");
        BehaviorExecutor.register(new VillagerSkill() {
            public String id() { return "test_composite"; }
            public boolean isComposite() { return true; }
            public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) { return false; }
            public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) { return SkillResult.RUNNING; }
        });
        check(!GoalScheduler.canYieldBetweenActions(goal("test_composite")), "ownership independent of farm type");
        check(GoalScheduler.shouldPreempt(root, new AgentGoal("rest", "urgent", 9)), "emergency can preempt composite");
        check(GoalParser.parse("TODO: harvest 1 64 2\nTODO: plant 1 64 2\nTODO: till 1 64 2", null).size() == 3, "primitive coordinate directives");
        System.out.println("TaskExecutionChecks: " + assertions + " assertions passed");
    }
}
