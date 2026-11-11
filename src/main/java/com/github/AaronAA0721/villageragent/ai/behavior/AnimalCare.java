package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillageRegistry;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.server.ServerWorld;

import java.util.*;
import java.util.function.Predicate;

/**
 * Shared livestock rules for the butcher, shepherd and fletcher.
 *
 * <p><b>Inside the village</b> each species is kept as a breeding pair: when exactly two adults of a species live in
 * the village (and no young one is growing up), the keeper feeds them so they breed; once there are more than two
 * adults, one adult is slaughtered for meat, leather, wool or feathers. A baby always grows into the third adult,
 * so a pair becomes a steady meat supply. <b>Outside the village</b> any adult livestock may be hunted.
 *
 * <p>"Inside the village" means within the village structure bounds known to {@link VillageRegistry}, grown by
 * {@link #VILLAGE_MARGIN} blocks so pens at the edge of the village count. When no village is known nearby,
 * every animal counts as village livestock (nothing is hunted, pairs are kept).
 *
 * <p>Named, leashed, ridden and carried animals are never slaughtered: they belong to a player.
 */
public final class AnimalCare {
    public static final double SCAN_RADIUS = 32;
    static final int VILLAGE_MARGIN = 12;
    /** Adults of one species kept alive inside the village. */
    static final int KEEP_PAIR = 2;

    public static final Set<EntityType<?>> LIVESTOCK = new HashSet<>(Arrays.asList(
            EntityType.COW, EntityType.MOOSHROOM, EntityType.PIG, EntityType.SHEEP,
            EntityType.CHICKEN, EntityType.RABBIT));

    public enum Kind { FEED, CULL, HUNT }

    /** One concrete thing to do with one animal. */
    public static final class Plan {
        public final AnimalEntity animal;
        public final Kind kind;
        Plan(AnimalEntity animal, Kind kind) { this.animal = animal; this.kind = kind; }
    }

    private AnimalCare() {}

    /**
     * Choose the nearest useful job, or null.
     *
     * @param species  which animal types this keeper works with
     * @param feed     may feed a village pair so it breeds
     * @param cull     may slaughter village adults above the pair
     * @param hunt     may slaughter adults outside the village
     * @param rejected animals that recently could not be reached
     */
    public static Plan plan(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                            Predicate<EntityType<?>> species, boolean feed, boolean cull, boolean hunt,
                            Set<UUID> rejected) {
        MutableBoundingBox village = villageBounds(world, villager);
        Map<EntityType<?>, List<AnimalEntity>> inside = new HashMap<>();
        List<Plan> options = new ArrayList<>();
        for (AnimalEntity animal : nearby(villager, world, species)) {
            if (inVillage(village, animal.blockPosition())) {
                inside.computeIfAbsent(animal.getType(), t -> new ArrayList<>()).add(animal);
            } else if (hunt && !animal.isBaby() && mayKill(animal) && !rejected.contains(animal.getUUID())) {
                options.add(new Plan(animal, Kind.HUNT));
            }
        }
        for (List<AnimalEntity> herd : inside.values()) {
            List<AnimalEntity> adults = new ArrayList<>();
            int babies = 0;
            for (AnimalEntity animal : herd) {
                if (animal.isBaby()) babies++; else adults.add(animal);
            }
            if (cull && adults.size() > KEEP_PAIR) {
                AnimalEntity victim = null;
                for (AnimalEntity animal : adults) {
                    if (!mayKill(animal) || animal.isInLove() || rejected.contains(animal.getUUID())) continue;
                    if (victim == null || villager.distanceToSqr(animal) < villager.distanceToSqr(victim)) victim = animal;
                }
                if (victim != null) options.add(new Plan(victim, Kind.CULL));
            } else if (feed && adults.size() == KEEP_PAIR && babies == 0) {
                boolean ready = true;
                for (AnimalEntity animal : adults) if (animal.getAge() != 0) ready = false;
                if (!ready) continue;
                for (AnimalEntity animal : adults) {
                    if (!animal.canFallInLove() || rejected.contains(animal.getUUID())) continue;
                    if (foodSlot(agent, animal) >= 0) { options.add(new Plan(animal, Kind.FEED)); break; }
                }
            }
        }
        Plan best = null;
        for (Plan p : options)
            if (best == null || villager.distanceToSqr(p.animal) < villager.distanceToSqr(best.animal)) best = p;
        return best;
    }

    /** Living livestock of the given species around the villager. */
    public static List<AnimalEntity> nearby(VillagerEntity villager, ServerWorld world, Predicate<EntityType<?>> species) {
        AxisAlignedBB box = villager.getBoundingBox().inflate(SCAN_RADIUS, 8, SCAN_RADIUS);
        return world.getEntitiesOfClass(AnimalEntity.class, box,
                e -> e.isAlive() && LIVESTOCK.contains(e.getType()) && species.test(e.getType()));
    }

    /** Count village adults of one type (used by chance factors). */
    public static int villageAdults(VillagerEntity villager, ServerWorld world, EntityType<?> type) {
        MutableBoundingBox village = villageBounds(world, villager);
        int n = 0;
        for (AnimalEntity a : nearby(villager, world, t -> t == type))
            if (!a.isBaby() && inVillage(village, a.blockPosition())) n++;
        return n;
    }

    /** Village bounds near this villager, or null when no village is known (everything then counts as inside). */
    static MutableBoundingBox villageBounds(ServerWorld world, VillagerEntity villager) {
        VillageRegistry registry = VillageRegistry.get(world);
        Long key = registry.villageNear(world, villager.blockPosition(), 48);
        return key == null ? null : registry.bounds(key);
    }

    static boolean inVillage(MutableBoundingBox village, BlockPos pos) {
        if (village == null) return true;
        return pos.getX() >= village.x0 - VILLAGE_MARGIN && pos.getX() <= village.x1 + VILLAGE_MARGIN
                && pos.getZ() >= village.z0 - VILLAGE_MARGIN && pos.getZ() <= village.z1 + VILLAGE_MARGIN;
    }

    /** Player-owned looking animals are left alone. */
    static boolean mayKill(AnimalEntity animal) {
        return !animal.hasCustomName() && !animal.isLeashed() && !animal.isVehicle() && !animal.isPassenger();
    }

    /** Inventory slot holding something this animal eats, or -1. */
    public static int foodSlot(VillagerAgentData agent, AnimalEntity animal) {
        List<ItemStack> items = agent.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && animal.isFood(stack)) return i;
        }
        return -1;
    }
}
