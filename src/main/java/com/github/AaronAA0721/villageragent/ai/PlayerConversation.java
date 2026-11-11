package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.github.AaronAA0721.villageragent.network.VillagerResponsePacket;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.MonsterEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player is talking to a villager: from opening the chat panel until it is closed, the villager drops what it
 * is doing, stands still and looks at the player, unless an emergency comes up.
 *
 * <ul>
 *   <li>{@link #begin} when the chat panel opens; {@link #touch} on every message or trade; {@link #end} when the
 *       panel closes.</li>
 *   <li>The hold also ends by itself when the player walks away (more than {@link #MAX_DISTANCE} blocks), leaves,
 *       or is silent for {@link #IDLE_TIMEOUT} ticks.</li>
 *   <li>Emergencies end it at once and the villager says so: he was hurt in the last few seconds, a hostile mob is
 *       within {@link #HOSTILE_RANGE} blocks, he is fighting, burning or in lava, or a priority-9 goal is queued
 *       (bedtime does not count).</li>
 *   <li>On the first held tick the running task is cancelled (navigation, block breaking, reservations), so after
 *       the talk the goal starts cleanly instead of resuming a half-finished walk. The goal itself stays queued.</li>
 * </ul>
 * Server thread only.
 */
public final class PlayerConversation {

    /** Ticks without a message or trade before the villager gets back to work (5 minutes). */
    public static final long IDLE_TIMEOUT = 6000;
    public static final double MAX_DISTANCE = 12;
    public static final double HOSTILE_RANGE = 10;

    private static final class Hold {
        final UUID player;
        long lastTouch;
        boolean started;
        Hold(UUID player, long now) { this.player = player; this.lastTouch = now; }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private PlayerConversation() {}

    /** The chat panel was opened: hold the villager for this player (replaces any other player's hold). */
    public static void begin(UUID villagerId, UUID playerId, long now) {
        Hold h = HOLDS.get(villagerId);
        if (h != null && h.player.equals(playerId)) { h.lastTouch = now; return; }
        HOLDS.put(villagerId, new Hold(playerId, now));
    }

    /** A message or trade from the player keeps the hold alive (and starts one if the panel was already open). */
    public static void touch(UUID villagerId, UUID playerId, long now) {
        begin(villagerId, playerId, now);
    }

    /** The player closed the chat panel. */
    public static void end(UUID villagerId, UUID playerId) {
        Hold h = HOLDS.get(villagerId);
        if (h != null && h.player.equals(playerId)) HOLDS.remove(villagerId);
    }

    /** True while some player is holding this villager in conversation. */
    public static boolean isHeld(UUID villagerId) {
        return HOLDS.containsKey(villagerId);
    }

    /**
     * Run once per agent tick before the villager's own brain. Returns true when the villager is held this tick:
     * it has been stopped and turned toward the player, and the caller must skip its goals and navigation.
     */
    public static boolean tick(ServerWorld w, VillagerEntity v, VillagerAgentData a, long now) {
        Hold h = HOLDS.get(a.getVillagerId());
        if (h == null) return false;
        ServerPlayerEntity player = w.getServer().getPlayerList().getPlayer(h.player);
        if (player == null || !player.isAlive() || player.level != w
                || player.distanceToSqr(v) > MAX_DISTANCE * MAX_DISTANCE || now - h.lastTouch > IDLE_TIMEOUT) {
            release(a, h, "player left or went quiet");
            return false;
        }
        String emergency = emergency(w, v, a);
        if (emergency != null) {
            release(a, h, "emergency: " + emergency);
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new VillagerResponsePacket(a.getVillagerId(), a.getName(), "Sorry, I have to go — " + emergency + "!"));
            return false;
        }
        if (!h.started) {
            h.started = true;
            ActionLifecycle.cancel(a, v, w); // drop the current task cleanly; the goal stays queued
            BrainLog.diagnostic(a, "conversation-hold", "player=" + player.getName().getString());
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(player, 30f, 30f);
        a.setCurrentActivity("talking with " + player.getName().getString());
        a.brain().goalStartedTick = now; // the pause is not "no progress"
        return true;
    }

    private static void release(VillagerAgentData a, Hold h, String why) {
        HOLDS.remove(a.getVillagerId());
        if (h.started) {
            a.setCurrentActivity("idle");
            BrainLog.diagnostic(a, "conversation-release", why);
        }
    }

    /** Why the villager must stop talking right now, or null. */
    private static String emergency(ServerWorld w, VillagerEntity v, VillagerAgentData a) {
        if (v.getLastHurtByMob() != null && v.tickCount - v.getLastHurtByMobTimestamp() < 100) return "I'm being attacked";
        if (v.isOnFire() || v.isInLava()) return "I'm burning";
        VillagerAction action = a.getCurrentAction();
        if (action != null && action.getActionType() == VillagerAction.ActionType.ATTACK) return "there's a fight";
        if (!w.getEntitiesOfClass(MonsterEntity.class, v.getBoundingBox().inflate(HOSTILE_RANGE),
                m -> m.isAlive()).isEmpty()) return "there's a monster nearby";
        for (AgentGoal g : a.getGoals())
            if (!g.isCompleted() && g.getPriority() >= 9 && !SleepSkill.isSleepGoal(g)) return "something urgent came up";
        return null;
    }
}
