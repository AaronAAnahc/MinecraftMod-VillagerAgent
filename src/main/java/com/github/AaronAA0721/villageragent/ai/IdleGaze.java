package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.server.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Idle body language: while a villager has nothing to do (no running task, or standing between two wander walks)
 * it keeps looking around instead of staring in one direction. Every 1.5–4.5 s it picks a new focus — a nearby
 * villager, player or animal it can see, or a point somewhere around it, often well behind its back — and holds
 * that focus every tick. The head turns at a calm speed and the body follows once the head is far off-axis, so
 * the villager visibly turns around.
 *
 * <p>Vanilla {@code LookController} only applies a target on the tick it was set, so the focus is re-applied every
 * tick; a one-shot {@code setLookAt} barely moves the head.
 */
public final class IdleGaze {
    /** Head yaw speed (degrees per tick) and pitch limit while idly looking around. */
    private static final float YAW_SPEED = 10f, PITCH_SPEED = 30f;
    /** Body starts turning after the head when it is this far off the body axis. */
    private static final float BODY_FOLLOW_DEG = 35f;

    private static final class Gaze {
        Entity entity;
        double x, y, z;
        long until;
    }

    private static final Map<UUID, Gaze> GAZES = new HashMap<>();

    private IdleGaze() {}

    /** Called every tick for every managed adult; does nothing unless the villager is idle. */
    public static void tick(VillagerEntity villager, ServerWorld world, VillagerAgentData agent) {
        if (!isIdle(villager, agent)) { GAZES.remove(villager.getUUID()); return; }
        long now = world.getGameTime();
        Gaze gaze = GAZES.computeIfAbsent(villager.getUUID(), id -> new Gaze());
        if (now >= gaze.until || (gaze.entity != null && (!gaze.entity.isAlive()
                || gaze.entity.distanceToSqr(villager) > 16 * 16))) pick(villager, world, gaze, now);
        if (gaze.entity != null) villager.getLookControl().setLookAt(gaze.entity, YAW_SPEED, PITCH_SPEED);
        else villager.getLookControl().setLookAt(gaze.x, gaze.y, gaze.z, YAW_SPEED, PITCH_SPEED);
        // Turn the body after the head so a look over the shoulder becomes a real turn.
        float off = MathHelper.wrapDegrees(villager.yHeadRot - villager.yBodyRot);
        if (Math.abs(off) > BODY_FOLLOW_DEG) {
            villager.yBodyRot += off * 0.12f;
            villager.yRot = villager.yBodyRot;
        }
    }

    public static void forget(UUID villager) { GAZES.remove(villager); }

    /** No task with a purpose, not walking, not asleep, not in a conversation or a reactive action. */
    static boolean isIdle(VillagerEntity villager, VillagerAgentData agent) {
        if (villager.isSleeping() || villager.isBaby() || agent.isSocializing()) return false;
        if (agent.getCurrentAction() != null) return false;
        if (!villager.getNavigation().isDone()) return false;
        AgentGoal running = agent.getRunningGoal();
        return running == null || "wander".equals(running.getGoalType());
    }

    private static void pick(VillagerEntity villager, ServerWorld world, Gaze gaze, long now) {
        Random random = villager.getRandom();
        gaze.until = now + 30 + random.nextInt(60);
        gaze.entity = null;
        if (random.nextInt(100) < 45) {
            Entity best = null;
            double bestScore = Double.MAX_VALUE;
            for (LivingEntity other : world.getEntitiesOfClass(LivingEntity.class, villager.getBoundingBox().inflate(10),
                    e -> e != villager && e.isAlive() && (e instanceof VillagerEntity || e instanceof PlayerEntity
                            || e instanceof AnimalEntity))) {
                if (!villager.canSee(other)) continue;
                // Prefer people over animals, closer over further, with some randomness so it is not always the same.
                double score = villager.distanceToSqr(other) * (other instanceof AnimalEntity ? 2.0 : 1.0)
                        * (0.5 + random.nextDouble());
                if (score < bestScore) { bestScore = score; best = other; }
            }
            if (best != null) { gaze.entity = best; return; }
        }
        // A spot around the villager: usually a sizeable turn, sometimes straight behind.
        float turn = (60f + random.nextFloat() * 120f) * (random.nextBoolean() ? 1f : -1f);
        float yaw = (villager.yHeadRot + turn) * ((float) Math.PI / 180f);
        double dist = 3 + random.nextDouble() * 4;
        gaze.x = villager.getX() - MathHelper.sin(yaw) * dist;
        gaze.z = villager.getZ() + MathHelper.cos(yaw) * dist;
        gaze.y = villager.getEyeY() + (random.nextDouble() - 0.6) * 1.5;
    }
}
