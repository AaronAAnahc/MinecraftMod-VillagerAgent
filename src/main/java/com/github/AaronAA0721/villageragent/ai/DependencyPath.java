package com.github.AaronAA0721.villageragent.ai;

import java.util.HashSet;
import java.util.Set;

/** Bounded active recursion path. Siblings may reuse an item; ancestors may not. */
public final class DependencyPath<K> {
    private final int maxDepth, maxVisits;
    private final Set<K> active = new HashSet<>();
    private int visits;
    public DependencyPath(int maxDepth, int maxVisits) {
        if (maxDepth < 1 || maxVisits < 1) throw new IllegalArgumentException("Positive limits required");
        this.maxDepth = maxDepth; this.maxVisits = maxVisits;
    }
    public boolean enter(K key) {
        if (active.isEmpty()) visits = 0;
        if (++visits > maxVisits || active.size() >= maxDepth || active.contains(key)) return false;
        active.add(key); return true;
    }
    public boolean contains(K key) { return active.contains(key); }
    public void leave(K key) { active.remove(key); }
}
