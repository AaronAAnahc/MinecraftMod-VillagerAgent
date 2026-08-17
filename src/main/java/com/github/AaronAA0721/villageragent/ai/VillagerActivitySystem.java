package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.Random;

/**
 * Translates a villager's {@code scheduledActivity} (set by {@link VillagerSchedulePlanner})
 * into real movement and world actions.
 *
 * <ul>
 *   <li><b>exploring</b> — picks a random point 20–50 blocks away and navigates there.</li>
 *   <li><b>resting</b>   — navigates to the villager's bed (HOME memory), then stops navigation.</li>
 *   <li><b>crafting</b>  — walks to the job-site block and logs work activity.</li>
 *   <li><b>farming / socializing</b> — handled by other systems; this class ignores them.</li>
 * </ul>
 */
public class VillagerActivitySystem {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Random RANDOM = new Random();

    /** Run every this many ticks (1 second = 20 ticks). */
    private static final int TICK_INTERVAL = 20;

    /** Minimum explore distance in blocks. */
    private static final int EXPLORE_MIN = 20;
    /** Maximum explore distance in blocks. */
    private static final int EXPLORE_MAX = 50;

    /** Squared arrival threshold — within 4 blocks counts as arrived. */
    private static final double ARRIVE_SQ = 16.0;

    /** Ticks before a stuck explorer gives up (~20 s at TICK_INTERVAL=20). */
    private static final int EXPLORE_STUCK_TIMEOUT = 200;

    // ── Public entry point ────────────────────────────────────────────────────

    public static void tick(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        long gameTime = world.getGameTime();
        if (gameTime % TICK_INTERVAL != 0) return;

        // Never interrupt farming or combat — those systems manage themselves
        if (agent.isInFarmingState() || agent.isOnFarmingCooldown()) return;
        // Never interrupt an in-progress build (single block or multi-block BuildJob)
        if (agent.getBuildJob() != null) return;
        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && (cur.getActionType() == VillagerAction.ActionType.ATTACK
                || cur.getActionType() == VillagerAction.ActionType.HARVEST
                || cur.getActionType() == VillagerAction.ActionType.GROW
                || cur.getActionType() == VillagerAction.ActionType.PLACE
                || cur.getActionType() == VillagerAction.ActionType.BREAK
                || cur.getActionType() == VillagerAction.ActionType.FLEE
                || cur.getActionType() == VillagerAction.ActionType.BUILD)) return;

        String scheduled = agent.getScheduledActivity();
        if (scheduled == null) return;

