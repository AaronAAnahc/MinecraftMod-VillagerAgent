package com.github.AaronAA0721.villageragent.ai;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Bounded basket search. Costs must be nonnegative and monotone in each purchase quantity. */
public final class AcquisitionSearch {
    public static int[] choose(int[] limits, int funds, int budget,
                               ToIntFunction<int[]> cost, Predicate<int[]> satisfies) {
        PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator
                .comparingInt((int[] q) -> cost.applyAsInt(q))
                .thenComparingInt(q -> Arrays.stream(q).sum())
                .thenComparing((int[] q) -> Arrays.toString(q)));
        Set<String> seen = new HashSet<>();
        int[] empty = new int[limits.length];
        if (satisfies.test(empty)) return empty;
        queue.add(empty); seen.add(Arrays.toString(empty));
        // Seed single-source batches so a useful full purchase is not hidden behind combinations.
        int[] best = null;
        for (int i = 0; i < limits.length; i++) {
            for (int n = 1; n <= limits[i]; n++) {
                int[] q = new int[limits.length]; q[i] = n;
                if (cost.applyAsInt(q) <= funds && (best == null || cost.applyAsInt(q) < cost.applyAsInt(best))
                        && satisfies.test(q)) best = q;
            }
        }
        while (!queue.isEmpty() && budget-- > 0) {
            int[] q = queue.remove();
            int price = cost.applyAsInt(q);
            if (price > funds || (best != null && price > cost.applyAsInt(best))) continue;
            if (satisfies.test(q)) return q;
            for (int i = 0; i < limits.length; i++) {
                if (q[i] >= limits[i]) continue;
                int[] next = q.clone(); next[i]++;
                if (cost.applyAsInt(next) <= funds && seen.add(Arrays.toString(next))) queue.add(next);
            }
        }
        return best;
    }
    private AcquisitionSearch() {}
}
