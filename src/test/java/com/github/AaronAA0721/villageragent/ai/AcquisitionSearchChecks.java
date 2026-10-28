package com.github.AaronAA0721.villageragent.ai;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public final class AcquisitionSearchChecks {
    public static void main(String[] args) {
        // Basket entries: finished hoe, logs, planks, sticks. One log yields four planks;
        // two planks yield four sticks. Reserve the two head planks before making handles.
        Predicate<int[]> hoe = q -> {
            if (q[0] > 0) return true;
            int planks = q[1] * 4 + q[2];
            return planks >= 2 && (q[3] >= 2 || planks >= 4);
        };
        ToIntFunction<int[]> prices = q -> q[0] * 5 + q[1] + q[2] * 2 + q[3];
        expect(AcquisitionSearch.choose(new int[]{1,1,4,2}, 5, 128, prices, hoe), new int[]{0,1,0,0});
        expect(AcquisitionSearch.choose(new int[]{1,0,2,2}, 10, 128, prices, hoe), new int[]{1,0,0,0});
        expect(AcquisitionSearch.choose(new int[]{0,0,2,2}, 6, 128, prices, hoe), new int[]{0,0,2,2});
        if (AcquisitionSearch.choose(new int[]{1,0,2,2}, 4, 128, prices, hoe) != null)
            throw new AssertionError("Cannot spend unavailable funds");
        expect(AcquisitionSearch.choose(new int[]{1,1,4,2}, 0, 128, prices, q -> true), new int[4]);
        // Transaction rounding is per seller/item batch, not per unit.
        ToIntFunction<int[]> rounded = q -> (int)Math.ceil(q[0] * .15) + (int)Math.ceil(q[1] * .05);
        expect(AcquisitionSearch.choose(new int[]{2,2}, 2, 128, rounded, q -> q[0] == 2 && q[1] == 2), new int[]{2,2});
        if (AcquisitionSearch.choose(new int[]{2,2}, 1, 128, rounded, q -> q[0] == 2 && q[1] == 2) != null)
            throw new AssertionError("Split purchases each require a whole emerald");
        // Budget exhaustion still retains a complete single-source candidate, never a partial plan.
        expect(AcquisitionSearch.choose(new int[]{1,1,4,2}, 5, 0, prices, hoe), new int[]{0,1,0,0});
        System.out.println("Acquisition search checks passed (8 cases)");
    }
    private static void expect(int[] actual, int[] expected) {
        if (!Arrays.equals(actual, expected)) throw new AssertionError(Arrays.toString(actual)
                + " != " + Arrays.toString(expected));
    }
}
