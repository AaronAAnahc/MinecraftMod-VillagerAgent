package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
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

    /** Minimum villagers (caller + responders) before a group may charge the threat. */
    public static final int RALLY_MIN_MEMBERS = 2;

    /** A rally goes quiet (and is forgotten) after this long without anyone answering it. */
    private static final long RALLY_EXPIRE_TICKS = 300L;

    /**
     * One alarm: which threat, where it was last seen, and who has answered the call.
     */
    public static final class RallyCall {
        public final UUID threatUuid;
        public BlockPos lastPos;
        public long lastUpdateTick;
        /** villager UUID → that villager's damage per second (so combined damage can be summed). */
        public final Map<UUID, Double> members = new HashMap<>();
        public double groupDps = 0.0;
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
        call.members.put(villager.getUUID(), CombatAction.villagerDps(agent));

        double sum = 0.0;
        for (double d : call.members.values()) sum += d;
        call.groupDps = sum;
        return call;
    }

    /**
     * Whether the group gathered around {@code threat} is big and strong enough to charge it:
     * at least {@link #RALLY_MIN_MEMBERS} members whose combined damage outpaces the threat's.
     * Once a rally starts charging it stays charging until the threat dies / it expires.
     */
    public boolean shouldCharge(LivingEntity threat) {
        RallyCall call = activeRallies.get(threat.getUUID());
        if (call == null || expired(call, threat.level.getGameTime())) return false;
        if (call.charging) return true;
        if (call.memberCount() >= RALLY_MIN_MEMBERS && call.groupDps >= CombatAction.threatDps(threat)) {
            call.charging = true;
            LOGGER.debug("Rally against {} now charging ({} members, {} dps)",
                    threat.getType().getRegistryName(), call.memberCount(), call.groupDps);
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
}
