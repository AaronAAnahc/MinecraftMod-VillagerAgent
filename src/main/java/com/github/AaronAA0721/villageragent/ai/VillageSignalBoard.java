package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A village-wide "shout" channel — a shared information pipe that lets one villager's alarm
 * reach every other villager, simulating everyone shouting in the village at once.
 *
 * <p>This is deliberately <b>not</b> a pairwise channel: each {@link RallyCall} records how many
 * villagers have answered the call (and their combined damage), so a group that individually
 * cannot win a fight can mob the threat together. The board is the single source of truth for
 * "who is rallying against whom", which is what makes "strength in numbers" an emergent behaviour
 * instead of a per-villager hard-coded check.
 */
public class VillageSignalBoard {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Shared storage; rally records carry a dimension and listeners/expiry filter by it.
     * Runtime boards are scoped to a persistent vanilla village identity.
     */
    private static final VillageSignalBoard INSTANCE = new VillageSignalBoard();
    private static final Map<UUID, VillageMessage> MESSAGE_INDEX = new HashMap<>();
    public static final TradeAppointments APPOINTMENTS = new TradeAppointments();

    /** Must have arrived near the threat before contributing to a charge. */
    public static final double RALLY_READY_RADIUS = 8.0;
    private static final double RALLY_RETAIN_RADIUS = 16.0;
    private static final long RESPONSE_EXPIRE_TICKS = 100L;

    /** Only a nearby direct sighting keeps an alarm alive. */
    private static final long RALLY_EXPIRE_TICKS = CombatAction.THREAT_MEMORY_TICKS;

    /**
     * One alarm: which threat, where it was last seen, and who has answered the call.
     */
    public static final class RallyCall {
        public final UUID threatUuid;
        public final ResourceLocation dimension;
        public BlockPos lastPos;
        public long lastUpdateTick;
        /** villager UUID → that villager's combat power (so combined power can be summed). */
        public final Map<UUID, Double> members = new HashMap<>();
        private final Map<UUID, Long> responseTicks = new HashMap<>();
        public double groupPower = 0.0;
        public boolean charging = false;
        private final Set<UUID> arrived = new HashSet<>();
        /** Listeners already told this rally has enough responders (logged once each). */
        private final Set<UUID> ignoredLogged = new HashSet<>();

        RallyCall(UUID threatUuid, ResourceLocation dimension) {
            this.threatUuid = threatUuid;
            this.dimension = dimension;
        }

        public int memberCount() {
            return members.size();
        }
    }

    private final Map<UUID, RallyCall> activeRallies = new ConcurrentHashMap<>();

    public static VillageSignalBoard get() {
        return INSTANCE;
    }

    /**
     * Register a villager as answering the call for {@code threat}. Idempotent per villager —
     * Re-answering refreshes membership, but only direct sightings refresh the alarm.
     * Returns null when there is neither a current alarm nor a visible threat.
     */
    public RallyCall respond(VillagerEntity villager, VillagerAgentData agent, LivingEntity threat) {
        UUID key = threat.getUUID();
        RallyCall call = activeRallies.get(key);
        long now = villager.level.getGameTime();
        boolean visible = CombatAction.isVisibleThreat(villager, threat);
        if (call == null || !call.dimension.equals(threat.level.dimension().location()) || expired(call, now)) {
            if (!visible) return null;
            call = new RallyCall(key, threat.level.dimension().location());
            activeRallies.put(key, call);
            BrainLog.diagnostic(agent, "rally-broadcast", "threat=" + key + " dimension=" + call.dimension
                    + " radius=" + ModConfig.COMBAT_RALLY_RADIUS.get() + " pos=" + threat.blockPosition());
        }
        if (!call.members.containsKey(villager.getUUID()))
            BrainLog.diagnostic(agent, "rally-response", "threat=" + key + " distance=" + villager.distanceTo(threat)
                    + " fatigue=" + agent.getFatigue() + " health=" + villager.getHealth());
        if (visible) {
            call.lastUpdateTick = now;
            call.lastPos = threat.blockPosition();
        }
        call.members.put(villager.getUUID(), CombatAction.combatPower(agent, villager));

        call.responseTicks.put(villager.getUUID(), now);
        return call;
    }

