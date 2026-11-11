package com.github.AaronAA0721.villageragent.ai;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** One execution's bounded target exclusions; never retry a known failed target. */
public final class TargetAttempts {
    private final int limit;
    private final Set<Object> rejected = new HashSet<>();
    public TargetAttempts(int limit) { if (limit < 1) throw new IllegalArgumentException(); this.limit = limit; }
    public void reject(Object target) { if (target != null) rejected.add(target); }
    public boolean excludes(Object target) { return rejected.contains(target); }
    public boolean exhausted() { return rejected.size() >= limit; }
    public int failures() { return rejected.size(); }
    /** Costs must come from complete paths, never endpoint displacement. */
    public <T> T select(Iterable<T> candidates, ToDoubleFunction<T> pathCost) {
        if (exhausted()) return null;
        T best = null; double cost = Double.POSITIVE_INFINITY;
        for (T candidate : candidates) {
            if (excludes(candidate)) continue;
            double next = pathCost.applyAsDouble(candidate);
            if (Double.isFinite(next) && next >= 0 && next < cost) { best = candidate; cost = next; }
        }
        return best;
    }
}
