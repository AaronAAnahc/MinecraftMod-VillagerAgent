package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Transient tree of execution frames. Only root goals belong in the scheduler. */
public final class TaskExecution {
    public static final class Frame {
        public final AgentGoal goal;
        private Frame child;
        private final Map<Class<?>, Object> state = new HashMap<>();
        private Frame(AgentGoal goal) { this.goal = goal; }
        public Frame child() { return child; }
        public <T> T state(Class<T> type, Supplier<T> factory) {
            return type.cast(state.computeIfAbsent(type, ignored -> factory.get()));
        }
    }
    private Frame root;
    private Frame current;
    public Frame root() { return root; }
    public AgentGoal activeGoal() {
        Frame frame = root;
        if (frame == null) return null;
        while (frame.child != null) frame = frame.child;
        return frame.goal;
    }
    public Frame current() {
        if (current == null) throw new IllegalStateException("No executing task");
        return current;
    }
    /** Caller must release the child action/resources before discarding its frame. */
    public void discardChild() { current().child = null; }
    public void reset() { root = null; current = null; }
    public SkillResult tick(AgentGoal goal, Function<AgentGoal, SkillResult> runner) {
        if (current != null) throw new IllegalStateException("Use child() for nested execution");
        if (root == null || root.goal != goal) root = new Frame(goal);
        return invoke(root, runner);
    }
    /** The parent owns terminal-result policy; a child never completes its parent. */
    public SkillResult child(Supplier<AgentGoal> factory, Function<AgentGoal, SkillResult> runner) {
        Frame parent = current();
        if (parent.child == null) parent.child = new Frame(factory.get());
        Frame child = parent.child;
        SkillResult result = invoke(child, runner);
        if (result != SkillResult.RUNNING) parent.child = null;
        return result;
    }
    private SkillResult invoke(Frame frame, Function<AgentGoal, SkillResult> runner) {
        Frame previous = current;
        current = frame;
        try {
            SkillResult result = runner.apply(frame.goal);
            return result == SkillResult.RUNNING && frame.goal.isCompleted() ? SkillResult.SUCCESS : result;
        } finally { current = previous; }
    }
    public String path() {
        StringBuilder out = new StringBuilder();
        for (Frame f = root; f != null; f = f.child) {
            if (out.length() > 0) out.append("/");
            out.append(f.goal.getGoalType());
        }
        return out.toString();
    }
}