    /** Re-evaluate live, arrived responders; merely acknowledging never supplies remote power. */
    public boolean shouldCharge(LivingEntity threat) {
        RallyCall call = activeRallies.get(threat.getUUID());
        long now = threat.level.getGameTime();
        if (call == null || !call.dimension.equals(threat.level.dimension().location())
                || expired(call, now) || !(threat.level instanceof ServerWorld)) return false;
        ServerWorld world = (ServerWorld) threat.level;
        double power = 0;
        int ready = 0;
        java.util.Iterator<UUID> members = call.members.keySet().iterator();
        while (members.hasNext()) {
            UUID id = members.next();
            net.minecraft.entity.Entity entity = world.getEntity(id);
            if (!(entity instanceof VillagerEntity) || !entity.isAlive()
                    || now - call.responseTicks.getOrDefault(id, Long.MIN_VALUE / 2) > RESPONSE_EXPIRE_TICKS) {
                members.remove(); call.responseTicks.remove(id); call.arrived.remove(id); continue;
            }
            VillagerEntity villager = (VillagerEntity) entity;
            VillagerAgentData agent = VillagerAgentManager.getAgent(id);
            double contribution = 0;
            VillagerAction action = agent == null ? null : agent.getCurrentAction();
            boolean pursuing = call.arrived.contains(id) && action != null
                    && action.getActionType() == VillagerAction.ActionType.ATTACK
                    && threat.getUUID().equals(action.getTargetEntityId())
                    && villager.distanceToSqr(threat) <= RALLY_RETAIN_RADIUS * RALLY_RETAIN_RADIUS;
            boolean atAssembly = villager.distanceToSqr(threat) <= RALLY_READY_RADIUS * RALLY_READY_RADIUS
                    && villager.canSee(threat);
            if (agent != null && !villager.isBaby() && !villager.isNoAi() && !TradeDemo.isActive(id)
                    && (atAssembly || pursuing) && CombatAction.mayAttack(villager, agent)) {
                call.arrived.add(id);
                contribution = CombatAction.combatPower(agent, villager);
                ready++;
            } else call.arrived.remove(id);
            call.members.put(id, contribution);
            power += contribution;
        }
        call.groupPower = power;
        boolean charge = ready >= 2 && power >= ModConfig.COMBAT_CHARGE_TOTAL_POWER.get();
        if (charge && !call.charging)
            LOGGER.debug("Rally against {} now charging ({} arrived, {} power)",
                    threat.getType().getRegistryName(), ready, power);
        if (!charge && call.charging)
            LOGGER.info("Rally charge stopped: threat={} ready={} power={} requiredPower={}",
                    threat.getUUID(), ready, power, ModConfig.COMBAT_CHARGE_TOTAL_POWER.get());
        call.charging = charge;
        return charge;
    }

    /**
     * Committed responders already bring this much more than the charge threshold before a rally stops
     * recruiting (a margin for wounds and stragglers).
     */
    public static final double RALLY_SURPLUS = 1.25;

    /**
     * Whether a rally still needs {@code listener}. Villagers already answering keep answering; a new listener
     * is not recruited once at least two live, able responders together reach
     * {@code COMBAT_CHARGE_TOTAL_POWER × RALLY_SURPLUS}. If responders fall away, recruiting resumes.
     */
    public boolean needsHelp(RallyCall call, VillagerEntity listener) {
        if (call.members.containsKey(listener.getUUID()) || !(listener.level instanceof ServerWorld)) return true;
        ServerWorld world = (ServerWorld) listener.level;
        long now = world.getGameTime();
        double committed = 0;
        int able = 0;
        for (UUID id : call.members.keySet()) {
            if (now - call.responseTicks.getOrDefault(id, Long.MIN_VALUE / 2) > RESPONSE_EXPIRE_TICKS) continue;
            net.minecraft.entity.Entity entity = world.getEntity(id);
            VillagerAgentData agent = VillagerAgentManager.getAgent(id);
            if (!(entity instanceof VillagerEntity) || !entity.isAlive() || agent == null
                    || !CombatAction.mayAttack((VillagerEntity) entity, agent)) continue;
            committed += CombatAction.combatPower(agent, (VillagerEntity) entity);
            able++;
        }
        double required = ModConfig.COMBAT_CHARGE_TOTAL_POWER.get() * RALLY_SURPLUS;
        boolean full = able >= 2 && committed >= required;
        if (full && call.ignoredLogged.add(listener.getUUID())) {
            VillagerAgentData agent = VillagerAgentManager.getAgent(listener.getUUID());
            if (agent != null) BrainLog.diagnostic(agent, "rally-not-needed", "threat=" + call.threatUuid
                    + " responders=" + able + " power=" + committed + " required=" + required);
        }
        return !full;
    }

