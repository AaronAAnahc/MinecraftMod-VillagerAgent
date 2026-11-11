package com.github.AaronAA0721.villageragent.ai.work;
import java.util.*;
import java.util.function.*;
/** Complete bounded connected-log component; leaves terminate rather than connect branches. */
public final class TreeGraph {
    public static <P> Set<P> scan(P root, Predicate<P> loaded, Predicate<P> log, Predicate<P> leaf,
                                  Predicate<P> protectedLog, Function<P,List<P>> neighbors, int limit) {
        Set<P> seen=new HashSet<>(),logs=new LinkedHashSet<>();
        ArrayDeque<P> queue=new ArrayDeque<>();queue.add(root);boolean leaves=false;
        while(!queue.isEmpty()) {
            P pos=queue.removeFirst();if(!seen.add(pos)) continue;
            if(!loaded.test(pos)) return Collections.emptySet();
            if(leaf.test(pos)) { leaves=true;continue; }
            if(!log.test(pos)) continue;
            if(protectedLog.test(pos)||logs.size()>=limit) return Collections.emptySet();
            logs.add(pos);queue.addAll(neighbors.apply(pos));
        }
        return leaves?logs:Collections.emptySet();
    }
    private TreeGraph() {}
}
