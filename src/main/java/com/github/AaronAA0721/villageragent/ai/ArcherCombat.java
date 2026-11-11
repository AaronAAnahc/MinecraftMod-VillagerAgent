package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.EndermanEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Ranged combat for villagers that carry a bow and arrows (fletchers start with a bow).
 *
 * <p>Battle state, run on the combat tick instead of the melee chase:
 * <ul>
 *   <li>Closer than {@link #RETREAT_BELOW} blocks and the monster can reach the archer → run away until the
 *       distance is above {@link #RETREAT_UNTIL} blocks (a cornered archer shoots instead).</li>
 *   <li>Further than {@link #RETREAT_BELOW} and in sight within {@link #MAX_SHOOT} blocks → stand, draw for
 *       {@link #DRAW_TICKS} ticks and shoot one arrow — but only when no villager or iron golem is in the line of
 *       fire; otherwise side-step to find a clear line.</li>
 *   <li>Out of range or out of sight → walk closer.</li>
 * </ul>
 * Without arrows (or without a bow) {@link #tick} returns false and the normal melee behaviour takes over.
 * Archers do not wait for a group charge: keeping their distance is their safety.
 */
public final class ArcherCombat {
    public static final double RETREAT_BELOW = 4.0;
    public static final double RETREAT_UNTIL = 7.0;
    public static final double MAX_SHOOT = 16.0;
    /** How far an archer looks for monsters to shoot (beyond the melee scan range). */
    public static final double TARGET_RANGE = 16.0;
    public static final int DRAW_TICKS = 20;
    /** Combat power credited to a loaded bow for group-strength and eligibility checks. */
    public static final float RANGED_POWER = 6.0f;

    private static final class State {
        boolean retreating;
        int draw;
        long reachCheckedTick = Long.MIN_VALUE;
        boolean reachable;
        long strafeUntil;
        int strafeSide = 1;
        long cornered;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private ArcherCombat() {}

    /** Carries a bow and at least one arrow. */
    public static boolean canShoot(VillagerAgentData agent) {
        return !bow(agent).isEmpty() && arrowSlot(agent) >= 0;
    }

    public static void forget(UUID villager) { STATES.remove(villager); }

    /**
     * Nearest target for an archer: the usual melee threats first (they are close), otherwise any visible monster
     * within {@link #TARGET_RANGE}, creepers included (they are safe to shoot from range). Endermen are ignored.
     */
    public static LivingEntity findTarget(VillagerEntity villager, ServerWorld world) {
        LivingEntity threat = CombatAction.findNearestThreat(villager, world);
        if (threat != null) return threat;
        if (villager.isSleeping()) return null;
        LivingEntity best = null;
        for (LivingEntity e : world.getEntitiesOfClass(LivingEntity.class, villager.getBoundingBox().inflate(TARGET_RANGE),
                e -> isValidTarget(villager, e))) {
            if (best == null || villager.distanceToSqr(e) < villager.distanceToSqr(best)) best = e;
        }
        return best;
    }

    /** A living monster in sight and in range; used to keep or switch the current target. */
    public static boolean isValidTarget(VillagerEntity villager, LivingEntity e) {
        if (e == null || !e.isAlive() || e == villager) return false;
        if (!(e instanceof IMob) && !CombatAction.isHostile(e, villager)) return false;
        if (e instanceof EndermanEntity) return false;
        return villager.distanceToSqr(e) <= TARGET_RANGE * TARGET_RANGE && villager.canSee(e);
    }

    /**
     * One ranged combat step (called every combat tick, {@code step} ticks apart).
     *
     * @return true when the archer handled this tick, false when it must fight in melee (no bow or no arrows)
     */
    public static boolean tick(VillagerEntity villager, ServerWorld world, VillagerAgentData agent,
                               LivingEntity target, int step) {
        if (!canShoot(agent)) { STATES.remove(villager.getUUID()); return false; }
        State st = STATES.computeIfAbsent(villager.getUUID(), id -> new State());
        long now = world.getGameTime();
        double d = Math.sqrt(villager.distanceToSqr(target));
        villager.setItemSlot(EquipmentSlotType.MAINHAND, bow(agent).copy());
        villager.getLookControl().setLookAt(target, 30f, 30f);

        if (st.retreating) {
            if (d > RETREAT_UNTIL) {
                st.retreating = false;
                villager.getNavigation().stop();
            } else {
                CombatAction.flee(villager, target);
                // Nowhere to run: shoot at point-blank range rather than freeze.
                if (villager.getNavigation().isDone()) {
                    if (st.cornered == 0) st.cornered = now;
                    if (now - st.cornered < 20) return true;
                } else { st.cornered = 0; st.draw = 0; return true; }
            }
        } else if (d < RETREAT_BELOW && reachable(villager, target, st, now)) {
            st.retreating = true;
            st.draw = 0;
            st.cornered = 0;
            CombatAction.flee(villager, target);
            return true;
        }

        if (d > MAX_SHOOT || !villager.canSee(target)) {
            st.draw = 0;
            villager.getNavigation().moveTo(target, 0.45);
            return true;
        }
        if (!clearShot(villager, world, target)) {
            st.draw = 0;
            strafe(villager, target, st, now);
            return true;
        }
        if (!st.retreating) villager.getNavigation().stop();
        st.draw += step;
        if (st.draw < DRAW_TICKS) return true;
        st.draw = 0;
        shoot(villager, world, agent, target);
        return true;
    }

    /** Whether the monster has a walkable path to the archer (cached briefly; pathing is not free). */
    private static boolean reachable(VillagerEntity villager, LivingEntity target, State st, long now) {
        if (now - st.reachCheckedTick < 10) return st.reachable;
        st.reachCheckedTick = now;
        Path path = villager.getNavigation().createPath(target, 0);
        st.reachable = path != null && path.canReach();
        return st.reachable;
    }

    /** No villager or iron golem between the archer's eyes and the target's body. */
    static boolean clearShot(VillagerEntity villager, ServerWorld world, LivingEntity target) {
        Vector3d from = villager.getEyePosition(1.0f);
        Vector3d to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        AxisAlignedBB span = new AxisAlignedBB(from, to).inflate(1.0);
        for (LivingEntity e : world.getEntitiesOfClass(LivingEntity.class, span,
                e -> e != villager && e != target && e.isAlive()
                        && (e instanceof VillagerEntity || e instanceof IronGolemEntity))) {
            if (e.getBoundingBox().inflate(0.3).clip(from, to).isPresent()) return false;
        }
        return true;
    }

    private static void strafe(VillagerEntity villager, LivingEntity target, State st, long now) {
        if (now < st.strafeUntil && !villager.getNavigation().isDone()) return;
        if (now >= st.strafeUntil) st.strafeSide = -st.strafeSide;
        st.strafeUntil = now + 30;
        double dx = target.getX() - villager.getX(), dz = target.getZ() - villager.getZ();
        double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        double px = -dz / len * st.strafeSide * 3, pz = dx / len * st.strafeSide * 3;
        villager.getNavigation().moveTo(villager.getX() + px, villager.getY(), villager.getZ() + pz, 0.5);
    }

    private static void shoot(VillagerEntity villager, ServerWorld world, VillagerAgentData agent, LivingEntity target) {
        ItemStack bow = bow(agent);
        int slot = arrowSlot(agent);
        if (bow.isEmpty() || slot < 0) return;
        ArrowEntity arrow = new ArrowEntity(world, villager);
        double dx = target.getX() - villager.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - villager.getZ();
        double horizontal = MathHelper.sqrt(dx * dx + dz * dz);
        // Same ballistic aim as a skeleton, with a steadier hand.
        arrow.shoot(dx, dy + horizontal * 0.2F, dz, 1.6F, 2.0F);
        arrow.pickup = AbstractArrowEntity.PickupStatus.ALLOWED;
        int power = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, bow);
        if (power > 0) arrow.setBaseDamage(arrow.getBaseDamage() + power * 0.5D + 0.5D);
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, bow) > 0) arrow.setSecondsOnFire(100);
        world.addFreshEntity(arrow);
        world.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.ARROW_SHOOT,
                SoundCategory.NEUTRAL, 1.0F, 1.0F / (villager.getRandom().nextFloat() * 0.4F + 1.2F) + 0.5F);
        villager.swing(Hand.MAIN_HAND);

        List<ItemStack> items = agent.getInventory().getItems();
        ItemStack arrows = items.get(slot);
        arrows.shrink(1);
        if (arrows.isEmpty()) items.set(slot, ItemStack.EMPTY);
        if (bow.isDamageableItem()) {
            bow.setDamageValue(bow.getDamageValue() + 1);
            if (bow.getDamageValue() >= bow.getMaxDamage()) {
                int b = items.indexOf(bow);
                if (b >= 0) items.set(b, ItemStack.EMPTY);
                villager.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
                agent.addMemory("My bow broke");
            }
        }
    }

    static ItemStack bow(VillagerAgentData agent) {
        for (ItemStack s : agent.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() instanceof BowItem) return s;
        return ItemStack.EMPTY;
    }

    private static int arrowSlot(VillagerAgentData agent) {
        List<ItemStack> items = agent.getInventory().getItems();
        for (int i = 0; i < items.size(); i++)
            if (!items.get(i).isEmpty() && items.get(i).getItem() == Items.ARROW) return i;
        return -1;
    }
}
