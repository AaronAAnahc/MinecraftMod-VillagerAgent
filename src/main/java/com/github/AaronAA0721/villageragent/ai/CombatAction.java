package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.server.ServerWorld;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Handles combat actions for villagers: detecting hostile mobs and attacking them.
 *
 * <p>Villagers will:
 * <ul>
 *   <li>Scan for hostile mobs within {@link #SCAN_RANGE} blocks</li>
 *   <li>Walk toward the nearest threat</li>
 *   <li>Attack when within {@link #ATTACK_RANGE_SQ} (melee range)</li>
 *   <li>Deal damage based on held weapon (or bare-hand)</li>
 *   <li>Play attack sounds and swing arm animation</li>
 * </ul>
 */
public class CombatAction {
    private static final Logger LOGGER = LogManager.getLogger();

    /** How far the villager can detect hostile mobs (blocks). */
    public static final double SCAN_RANGE = 6.0;

    /** Melee attack range squared. ~2.25 = 1.5 blocks. */
    public static final double ATTACK_RANGE_SQ = 2.25;

    /** Minimum ticks between attacks (10 ticks = 0.5 seconds, like player). */
    public static final int ATTACK_COOLDOWN_TICKS = 10;

    /** If the villager can't reach the target in this many ticks, give up. */
    public static final int CHASE_TIMEOUT_TICKS = 200; // ~10 seconds

    /** Base damage with bare hands. */
    private static final float BASE_DAMAGE = 1.0F;

    // ---------------------------------------------------------------
    //  Target scanning
    // ---------------------------------------------------------------

    /**
     * Find the nearest hostile mob within scan range.
     * Also considers entities that recently attacked the villager (revenge target).
     */
    public static LivingEntity findNearestThreat(VillagerEntity villager, ServerWorld world) {
        AxisAlignedBB scanBox = villager.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> entities = world.getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e.isAlive() && isHostile(e, villager));

        if (entities.isEmpty()) return null;

        // Sort by distance, pick closest
        entities.sort(Comparator.comparingDouble(villager::distanceToSqr));
        return entities.get(0);
    }

    /**
     * Check whether an entity is considered hostile to the villager.
     * Includes all vanilla hostile mobs that target villagers.
     */
    public static boolean isHostile(LivingEntity entity, VillagerEntity villager) {
        if (entity == villager) return false;
        if (!entity.isAlive()) return false;

        // Standard hostile mobs
        if (entity instanceof ZombieEntity) return true;
        if (entity instanceof SkeletonEntity) return true;
        if (entity instanceof VindicatorEntity) return true;
        if (entity instanceof EvokerEntity) return true;
        if (entity instanceof PillagerEntity) return true;
        if (entity instanceof RavagerEntity) return true;
        if (entity instanceof VexEntity) return true;
        if (entity instanceof WitchEntity) return true;
        if (entity instanceof CreeperEntity) return false; // Don't melee creepers!

        // Revenge: if something attacked us, fight back (except players and iron golems)
        LivingEntity lastHurt = villager.getLastHurtByMob();
        if (lastHurt != null && lastHurt.equals(entity)
                && !(entity instanceof IronGolemEntity)
                && !(entity instanceof VillagerEntity)) {
            return true;
        }

        return false;
    }

    // ---------------------------------------------------------------
    //  Attack execution
    // ---------------------------------------------------------------

    /**
     * Perform a melee attack on the target entity.
     * Calculates damage from held weapon, swings arm, plays sound.
     *
     * @return true if the attack was performed
     */
    public static boolean attackEntity(VillagerEntity villager, LivingEntity target,
                                        VillagerAgentData agent) {
        if (target == null || !target.isAlive()) return false;

        double distSq = villager.distanceToSqr(target);
        if (distSq > ATTACK_RANGE_SQ) return false;

        // Calculate damage and equip best weapon visually
        float damage = findBestWeaponDamage(agent, villager);

        // Look at target
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Deal damage
        target.hurt(DamageSource.mobAttack(villager), damage);

        // Swing arm animation
        villager.swing(Hand.MAIN_HAND);

        // Play attack sound
        villager.level.playSound(null,
                villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL,
                1.0F, 1.0F);

        // Knockback
        double kbX = target.getX() - villager.getX();
        double kbZ = target.getZ() - villager.getZ();
        double kbLen = Math.sqrt(kbX * kbX + kbZ * kbZ);
        if (kbLen > 0) {
            target.push(kbX / kbLen * 0.4, 0.1, kbZ / kbLen * 0.4);
        }

        LOGGER.debug("{} attacked {} for {} damage",
                agent.getName(), target.getType().getRegistryName(), damage);
        return true;
    }

    /**
     * Scan the entire inventory for the best weapon. If a villager entity is provided,
     * equip the best weapon in its main hand for visual display.
     *
     * @return the damage value of the best weapon, or BASE_DAMAGE if no weapon found
     */
    public static float findBestWeaponDamage(VillagerAgentData agent, VillagerEntity villager) {
        float bestDamage = BASE_DAMAGE;
        ItemStack bestWeapon = ItemStack.EMPTY;

        AgentInventory inv = agent.getInventory();
        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            float dmg = 0;
            if (stack.getItem() instanceof SwordItem) {
                dmg = ((SwordItem) stack.getItem()).getDamage() + 1.0F;
            } else if (stack.getItem() instanceof AxeItem) {
                dmg = ((AxeItem) stack.getItem()).getAttackDamage() + 1.0F;
            } else if (stack.getItem() instanceof ToolItem) {
                // Pickaxes, shovels, hoes — lower priority but still better than fists
                dmg = ((ToolItem) stack.getItem()).getAttackDamage() + 1.0F;
            }

            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestWeapon = stack;
            }
        }

        // Equip the best weapon visually in the villager's main hand
        if (villager != null) {
            villager.setItemSlot(EquipmentSlotType.MAINHAND, bestWeapon.isEmpty() ? ItemStack.EMPTY : bestWeapon.copy());
        }

        return bestDamage;
    }

    // ── Combat decision: time-to-kill (TTK) + rally ─────────────────────────
    // A villager fights when it can kill the threat faster than the threat can kill it
    // (time to kill = hits needed × interval). If it would lose, it flees and shouts for
    // help — VillageSignalBoard records how many villagers answered the call so a group that
    // individually cannot win can mob the threat together ("strength in numbers" as an
    // emergent rally, not a hard-coded per-villager check).

    /** How far (blocks) a villager backs off per flee step. */
    public static final double FLEE_DISTANCE = 6.0;

    /** Once this far (blocks) from the threat, a fleeing villager holds its ground instead of
     *  running forever — it keeps shouting for help and can turn to charge when others arrive. */
    public static final double FLEE_HOLD_DIST = 7.0;

    /** A chasing villager gives up if the target gets this far (blocks) away. Larger than the
     *  rally radius so villagers charging a rally can run in from a distance. */
    public static final double CHASE_MAX_DIST = 24.0;

    /** Vanilla melee mobs swing roughly once a second. Their ATTACK_SPEED attribute is a
     *  player-centric stat, so we estimate a fixed interval rather than misreading it. */
    private static final double MOB_ATTACK_INTERVAL_TICKS = 20.0;

    /** Per-level bonus added to a villager's combat power (§3b of the sleep/combat SPEC). */
    public static final double LEVEL_POWER_BONUS = 0.3;

    /** Villager's damage per second (best weapon — or bare hands — × attacks/sec on
     *  {@link #ATTACK_COOLDOWN_TICKS}). Retained for the solo time-to-kill comparison. */
    public static double villagerDps(VillagerAgentData agent) {
        float damagePerHit = findBestWeaponDamage(agent, null); // BASE_DAMAGE if unarmed
        double attacksPerSecond = 20.0 / ATTACK_COOLDOWN_TICKS;
        return damagePerHit * attacksPerSecond;
    }

    /** Threat's estimated damage per second (attack damage × ~1 swing/sec). */
    public static double threatDps(LivingEntity threat) {
        double damage = 1.0;
        if (threat.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            damage = threat.getAttribute(Attributes.ATTACK_DAMAGE).getValue();
        }
        return Math.max(0.0, damage) * (20.0 / MOB_ATTACK_INTERVAL_TICKS);
    }

    /**
     * Whether the villager holds a real weapon — damage strictly above the bare-hand base.
     * When {@code combat_require_weapon} is off, even fists count as "armed".
     */
    public static boolean isArmed(VillagerAgentData agent) {
        return combatPower(agent, null) > 0.0;
    }

    /**
     * Combat power of one villager (§3b): weapon damage + villager-level bonus.
     * <ul>
     *   <li>Bare hands contribute 0 when {@code combat_require_weapon} is on — so they
     *       neither solo-fight nor count toward a group charge's total power.</li>
     *   <li>With the flag off, a fist is worth {@link #BASE_DAMAGE} + level bonus (legacy behaviour).</li>
     * </ul>
     */
    public static double combatPower(VillagerAgentData agent, VillagerEntity villager) {
        boolean requireWeapon = ModConfig.COMBAT_REQUIRE_WEAPON.get();
        float weaponDamage = findBestWeaponDamage(agent, null); // BASE_DAMAGE if unarmed
        boolean hasRealWeapon = weaponDamage > BASE_DAMAGE + 1e-3;
        if (requireWeapon && !hasRealWeapon) return 0.0;
        int level = (villager != null) ? villager.getVillagerData().getLevel() : 0;
        return weaponDamage + level * LEVEL_POWER_BONUS;
    }

    /**
     * Whether this villager may actively attack (solo or as part of a group charge): it must be
     * armed (per {@link #combatPower}) <em>and</em> healthy enough
     * ({@code combat_charge_min_health}). Bare-handed or badly-wounded villagers only pin/distract.
     */
    /** Reuse the sleep scheduler thresholds and the actual world clock, not cached night state. */
    public static boolean maySupport(VillagerEntity villager, VillagerAgentData agent) {
        float fatigue = agent.getFatigue();
        return fatigue < GoalScheduler.REST_INTERRUPT_FATIGUE
                && !(com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isNightTime(villager.level.getDayTime())
                    && fatigue >= GoalScheduler.REST_MIN_FATIGUE);
    }

    public static boolean mayAttack(VillagerEntity villager, VillagerAgentData agent) {
        return maySupport(villager, agent) && combatPower(agent, villager) > 0.0
                && villager.getHealth() >= ModConfig.COMBAT_CHARGE_MIN_HEALTH.get();
    }

    /**
     * Time-to-kill comparison: fight only if the time to kill the threat is at most
     * {@code combat_solo_win_factor} × the time for the threat to kill the villager.
     * A bare-handed villager (with {@code combat_require_weapon} on) never wins solo.
     */
    public static boolean wouldWinSolo(VillagerEntity villager, VillagerAgentData agent, LivingEntity threat) {
        if (ModConfig.COMBAT_REQUIRE_WEAPON.get() && !isArmed(agent)) return false;
        double myDps = villagerDps(agent);
        double tDps = threatDps(threat);
        if (tDps <= 0.0) return true;  // threat can't hurt us → free win
        if (myDps <= 0.0) return false;
        double myTimeToKill = threat.getHealth() / myDps;
        double threatTimeToKill = villager.getHealth() / tDps;
        return myTimeToKill <= threatTimeToKill * ModConfig.COMBAT_SOLO_WIN_FACTOR.get();
    }

    /** Vanilla villager panic package uses normal speed 0.5 multiplied by 1.5. */
    public static final double PANIC_SPEED = 0.75;

    public static void flee(VillagerEntity villager, LivingEntity threat) {
        // Preserve a useful escape path instead of replacing it every scan.
        net.minecraft.pathfinding.Path current = villager.getNavigation().getPath();
        if (!villager.getNavigation().isDone() && current != null
                && current.getTarget().distSqr(threat.blockPosition()) > villager.distanceToSqr(threat)) return;
        for (int attempt = 0; attempt < 6; attempt++) {
            net.minecraft.util.math.vector.Vector3d pos = net.minecraft.entity.ai.RandomPositionGenerator
                    .getLandPosAvoid(villager, 16, 7, threat.position());
            if (pos == null || pos.distanceToSqr(threat.position()) <= villager.distanceToSqr(threat)) continue;
            net.minecraft.pathfinding.Path path = villager.getNavigation().createPath(pos.x, pos.y, pos.z, 0);
            if (path != null && path.canReach() && villager.getNavigation().moveTo(path, PANIC_SPEED)) return;
        }
    }

    /**
     * Get the UUID of a target entity (for tracking across ticks).
     */
    public static UUID getTargetId(LivingEntity target) {
        return target != null ? target.getUUID() : null;
    }
}
