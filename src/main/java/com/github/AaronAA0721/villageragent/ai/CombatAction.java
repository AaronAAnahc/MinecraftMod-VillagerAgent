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
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
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

    /** Brief loss of sight must not repeatedly interrupt and restart work. */
    public static final long THREAT_MEMORY_TICKS = 40L;

    /**
     * A threat this villager is aware of. Ordinary hostiles count when seen within {@link #SCAN_RANGE}.
     * Its own pursuer (a mob targeting it, or its last attacker) is noticed further away: heard within
     * {@link #SLEEPER_WAKE_RANGE}, seen within {@link #CHASED_RANGE}. A sleeping villager notices only
     * its own pursuer, so monsters walking through a sleeping village do not wake everyone.
     */
    public static boolean isVisibleThreat(VillagerEntity villager, LivingEntity threat) {
        if (!isHostile(threat, villager)) return false;
        if (isPursuer(threat, villager)) {
            double d = villager.distanceToSqr(threat);
            if (d <= SLEEPER_WAKE_RANGE * SLEEPER_WAKE_RANGE) return true;
            if (d <= CHASED_RANGE * CHASED_RANGE && villager.canSee(threat)) return true;
        }
        if (villager.isSleeping()) return false;
        return villager.getBoundingBox().inflate(SCAN_RANGE).intersects(threat.getBoundingBox())
                && villager.canSee(threat);
    }

    /** The threat is targeting this villager or was the last thing to hurt it. */
    public static boolean isPursuer(LivingEntity threat, VillagerEntity villager) {
        return (threat instanceof MobEntity && ((MobEntity) threat).getTarget() == villager)
                || threat.equals(villager.getLastHurtByMob());
    }

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
        // Box covers the pursuer range; isVisibleThreat applies the per-case distance and sight rules.
        AxisAlignedBB scanBox = villager.getBoundingBox().inflate(CHASED_RANGE);
        List<LivingEntity> entities = world.getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> isVisibleThreat(villager, e));

        if (entities.isEmpty()) return null;

        // Sort by distance, pick closest
        entities.sort(Comparator.comparingDouble(villager::distanceToSqr));
        return entities.get(0);
    }

    /** A hostile that targets this villager is noticed from further away than ordinary sightings. */
    public static final double CHASED_RANGE = 16.0;
    /** A sleeping villager wakes when its pursuer comes this close (it hears it; no line of sight). */
    public static final double SLEEPER_WAKE_RANGE = 10.0;

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
        if (target == null || !target.isAlive() || !villager.canSee(target)) return false;

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
        // A bow with arrows is a real weapon too (ArcherCombat keeps its distance and shoots).
        if (ArcherCombat.canShoot(agent)) weaponDamage = Math.max(weaponDamage, ArcherCombat.RANGED_POWER);
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
        // Curfew: everyone is in bed by rule, not because they are tired, so neighbours do get up to
        // help a resident who is being chased and go back to bed afterwards.
        if (com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.curfew(villager.level.getDayTime()))
            return fatigue < GoalScheduler.REST_INTERRUPT_FATIGUE;
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

    // ── Smart fleeing ────────────────────────────────────────────────────────
    // Every flee step samples several escape candidates and scores them:
    //  • underground  → strongly prefer gaining height (never dig deeper), and head for the
    //                   village mining well when one is known;
    //  • outside home → prefer candidates that close the distance to the home village;
    //  • inside home  → just get away from monsters, but don't run out of the village.
    // A candidate is discarded when its path runs past a hostile (it is "blocked by enemies").

    /** Below the local surface by more than this many blocks counts as underground. */
    public static final int UNDERGROUND_DEPTH = 6;
    /** Path nodes closer than this to a hostile (and closer than we already are) block a route. */
    private static final double PATH_CLEARANCE = 3.0;
    /** Radius in which other monsters are also avoided while fleeing. */
    private static final double FLEE_AWARE_RANGE = 16.0;
    /** How far toward the village / surface one flee leg goes (stays inside pathfinding range). */
    private static final int FLEE_LEG = 16;

    public static void flee(VillagerEntity villager, LivingEntity threat) {
        if (!(villager.level instanceof ServerWorld)) return;
        ServerWorld world = (ServerWorld) villager.level;
        BlockPos here = villager.blockPosition();
        boolean underground = isUnderground(world, here);
        List<LivingEntity> hostiles = fleeHazards(villager, world, threat);

        VillageRegistry registry = VillageRegistry.get(world);
        Long home = registry.join(world, villager.getUUID(), here);
        MutableBoundingBox bounds = home == null ? null : registry.bounds(home);
        boolean inVillage = bounds != null && insideXZ(bounds, here, 0);

        // Preserve a still-useful escape path instead of replacing it every scan.
        Path current = villager.getNavigation().getPath();
        if (!villager.getNavigation().isDone() && current != null
                && current.getTarget().distSqr(threat.blockPosition()) > villager.distanceToSqr(threat)
                && (!underground || current.getTarget().getY() >= here.getY())        // not heading deeper
                && (!inVillage || insideXZ(bounds, current.getTarget(), 2))) return;  // not leaving the village

        List<Vector3d> candidates = new ArrayList<>();
        int vertical = underground ? 10 : 7;
        // 1. Plain "away from the threat" samples — always available.
        for (int i = 0; i < 4; i++) addIfPresent(candidates,
                RandomPositionGenerator.getLandPosAvoid(villager, FLEE_LEG, vertical, threat.position()));
        // 2. Underground: head for the mining well entrance (the way back up), and for the surface.
        if (underground) {
            BlockPos well = home == null ? null : registry.getWellPos(home);
            if (well != null && well.distSqr(here) < 64 * 64) {
                Vector3d w = Vector3d.atBottomCenterOf(well);
                if (well.distSqr(here) <= FLEE_LEG * FLEE_LEG) candidates.add(w);
                for (int i = 0; i < 2; i++) addIfPresent(candidates,
                        RandomPositionGenerator.getLandPosTowards(villager, FLEE_LEG, vertical, w));
            }
            Vector3d up = new Vector3d(villager.getX(), surfaceY(world, here) + 1, villager.getZ());
            for (int i = 0; i < 3; i++) addIfPresent(candidates,
                    RandomPositionGenerator.getLandPosTowards(villager, FLEE_LEG, vertical, up));
        }
        // 3. Outside the home village: run toward it.
        if (bounds != null && !inVillage) {
            Vector3d goal = nearestVillagePoint(bounds, here, villager.getY());
            if (goal.distanceToSqr(villager.position()) <= FLEE_LEG * FLEE_LEG) candidates.add(goal);
            for (int i = 0; i < 3; i++) addIfPresent(candidates,
                    RandomPositionGenerator.getLandPosTowards(villager, FLEE_LEG, vertical, goal));
        }

        Path best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Vector3d c : candidates) {
            Path path = villager.getNavigation().createPath(c.x, c.y, c.z, 0);
            if (path == null || !path.canReach() || path.getNodeCount() == 0) continue;
            if (blockedByEnemies(villager, path, hostiles)) continue;
            double score = scoreEscape(villager, path.getTarget(), hostiles, threat, underground, bounds, inVillage);
            if (score > bestScore) { bestScore = score; best = path; }
        }
        if (best != null && villager.getNavigation().moveTo(best, PANIC_SPEED)) return;

        // Fallback (every route is blocked): the old behaviour — any reachable spot further away.
        for (int attempt = 0; attempt < 6; attempt++) {
            Vector3d pos = RandomPositionGenerator.getLandPosAvoid(villager, FLEE_LEG, 7, threat.position());
            if (pos == null || pos.distanceToSqr(threat.position()) <= villager.distanceToSqr(threat)) continue;
            Path path = villager.getNavigation().createPath(pos.x, pos.y, pos.z, 0);
            if (path != null && path.canReach() && villager.getNavigation().moveTo(path, PANIC_SPEED)) return;
        }
    }

    /** Higher is better. Endpoint safety first, then height (underground) and village pull. */
    private static double scoreEscape(VillagerEntity villager, BlockPos end, List<LivingEntity> hostiles,
                                      LivingEntity threat, boolean underground,
                                      MutableBoundingBox bounds, boolean inVillage) {
        Vector3d endV = Vector3d.atBottomCenterOf(end);
        double nearest = Double.MAX_VALUE;
        for (LivingEntity h : hostiles) nearest = Math.min(nearest, Math.sqrt(h.distanceToSqr(endV)));
        double nowDist = Math.sqrt(villager.distanceToSqr(threat));
        double score = Math.min(nearest, FLEE_AWARE_RANGE);
        if (Math.sqrt(threat.distanceToSqr(endV)) < nowDist) score -= 8.0; // ends nearer the threat

        if (underground) {
            double dy = end.getY() - villager.getY();
            score += dy >= 0 ? 1.5 * dy : 6.0 * dy;           // climbing is good, going deeper is very bad
        }
        if (bounds != null) {
            BlockPos here = villager.blockPosition();
            if (inVillage) {
                if (!insideXZ(bounds, end, 2)) score -= 10.0;   // stay inside the village
            } else {
                score += 1.5 * (distToBoundsXZ(bounds, here) - distToBoundsXZ(bounds, end));
                if (insideXZ(bounds, end, 0)) score += 6.0;     // made it home
            }
        }
        return score;
    }

    /** A route is blocked when some node gets within {@link #PATH_CLEARANCE} of a hostile
     *  and closer to it than the villager already is (so stepping away from an adjacent mob is fine). */
    private static boolean blockedByEnemies(VillagerEntity villager, Path path, List<LivingEntity> hostiles) {
        for (LivingEntity h : hostiles) {
            double nowSq = villager.distanceToSqr(h);
            double limitSq = Math.min(PATH_CLEARANCE * PATH_CLEARANCE, nowSq);
            for (int i = 0; i < path.getNodeCount(); i++) {
                if (h.distanceToSqr(Vector3d.atBottomCenterOf(path.getNodePos(i))) < limitSq) return true;
            }
        }
        return false;
    }

    /** Monsters to steer around while fleeing: the threat plus any hostile / IMob nearby (creepers too). */
    private static List<LivingEntity> fleeHazards(VillagerEntity villager, ServerWorld world, LivingEntity threat) {
        List<LivingEntity> list = world.getEntitiesOfClass(LivingEntity.class,
                villager.getBoundingBox().inflate(FLEE_AWARE_RANGE),
                e -> e != villager && e.isAlive() && (e instanceof IMob || isHostile(e, villager)));
        if (!list.contains(threat)) list.add(threat);
        return list;
    }

    /** Highest solid ground in a 9×9 area around {@code pos} — a single column can be a shaft. */
    public static int surfaceY(ServerWorld world, BlockPos pos) {
        int top = Integer.MIN_VALUE;
        for (int dx = -4; dx <= 4; dx += 4)
            for (int dz = -4; dz <= 4; dz += 4)
                top = Math.max(top, world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX() + dx, pos.getZ() + dz));
        return top;
    }

    public static boolean isUnderground(ServerWorld world, BlockPos pos) {
        return pos.getY() < surfaceY(world, pos) - UNDERGROUND_DEPTH;
    }

    private static boolean insideXZ(MutableBoundingBox b, BlockPos p, int margin) {
        return p.getX() >= b.x0 - margin && p.getX() <= b.x1 + margin
                && p.getZ() >= b.z0 - margin && p.getZ() <= b.z1 + margin;
    }

    private static double distToBoundsXZ(MutableBoundingBox b, BlockPos p) {
        double dx = Math.max(0, Math.max(b.x0 - p.getX(), p.getX() - b.x1));
        double dz = Math.max(0, Math.max(b.z0 - p.getZ(), p.getZ() - b.z1));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** A point a few blocks inside the village bounds, on the side nearest {@code from}. */
    private static Vector3d nearestVillagePoint(MutableBoundingBox b, BlockPos from, double y) {
        int inset = 4;
        int x = Math.max(b.x0 + inset, Math.min(b.x1 - inset, from.getX()));
        int z = Math.max(b.z0 + inset, Math.min(b.z1 - inset, from.getZ()));
        return new Vector3d(x + 0.5, y, z + 0.5);
    }

    private static void addIfPresent(List<Vector3d> list, Vector3d v) { if (v != null) list.add(v); }

    /**
     * Get the UUID of a target entity (for tracking across ticks).
     */
    public static UUID getTargetId(LivingEntity target) {
        return target != null ? target.getUUID() : null;
    }
}
