package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.ZombifiedPiglinEntity;
import net.minecraft.world.server.ServerWorld;

/**
 * Combat rules for the Guard profession.
 *
 * <p>Ordinary villagers only fight when the village rally says the group is strong enough
 * ({@link VillageSignalBoard#shouldCharge}). A guard with a real weapon is the exception: it engages alone,
 * looks much further ({@code guard_scan_range}), goes after monsters that are hunting <em>other</em> villagers,
 * answers every rally in its village, and still gets out of bed when a monster comes close at night.
 * Creepers are never charged (same rule as everyone else's melee).
 */
public final class GuardCombat {
    private GuardCombat() {}

    public static boolean isGuard(VillagerAgentData agent) {
        return agent != null && "guard".equalsIgnoreCase(agent.getProfession());
    }

    /** A guard holding a real weapon does not wait for a group before charging. */
    public static boolean fightsAlone(VillagerAgentData agent) {
        return isGuard(agent) && CombatAction.isArmed(agent);
    }

    public static double range() {
        return ModConfig.GUARD_SCAN_RANGE.get();
    }

    /**
     * Best target for a guard: its own pursuer first, then the nearest visible monster in range. Monsters that
     * are already chasing a villager count as four times closer, so the guard protects residents before it
     * picks a fight with a zombie idling at the edge of its sight.
     */
    public static LivingEntity findTarget(VillagerEntity guard, ServerWorld world) {
        LivingEntity own = CombatAction.findNearestThreat(guard, world);
        if (own != null) return own;
        double r = guard.isSleeping() ? CombatAction.SLEEPER_WAKE_RANGE : range();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesOfClass(LivingEntity.class, guard.getBoundingBox().inflate(r),
                e -> isValidTarget(guard, e, r))) {
            double score = guard.distanceToSqr(e);
            if (e instanceof MobEntity && ((MobEntity) e).getTarget() instanceof VillagerEntity) score *= 0.25;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    /** Used to keep (or drop) the current target. */
    public static boolean isValidTarget(VillagerEntity guard, LivingEntity e) {
        return isValidTarget(guard, e, range());
    }

    private static boolean isValidTarget(VillagerEntity guard, LivingEntity e, double r) {
        if (e == null || e == guard || !e.isAlive()) return false;
        if (!CombatAction.isHostile(e, guard)) return false;
        // Zombified piglins are neutral until provoked: leave them alone unless they are already angry.
        if (e instanceof ZombifiedPiglinEntity && ((MobEntity) e).getTarget() == null
                && !e.equals(guard.getLastHurtByMob())) return false;
        if (guard.distanceToSqr(e) > r * r) return false;
        return guard.isSleeping() || guard.canSee(e); // a sleeping guard hears what it cannot see
    }
}
