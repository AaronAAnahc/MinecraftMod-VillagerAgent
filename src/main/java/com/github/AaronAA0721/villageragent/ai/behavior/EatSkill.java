package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.SupplyPolicy;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Food;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particles.ItemParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.world.server.ServerWorld;

import java.util.List;

/**
 * Eat food held in the inventory to restore hunger, with a visible chewing animation
 * (food held in hand, eating particles, eat/burp sounds). When no edible is carried it
 * delegates to an {@code acquire_item} child for food. Seeded by {@code VillagerNeedsSystem}
 * when hunger falls below its threshold; it is the goal-driven replacement for the old
 * background auto-eat.
 *
 * <p>An <em>explicit</em> eat (asked for by the LLM or a player, e.g. the last step of a shared meal)
 * eats at least one item even when the villager is not hungry; a need-driven eat stops at {@link #EAT_END}.
 */
public final class EatSkill implements VillagerSkill {

    /** Stop eating once hunger reaches this, so a villager does not graze continuously. */
    static final float EAT_END = 85f;
    private static final float NUTRITION_FACTOR = 8.0f;
    /** Ticks spent chewing one food item before it is swallowed (matches vanilla eating duration). */
    private static final int CHEW_TICKS = 32;
    private static final String DEFAULT_FOOD = "minecraft:bread";

    private static final class State { int chew; Item holding; boolean ateOnce; }

    @Override public String id() { return "eat"; }
    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // Hungrier = more urgent. 100=full→x0.1, 40=threshold→x1, 15=starving→x4
        float hunger = a.getHunger();
        if (hunger >= EAT_END) return isExplicit(g) ? 1.0 : 0; // full: only a requested meal still happens
        return Math.max(0.1, Math.pow((100 - hunger) / 60.0, 2));
    }

    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "eat".equals(g.getGoalType());
    }

    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        if (a.getHunger() >= EAT_END && (!isExplicit(g) || s.ateOnce)) { clearHand(v); return SkillResult.SUCCESS; }

        // Pick a food, or acquire one through the shared acquisition method.
        if (s.holding == null) {
            Item food = findFood(a);
            if (food == null) {
                SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                    AgentGoal acquire = new AgentGoal("acquire_item", "Acquire food to eat", g.getPriority());
                    acquire.setTargetItem(DEFAULT_FOOD);
                    // Any edible food satisfies hunger; measured in hunger points. If the villager cannot
                    // produce food itself, the acquisition's request method posts a generic #food request.
                    acquire.setRequestCategory(SupplyPolicy.FOOD);
                    acquire.setTargetQuantity(foodPointsNeeded(a));
                    return acquire;
                });
                if (r == SkillResult.SUCCESS || r == SkillResult.RUNNING) return SkillResult.RUNNING;
                g.setLastFailure(FailureReason.MISSING_ITEM.with("food"));
                return r;
            }
            s.holding = food;
            s.chew = 0;
            v.setItemSlot(EquipmentSlotType.MAINHAND, new ItemStack(food));
        }

        // Chew: hold still, emit eating particles and sound.
        v.getNavigation().stop();
        v.getLookControl().setLookAt(v.getX(), v.getEyeY() - 1.0, v.getZ());
        s.chew++;
        a.brain().goalStartedTick = w.getGameTime(); // productive; do not trip the no-progress timeout
        if (s.chew % 4 == 0) {
            w.sendParticles(new ItemParticleData(ParticleTypes.ITEM, new ItemStack(s.holding)),
                    v.getX(), v.getEyeY() - 0.1, v.getZ(), 4, 0.1, 0.1, 0.1, 0.02);
            w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.GENERIC_EAT, SoundCategory.NEUTRAL,
                    0.8f, 1.0f + (v.getRandom().nextFloat() - 0.5f) * 0.2f);
        }
        if (s.chew < CHEW_TICKS) return SkillResult.RUNNING;

        // Swallow: consume one item, restore hunger.
        if (!consumeOne(a, s.holding)) { s.holding = null; clearHand(v); return SkillResult.RUNNING; }
        a.setHunger(Math.min(100f, a.getHunger() + nutrition(s.holding) * NUTRITION_FACTOR));
        a.addMemory("Ate " + name(s.holding) + " — hunger satisfied");
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.PLAYER_BURP, SoundCategory.NEUTRAL, 0.5f, 0.9f);
        s.holding = null; s.chew = 0; s.ateOnce = true;
        if (a.getHunger() >= EAT_END) { clearHand(v); return SkillResult.SUCCESS; }
        return SkillResult.RUNNING; // still hungry: eat another if food remains
    }

    /** Requested by the LLM or a player rather than seeded by the hunger need. */
    private static boolean isExplicit(AgentGoal g) {
        return g.getSource() != AgentGoal.Source.RULE;
    }

    /** Hunger points (vanilla nutrition) needed to reach the eating end threshold. */
    private static int foodPointsNeeded(VillagerAgentData a) {
        return Math.max(1, (int) Math.ceil((EAT_END - a.getHunger()) / NUTRITION_FACTOR));
    }

    private static Item findFood(VillagerAgentData a) {
        for (ItemStack stack : a.getInventory().getItems()) {
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item.isEdible() && item.getFoodProperties() != null) return item;
        }
        return null;
    }

    private static boolean consumeOne(VillagerAgentData a, Item item) {
        List<ItemStack> items = a.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            stack.shrink(1);
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private static float nutrition(Item item) {
        Food food = item.getFoodProperties();
        return food == null ? 0f : food.getNutrition();
    }

    private static String name(Item item) {
        return item.getRegistryName() == null ? "food" : item.getRegistryName().getPath().replace('_', ' ');
    }

    private static void clearHand(VillagerEntity v) {
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
    }
}
