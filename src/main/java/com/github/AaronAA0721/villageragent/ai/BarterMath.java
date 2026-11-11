package com.github.AaronAA0721.villageragent.ai;
import java.util.*;
/** Pure integer bundle selection; all values are in the respective owner's subjective units. */
public final class BarterMath {
    public static final class Candidate {
        public final String item; public final int count, priority; public final double buyer, seller;
        public Candidate(String item,int count,double buyer,double seller,int priority) {
            this.item=item;this.count=count;this.buyer=buyer;this.seller=seller;this.priority=priority;
        }
    }
    public static final class Bundle {
        public final Map<String,Integer> payment=new LinkedHashMap<>(), change=new LinkedHashMap<>();
        public double paid, returned, sellerNet, probability;
    }
    public static double probability(double paid,double gained) {
        if(!Double.isFinite(paid)||!Double.isFinite(gained)||gained<=0) return 0;
        return Math.max(0,Math.min(1,2-paid/gained));
    }
    public static Bundle select(List<Candidate> pay,List<Candidate> change,double asking,double gained) {
        if(!Double.isFinite(asking)||asking<=0||!Double.isFinite(gained)||gained<=0) return null;
        double tolerance=Math.ulp(asking)*4;
        Bundle result=new Bundle();
        Comparator<Candidate> order=Comparator.comparingDouble((Candidate c)->c.seller/c.buyer).reversed()
            .thenComparing(Comparator.comparingInt((Candidate c)->c.priority).reversed()).thenComparing(c->c.item);
        List<Candidate> sorted=new ArrayList<>(pay);sorted.sort(order);
        for(Candidate c:sorted) {
            if(!valid(c)||result.sellerNet>=asking) continue;
            int n=(int)Math.min(c.count,Math.ceil((asking-result.sellerNet)/c.seller));
            result.payment.put(c.item,n);result.paid+=n*c.buyer;result.sellerNet+=n*c.seller;
        }
        if(result.payment.isEmpty()||result.sellerNet+tolerance<asking) return null;
        sorted=new ArrayList<>(change);sorted.sort(order.reversed());
        for(Candidate c:sorted) {
            if(!valid(c)||result.payment.containsKey(c.item)) continue;
            int n=(int)Math.min(c.count,Math.floor((result.sellerNet-asking+tolerance)/c.seller));
            if(n<=0) continue;
            result.change.put(c.item,n);result.returned+=n*c.buyer;result.sellerNet-=n*c.seller;
        }
        result.probability=probability(result.paid-result.returned,gained);
        return Double.isFinite(result.sellerNet)&&result.sellerNet+tolerance>=asking?result:null;
    }
    private static boolean valid(Candidate c) { return c.count>0&&c.buyer>0&&c.seller>0&&Double.isFinite(c.buyer)&&Double.isFinite(c.seller); }
    private BarterMath() {}
}
