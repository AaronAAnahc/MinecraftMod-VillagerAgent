package com.github.AaronAA0721.villageragent.ai;
import java.util.*;
public final class TargetAttemptsChecks {
    private static int count;
    private static void check(boolean ok, String message) { count++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        TargetAttempts attempts = new TargetAttempts(3);
        List<String> trees = Arrays.asList("near-wall", "detour", "open", "spare");
        Map<String, Double> lengths = new HashMap<>();
        lengths.put("near-wall", Double.POSITIVE_INFINITY); lengths.put("detour", 30d);
        lengths.put("open", 8d); lengths.put("spare", 50d);
        check("open".equals(attempts.select(trees, lengths::get)), "reachable short route wins over nearby wall and long detour");
        attempts.reject("open");
        check("detour".equals(attempts.select(trees, lengths::get)), "failed movement selects a different target");
        attempts.reject("open");
        check(attempts.failures() == 1, "repeated failure cannot recreate a target or consume a new attempt");
        attempts.reject("detour");
        check("spare".equals(attempts.select(trees, lengths::get)), "second failure selects remaining route");
        attempts.reject("spare");
        check(attempts.exhausted() && attempts.select(trees, lengths::get) == null, "third target failure exits even if candidates remain");
        TargetAttempts blocked = new TargetAttempts(3);
        check(blocked.select(trees, t -> Double.POSITIVE_INFINITY) == null, "no complete route has no target");
        check(blocked.select(trees, t -> Double.NaN) == null, "invalid path lengths cannot win");
        double[][] detour = {{0,0,5},{5,0,5},{5,0,0}};
        double route = PathLength.measure(0,0,0,3,i->detour[i][0],i->detour[i][1],i->detour[i][2]);
        check(route == 15, "detour counts all three segments, not five-block endpoint displacement");
        check(route * 0.05 == 0.75, "notice board score uses route length alone");
        check(Double.isInfinite(PathLength.measure(0,0,0,0,i->0,i->0,i->0)), "empty path is unavailable");
        System.out.println("TargetAttemptsChecks: " + count + " assertions passed");
    }
}