    /** Nearest active rally inside the configured same-dimension broadcast radius. */
    public RallyCall nearestRally(VillagerEntity villager) {
        double radius = ModConfig.COMBAT_RALLY_RADIUS.get();
        double r2 = radius * radius;
        long now = villager.level.getGameTime();
        RallyCall best = null;
        double bestD = r2;
        for (Map.Entry<UUID, RallyCall> e : activeRallies.entrySet()) {
            RallyCall c = e.getValue();
            if (!c.dimension.equals(villager.level.dimension().location())
                    || expired(c, now) || !(villager.level instanceof ServerWorld)
                    || ((ServerWorld) villager.level).getEntity(c.threatUuid) == null) continue;
            double d = villager.blockPosition().distSqr(c.lastPos);
            if (d <= bestD) {
                best = c;
                bestD = d;
            }
        }
        return best;
    }

    /** Forget stale rallies (called once per tick). */
    public void pruneExpired(ServerWorld world) {
        long now = world.getGameTime();
        activeRallies.entrySet().removeIf(e -> e.getValue().dimension.equals(world.dimension().location())
                && expired(e.getValue(), now));
    }

    public boolean hasActiveRally(VillagerEntity villager, UUID threatId) {
        RallyCall call = activeRallies.get(threatId);
        double radius = ModConfig.COMBAT_RALLY_RADIUS.get();
        return call != null && call.dimension.equals(villager.level.dimension().location())
                && !expired(call, villager.level.getGameTime())
                && villager.blockPosition().distSqr(call.lastPos) <= radius * radius;
    }

