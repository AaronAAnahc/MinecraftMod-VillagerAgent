package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Shepherd routine: keep the flock breeding and shear it for wool.
 * <ul>
 *   <li><b>Feed</b> — when the village has exactly two adult sheep and no lamb ({@link AnimalCare}), walk over and
 *       feed them wheat so they breed. Feeding needs no shears.</li>
 *   <li><b>Shear</b> — find a nearby un-sheared sheep, walk up to it and shear it (shears required, starter item).
 *       Plays the shear sound, drops wool matching the sheep's colour and gains CRAFTING XP. Wool regrows.</li>
 * </ul>
 */
public final class ShepherdSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("ShepherdSkill");

    private static final class State {
        SheepEntity target;
        AnimalJob feeding;
        final java.util.Set<java.util.UUID> rejected = new java.util.HashSet<>();
    }

    @Override public String id() { return "shepherd_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        int sheep = 0;
        for (com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory cm : a.getAllChunkMemories())
            sheep += cm.entityCount(com.github.AaronAA0721.villageragent.ai.memory.EntityCategory.ANIMAL);
        return Math.max(0.3, Math.min(3, sheep / 4.0));
    }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "shepherd_work".equals(g.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();

        // Feeding the village pair (AnimalCare rules: exactly two adult sheep and no lamb) comes first:
        // it needs only wheat, not shears.
        if (s.feeding == null && s.target == null) {
            AnimalCare.Plan plan = AnimalCare.plan(a, v, w, type -> type == EntityType.SHEEP,
                    true, false, false, s.rejected);
            if (plan != null) { s.feeding = new AnimalJob(plan); a.setCurrentActivity("feeding sheep"); }
        }
        if (s.feeding != null) {
            AnimalJob.Status status = s.feeding.tick(a, v, w);
            if (status == AnimalJob.Status.RUNNING) return SkillResult.RUNNING;
            AnimalJob job = s.feeding;
            s.feeding = null;
            if (status == AnimalJob.Status.FAILED) { s.rejected.add(job.plan.animal.getUUID()); return SkillResult.RUNNING; }
            a.addMemory("Fed a sheep with wheat so the flock grows");
            g.markProgress();
            GoalCommitment.workUnitDone(a, now);
            return SkillResult.RUNNING;
        }

        // Need shears.
        if (!hasShears(a)) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:shears"));
            LOG.debug("{} wants to shear but has no shears", a.getName());
            return SkillResult.BLOCKED;
        }

        // Find a woolly sheep.
        if (s.target == null || !s.target.isAlive() || s.target.isSheared()) {
            s.target = findWoollySheep(v, w);
            if (s.target == null) {
                LOG.debug("{} found no woolly sheep nearby", a.getName());
                return SkillResult.WAITING;
            }
            a.setCurrentActivity("shearing sheep");
            LOG.debug("{} targeting sheep at {}", a.getName(), s.target.blockPosition());
        }

        // Walk to sheep.
        if (v.distanceToSqr(s.target) > 4) {
            v.getNavigation().moveTo(s.target, VillagerNavigationSupport.WALK_SPEED);
            return SkillResult.RUNNING;
        }

        // Shear using Forge IForgeShearable API — drops wool entities in the world naturally.
        v.getNavigation().stop();
        v.swing(net.minecraft.util.Hand.MAIN_HAND);
        w.playSound(null, v.getX(), v.getY(), v.getZ(),
                SoundEvents.SHEEP_SHEAR, SoundCategory.NEUTRAL, 1.0f, 1.0f);

        // IForgeShearable.onSheared returns the drop stacks and handles setSheared(true) internally.
        ItemStack shears = findShearsStack(a);
        java.util.List<ItemStack> drops = s.target.onSheared(null, shears, w, s.target.blockPosition(),
                net.minecraft.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                        net.minecraft.enchantment.Enchantments.BLOCK_FORTUNE, shears));
        int woolCount = 0;
        for (ItemStack drop : drops) {
            // Spawn each drop as a world item entity — ItemAttractionSystem picks them up.
            net.minecraft.block.Block.popResource(w, s.target.blockPosition(), drop);
            woolCount += drop.getCount();
        }
        damageShears(a);
        a.gainCapabilityXp(com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId.CRAFTING,
                ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
        a.addMemory("Sheared a sheep for " + woolCount + " wool");
        LOG.debug("{} sheared sheep, got {} wool", a.getName(), woolCount);
        g.markProgress();
        GoalCommitment.workUnitDone(a, now);
        s.target = null;
        return SkillResult.SUCCESS;
    }

    private static boolean hasShears(VillagerAgentData a) {
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() == Items.SHEARS) return true;
        return false;
    }

    /** Return the actual shears ItemStack for passing to IForgeShearable.onSheared(). */
    private static ItemStack findShearsStack(VillagerAgentData a) {
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() == Items.SHEARS) return s;
        return ItemStack.EMPTY;
    }

    private static void damageShears(VillagerAgentData a) {
        List<ItemStack> items = a.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || s.getItem() != Items.SHEARS) continue;
            s.setDamageValue(s.getDamageValue() + 1);
            if (s.getDamageValue() >= s.getMaxDamage()) items.set(i, ItemStack.EMPTY);
            return;
        }
    }

    private static SheepEntity findWoollySheep(VillagerEntity v, ServerWorld w) {
        AxisAlignedBB box = new AxisAlignedBB(v.blockPosition()).inflate(24);
        List<SheepEntity> sheep = w.getEntitiesOfClass(SheepEntity.class, box,
                e -> e.isAlive() && !e.isBaby() && !e.isSheared());
        if (sheep.isEmpty()) return null;
        sheep.sort((a, b) -> Double.compare(a.distanceToSqr(v), b.distanceToSqr(v)));
        return sheep.get(0);
    }
}
