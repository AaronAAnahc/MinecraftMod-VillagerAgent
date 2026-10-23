package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/** Root submission boundary, including callers using the legacy mutable-list API. */
public final class GoalQueue extends AbstractList<AgentGoal> {
    private final List<AgentGoal> roots = new ArrayList<>();
    @Override public AgentGoal get(int index) { return roots.get(index); }
    @Override public int size() { return roots.size(); }
    @Override public boolean add(AgentGoal goal) {
        if (!BehaviorExecutor.allowsRoot(goal)) return false;
        roots.add(goal); modCount++; return true;
    }
    @Override public void add(int index, AgentGoal goal) {
        requireRoot(goal); roots.add(index, goal); modCount++;
    }
    @Override public AgentGoal set(int index, AgentGoal goal) { requireRoot(goal); return roots.set(index, goal); }
    @Override public AgentGoal remove(int index) { modCount++; return roots.remove(index); }
    private static void requireRoot(AgentGoal goal) {
        if (!BehaviorExecutor.allowsRoot(goal)) throw new IllegalArgumentException("Invalid root task/source");
    }
}