    private boolean expired(RallyCall call, long now) {
        return now - call.lastUpdateTick >= RALLY_EXPIRE_TICKS;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Generic village message bus (LLM-free). THREAT rallies above are kept intact;
    //  this extends the board with typed messages so non-combat emergence (supply/
    //  demand trade, food shortage, build calls) can propagate through the same pipe.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Request lifecycle. Transitions: OPEN → CLAIMED (a provider claims the whole request at the board),
     * CLAIMED → OPEN (only if the meeting could not be arranged), CLAIMED → CONSUMED (its meeting ended),
     * OPEN → EXPIRED (TTL). Spec and quantity never change; the owner retires CONSUMED/EXPIRED requests.
     */
    public enum RequestState { OPEN, CLAIMED, CONSUMED, EXPIRED }

    /** Message categories that villagers can broadcast to the whole village. */
    public enum MsgType { THREAT, TRADE_WANT, TRADE_HAVE, FOOD_SHORTAGE, BUILD_NEEDED }

    /** A single broadcast message on the village board. */
    public static final class VillageMessage {
        public final UUID id = UUID.randomUUID();
        public final MsgType type;
        public final UUID author;
        public BlockPos origin;
        public final ResourceLocation item;   // TRADE_* → item registry name; else null
        public int quantity;
        public long postedTick;
        public long expireTick;
        public final Set<UUID> responders = new HashSet<>();
        public UUID matchedWith = null;       // TRADE: the counterpart message id
        public boolean resolved = false;
        /**
         * Owner key: a method attempt for attempt-scoped messages, or the author's own id for a villager-owned
         * cooperative request (which outlives the method that posted it). Null denotes ordinary shared demand.
         */
        public UUID ownerAttempt;
        public VillageSignalBoard market;
        /** Author subjective value per item; negative means no valid quote. */
        public double unitPrice = -1;
        public int agreedPayment = -1;
        public BarterExchange exchange;
        /** @deprecated retired exclusive-producer claim; cooperative production is non-exclusive. Always null. */
        @Deprecated public UUID productionWorker;
        /** Cooperative request matching specification; null for informational TRADE_HAVE stock. */
        public SupplyPolicy.Spec spec;
        /**
         * The single delivery claim. A request's spec and quantity never change after posting; claiming
         * consumes it. Once the claim's meeting ends the request stays consumed until its owning acquisition
         * method retires it (and posts a new request if the objective is still unmet).
         */
        CooperativeSupply.Reservation claim; // Written only by CooperativeSupply state transitions.

        public boolean isRequest() { return type == MsgType.TRADE_WANT && spec != null; }
        public CooperativeSupply.Reservation claim() { return claim; }
        public boolean claimed() { return claim != null; }
        /** Claimed and its delivery meeting already ended. */
        public boolean consumed() { return claim != null && claim.released(); }
        public RequestState state(long now) {
            if (claim != null) return claim.released() ? RequestState.CONSUMED : RequestState.CLAIMED;
            return resolved || expireTick <= now ? RequestState.EXPIRED : RequestState.OPEN;
        }

        VillageMessage(MsgType type, UUID author, BlockPos origin, ResourceLocation item,
                       int quantity, long now, long ttl) {
            this.type = type;
            this.author = author;
            this.origin = origin;
            this.item = item;
            this.quantity = quantity;
            this.postedTick = now;
            this.expireTick = now + ttl;
        }
    }

    private final Map<MsgType, List<VillageMessage>> board = new HashMap<>();
    private static final double MSG_HEAR_RADIUS = 48.0;

    /** Post (or refresh) a message; returns its id. {@code now} is the current game time. */
    public UUID post(UUID author, BlockPos origin, MsgType type, ResourceLocation item, int qty, long now) {
        List<VillageMessage> list = board.computeIfAbsent(type, k -> new ArrayList<>());
        // De-dupe by author+item so a villager refreshes its own offer instead of spamming.
        for (VillageMessage m : list) {
            if (!m.resolved && m.ownerAttempt == null && m.author.equals(author)
                    && (type == MsgType.THREAT || java.util.Objects.equals(m.item, item))) {
                if (m.matchedWith != null) return m.id; // Freeze the agreed quantity while meeting.
                m.quantity = qty;
                m.origin = origin;
                m.postedTick = now;
                m.expireTick = now + messageTtl();
                return m.id;
            }
        }
        VillageMessage m = new VillageMessage(type, author, origin, item, qty, now, messageTtl());
        m.market = this; MESSAGE_INDEX.put(m.id, m);
        list.add(m);
        return m.id;
    }

    /** Isolated method-owned demand; it never overwrites a different task's request. */
    public UUID postOwnedWant(UUID owner, UUID author, BlockPos origin, ResourceLocation item, int qty, long now) {
        return postOwnedRequest(owner, author, origin, SupplyPolicy.Spec.exact(item.toString()), qty, now);
    }

    /** Method-owned cooperative request; cancelled with its owner by {@link #removeOwned(UUID)}. */
    public UUID postOwnedRequest(UUID owner, UUID author, BlockPos origin, SupplyPolicy.Spec spec, int qty, long now) {
        if (owner == null) throw new IllegalArgumentException("Missing attempt owner");
        List<VillageMessage> list = board.computeIfAbsent(MsgType.TRADE_WANT, key -> new ArrayList<>());
        // One villager never has two live requests for the same thing on the board: join the existing one.
        String key = spec.key();
        for (VillageMessage m : list) {
            if (!m.isRequest() || !m.author.equals(author) || !key.equals(m.spec.key())) continue;
            RequestState state = m.state(now);
            if (state != RequestState.OPEN && state != RequestState.CLAIMED) continue;
            if (state == RequestState.OPEN) {
                // Unclaimed, so still safe to widen: cover the larger need and keep it posted.
                m.quantity = Math.max(m.quantity, qty);
                m.expireTick = Math.max(m.expireTick, now + messageTtl());
            }
            return m.id;
        }
        VillageMessage message = new VillageMessage(MsgType.TRADE_WANT, author, origin,
                spec.isCategory() || spec.item.startsWith(SupplyPolicy.CATEGORY_PREFIX) ? null : new ResourceLocation(spec.item),
                qty, now, messageTtl());
        message.spec = spec;
        message.ownerAttempt = owner;
        message.market = this; MESSAGE_INDEX.put(message.id, message);
        list.add(message);
        return message.id;
    }

    /** Messages of {@code type} within hearing radius of {@code pos} at game time {@code now}. */
    public List<VillageMessage> hear(BlockPos pos, MsgType type, long now) {
        List<VillageMessage> out = new ArrayList<>();
        List<VillageMessage> list = board.get(type);
        if (list == null) return out;
        double r2 = MSG_HEAR_RADIUS * MSG_HEAR_RADIUS;
        for (VillageMessage m : list) {
            if (m.resolved || m.expireTick <= now) continue;
            out.add(m);
        }
        return out;
    }

    /** Mark a villager as having answered/acknowledged a message. */
    public void ack(UUID villager, UUID msgId) {
        for (List<VillageMessage> list : board.values()) {
            for (VillageMessage m : list) {
                if (m.id.equals(msgId)) { m.responders.add(villager); return; }
            }
        }
    }

    /** Pair a TRADE_WANT with a TRADE_HAVE of the same item (have.qty >= want.qty). */
    public boolean matchTrade(UUID wantId, UUID haveId) {
        VillageMessage want = find(wantId), have = find(haveId);
        if (want == null || have == null) return false;
        if (want.market != have.market) return false;
        if (want.resolved || have.resolved) return false;
        if (want.type != MsgType.TRADE_WANT || have.type != MsgType.TRADE_HAVE) return false;
        if (want.matchedWith != null || have.matchedWith != null) return false;
        if (!java.util.Objects.equals(want.item, have.item)) return false;
        if (have.quantity < want.quantity) return false;
        if (want.author.equals(have.author)) return false; // no self-trade
        if (!APPOINTMENTS.reserve(want.id, want.author, have.author,
                Math.min(want.expireTick, have.expireTick))) return false;
        want.matchedWith = have.id;
        have.matchedWith = want.id;
        return true;
    }

    /** Resolve (retire) a trade pair after the exchange completes. */
    public void resolveTrade(UUID wantId) {
        VillageMessage want = find(wantId);
        if (want == null) return;
        want.resolved = true;
        APPOINTMENTS.release(want.id);
        if (want.matchedWith != null) {
            VillageMessage have = find(want.matchedWith);
            if (have != null) have.resolved = true;
        }
    }

    /** Every live cooperative request posted by {@code author}, on any board. */
    public static List<VillageMessage> requestsBy(UUID author) {
        List<VillageMessage> out = new ArrayList<>();
        for (VillageMessage m : MESSAGE_INDEX.values()) if (m.isRequest() && m.author.equals(author)) out.add(m);
        return out;
    }

    /** Find any message across all types by id. */
    public VillageMessage find(UUID id) {
        return MESSAGE_INDEX.get(id);
    }

    public List<VillageMessage> messagesOfType(MsgType type) {
        List<VillageMessage> list = board.get(type);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /**
     * Remove every still-open message posted by {@code author} of {@code type} matching
     * {@code item} (null item = any item of that type). Used by {@code AcquireItemSkill} to
     * retire the trade wants it published once the item is in hand (so the village board is
     * not left advertising a demand that is already satisfied). Resolved messages are ignored.
     * @return how many were removed
     */
    public int removeByAuthor(UUID author, MsgType type, ResourceLocation item) {
        int removed = 0;
        for (VillageMessage m : new ArrayList<>(MESSAGE_INDEX.values())) {
            if (m.type != type) continue;
            if (m.resolved) continue;
            if (m.ownerAttempt != null) continue;
            if (!m.author.equals(author)) continue;
            if (item != null && !java.util.Objects.equals(m.item, item)) continue;
            m.market.removeMessage(m);
            removed++;
        }
        return removed;
    }

    /** Drop expired / resolved messages. Call once per tick. */
    public void clearExpiredMessages(long now) {
        for (List<VillageMessage> list : board.values())
            for (VillageMessage m : new ArrayList<>(list))
                // A claimed request belongs to its delivery and owner; only they retire it.
                if (m.resolved || (m.expireTick <= now && m.claim == null)) removeMessage(m);
    }

    public void unmatch(UUID id) {
        VillageMessage m=find(id); if(m==null) { APPOINTMENTS.release(id); return; }
        VillageMessage other=find(m.matchedWith);
        APPOINTMENTS.release(m.type==MsgType.TRADE_WANT?m.id:other==null?m.id:other.id);
        m.matchedWith=null; m.agreedPayment=-1; m.exchange=null;
        if(other!=null) { other.matchedWith=null; other.agreedPayment=-1; other.exchange=null; }
    }
    private void removeMessage(VillageMessage m) {
        unmatch(m.id);
        List<VillageMessage> list=board.get(m.type); if(list!=null) list.remove(m);
        MESSAGE_INDEX.remove(m.id);
    }
    /**
     * Cancel every request posted by method attempt {@code owner} (its acquisition was cancelled or ended).
     * A live delivery claim on such a request is ended too, so the provider is not left with a dangling meeting.
     * @return how many messages were removed
     */
    public int removeOwned(UUID owner) {
        if (owner == null) return 0;
        int removed = 0;
        for (VillageMessage m : new ArrayList<>(MESSAGE_INDEX.values())) {
            if (!owner.equals(m.ownerAttempt)) continue;
            if (m.claim != null) CooperativeSupply.release(m.claim);
            m.market.removeMessage(m);
            removed++;
        }
        return removed;
    }

    /**
     * Owner-side retirement of one request (CONSUMED after its meeting, or abandoned before a re-post).
     * Ends its claim if still open, then removes it from its board.
     */
    public void retire(UUID request) {
        VillageMessage m = find(request);
        if (m == null) return;
        if (m.claim != null) CooperativeSupply.release(m.claim);
        m.market.removeMessage(m);
    }

    public void removeResident(UUID resident) {
        for(List<VillageMessage> list:board.values()) for(VillageMessage m:new ArrayList<>(list))
            if(m.author.equals(resident)) removeMessage(m);
    }
    public void clearMessages() {
        for(List<VillageMessage> list:board.values()) for(VillageMessage m:new ArrayList<>(list)) removeMessage(m);
    }

    private long messageTtl() {
        return ModConfig.TRADE_MSG_TTL.get();
    }
}
