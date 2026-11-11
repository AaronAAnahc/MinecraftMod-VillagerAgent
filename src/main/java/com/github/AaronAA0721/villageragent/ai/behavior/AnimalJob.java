package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.CombatAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.work.DropCollector;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.world.server.ServerWorld;

import java.util.List;

/**
 * Executes one {@link AnimalCare.Plan}: walk up to the animal, then either feed it (it falls in love and breeds
 * through its own vanilla AI) or strike it down with the best carried weapon and collect the vanilla loot drops.
 */
final class AnimalJob {
    enum Status { RUNNING, DONE, FAILED }

    private static final double FEED_REACH_SQ = 2.5 * 2.5;
    private static final double HIT_REACH_SQ = 2.0 * 2.0;
    private static final int APPROACH_TIMEOUT = 300;
    private static final int HIT_INTERVAL = 12;

    final AnimalCare.Plan plan;
    private long started = -1, nextPath, nextHit;
    private DropCollector drops;
    /** What the job produced, for memories ("cow", "chicken"). */
    final String animalName;

    AnimalJob(AnimalCare.Plan plan) {
        this.plan = plan;
        this.animalName = plan.animal.getType().getRegistryName() == null ? "animal"
                : plan.animal.getType().getRegistryName().getPath();
    }

    Status tick(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        if (started < 0) started = now;
        agent.brain().goalStartedTick = now; // steady visible progress; do not trip the no-progress watchdog
        if (drops != null) return drops.tick(agent, villager, world) ? Status.DONE : Status.RUNNING;

        AnimalEntity animal = plan.animal;
        if (!animal.isAlive()) {
            if (plan.kind == AnimalCare.Kind.FEED) return Status.FAILED;
            // Killed (possibly by the last hit): gather the vanilla drops where it fell.
            drops = new DropCollector(animal.blockPosition(), 5);
            return Status.RUNNING;
        }
        if (plan.kind == AnimalCare.Kind.FEED && !animal.canFallInLove()) return Status.FAILED;
        if (now - started > APPROACH_TIMEOUT && villager.distanceToSqr(animal) > HIT_REACH_SQ * 4) return Status.FAILED;

        double reach = plan.kind == AnimalCare.Kind.FEED ? FEED_REACH_SQ : HIT_REACH_SQ;
        if (villager.distanceToSqr(animal) > reach) {
            if (now >= nextPath) {
                nextPath = now + 10;
                villager.getNavigation().moveTo(animal, plan.kind == AnimalCare.Kind.FEED
                        ? VillagerNavigationSupport.WALK_SPEED : VillagerNavigationSupport.WALK_SPEED * 1.3);
            }
            return Status.RUNNING;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(animal, 30f, 30f);

        if (plan.kind == AnimalCare.Kind.FEED) {
            int slot = AnimalCare.foodSlot(agent, animal);
            if (slot < 0) return Status.FAILED;
            List<ItemStack> items = agent.getInventory().getItems();
            ItemStack food = items.get(slot);
            villager.setItemSlot(EquipmentSlotType.MAINHAND, new ItemStack(food.getItem()));
            villager.swing(Hand.MAIN_HAND);
            food.shrink(1);
            if (food.isEmpty()) items.set(slot, ItemStack.EMPTY);
            animal.setInLove(null);
            world.playSound(null, animal.getX(), animal.getY(), animal.getZ(), SoundEvents.GENERIC_EAT,
                    SoundCategory.NEUTRAL, 0.6f, 1.0f);
            return Status.DONE;
        }

        if (now < nextHit) return Status.RUNNING;
        nextHit = now + HIT_INTERVAL;
        float weapon = CombatAction.findBestWeaponDamage(agent, villager); // also shows the weapon in hand
        // Never more than two strikes: the job is slaughter, not a fight.
        float damage = Math.max(weapon, animal.getMaxHealth() / 2f + 0.5f);
        villager.swing(Hand.MAIN_HAND);
        world.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        // Vanilla damage: the animal's own loot table drops meat/leather/wool/feathers on death.
        animal.hurt(DamageSource.mobAttack(villager), damage);
        return Status.RUNNING;
    }
}
