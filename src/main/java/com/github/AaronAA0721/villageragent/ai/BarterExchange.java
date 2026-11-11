package com.github.AaronAA0721.villageragent.ai;
import net.minecraft.item.*;
import java.util.*;
/** Frozen terms; inventory is only changed by TradeInventory's all-or-nothing plan. */
public final class BarterExchange {
    public final ItemStack[] payment, goods;
    public final double probability, cost;
    private BarterExchange(ItemStack[] payment,ItemStack[] goods,double probability,double cost) {
        this.payment=payment;this.goods=goods;this.probability=probability;this.cost=cost;
    }
    public static BarterExchange quote(VillagerAgentData b,VillagerAgentData s,String item,int quantity) {
        return quote(b,s,b.getInventory().getItems(),s.getInventory().getItems(),item,quantity);
    }
    public static BarterExchange quote(VillagerAgentData b,VillagerAgentData s,List<ItemStack> bs,List<ItemStack> ss,String item,int quantity) {
        if(b==null||s==null||quantity<=0||available(s,ss,item)<quantity) return null;
        double asking=VillagerEconomySystem.villagerValue(s,item)*quantity;
        double gained=VillagerEconomySystem.villagerValue(b,item)*quantity;
        List<BarterMath.Candidate> pay=candidates(b,s,b,bs,item,0), change=candidates(b,s,s,ss,item,quantity);
        BarterMath.Bundle bundle=BarterMath.select(pay,change,asking,gained);
        if(bundle==null||bundle.probability<=0) return null;
        Map<String,Integer> received=new LinkedHashMap<>(bundle.change);received.merge(item,quantity,Integer::sum);
        BarterExchange exchange=new BarterExchange(stacks(bundle.payment),stacks(received),bundle.probability,Math.max(0,bundle.paid-bundle.returned));
        return exchange.plan(bs,ss)==null?null:exchange;
    }
    private static List<BarterMath.Candidate> candidates(VillagerAgentData b,VillagerAgentData s,VillagerAgentData owner,List<ItemStack> stock,String item,int exclude) {
        List<BarterMath.Candidate> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ItemStack stack:stock) {
            if(stack.isEmpty()||stack.hasTag()) continue;
            String id=stack.getItem().getRegistryName().toString();
            if(!seen.add(id)||(owner==b&&id.equals(item))) continue;
            int count=available(owner,stock,id)-(id.equals(item)?exclude:0);
            result.add(new BarterMath.Candidate(id,count,VillagerEconomySystem.villagerValue(b,id),
                VillagerEconomySystem.villagerValue(s,id),VillagerEconomySystem.paymentPriority(owner,id)));
        }
        return result;
    }
    public static int available(VillagerAgentData owner,List<ItemStack> stock,String item) {
        if(VillagerEconomySystem.retained(owner,item)) return 0;
        Item type=com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(item);
        return Math.max(0,TradeInventory.count(stock,new ItemStack(type))-VillagerEconomySystem.reserve(owner,item));
    }
    private static ItemStack[] stacks(Map<String,Integer> counts) {
        List<ItemStack> result=new ArrayList<>();counts.forEach((id,count)->{
            Item item=com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(id);
            while(count>0) { int n=Math.min(count,new ItemStack(item).getMaxStackSize());result.add(new ItemStack(item,n));count-=n; }
        });return result.toArray(new ItemStack[0]);
    }
    public TradeInventory.Plan plan(List<ItemStack> b,List<ItemStack> s) {
        return TradeInventory.plan(b,s,Collections.emptyList(),payment,goods);
    }
}
