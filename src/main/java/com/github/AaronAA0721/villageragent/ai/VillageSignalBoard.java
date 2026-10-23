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
     * The village is assumed to live in a single overworld, so one shared board suffices. If
     * multi-dimension villages ever matter, key this per {@code ServerWorld} instead.
     */
    private static final VillageSignalBoard INSTANCE = new VillageSignalBoard();

    /** How far (blocks) a rally shout carries — villagers within this radius can hear the call. */
    public static final double RALLY_RADIUS = 20.0;

    /** A rally goes quiet (and is forgotten) after this long without anyone answering it. */
    private static final long RALLY_EXPIRE_TICKS = 300L;

    /**
     * One alarm: which threat, where it was last seen, and who has answered the call.
     */
    public static final class RallyCall {
        public final UUID threatUuid;
        public BlockPos lastPos;
        public long lastUpdateTick;
        /** villager UUID → that villager's combat power (so combined power can be summed). */
        public final Map<UUID, Double> members = new HashMap<>();
        public double groupPower = 0.0;
        public boolean charging = false;

        RallyCall(UUID threatUuid) {
            this.threatUuid = threatUuid;
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
     * re-answering just refreshes its DPS contribution and the rally's last-seen position/tick.
     * Returns the (new or existing) rally.
     */
    public RallyCall respond(VillagerEntity villager, VillagerAgentData agent, LivingEntity threat) {
        UUID key = threat.getUUID();
        RallyCall call = activeRallies.get(key);
        long now = villager.level.getGameTime();
        if (call == null || expired(call, now)) {
            call = new RallyCall(key);
            activeRallies.put(key, call);
        }
        call.lastUpdateTick = now;
        call.lastPos = threat.blockPosition();
        call.members.put(villager.getUUID(), CombatAction.combatPower(agent, villager));

        double sum = 0.0;
        for (double d : call.members.values()) sum += d;
        call.groupPower = sum;
        return call;
    }

    /**
     * Whether the responders gathered around {@code threat} have enough combined combat power to
     * charge it: {@code Σ combatPower(responder) >= combat_charge_total_power} (no head-count, no
     * direct DPS-vs-threat comparison). Bare-handed responders contribute 0, so a group of fists
     * never triggers a charge. Once a rally starts charging it stays charging until the threat
     * dies or it expires.
     */
    public boolean shouldCharge(LivingEntity threat) {
        RallyCall call = activeRallies.get(threat.getUUID());
        if (call == null || expired(call, threat.level.getGameTime())) return false;
        if (call.charging) return true;
        if (call.groupPower >= ModConfig.COMBAT_CHARGE_TOTAL_POWER.get()) {
            call.charging = true;
            LOGGER.debug("Rally against {} now charging ({} responders, {} power)",
                    threat.getType().getRegistryName(), call.memberCount(), call.groupPower);
            return true;
        }
        return false;
    }

    /** Nearest active rally within {@code radius} of the villager — i.e. can it hear a shout? */
    public RallyCall nearestRally(VillagerEntity villager, double radius) {
        double r2 = radius * radius;
        long now = villager.level.getGameTime();
        RallyCall best = null;
        double bestD = r2;
        for (Map.Entry<UUID, RallyCall> e : activeRallies.entrySet()) {
            RallyCall c = e.getValue();
            if (expired(c, now)) continue;
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
        activeRallies.entrySet().removeIf(e -> expired(e.getValue(), now));
    }

    private boolean expired(RallyCall call, long now) {
        return now - call.lastUpdateTick > RALLY_EXPIRE_TICKS;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Generic village message bus (LLM-free). THREAT rallies above are kept intact;
    //  this extends the board with typed messages so non-combat emergence (supply/
    //  demand trade, food shortage, build calls) can propagate through the same pipe.
    // ─────────────────────────────────────────────────────────────────────────────

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

    private static final Map<MsgType, List<VillageMessage>> board = new ConcurrentHashMap<>();
    private static final double MSG_HEAR_RADIUS = 48.0;

    /** Post (or refresh) a message; returns its id. {@code now} is the current game time. */
    public UUID post(UUID author, BlockPos origin, MsgType type, ResourceLocation item, int qty, long now) {
        List<VillageMessage> list = board.computeIfAbsent(type, k -> new ArrayList<>());
        // De-dupe by author+item so a villager refreshes its own offer instead of spamming.
        for (VillageMessage m : list) {
            if (!m.resolved && m.author.equals(author)
                    && (type == MsgType.THREAT || java.util.Objects.equals(m.item, item))) {
                m.quantity = qty;
                m.origin = origin;
                m.postedTick = now;
                m.expireTick = now + messageTtl();
                return m.id;
            }
        }
        VillageMessage m = new VillageMessage(type, author, origin, item, qty, now, messageTtl());
        list.add(m);
        return m.id;
    }

    /** Messages of {@code type} within hearing radius of {@code pos} at game time {@code now}. */
    public List<VillageMessage> hear(BlockPos pos, MsgType type, long now) {
        List<VillageMessage> out = new ArrayList<>();
        List<VillageMessage> list = board.get(type);
        if (list == null) return out;
        double r2 = MSG_HEAR_RADIUS * MSG_HEAR_RADIUS;
        for (VillageMessage m : list) {
            if (m.resolved || m.expireTick <= now) continue;
            if (pos.distSqr(m.origin) <= r2) out.add(m);
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
        if (want.type != MsgType.TRADE_WANT || have.type != MsgType.TRADE_HAVE) return false;
        if (want.matchedWith != null || have.matchedWith != null) return false;
        if (!java.util.Objects.equals(want.item, have.item)) return false;
        if (have.quantity < want.quantity) return false;
        if (want.author.equals(have.author)) return false; // no self-trade
        want.matchedWith = have.id;
        have.matchedWith = want.id;
        return true;
    }

    /** Resolve (retire) a trade pair after the exchange completes. */
    public void resolveTrade(UUID wantId) {
        VillageMessage want = find(wantId);
        if (want == null) return;
        want.resolved = true;
        if (want.matchedWith != null) {
            VillageMessage have = find(want.matchedWith);
            if (have != null) have.resolved = true;
        }
    }

    /** Find any message across all types by id. */
    public VillageMessage find(UUID id) {
        for (List<VillageMessage> list : board.values()) {
            for (VillageMessage m : list) if (m.id.equals(id)) return m;
        }
        return null;
    }

    public List<VillageMessage> messagesOfType(MsgType type) {
        List<VillageMessage> list = board.get(type);
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * Remove every still-open message posted by {@code author} of {@code type} matching
     * {@code item} (null item = any item of that type). Used by {@code AcquireItemSkill} to
     * retire the trade wants it published once the item is in hand (so the village board is
     * not left advertising a demand that is already satisfied). Resolved messages are ignored.
     * @return how many were removed
     */
    public int removeByAuthor(UUID author, MsgType type, ResourceLocation item) {
        List<VillageMessage> list = board.get(type);
        if (list == null) return 0;
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            VillageMessage m = list.get(i);
            if (m.resolved) continue;
            if (!m.author.equals(author)) continue;
            if (item != null && !java.util.Objects.equals(m.item, item)) continue;
            if (m.matchedWith != null) {
                VillageMessage counterpart = find(m.matchedWith);
                if (counterpart != null) counterpart.matchedWith = null;
            }
            list.remove(i);
            removed++;
        }
        return removed;
    }

    /** Drop expired / resolved messages. Call once per tick. */
    public void clearExpiredMessages(long now) {
        for (List<VillageMessage> list : board.values()) {
            list.removeIf(m -> m.resolved || m.expireTick <= now);
        }
    }

    private long messageTtl() {
        return ModConfig.TRADE_MSG_TTL.get();
    }
}
