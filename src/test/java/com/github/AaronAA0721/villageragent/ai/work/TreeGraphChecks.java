package com.github.AaronAA0721.villageragent.ai.work;
import java.util.*;
public final class TreeGraphChecks {
    private static int checks;
    private static void check(boolean b,String message) { checks++;if(!b) throw new AssertionError(message); }
    private static Set<Integer> scan(Set<Integer> logs,Set<Integer> leaves,Set<Integer> protectedLogs,Set<Integer> unloaded,int cap) {
        return TreeGraph.scan(0,p->!unloaded.contains(p),logs::contains,leaves::contains,protectedLogs::contains,
            p->Arrays.asList(p-1,p+1),cap);
    }
    private static Set<Integer> set(Integer... n) { return new HashSet<>(Arrays.asList(n)); }
    public static void main(String[] args) {
        check(scan(set(0,1,2),set(3),set(),set(),512).equals(set(0,1,2)),"leaf at far end recognizes complete trunk");
        check(scan(set(0,1,2),set(4),set(),set(),512).isEmpty(),"nearby disconnected leaf cannot recognize tree");
        check(scan(set(0,1,3),set(2),set(),set(),512).equals(set(0,1)),"leaves do not bridge separate trees");
        check(scan(set(0,1,2),set(3),set(2),set(),512).isEmpty(),"one village log rejects entire connected tree");
        check(scan(set(0,1,2),set(3),set(),set(2),512).isEmpty(),"unloaded trunk cannot be truncated");
        check(scan(set(0,1,2),set(3),set(),set(3),512).isEmpty(),"unknown boundary fails closed");
        check(scan(set(0,1,2),set(3),set(),set(),2).isEmpty(),"oversized component rejected, never partly returned");
        check(scan(set(0,1,2),set(3),set(),set(),3).size()==3,"exact cap remains valid");
        check(scan(set(0,1),set(),set(),set(),512).isEmpty(),"bare log construction rejected");
        check(scan(set(1),set(2),set(),set(),512).isEmpty(),"removed root yields no tree");
        System.out.println("TreeGraphChecks: "+checks+" assertions passed");
    }
}
