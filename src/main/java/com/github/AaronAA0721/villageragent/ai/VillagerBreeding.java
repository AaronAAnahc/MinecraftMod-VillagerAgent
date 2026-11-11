package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Baby villagers. Managed villagers never run the vanilla brain (see {@code VillagerEntityMixin}), so vanilla
 * breeding never happens; this replaces it with a food-surplus rule.
 *
 * <p>A villager is ready to start a family when it is an adult off breeding cooldown, awake, fed (hunger ≥ 60) and
 * not exhausted, and carries <b>more food than it needs for two days</b>. One day of food is the hunger lost in a day
 * ({@code hunger_decay_per_minute} × 20 minutes) converted to food points the way {@code EatSkill} restores hunger
 * (8 hunger per food point) — 3.75 food points with the default decay, so the surplus line is about two loaves of
 * bread. There must also be room: more beds than villagers within {@link #ROOM_RADIUS} blocks (vanilla needs a free
 * bed too).
 *
 * <p>A ready villager looks for another ready villager within {@link #PARTNER_RANGE} blocks. The pair is reserved
 * and the initiator gets a one-shot {@code make_family} goal ({@code behavior.BreedSkill}): it walks to the partner,
 * who stops and waits, both show hearts, and a baby is spawned with the vanilla offspring logic. Each parent spends
 * one day of food and gets the vanilla 6000-tick (5 minute) breeding cooldown.
 */
public final class VillagerBreeding {
    public static final String GOAL = "make_family";
    static final double PARTNER_RANGE = 24;
    static final int ROOM_RADIUS = 48;
    public static final int PARENT_COOLDOWN = 6000;
    private static final int CHECK_INTERVAL = 600;
    private static final long PAIR_TIMEOUT = 1400;
    /** Same conversion as EatSkill: one vanilla food point restores 8 hunger. */
    private static final float HUNGER_PER_FOOD_POINT = 8f;

    private static final Map<UUID, UUID> PARTNER = new HashMap<>();
    private static final Map<UUID, Long> PAIRED_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> NEXT_CHECK = new HashMap<>();

    private VillagerBreeding() {}

    /** Food points one villager eats in a day. */
    public static float dailyFoodPoints() {
        return ModConfig.HUNGER_DECAY_PER_MINUTE.get().floatValue() * 20f / HUNGER_PER_FOOD_POINT;
    }

    /** Food points carried (vanilla nutrition of every edible, non-harmful item). */
    public static float foodPoints(VillagerAgentData agent) {
        float total = 0;
        for (ItemStack stack : agent.getInventory().getItems()) {
            if (stack.isEmpty() || !SupplyPolicy.edible(stack.getItem())) continue;
            total += SupplyPolicy.nutrition(stack.getItem()) * stack.getCount();
        }
        return total;
    }

    /** More food than two days of eating. */
    public static boolean hasSurplus(VillagerAgentData agent) {
        return foodPoints(agent) > 2f * dailyFoodPoints();
    }

    /** Adult, off cooldown, awake, fed, rested, not in a fight, with surplus food. */
    public static boolean ready(VillagerEntity villager, VillagerAgentData agent) {
        if (villager == null || agent == null || !villager.isAlive() || villager.isBaby() || villager.getAge() != 0) return false;
        if (villager.isSleeping() || SleepSkill.curfew(villager.level.getDayTime())) return false;
        if (agent.getHunger() < 60f || agent.getFatigue() >= 60f) return false;
        VillagerAction action = agent.getCurrentAction();
        if (action != null && !action.isGoalDriven()) return false; // fighting, fleeing, rallying, building
        return hasSurplus(agent);
    }

    /** Think-cadence check: pair up with a ready neighbour and seed the {@code make_family} goal. */
    public static void maintain(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        long now = world.getGameTime();
        UUID id = villager.getUUID();
        Long next = NEXT_CHECK.get(id);
        if (next != null && now < next) return;
        NEXT_CHECK.put(id, now + CHECK_INTERVAL + villager.getRandom().nextInt(200));
        if (partnerOf(id, now) != null) return;
        for (AgentGoal g : agent.getGoals()) if (GOAL.equals(g.getGoalType()) && !g.isCompleted()) return;
        if (agent.getGoals().size() >= 10 || !ready(villager, agent) || !roomForBaby(world, villager)) return;

        VillagerEntity partner = null;
        for (VillagerEntity other : world.getEntitiesOfClass(VillagerEntity.class,
                villager.getBoundingBox().inflate(PARTNER_RANGE, 6, PARTNER_RANGE),
                e -> e != villager && e.isAlive() && !e.isBaby())) {
            if (partnerOf(other.getUUID(), now) != null) continue;
            if (!ready(other, VillagerAgentManager.getAgent(other.getUUID()))) continue;
            if (partner == null || villager.distanceToSqr(other) < villager.distanceToSqr(partner)) partner = other;
        }
        if (partner == null) return;

        PARTNER.put(id, partner.getUUID());
        PARTNER.put(partner.getUUID(), id);
        PAIRED_UNTIL.put(id, now + PAIR_TIMEOUT);
        PAIRED_UNTIL.put(partner.getUUID(), now + PAIR_TIMEOUT);
        AgentGoal goal = new AgentGoal(GOAL, "Start a family with a neighbour", 5);
        goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        goal.setImportance(5);
        agent.getGoals().add(goal);
        BrainLog.goalAdded(agent, goal, "breeding:surplus_food");
    }

    /** The reserved partner, or null when not paired (or the reservation expired). */
    public static UUID partnerOf(UUID villager, long now) {
        UUID partner = PARTNER.get(villager);
        if (partner == null) return null;
        Long until = PAIRED_UNTIL.get(villager);
        if (until == null || now > until) { release(villager); return null; }
        return partner;
    }

    public static void release(UUID villager) {
        UUID partner = PARTNER.remove(villager);
        PAIRED_UNTIL.remove(villager);
        if (partner != null && villager.equals(PARTNER.get(partner))) {
            PARTNER.remove(partner);
            PAIRED_UNTIL.remove(partner);
        }
    }

    public static void forget(UUID villager) {
        release(villager);
        NEXT_CHECK.remove(villager);
    }

    /** More beds than villagers (adults and children) nearby. */
    public static boolean roomForBaby(ServerWorld world, VillagerEntity villager) {
        long beds = world.getPoiManager().getCountInRange(PointOfInterestType.HOME.getPredicate(),
                villager.blockPosition(), ROOM_RADIUS, PointOfInterestManager.Status.ANY);
        int villagers = world.getEntitiesOfClass(VillagerEntity.class,
                villager.getBoundingBox().inflate(ROOM_RADIUS), VillagerEntity::isAlive).size();
        return beds > villagers;
    }

    /** Eat into the surplus: remove food items worth at least {@code points} food points, cheapest first. */
    public static void spendFood(VillagerAgentData agent, float points) {
        List<ItemStack> items = agent.getInventory().getItems();
        while (points > 0) {
            int best = -1;
            for (int i = 0; i < items.size(); i++) {
                ItemStack s = items.get(i);
                if (s.isEmpty() || !SupplyPolicy.edible(s.getItem())) continue;
                if (best < 0 || nutrition(s.getItem()) < nutrition(items.get(best).getItem())) best = i;
            }
            if (best < 0) return;
            ItemStack s = items.get(best);
            points -= Math.max(1, nutrition(s.getItem()));
            s.shrink(1);
            if (s.isEmpty()) items.set(best, ItemStack.EMPTY);
        }
    }

    private static int nutrition(Item item) { return SupplyPolicy.nutrition(item); }
}
