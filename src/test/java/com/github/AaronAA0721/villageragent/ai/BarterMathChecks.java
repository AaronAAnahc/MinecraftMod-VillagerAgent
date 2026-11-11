package com.github.AaronAA0721.villageragent.ai;
import java.util.*;
public final class BarterMathChecks {
    private static int checks;
    private static void check(boolean b,String message) { checks++;if(!b) throw new AssertionError(message); }
    private static BarterMath.Candidate c(String item,int count,double buyer,double seller,int priority) {
        return new BarterMath.Candidate(item,count,buyer,seller,priority);
    }
    public static void main(String[] args) {
        check(BarterMath.probability(10,10)==1,"equal value always accepted");
        check(BarterMath.probability(20,10)==0,"double cost rejected");
        check(BarterMath.probability(15,10)==0.5,"relative cost controls chance");
        check(BarterMath.probability(-1,10)==1,"beneficial change capped at one");
        check(BarterMath.probability(Double.NaN,10)==0,"NaN rejected");
        check(BarterMath.probability(1,0)==0,"missing gain rejected");
        check(BarterMath.select(Collections.emptyList(),Collections.emptyList(),1,1)==null,"no free purchase");
        List<BarterMath.Candidate> pay=Arrays.asList(c("wheat",8,1,2,0),c("emerald",2,10,10,99));
        BarterMath.Bundle b=BarterMath.select(pay,Collections.emptyList(),6,10);
        check(b.payment.get("wheat")==3&&!b.payment.containsKey("emerald"),"spread beats priority, producer's surplus pays first");
        check(b.sellerNet==6&&b.paid==3&&b.probability==1,"seller and buyer use different tables");
        b=BarterMath.select(Arrays.asList(c("a",1,2,2,0),c("b",1,2,2,3)),Collections.emptyList(),2,2);
        check(b.payment.containsKey("b"),"explicit priority breaks equal-spread tie");
        check(BarterMath.select(Arrays.asList(c("a",1,1,2,0)),Collections.emptyList(),3,3)==null,"short payment rejects entire bundle");
        check(BarterMath.select(Arrays.asList(c("a",10,0,2,0)),Collections.emptyList(),3,3)==null,"unpriced buyer item excluded");
        check(BarterMath.select(Arrays.asList(c("a",10,2,Double.POSITIVE_INFINITY,0)),Collections.emptyList(),3,3)==null,"infinite seller value excluded");
        b=BarterMath.select(Arrays.asList(c("coin",1,10,10,0)),Arrays.asList(c("bread",4,2,1,0)),6,10);
        check(b.change.get("bread")==4&&b.sellerNet==6&&b.paid-b.returned==2,"change uses seller surplus, keeps ask covered");
        b=BarterMath.select(Arrays.asList(c("coin",1,10,10,0)),Collections.emptyList(),6,6);
        check(Math.abs(b.probability-1.0/3)<1e-8,"unavailable change reduces willingness without free rounding");
        check(BarterMath.select(pay,Collections.emptyList(),Double.NaN,1)==null,"invalid ask rejected");
        b=BarterMath.select(Arrays.asList(c("tiny",1,1e-12,1e-12,0)),Collections.emptyList(),1e-12,1e-12);
        check(b!=null&&b.payment.get("tiny")==1,"tiny positive price never becomes a free trade");
        for(int ask=1;ask<=15;ask++) {
            b=BarterMath.select(pay,Arrays.asList(c("bread",8,2,1,0)),ask,20);
            check(b!=null&&b.sellerNet+1e-8>=ask,"seller coverage invariant");
            check(b.payment.getOrDefault("wheat",0)<=8&&b.payment.getOrDefault("emerald",0)<=2,"stock bound");
        }
        System.out.println("BarterMathChecks: "+checks+" assertions passed");
    }
}
