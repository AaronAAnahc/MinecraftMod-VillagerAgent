package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Unified validation gate for all LLM-generated goals before they enter the goal queue.
 *
 * <p>Every path that creates an {@link AgentGoal} from LLM output (chat TODO:, thought GOAL:,
 * harness daily decision) must call {@link #validate} before enqueuing. This is the single
 * "generation space ⊆ execution space" enforcement point, ensuring invalid LLM output never
 * becomes a goal that wastes ticks before failing at runtime.
 *
 * <p>Checks performed:
 * <ol>
 *   <li><b>Handler existence</b> — the goal type has a registered skill in {@link BehaviorExecutor}.</li>
 *   <li><b>Item grounding</b> — for craft/gather/acquire_item/drop_item/pickup_item, the target item
 *       must be a real Forge-registered item (not {@code minecraft:nonexistent_item}).</li>
 *   <li><b>Coordinate sanity</b> — for move/harvest/plant/till/collect_crops, the target position must
 *       be within world bounds (Y 0–255) and not absurdly far (≤256 blocks).</li>
 *   <li><b>Craft feasibility</b> — for craft goals, a native recipe must exist.</li>
 *   <li><b>Composites</b> — every step of a {@code sequence} and the plan of an {@code invite}
 *       pass the same checks (recursively); invitees must be real villagers other than self. The plan of an
 *       {@code ask} is checked without the asker's own abilities: the invitee is checked when it accepts.</li>
 *   <li><b>Can I do it?</b> — profession work ({@code work}, {@code fish}, {@code smelt}, ...) needs the
 *       routine's own profession/skill gate; a {@code gift} needs an item the giver can spare.</li>
 * </ol>
 * Checks that depend on the villager itself are skipped when {@code agent} is null.
 */
public final class GoalValidator {

    private static final Logger LOGGER = LogManager.getLogger("GoalValidator");

    private GoalValidator() {}

    /**
     * Result of a validation check.
     */
    public static final class Result {
        public final boolean ok;
        public final String reason;

        private Result(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }

        public static Result accept() { return new Result(true, "ok"); }
        public static Result reject(String reason) { return new Result(false, reason); }
    }

    /**
     * Validate a goal before it enters the queue. Returns {@link Result#ok} = true if the goal
     * is safe to enqueue; false with a human-readable reason otherwise.
     *
     * @param goal     the goal to validate (never null)
     * @param agent    the owning villager's data (may be null for lightweight checks)
     * @param villager the entity (may be null for lightweight checks)
     * @param world    the server world (may be null — skips recipe/chunk checks)
     */
    public static Result validate(AgentGoal goal, VillagerAgentData agent,
                                  VillagerEntity villager, ServerWorld world) {
        if (goal == null) return Result.reject("null-goal");

        String type = goal.getGoalType();
        if (type == null || type.isEmpty()) return Result.reject("empty-goal-type");

        // Gate 1: handler existence — no handler means the goal will sit in the queue until
        // it times out and gets pruned. Reject it now.
        if (!BehaviorExecutor.hasHandlerFor(type)) {
            return Result.reject("no-handler:" + type);
        }

        // Gate 2: item grounding — if the goal references a target item, it must be real.
        String targetItem = goal.getTargetItem();
        if (targetItem != null && !targetItem.isEmpty()) {
            Result itemCheck = validateItem(targetItem);
            if (!itemCheck.ok) return itemCheck;
        }

        // Gate 3: type-specific checks
        switch (type) {
            case "sequence":
                return validateSequence(goal, agent, villager, world);
            case "invite":
                return validateInvite(goal, agent, villager, world);
            case "craft":
                return validateCraft(goal, agent, villager, world);
            case "place_block":
            case "place_near_self":
                return validatePlace(goal, villager, world);
            case "gift":
                return validateGift(goal, agent);
            case "work":
                return agent == null || world == null || RoutineScheduler.primaryWork(agent, world) != null
                        ? Result.accept() : Result.reject("work:no-job");
            case "move":
            case "harvest":
            case "plant":
            case "till":
            case "collect_crops":
                return validateLocation(goal, villager);
            default:
                if (RoutineScheduler.isWork(type) && agent != null && world != null
                        && !RoutineScheduler.eligible(type, agent, world)) {
                    return Result.reject("not-my-job:" + type);
                }
                return Result.accept();
        }
    }

    private static Result validateGift(AgentGoal goal, VillagerAgentData agent) {
        if (goal.getTargetEntityId() == null) return Result.reject("gift:no-recipient");
        if (agent != null && goal.getTargetEntityId().equals(agent.getVillagerId())) return Result.reject("gift:self");
        if (VillagerAgentManager.getAgent(goal.getTargetEntityId()) == null) return Result.reject("gift:unknown-villager");
        if (goal.getTargetItem() == null) return Result.reject("gift:no-item");
        net.minecraft.item.Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(normalizeItemId(goal.getTargetItem())));
        if (agent != null && (item == null || CooperativeSupply.giftable(agent, item) <= 0)) {
            return Result.reject("gift:nothing-to-spare:" + goal.getTargetItem());
        }
        return Result.accept();
    }

    /**
     * Convenience: validate and log rejection. Returns true if the goal is acceptable.
     */
    public static boolean check(AgentGoal goal, VillagerAgentData agent,
                                VillagerEntity villager, ServerWorld world, String source) {
        Result r = validate(goal, agent, villager, world);
        if (!r.ok) {
            LOGGER.info("[{}] Rejected LLM goal '{}' (type={}): {}",
                    source,
                    goal.getDescription(),
                    goal.getGoalType(),
                    r.reason);
            if (agent != null) {
                BrainLog.diagnostic(agent, "goal-rejected",
                        "source=" + source + " type=" + goal.getGoalType() + " reason=" + r.reason);
            }
        }
        return r.ok;
    }

    // ── Composite validation ────────────────────────────────────────────────

    private static Result validateSequence(AgentGoal goal, VillagerAgentData agent,
                                           VillagerEntity villager, ServerWorld world) {
        if (!goal.hasSteps()) return Result.reject("sequence:no-steps");
        if (goal.getSteps().size() > LlmGoalCatalog.MAX_STEPS) return Result.reject("sequence:too-many-steps");
        for (int i = 0; i < goal.getSteps().size(); i++) {
            AgentGoal step = goal.getSteps().get(i);
            if ("sequence".equals(step.getGoalType()) || "invite".equals(step.getGoalType()))
                return Result.reject("sequence:nested-" + step.getGoalType());
            Result r = validate(step, agent, villager, world);
            if (!r.ok) return Result.reject("sequence:step" + (i + 1) + ":" + r.reason);
        }
        return Result.accept();
    }

    private static Result validateInvite(AgentGoal goal, VillagerAgentData agent,
                                         VillagerEntity villager, ServerWorld world) {
        if (goal.getInvitees().isEmpty()) return Result.reject("invite:no-invitees");
        for (java.util.UUID id : goal.getInvitees()) {
            if (agent != null && id.equals(agent.getVillagerId())) return Result.reject("invite:self");
            if (VillagerAgentManager.getAgent(id) == null) return Result.reject("invite:unknown-villager:" + id);
        }
        AgentGoal plan = goal.getInvitePlan();
        if (plan == null) return Result.reject("invite:no-activity");
        if ("invite".equals(plan.getGoalType())) return Result.reject("invite:nested-invite");
        // An ask is carried out by the invitees alone: their abilities are checked when they accept.
        Result r = validate(plan, plan.isShared() ? agent : null, villager, world);
        return r.ok ? Result.accept() : Result.reject("invite:activity:" + r.reason);
    }

    // ── Item validation ─────────────────────────────────────────────────────

    private static Result validateItem(String itemId) {
        String normalized = normalizeItemId(itemId);
        if (ForgeRegistries.ITEMS.getValue(new ResourceLocation(normalized)) == null) {
            return Result.reject("unknown-item:" + normalized);
        }
        return Result.accept();
    }

    // ── Craft validation ────────────────────────────────────────────────────

    private static Result validateCraft(AgentGoal goal, VillagerAgentData agent,
                                        VillagerEntity villager, ServerWorld world) {
        String item = goal.getTargetItem();
        if (item == null || item.isEmpty()) {
            return Result.reject("craft:missing-target-item");
        }

        String normalized = normalizeItemId(item);

        // Check item is real
        if (ForgeRegistries.ITEMS.getValue(new ResourceLocation(normalized)) == null) {
            return Result.reject("craft:unknown-item:" + normalized);
        }

        // Check recipe exists (if world available)
        if (world != null && NativeRecipeResolver.resolveRecipe(world, normalized) == null) {
            return Result.reject("craft:no-recipe:" + normalized);
        }

        return Result.accept();
    }

    // ── Place validation ───────────────────────────────────────────────────

    /**
     * Cheap creation-time checks for block placement. Reachability (a complete path to a cell next to the
     * target) and "do I hold the block" are re-checked by {@code PlaceBlockSkill} at run time, because the
     * villager's position and inventory change between now and then.
     */
    private static Result validatePlace(AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String item = normalizeItemId(goal.getTargetItem());
        if (!(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)) instanceof net.minecraft.item.BlockItem)) {
            return Result.reject("place:not-a-block:" + item);
        }
        goal.setTargetItem(item);
        if (!"place_block".equals(goal.getGoalType())) return Result.accept();
        BlockPos pos = goal.getTargetLocation();
        if (pos == null) return Result.reject("place:missing-location");
        Result loc = validateLocation(goal, villager);
        if (!loc.ok) return loc;
        if (world != null && world.isLoaded(pos)) {
            net.minecraft.block.BlockState there = world.getBlockState(pos);
            if (!there.isAir(world, pos) && !there.getMaterial().isReplaceable()) {
                return Result.reject("place:occupied:" + pos + ":" + there.getBlock().getRegistryName());
            }
        }
        return Result.accept();
    }

    // ── Location validation ─────────────────────────────────────────────────

    private static Result validateLocation(AgentGoal goal, VillagerEntity villager) {
        BlockPos pos = goal.getTargetLocation();
        if (pos == null) {
            // Some location-based goals may be valid without explicit coords (e.g. farm uses
            // its own search), so only reject if the goal explicitly promises a location.
            return Result.accept();
        }

        if (pos.getY() < 0 || pos.getY() > 255) {
            return Result.reject("location:out-of-world:y=" + pos.getY());
        }

        if (villager != null && villager.blockPosition().distSqr(pos) > 256L * 256L) {
            return Result.reject("location:too-far:" + pos);
        }

        return Result.accept();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String normalizeItemId(String s) {
        if (s == null) return "minecraft:air";
        String t = s.trim();
        if (t.isEmpty()) return "minecraft:air";
        if (!t.contains(":")) t = "minecraft:" + t;
        return t;
    }
}
