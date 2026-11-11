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

/**
 * Shared state of joint meetings. Every meeting — a free cooperative delivery or an operator-directed
 * exchange — is a pair of {@code meet_trade} root goals, one per participant: walk towards the partner, hand
 * over where they meet, pick up. For a delivery only the provider's goal exists at first: it walks to the
 * requester, and the requester's goal is added (invited) once the provider is within 16 blocks and can reach it. The coordinator owns only the session (booking, readiness,
 * meeting point, thrown items). The requester's acquisition and the provider's {@code supply_request} do not
 * drive the meeting; they only observe the request it consumes. Paid villager-to-villager board matching is
 * retired.
 */
public final class TradeCoordinator {
    private static final int PICKUP_TIMEOUT_TICKS = 600;
    /** How long the first arrival waits at the meeting point for its partner (busy partners finish their task first). */
    private static final int PARTNER_WAIT_TICKS = 1800;
    /** Both participants within three blocks of each other, with line of sight, can hand over. */
    private static final double HANDOFF_DIST_SQ = 9;
    /** A delivery's requester is invited once the walking provider is this close (16 blocks) and can reach it. */
    private static final double INVITE_DIST_SQ = 256;
    /** Within 6 blocks and in sight, a villager without a path waits for its partner instead of failing. */
    private static final double CLOSE_WAIT_DIST_SQ = 36;
    /** Path searches (about one per second) that may fail in a row before a far-away participant gives up. */
    private static final int MAX_PATH_MISSES = 10;
    private static final class Meeting {
        final ServerWorld world;
        final UUID id, buyer, seller;
        final BlockPos position;
        long droppedAt = -1;
        final List<net.minecraft.entity.item.ItemEntity> drops = new ArrayList<>();
        ItemStack goods = ItemStack.EMPTY, payment = ItemStack.EMPTY;
        /** Free one-way delivery: buyer is the requester, seller is the provider. */
        final CooperativeSupply.Reservation delivery;
        /** Last tick each participant's meeting subtask executed (delivery sessions). */
        final Map<UUID, Long> seen = new HashMap<>();
        /** Consecutive failed path searches per participant. */
        final Map<UUID, Integer> pathMisses = new HashMap<>();
        /** Last path search per participant (searches run at most every 20 ticks, 5 when navigation ended). */
        final Map<UUID, Long> lastPlan = new HashMap<>();
        /** Delivery: tick the requester was given its meet_trade goal (provider close and reachable); -1 before. */
        long invitedAt = -1;
        long deadline;
        /** Delivery: the walking participant's last path to its partner reached it. */
        boolean walkerReaches;
        /**
         * Delivery booked from a claimed offer: the requester walks to the provider and the provider is invited
         * when it is close. Otherwise (a claimed request) the provider walks to the requester.
         */
        boolean pickup;
        UUID walker() { return pickup ? buyer : seller; }
        UUID waiter() { return pickup ? seller : buyer; }
        Meeting(ServerWorld w, CooperativeSupply.Reservation r, BlockPos position) {
            world=w; id=r.id; buyer=r.requester; seller=r.provider; this.position=position; delivery=r;
        }
        Meeting(ServerWorld w, UUID id, VillagerEntity seller, VillagerEntity buyer, ItemStack goods, ItemStack payment) {
            world=w; this.id=id; this.buyer=buyer.getUUID(); this.seller=seller.getUUID();
            position=buyer.blockPosition(); this.goods=goods.copy(); this.payment=payment.copy(); delivery=null;
        }
        boolean directed() { return delivery==null && !goods.isEmpty(); }
    }
    private static final Map<UUID,Meeting> MEETINGS=new LinkedHashMap<>();
    /** True while the meeting for this delivery claim (or exchange session) is still in progress. */
    public static boolean active(UUID meeting) { return meeting != null && MEETINGS.containsKey(meeting); }
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
        for(UUID id:Arrays.asList(m.buyer,m.seller)) addGoal(m,id,deadline);
        MEETINGS.put(m.id,m);
    }
    /** One participant's meet_trade goal (added even at the 10-goal soft cap: a booked meeting must not be lost). */
    private static void addGoal(Meeting m,UUID id,long deadline) {
        VillagerAgentData a=VillagerAgentManager.getAgent(id);
        if(a==null||goal(a,m.id)!=null) return;
        AgentGoal g=new AgentGoal("meet_trade",m.delivery!=null?"Meet for free delivery":"Meet for agreed trade",8);
        g.setTradeMessageId(m.id); g.setLifecycle(AgentGoal.Lifecycle.EXPIRING); g.setDeadlineTick(deadline);
        a.getGoals().add(g);
        BrainLog.goalAdded(a, g, "meeting:" + m.id);
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
        source.sendSuccess(new StringTextComponent("交易已预约：等待双方自然结束当前任务后会合，双方丢出实物并原地等待接触拾取后入账。"),false);
        return 1;
    }
    private static TradeInventory.Plan directedPlan(Meeting m) {
        VillagerAgentData b=VillagerAgentManager.getAgent(m.buyer),s=VillagerAgentManager.getAgent(m.seller);
        return b==null||s==null?null:TradeInventory.plan(b.getInventory().getItems(),s.getInventory().getItems(),
                Collections.emptyList(),new ItemStack[]{m.payment},new ItemStack[]{m.goods});
    }
    /**
     * Book the joint delivery session: both participants get a {@code meet_trade} goal for it. The caller
     * releases the claim on false.
     */
    public static boolean enqueueDelivery(ServerWorld world,CooperativeSupply.Reservation r,long deadline) {
        return enqueueDelivery(world,r,deadline,false);
    }
    /** @param pickup the requester claimed an offer and walks to the provider (instead of the provider walking). */
    public static boolean enqueueDelivery(ServerWorld world,CooperativeSupply.Reservation r,long deadline,boolean pickup) {
        if(MEETINGS.containsKey(r.id)) return true;
        VillagerEntity b=entity(world,r.requester),s=entity(world,r.provider);
        VillagerAgentData ba=VillagerAgentManager.getAgent(r.requester),sa=VillagerAgentManager.getAgent(r.provider);
        String why=b==null||s==null||ba==null||sa==null?"participant_missing"
                : TradeDemo.isActive(b.getUUID())||TradeDemo.isActive(s.getUUID())?"demo_active"
                : !VillageMarkets.same(world,r.requester,r.provider)?"different_market"
                : !owns(r.requester,r.id)||!owns(r.provider,r.id)?"appointment_not_held":null;
        // The meeting goals are added even when a goal list is at its soft cap: a booked delivery must not be lost.
        if(why!=null) {
            if(sa!=null) BrainLog.diagnostic(sa,"trade-book-failed","request="+r.request+" requester="+r.requester+" reason="+why);
            return false;
        }
        // Only one participant sets off: the provider walks to the requester for a claimed request, the requester
        // walks to the provider for a claimed offer. The other one is invited once the walker is close.
        Meeting m=new Meeting(world,r,(pickup?s:b).blockPosition().immutable());
        m.deadline=deadline;
        m.pickup=pickup;
        addGoal(m,m.walker(),deadline);
        MEETINGS.put(m.id,m);
        BrainLog.diagnostic(sa,"trade-booked","session="+r.id+" role=provider partner="+r.requester
                +(pickup?" invite=when_requester_near":" requesterAt="+b.blockPosition()));
        BrainLog.diagnostic(ba,"trade-booked","session="+r.id+" role=requester partner="+r.provider
                +(pickup?" walk_to=provider providerAt="+s.blockPosition():" invite=when_provider_near"));
        return true;
    }
    /** Booked delivery session for {@code request} in which {@code requester} receives; null if none. */
    public static UUID deliveryFor(UUID request,UUID requester) {
        for(Meeting m:MEETINGS.values())
            if(m.delivery!=null&&m.delivery.request.equals(request)&&m.buyer.equals(requester)) return m.id;
        return null;
    }
    public static SkillResult advance(VillagerAgentData a,VillagerEntity v,ServerWorld world,AgentGoal g) {
        return advanceSession(a,v,world,g.getTradeMessageId());
    }
    /**
     * Primitive meeting step, executed by the participant's own subtask each tick: report ready, walk to the
     * meeting point, then wait for the handoff. Callers check {@link #active(UUID)} to know when it ended.
     */
    public static SkillResult advanceSession(VillagerAgentData a,VillagerEntity v,ServerWorld world,UUID session) {
        Meeting m=MEETINGS.get(session);
        TradeAppointments.Appointment ap=VillageSignalBoard.APPOINTMENTS.get(session);
        if(m==null||m.world!=world||ap==null||!owns(a.getVillagerId(),m.id)) return SkillResult.DELETE;
        m.seen.put(a.getVillagerId(),world.getGameTime());
        if(m.droppedAt>=0) {
            return SkillResult.RUNNING;
        }
        // Walk to the meeting point straight away; readiness only means "this villager is on its way/there".
        long now=world.getGameTime();
        VillageSignalBoard.APPOINTMENTS.ready(m.id,a.getVillagerId(),now);
        VillagerEntity partner=entity(world,a.getVillagerId().equals(m.buyer)?m.seller:m.buyer);
        boolean withPartner=partner!=null && v.distanceToSqr(partner)<=HANDOFF_DIST_SQ && v.canSee(partner);
        if(withPartner || (partner==null && v.blockPosition().distSqr(m.position)<=2)) {
            if(a.getCurrentAction()!=null) ActionLifecycle.clearBody(a,v,world);
            v.getNavigation().stop();
            if(partner!=null) v.getLookControl().setLookAt(partner,30f,30f);
            return SkillResult.RUNNING;
        }
        VillagerAction move=a.getCurrentAction();
        if(move==null || move.getActionType()!=VillagerAction.ActionType.MOVE) {
            move=new VillagerAction(VillagerAction.ActionType.MOVE,"Meet for trade");
            move.setGoalDriven(true); move.setPhase(VillagerAction.ActionPhase.WALKING);
            move.setTargetBlockPos(m.position); a.setCurrentAction(move);
        }
        long lastPlan=m.lastPlan.getOrDefault(a.getVillagerId(),Long.MIN_VALUE/2);
        if(now-lastPlan>=20 || (v.getNavigation().isDone() && now-lastPlan>=5)) {
            m.lastPlan.put(a.getVillagerId(),now);
            // Walk towards the partner (both do, once both have their goal); the handoff happens where they meet.
            BlockPos target=partner!=null?partner.blockPosition():m.position;
            net.minecraft.pathfinding.Path path=v.getNavigation().createPath(target,1);
            if((path==null||!path.canReach()) && partner!=null) {
                // The partner may stand where nobody can path to (a ledge, a crop block): aim beside it instead.
                BlockPos beside=ReachablePaths.standingBeside(world,v,target);
                net.minecraft.pathfinding.Path alt=beside==null?null:v.getNavigation().createPath(beside,0);
                if(alt!=null&&(path==null||alt.canReach())) { path=alt; target=beside; }
            }
            if(a.getVillagerId().equals(m.walker())) m.walkerReaches=path!=null&&path.canReach();
            if(path==null) {
                // Close and in sight: hold position, the partner (or its own replanning) closes the gap.
                if(partner!=null && v.distanceToSqr(partner)<=CLOSE_WAIT_DIST_SQ && v.canSee(partner)) {
                    v.getNavigation().stop();
                    v.getLookControl().setLookAt(partner,30f,30f);
                    return SkillResult.RUNNING;
                }
                int misses=m.pathMisses.merge(a.getVillagerId(),1,Integer::sum);
                BrainLog.diagnostic(a,"trade-meeting-no-path","session="+m.id+" from="+v.blockPosition()
                        +" target="+target+" misses="+misses);
                if(misses>=MAX_PATH_MISSES) return SkillResult.FAIL;
            } else {
                m.pathMisses.remove(a.getVillagerId());
                move.setTargetBlockPos(target.immutable());
                v.getNavigation().moveTo(path,VillagerNavigationSupport.WALK_SPEED);
            }
        }
        return SkillResult.RUNNING;
    }
    private static boolean readyGoal(VillagerAgentData a,UUID id) {
        AgentGoal g=a.getRunningGoal(); return g!=null && "meet_trade".equals(g.getGoalType())
                && id.equals(g.getTradeMessageId()) && !g.isSuspended();
    }
    public static boolean canSettle(UUID id) {
        Meeting m=MEETINGS.get(id); TradeAppointments.Appointment a=VillageSignalBoard.APPOINTMENTS.get(id);
        return m!=null&&m.droppedAt<0&&a!=null&&a.started;
    }
    public static boolean awaitingPickup(UUID id) {
        Meeting m=MEETINGS.get(id);
        return m!=null&&m.droppedAt>=0;
    }
    public static void tick(ServerWorld world) {
        long now=world.getGameTime();
        for(Meeting m:new ArrayList<>(MEETINGS.values())) {
            if(m.world!=world) continue;
            TradeAppointments.Appointment ap=VillageSignalBoard.APPOINTMENTS.get(m.id);
            VillagerEntity b=entity(world,m.buyer),s=entity(world,m.seller);
            VillagerAgentData ba=VillagerAgentManager.getAgent(m.buyer),sa=VillagerAgentManager.getAgent(m.seller);
            // Outgoing stock has been debited. Never repeat or refund a physical handoff.
            if(m.droppedAt>=0) {
                if(now-m.droppedAt>=PICKUP_TIMEOUT_TICKS || ap==null || b==null || s==null || ba==null || sa==null
                        || b.isNoAi() || s.isNoAi() || !readyGoal(ba,m.id) || !readyGoal(sa,m.id)
                        || (ba.getCurrentAction()!=null&&!ba.getCurrentAction().isGoalDriven())
                        || (sa.getCurrentAction()!=null&&!sa.getCurrentAction().isGoalDriven())) {
                    finish(m,false);
                } else if (!collectDrops(m,b,s,ba,sa,now)) {
                    finish(m,false);
                } else if (m.drops.isEmpty()) {
                    finish(m,true);
                }
                continue;
            }
            // Delivery: once the walker is close and can reach its partner, the partner gets its own meet_trade
            // goal; it joins when its current task ends or reaches a yield point.
            if(m.delivery!=null && m.invitedAt<0 && b!=null && s!=null && ba!=null && sa!=null
                    && s.distanceToSqr(b)<=INVITE_DIST_SQ && (m.walkerReaches || s.canSee(b))) {
                m.invitedAt=now;
                addGoal(m,m.waiter(),m.deadline);
                BrainLog.diagnostic(m.pickup?sa:ba,"trade-invite","session="+m.id+" walker="+m.walker()
                        +" distance="+(int)Math.sqrt(s.distanceToSqr(b)));
            }
            String why=cancelReason(m,ap,b,s,ba,sa,now,world);
            if(why!=null) {
                BrainLog.diagnostic(ba!=null?ba:sa,"trade-meeting-cancel","session="+m.id+" reason="+why);
                finish(m,false); continue;
            }
            if(!ap.started && ap.buyerReady && ap.sellerReady) {
                ap.started=true;
                BrainLog.diagnostic(ba,"trade-meeting-start","session="+m.id+" partner="+m.seller);
            }
            // The handoff happens wherever the two meet: at the meeting point, or on the way if one walked to the other.
            if(ap.started && b.distanceToSqr(s)<=HANDOFF_DIST_SQ && b.canSee(s)) {
                boolean ok = m.delivery != null ? settleDeliveryDrop(m, b, s, world)
                        : settleDirectedDrop(m, b, s, world);
                if(ok) {
                    m.droppedAt=now;
                    ActionLifecycle.clearBody(ba,b,world);
                    ActionLifecycle.clearBody(sa,s,world);
                } else finish(m,false);
            }
        }
    }
    /** First reason a booked meeting can no longer happen, for the log; null while it is still viable. */
    private static String cancelReason(Meeting m,TradeAppointments.Appointment ap,VillagerEntity b,VillagerEntity s,
                                       VillagerAgentData ba,VillagerAgentData sa,long now,ServerWorld world) {
        if(ap==null) return "appointment_gone";
        if(b==null||s==null||ba==null||sa==null||b.isNoAi()||s.isNoAi()||b.isBaby()||s.isBaby()) return "participant_missing";
        if(now>=ap.deadline) return "deadline";
        if(!VillageMarkets.same(world,m.buyer,m.seller)) return "different_market";
        if(m.delivery!=null && !com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_ECONOMY.get()) return "economy_disabled";
        if(m.delivery!=null && !CooperativeSupply.revalidate(world,m.delivery)) return "stock_or_space_changed";
        if(m.delivery==null && (!m.directed() || directedPlan(m)==null)) return "exchange_no_longer_possible";
        // Combat/building owns the body.
        if((ba.getCurrentAction()!=null&&!ba.getCurrentAction().isGoalDriven())
                ||(sa.getCurrentAction()!=null&&!sa.getCurrentAction().isGoalDriven())) return "body_taken";
        boolean invited=m.delivery==null||m.invitedAt>=0;
        boolean sellerNeeded=m.delivery==null||!m.pickup||invited, buyerNeeded=m.delivery==null||m.pickup||invited;
        if(sellerNeeded && goal(sa,m.id)==null) return "provider_goal_gone";
        if(buyerNeeded && goal(ba,m.id)==null) return "requester_goal_gone";
        if(m.delivery!=null) {
            // The invited partner has PARTNER_WAIT_TICKS after its invitation to finish its task and join.
            boolean waiterReady=m.pickup?ap.sellerReady:ap.buyerReady;
            if(m.invitedAt>=0 && !waiterReady && now-m.invitedAt>PARTNER_WAIT_TICKS)
                return m.pickup?"provider_never_came":"requester_never_came";
        } else if(ap.firstReady>=0 && now-ap.firstReady>PARTNER_WAIT_TICKS) return "partner_never_came";
        if(ap.buyerReady&&!readyGoal(ba,m.id)) return "requester_left";
        if(ap.sellerReady&&!readyGoal(sa,m.id)) return "provider_left";
        return null;
    }
    private static boolean settleDirectedDrop(Meeting m, VillagerEntity buyer, VillagerEntity seller, ServerWorld w) {
        if (directedPlan(m) == null) return false;
        return throwExchange(m, buyer, seller, new ItemStack[]{m.payment}, new ItemStack[]{m.goods});
    }

    /** One-way free transfer: no payment stacks, provider stock revalidated immediately before throwing. */
    private static boolean settleDeliveryDrop(Meeting m, VillagerEntity requester, VillagerEntity provider, ServerWorld w) {
        if (!canSettle(m.id) || !CooperativeSupply.revalidate(w, m.delivery)) return false;
        boolean thrown = throwExchange(m, requester, provider, new ItemStack[0], CooperativeSupply.copies(m.delivery.stacks));
        if (thrown) CooperativeSupply.markThrown(m.delivery);
        return thrown;
    }

    /** Stage real drops, then commit only outgoing debits. Receipts belong exclusively to contact pickup. */
    private static boolean throwExchange(Meeting m, VillagerEntity buyer, VillagerEntity seller,
                                         ItemStack[] payment, ItemStack[] goods) {
        VillagerAgentData ba = VillagerAgentManager.getAgent(m.buyer);
        VillagerAgentData sa = VillagerAgentManager.getAgent(m.seller);
        if (ba == null || sa == null || TradeInventory.plan(ba.getInventory().getItems(),
                sa.getInventory().getItems(), Collections.emptyList(), payment, goods) == null) return false;
        List<ItemStack> buyerStock = TradeInventory.copy(ba.getInventory().getItems());
        List<ItemStack> sellerStock = TradeInventory.copy(sa.getInventory().getItems());
        if (!debit(buyerStock, payment) || !debit(sellerStock, goods)) return false;
        if (!spawnDrops(m, seller, buyer, goods) || !spawnDrops(m, buyer, seller, payment)) {
            for (net.minecraft.entity.item.ItemEntity drop : m.drops) drop.remove();
            m.drops.clear();
            return false;
        }
        for (int i=0;i<buyerStock.size();i++) ba.getInventory().setStackInSlot(i,buyerStock.get(i));
        for (int i=0;i<sellerStock.size();i++) sa.getInventory().setStackInSlot(i,sellerStock.get(i));
        m.droppedAt=m.world.getGameTime();
        VillagerEquipmentHelper.refreshEquipment(buyer,ba);
        VillagerEquipmentHelper.refreshEquipment(seller,sa);
        buyer.swing(net.minecraft.util.Hand.MAIN_HAND);
        seller.swing(net.minecraft.util.Hand.MAIN_HAND);
        return true;
    }

    private static boolean debit(List<ItemStack> stock, ItemStack[] offers) {
        for (ItemStack offer : offers) {
            int left=offer.getCount();
            for (ItemStack stack : stock) {
                if (!TradeInventory.matches(stack,offer)) continue;
                int n=Math.min(left,stack.getCount());
                stack.shrink(n); left-=n;
                if (left==0) break;
            }
            if (left!=0) return false;
        }
        return true;
    }

    private static boolean spawnDrops(Meeting m, VillagerEntity sender, VillagerEntity recipient, ItemStack[] offers) {
        for (ItemStack offer : offers) {
            if (offer.isEmpty()) continue;
            net.minecraft.entity.item.ItemEntity drop = new net.minecraft.entity.item.ItemEntity(
                    m.world,sender.getX(),sender.getEyeY()-0.3,sender.getZ(),offer.copy());
            drop.setDeltaMovement(recipient.position().subtract(sender.position()).normalize().scale(0.22).add(0,0.15,0));
            // Infinite delay also prevents vanilla merging; only this session collects these entities.
            drop.setPickUpDelay(32767);
            drop.setThrower(sender.getUUID());
            drop.setOwner(recipient.getUUID());
            m.drops.add(drop);
            if (!m.world.addFreshEntity(drop)) return false;
        }
        return true;
    }

    private static boolean collectDrops(Meeting m, VillagerEntity buyer, VillagerEntity seller,
                                        VillagerAgentData ba, VillagerAgentData sa, long now) {
        // Stay at the handoff position; only collect reserved drops that reach contact range.
        buyer.getNavigation().stop();
        seller.getNavigation().stop();
        for (Iterator<net.minecraft.entity.item.ItemEntity> it=m.drops.iterator();it.hasNext();) {
            net.minecraft.entity.item.ItemEntity drop=it.next();
            if (!drop.isAlive() || m.world.getEntity(drop.getUUID()) != drop) return false;
            if (now-m.droppedAt<15) continue;
            if (!m.buyer.equals(drop.getOwner()) && !m.seller.equals(drop.getOwner())) return false;
            boolean toBuyer=m.buyer.equals(drop.getOwner());
            VillagerEntity recipient=toBuyer?buyer:seller;
            if (ItemAttractionSystem.pickupBox(recipient).intersects(drop.getBoundingBox())) {
                ItemStack type = drop.getItem().copy();
                int before = type.getCount();
                drop.setPickUpDelay(0);
                ItemAttractionSystem.collect(drop,recipient,toBuyer?ba:sa,Integer.MAX_VALUE);
                int after = drop.isAlive() ? drop.getItem().getCount() : 0;
                // Credit only what actually entered the requester's inventory, once per picked count.
                if (m.delivery != null && toBuyer && before > after) CooperativeSupply.credit(m.delivery, type, before - after);
                if (!drop.isAlive()) { it.remove(); continue; }
                drop.setPickUpDelay(32767);
            }
        }
        return true;
    }

    private static void finish(Meeting m,boolean success) {
        MEETINGS.remove(m.id);
        // Thrown items are never refunded or replayed; unreceived credit returns to the request.
        if(m.delivery!=null) {
            CooperativeSupply.release(m.delivery);
            if(!m.delivery.thrown()) {
                // Nothing changed hands: CLAIMED -> OPEN, so this or another provider can claim the request again.
                VillageSignalBoard.VillageMessage req=VillageSignalBoard.get().find(m.delivery.request);
                if(req!=null && req.claim==m.delivery) {
                    req.claim=null;
                    VillagerAgentData ra=VillagerAgentManager.getAgent(m.buyer);
                    if(ra!=null) BrainLog.diagnostic(ra,"request-reopened","request="+req.id+" session="+m.id);
                }
            }
        }
        for (net.minecraft.entity.item.ItemEntity drop : m.drops) {
            if (drop.isAlive()) { drop.setOwner(null); drop.setPickUpDelay(10); }
        }
        m.drops.clear();
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
