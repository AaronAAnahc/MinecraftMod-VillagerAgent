package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.command.CommandSource;
import net.minecraft.util.text.StringTextComponent;
import java.util.*;

/** Two ready participants start one meeting. Ordinary work is never cancelled to start a trade. */
public final class TradeCoordinator {
    private static final class Meeting {
        final ServerWorld world;
        final UUID id, buyer, seller;
        final BlockPos position;
        ItemStack goods = ItemStack.EMPTY, payment = ItemStack.EMPTY;
        Meeting(ServerWorld w, VillageSignalBoard.VillageMessage want, VillageSignalBoard.VillageMessage have,
                VillagerEntity customer) {
            world=w; id=want.id; buyer=want.author; seller=have.author; position=customer.blockPosition();
        }
        Meeting(ServerWorld w, UUID id, VillagerEntity seller, VillagerEntity buyer, ItemStack goods, ItemStack payment) {
            world=w; this.id=id; this.buyer=buyer.getUUID(); this.seller=seller.getUUID();
            position=buyer.blockPosition(); this.goods=goods.copy(); this.payment=payment.copy();
        }
        boolean directed() { return !goods.isEmpty(); }
    }
    private static final Map<UUID,Meeting> MEETINGS=new LinkedHashMap<>();
    public static boolean busy(UUID resident) { return VillageSignalBoard.APPOINTMENTS.forResident(resident)!=null; }
    public static boolean owns(UUID resident,UUID session) {
        TradeAppointments.Appointment a=VillageSignalBoard.APPOINTMENTS.forResident(resident);
        return a!=null && a.id.equals(session);
    }
    private static VillagerEntity entity(ServerWorld w,UUID id) {
        Entity e=w.getEntity(id); return e instanceof VillagerEntity && e.isAlive()?(VillagerEntity)e:null;
    }
    private static AgentGoal goal(VillagerAgentData a,UUID id) {
        for(AgentGoal g:a.getGoals()) if("meet_trade".equals(g.getGoalType()) && id.equals(g.getTradeMessageId())) return g;
        return null;
    }
    private static void addGoals(Meeting m,long deadline) {
        for(UUID id:Arrays.asList(m.buyer,m.seller)) {
            VillagerAgentData a=VillagerAgentManager.getAgent(id);
            AgentGoal g=new AgentGoal("meet_trade","Meet for agreed trade",8);
            g.setTradeMessageId(m.id); g.setLifecycle(AgentGoal.Lifecycle.EXPIRING); g.setDeadlineTick(deadline);
            a.getGoals().add(g);
        }
        MEETINGS.put(m.id,m);
    }
    /** Operator chooses terms, but admission and settlement obey the same two-party barrier. */
    public static int requestExchange(CommandSource source, Entity first, Entity second, ItemStack goods, ItemStack payment) {
        ServerWorld w=source.getLevel();
        if(!com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_AI_AGENTS.get()
                || !(first instanceof VillagerEntity)||!(second instanceof VillagerEntity)||first==second
                || first.level!=w||second.level!=w||!first.isAlive()||!second.isAlive()
                || goods.isEmpty()||payment.isEmpty()||first.distanceToSqr(second)>128*128
                || busy(first.getUUID())||busy(second.getUUID())||TradeDemo.isActive(first.getUUID())||TradeDemo.isActive(second.getUUID())) {
            source.sendFailure(new StringTextComponent("交易无法预约：双方必须同维度、在范围内且未占用其他交易会话。")); return 0;
        }
        VillagerEntity s=(VillagerEntity)first,b=(VillagerEntity)second;
        if(s.isBaby()||b.isBaby()||s.isNoAi()||b.isNoAi()) return 0;
        VillagerAgentData sa=VillagerAgentManager.getOrCreateAgent(s),ba=VillagerAgentManager.getOrCreateAgent(b);
        if(sa.isSocializing()||ba.isSocializing()||sa.getGoals().size()>=10||ba.getGoals().size()>=10) return 0;
        Meeting m=new Meeting(w,UUID.randomUUID(),s,b,goods,payment);
        if(!VillageMarkets.same(w,s.getUUID(),b.getUUID())||directedPlan(m)==null) {
            source.sendFailure(new StringTextComponent("交易要求双方属于同一村庄市场，库存、付款和接收空间均充足。")); return 0;
        }
        long deadline=w.getGameTime()+2400;
        if(!VillageSignalBoard.APPOINTMENTS.reserve(m.id,m.buyer,m.seller,deadline)) return 0;
        addGoals(m,deadline);
        source.sendSuccess(new StringTextComponent("交易已预约：等待双方自然结束当前任务后会合，库存交割一次性提交。"),false);
        return 1;
    }
    private static TradeInventory.Plan directedPlan(Meeting m) {
        VillagerAgentData b=VillagerAgentManager.getAgent(m.buyer),s=VillagerAgentManager.getAgent(m.seller);
        return b==null||s==null?null:TradeInventory.plan(b.getInventory().getItems(),s.getInventory().getItems(),
                Collections.emptyList(),new ItemStack[]{m.payment},new ItemStack[]{m.goods});
    }
    private static boolean settleDirected(Meeting m) {
        TradeInventory.Plan plan=directedPlan(m); if(plan==null) return false;
        VillagerAgentData b=VillagerAgentManager.getAgent(m.buyer),s=VillagerAgentManager.getAgent(m.seller);
        for(int i=0;i<plan.player.size();i++) b.getInventory().setStackInSlot(i,plan.player.get(i));
        for(int i=0;i<plan.villager.size();i++) s.getInventory().setStackInSlot(i,plan.villager.get(i));
        return true;
    }
    public static boolean enqueue(ServerWorld world,VillageSignalBoard.VillageMessage want,VillageSignalBoard.VillageMessage have) {
        if(MEETINGS.containsKey(want.id)) return true;
        VillagerEntity b=entity(world,want.author),s=entity(world,have.author);
        if(b==null||s==null||TradeDemo.isActive(b.getUUID())||TradeDemo.isActive(s.getUUID())) return false;
        if(!VillageMarkets.same(world,want.author,have.author)) return false;
        if(!owns(want.author,want.id)||!owns(have.author,want.id)) return false;
        VillagerAgentData ba=VillagerAgentManager.getAgent(want.author),sa=VillagerAgentManager.getAgent(have.author);
        if(ba==null||sa==null||ba.getGoals().size()>=10||sa.getGoals().size()>=10) return false;
        addGoals(new Meeting(world,want,have,b),Math.min(want.expireTick,have.expireTick));
        return true;
    }
    public static SkillResult advance(VillagerAgentData a,VillagerEntity v,ServerWorld world,AgentGoal g) {
        Meeting m=MEETINGS.get(g.getTradeMessageId());
        TradeAppointments.Appointment ap=VillageSignalBoard.APPOINTMENTS.get(g.getTradeMessageId());
        if(m==null||m.world!=world||ap==null||!owns(a.getVillagerId(),m.id)) return SkillResult.DELETE;
        VillageSignalBoard.APPOINTMENTS.ready(m.id,a.getVillagerId(),world.getGameTime());
        if(!ap.started) return SkillResult.RUNNING;
        if(v.blockPosition().distSqr(m.position)>2) {
            if(a.getCurrentAction()==null) {
                VillagerAction move=new VillagerAction(VillagerAction.ActionType.MOVE,"Meet for trade");
                move.setGoalDriven(true); move.setTargetBlockPos(m.position); a.setCurrentAction(move);
            }
            if(world.getGameTime()%20==0 || v.getNavigation().isDone())
                v.getNavigation().moveTo(m.position.getX()+0.5,m.position.getY(),m.position.getZ()+0.5,VillagerNavigationSupport.WALK_SPEED);
        } else if(a.getCurrentAction()!=null) ActionLifecycle.clearBody(a,v,world);
        return SkillResult.RUNNING;
    }
    private static boolean readyGoal(VillagerAgentData a,UUID id) {
        AgentGoal g=a.getRunningGoal(); return g!=null && "meet_trade".equals(g.getGoalType())
                && id.equals(g.getTradeMessageId()) && !g.isSuspended();
    }
    public static boolean canSettle(UUID id) {
        Meeting m=MEETINGS.get(id); TradeAppointments.Appointment a=VillageSignalBoard.APPOINTMENTS.get(id);
        return m!=null&&a!=null&&a.started;
    }
    public static void tick(ServerWorld world) {
        long now=world.getGameTime();
        for(Meeting m:new ArrayList<>(MEETINGS.values())) {
            if(m.world!=world) continue;
            TradeAppointments.Appointment ap=VillageSignalBoard.APPOINTMENTS.get(m.id);
            VillageSignalBoard.VillageMessage want=VillageSignalBoard.get().find(m.id);
            VillageSignalBoard.VillageMessage have=want==null?null:VillageSignalBoard.get().find(want.matchedWith);
            VillagerEntity b=entity(world,m.buyer),s=entity(world,m.seller);
            VillagerAgentData ba=VillagerAgentManager.getAgent(m.buyer),sa=VillagerAgentManager.getAgent(m.seller);
            if(ap==null||b==null||s==null||ba==null||sa==null
                    || b.isNoAi()||s.isNoAi()||b.isBaby()||s.isBaby()||now>=ap.deadline
                    || (!m.directed() && (!com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_ECONOMY.get()
                        ||want==null||have==null||want.resolved||!VillagerEconomySystem.validateTradeFunds(want,have)))
                    || (m.directed() && directedPlan(m)==null)
                    || !VillageMarkets.same(world,m.buyer,m.seller)
                    || goal(ba,m.id)==null||goal(sa,m.id)==null
                    || (ap.firstReady>=0 && now-ap.firstReady>600)
                    || (ap.buyerReady&&!readyGoal(ba,m.id)) || (ap.sellerReady&&!readyGoal(sa,m.id))) {
                finish(m,false); continue;
            }
            // Combat/building can own the body even while the old root reference remains.
            if((ba.getCurrentAction()!=null&&!ba.getCurrentAction().isGoalDriven())
                    ||(sa.getCurrentAction()!=null&&!sa.getCurrentAction().isGoalDriven())) { finish(m,false); continue; }
            if(!ap.started && ap.buyerReady && ap.sellerReady) {
                ap.started=true;
                BrainLog.diagnostic(ba,"trade-meeting-start","session="+m.id+" partner="+m.seller);
            }
            if(ap.started && b.distanceToSqr(s)<=9 && b.blockPosition().distSqr(m.position)<=9
                    && s.blockPosition().distSqr(m.position)<=9 && b.canSee(s)) {
                finish(m,m.directed()?settleDirected(m):VillagerEconomySystem.completeMatchedExchange(world,m.id)>0);
            }
        }
    }
    private static void finish(Meeting m,boolean success) {
        MEETINGS.remove(m.id);
        if(!success) VillageSignalBoard.get().unmatch(m.id);
        VillageSignalBoard.APPOINTMENTS.release(m.id);
        for(UUID id:Arrays.asList(m.buyer,m.seller)) {
            VillagerAgentData a=VillagerAgentManager.getAgent(id); VillagerEntity v=entity(m.world,id);
            if(a==null) continue;
            AgentGoal g=goal(a,m.id);
            if(g!=null) {
                if(a.getRunningGoal()==g) {
                    if(v!=null && (a.getCurrentAction()==null||a.getCurrentAction().isGoalDriven())) ActionLifecycle.cancel(a,v,m.world);
                    a.setRunningGoal(null);
                }
                a.getGoals().remove(g);
            }
            BrainLog.diagnostic(a,"trade-meeting-end","session="+m.id+" success="+success);
        }
    }
    public static void unload(ServerWorld world) {
        for(Meeting m:new ArrayList<>(MEETINGS.values())) if(m.world==world) finish(m,false);
    }
    private TradeCoordinator() {}
}