        switch (scheduled) {
            case "exploring": handleExploring(world, villager, agent); break;
            case "resting":   handleResting(world, villager, agent);   break;
            case "crafting":  handleCrafting(world, villager, agent);  break;
            default: break; // farming / socializing handled elsewhere
        }
    }

    // ── Exploring ─────────────────────────────────────────────────────────────

    private static void handleExploring(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        VillagerAction cur = agent.getCurrentAction();

        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE) {
            // Continue existing explore walk
            BlockPos target = cur.getTargetBlockPos();
            if (target == null) { agent.setCurrentAction(null); return; }

            double distSq = villager.blockPosition().distSqr(target);
            if (distSq <= ARRIVE_SQ) {
                // Arrived!
                agent.addMemory("Explored a new area around (" + target.getX() + ", " + target.getZ() + ")");
                agent.setCurrentAction(null);
                LOGGER.debug("{} finished exploring to {}", agent.getName(), target);
                return;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > EXPLORE_STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach the area I wanted to explore — the path was blocked");
                agent.setCurrentAction(null);
                LOGGER.debug("{} gave up exploring (stuck)", agent.getName());
                return;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.35);
            }
            return;
        }

        if (cur != null) return; // unrelated action

        BlockPos target = pickExploreTarget(world, villager);
        if (target == null) return;

        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Exploring the surroundings");
        move.setTargetBlockPos(target);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        agent.setCurrentAction(move);
        agent.setCurrentActivity("exploring");
        villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.35);
        LOGGER.debug("{} set out to explore {}", agent.getName(), target);
    }

    private static BlockPos pickExploreTarget(ServerWorld world, VillagerEntity villager) {
        BlockPos origin = villager.blockPosition();
        for (int i = 0; i < 10; i++) {
            double angle = RANDOM.nextDouble() * 2 * Math.PI;
            int dist = EXPLORE_MIN + RANDOM.nextInt(EXPLORE_MAX - EXPLORE_MIN);
            int dx = (int) (Math.cos(angle) * dist);
            int dz = (int) (Math.sin(angle) * dist);
            BlockPos ground = findGround(world, origin.offset(dx, 0, dz));
            if (ground != null) return ground;
        }
        return null;
    }

    private static BlockPos findGround(ServerWorld world, BlockPos candidate) {
        for (int dy = 5; dy >= -10; dy--) {
            BlockPos check = candidate.offset(0, dy, 0);
            BlockPos below = check.below();
            if (world.isEmptyBlock(check)
                    && world.getBlockState(below).isSolidRender(world, below)) {
                return check;
            }
        }
        return null;
    }

    // ── Resting ───────────────────────────────────────────────────────────────

    /**
     * Resting is a reflex/skill, not LLM micro-control: resolve the bed (from the structure index
     * if no vanilla HOME exists) and walk/sleep. Delegates to {@link SleepSkill}, which also closes
     * the HOME-refill gap (P0) by remembering the chosen bed in the agent's persisted home bed.
     */
    private static void handleResting(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        SleepSkill.tickRest(agent, villager, world);
    }

    /** Sleep-heal: regenerate 1 HP every {@code healInterval} ticks while actually sleeping, so a
     *  full night's rest restores ~25% of max health. healInterval = night length / (25% × max HP).
     *  Sleeping itself is driven by vanilla / another module — this only checks {@code isSleeping()}. */
    private static final long NIGHT_DURATION_TICKS = 10000L;  // 13000 → 23000
    private static final float SLEEP_HEAL_FRACTION = 0.25f;   // full night restores 25% max HP

    public static void applySleepHealing(ServerWorld world, VillagerEntity villager) {
        if (!villager.isSleeping()) return;
        if (villager.getHealth() >= villager.getMaxHealth()) return;

        // 20 HP → 25% = 5 HP → healInterval = 10000 / 5 = 2000 ticks (100 s) per 1 HP.
        int healInterval = Math.max(1, (int) (NIGHT_DURATION_TICKS / (villager.getMaxHealth() * SLEEP_HEAL_FRACTION)));

        if (world.getGameTime() % healInterval == 0) {
            villager.heal(1.0f);
        }
    }

    // ── Crafting ─────────────────────────────────────────────────────────────

    /** Ticks the villager must remain at the workstation before crafting completes (~10 s). */
    private static final int CRAFT_DURATION = 200;

    private static void handleCrafting(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        VillagerAction cur = agent.getCurrentAction();

        // ── Phase A: Walking to workstation ───────────────────────────────────
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE) {
            BlockPos target = cur.getTargetBlockPos();
            if (target == null) { agent.setCurrentAction(null); return; }

            double distSq = villager.blockPosition().distSqr(target);
            if (distSq <= ARRIVE_SQ) {
                // Arrived — begin working phase
                VillagerAction work = new VillagerAction(VillagerAction.ActionType.CRAFT,
                        "Working at " + agent.getProfession().toLowerCase() + " station");
                work.setTargetBlockPos(target);
                work.setPhase(VillagerAction.ActionPhase.ACTING);
                agent.setCurrentAction(work);
                agent.setCurrentActivity("crafting");
                villager.getNavigation().stop();
                LOGGER.debug("{} arrived at workstation — starting work", agent.getName());
                return;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > EXPLORE_STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach my workstation today");
                agent.setCurrentAction(null);
                return;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(
                        target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.4);
            }
            return;
        }

        // ── Phase B: Working at the bench ─────────────────────────────────────
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.CRAFT
                && cur.getPhase() == VillagerAction.ActionPhase.ACTING) {
            cur.incrementStuckTicks(); // reused as "work timer"
            if (cur.getStuckTicks() >= CRAFT_DURATION) {
                finalizeCrafting(world, villager, agent);
                agent.setCurrentAction(null);
            }
            return;
        }

        // ── Phase C: Idle — walk to job site ─────────────────────────────────
        if (cur != null) return; // some unrelated action

        Optional<GlobalPos> jobOpt = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (!jobOpt.isPresent()) {
            agent.addMemory("Wanted to work but couldn't find my workstation today");
            return;
        }

        BlockPos jobPos = jobOpt.get().pos();
        double distSq = villager.blockPosition().distSqr(jobPos);

        if (distSq > ARRIVE_SQ) {
            VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Walking to workstation to craft");
            move.setTargetBlockPos(jobPos);
            move.setPhase(VillagerAction.ActionPhase.WALKING);
            agent.setCurrentAction(move);
            agent.setCurrentActivity("crafting");
            villager.getNavigation().moveTo(jobPos.getX() + 0.5, jobPos.getY(), jobPos.getZ() + 0.5, 0.4);
        } else {
            // Already right at workstation — jump straight to working phase
            VillagerAction work = new VillagerAction(VillagerAction.ActionType.CRAFT,
                    "Working at " + agent.getProfession().toLowerCase() + " station");
            work.setTargetBlockPos(jobPos);
            work.setPhase(VillagerAction.ActionPhase.ACTING);
            agent.setCurrentAction(work);
            agent.setCurrentActivity("crafting");
        }
    }

    /**
     * Attempt to craft an item from the villager's profession catalog at its workstation.
     * This is the goal-driven path ({@link ProfessionCraftCatalog} + {@link NativeRecipeResolver}),
     * matching what {@code executeCraftGoal} does — the old hard-coded {@code RecipeRegistry}
     * path is retired so every craft resolves through the same catalogue + native recipe.
     */
    private static void finalizeCrafting(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        String profession = agent.getProfession();
        int level = villager.getVillagerData().getLevel();

        for (String itemId : ProfessionCraftCatalog.getCraftableIds(profession, level)) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
            if (item == null || item == Items.AIR) continue;
            ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, item);
            if (recipe == null) continue;
            // consumeAndProduce re-verifies materials at execution time; skip to the next if short.
            if (!recipe.consumeAndProduce(agent.getInventory(), 1)) continue;
            if (ProfessionCraftCatalog.canEnchant(profession, level, itemId)) {
                enchantFirstCrafted(agent, item, villager);
            }
            agent.addMemory("Crafted " + itemId + " at my workstation — good work today!");
            LOGGER.info("{} crafted '{}'", agent.getName(), itemId);
            return;
        }

        // Nothing craftable right now — flavour memory.
        agent.addMemory("Spent the session working at my " + profession.toLowerCase()
                + " station — kept busy tidying and preparing materials");
        LOGGER.debug("{} crafting session finished (no craftable recipe)", agent.getName());
    }

    /** Apply a random Master-level enchantment to the first freshly-crafted stack. */
    private static void enchantFirstCrafted(VillagerAgentData agent, Item target, VillagerEntity villager) {
        AgentInventory inv = agent.getInventory();
        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack s = inv.getItems().get(i);
            if (!s.isEmpty() && s.getItem() == target) {
                ItemStack enchanted = EnchantmentHelper.enchantItem(
                        villager.getRandom(), s, 5 + villager.getRandom().nextInt(15), false);
                inv.getItems().set(i, enchanted);
                return;
            }
        }
    }
}

