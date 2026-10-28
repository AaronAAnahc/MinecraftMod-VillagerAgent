package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.*;
import com.github.AaronAA0721.villageragent.ai.work.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Acquisition adapters: the generic executor owns selection, clocks and hysteresis. */
public final class AcquisitionMethods {
    public static final class Context {
        public final VillagerAgentData agent;
        public final VillagerEntity villager;
        public final ServerWorld world;
        public final Item target;
        public final int desired;
        public final boolean executing;
        TaskContract craftingContract;
        public Context(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                       Item target, int desired, boolean executing) {
            this.agent = agent; this.villager = villager; this.world = world;
            this.target = target; this.desired = desired; this.executing = executing;
        }
        int count(Item item) { return agent.getInventory().countItem(new ItemStack(item)); }
        boolean unlocked() {
            String id = target.getRegistryName().toString();
            return agent.getUnlockedRecipes().contains(id)
                    || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), id);
        }
        void discardChild() {
            if (!executing) return;
            TaskExecution.Frame child = agent.brain().tasks.current().child();
            if (child == null) return;
            ActionLifecycle.clearBody(agent, villager, world);
            ActionLifecycle.releaseTree(child, agent, world);
            agent.brain().tasks.discardChild();
        }
    }
    public static List<TaskMethod<Context>> candidates(Context c) {
        return ItemTaskCatalog.discover(c);
    }

    private abstract static class Owned implements TaskMethod<Context> {
        UUID owner, purchase;
        VillageSignalBoard.VillageMessage transaction;
        double remainingCost;
        final MotionProgress motion = new MotionProgress();
        public void start(Context c, UUID attempt) { owner = attempt; }
        public void close(Context c) {
            if (owner == null) return;
            c.agent.brain().acquisitions.cancelChildren(owner);
            VillageSignalBoard.get().removeOwned(owner);
            WorkOrderSavedData data = WorkOrderSavedData.get(c.world);
            data.book.cancelOwned(owner); data.setDirty();
            c.discardChild();
        }
        /** null means the purchase completed and the method can continue. */
        Update purchaseTick(Context c) {
            VillageSignalBoard board = VillageSignalBoard.get();
            VillageSignalBoard.VillageMessage want = board.find(purchase);
            // Resolved board entries may be pruned while this task is on its WAITING cooldown.
            if (want == null && transaction != null && transaction.resolved) want = transaction;
            if (want == null) return new Update(Status.FAILED, false, true);
            if (want.resolved) {
                board.removeOwned(owner); purchase = null; transaction = null; motion.reset(); return null;
            }
            VillageSignalBoard.VillageMessage offer = want.matchedWith == null ? null : board.find(want.matchedWith);
            if (offer == null || want.expireTick <= c.world.getGameTime() || offer.resolved
                    || offer.expireTick <= c.world.getGameTime()
                    || !VillagerEconomySystem.validateTradeFunds(want, offer)) return new Update(Status.FAILED, false, true);
            Entity seller = c.world.getEntity(offer.author);
            return Update.waiting(motion.toward(seller, c.villager.blockPosition()));
        }
        boolean beginPurchase(Context c, AcquisitionPlanner.Choice choice) {
            remainingCost = choice.emeralds;
            purchase = AcquisitionPlanner.purchase(choice, c.agent, c.villager, c.world, owner);
            transaction = purchase == null ? null : VillageSignalBoard.get().find(purchase);
            return purchase != null;
        }
    }

    static final class Buy extends Owned {
        AcquisitionPlanner.Choice quote;
        public String id() { return "buy_product"; }
        public double estimate(Context c) {
            if (purchase != null) return remainingCost;
            quote = AcquisitionPlanner.choose(c.agent, c.villager, c.world, c.target, c.desired, false);
            return quote == null ? Double.POSITIVE_INFINITY : quote.emeralds;
        }
        public Update tick(Context c) {
            if (purchase != null) {
                Update update = purchaseTick(c);
                return update == null ? new Update(Status.DONE, true, true) : update;
            }
            return quote != null && beginPurchase(c, quote) ? Update.waiting(false)
                    : new Update(Status.FAILED, false, true);
        }
    }

    static final class Craft extends Owned {
        AcquisitionPlanner.Choice quote;
        AgentGoal child;
        long progress;
        boolean legacy;
        public String id() { return "craft"; }
        public double estimate(Context c) {
            if (purchase != null) return remainingCost;
            if (child != null) return 0; // In-progress production has no new purchase commitment.
            legacy = !CraftingPlan.supports(c.world, c.target);
            if (legacy) {
                ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(c.world, c.target);
                return c.unlocked() && recipe != null && NativeRecipeResolver.missingMaterialQuantities(
                        c.agent.getInventory(), recipe, Math.max(1, c.desired - c.count(c.target))).isEmpty()
                        ? 0 : Double.POSITIVE_INFINITY;
            }
            quote = AcquisitionPlanner.choose(c.agent, c.villager, c.world, c.target, c.desired, true);
            return quote == null ? Double.POSITIVE_INFINITY : quote.emeralds;
        }
        public Update tick(Context c) {
            boolean advanced = false;
            if (purchase != null) {
                Update update = purchaseTick(c);
                if (update != null) return update;
                quote = null; advanced = true;
                if (!Double.isFinite(estimate(c))) return new Update(Status.FAILED, true, true);
            }
            if (quote != null && quote.needsPurchase()) {
                boolean started = beginPurchase(c, quote); quote = null;
                return started ? Update.waiting(advanced) : new Update(Status.FAILED, advanced, true);
            }
            if (child == null) {
                child = new AgentGoal("craft", "Craft acquisition " + c.target.getRegistryName(), 6);
                child.setTargetItem(c.target.getRegistryName().toString());
                child.setMethodAttempt(owner);
                child.setTargetQuantity(Math.max(1, c.desired - c.count(c.target)));
            }
            SkillResult result = BehaviorExecutor.executeChild(c.agent, c.villager, c.world, () -> child);
            advanced |= child.getProgressVersion() != progress;
            progress = child.getProgressVersion();
            VillagerAction action = c.agent.getCurrentAction();
            if (action != null && action.getActionType() == VillagerAction.ActionType.MOVE)
                advanced |= motion.toward(c.villager, action.getTargetBlockPos());
            if (result == SkillResult.SUCCESS) return new Update(Status.DONE, true, true);
            if (result == SkillResult.FAIL || result == SkillResult.BLOCKED || result == SkillResult.DELETE)
                return new Update(Status.FAILED, advanced, true);
            // Each completed tick is a safe boundary; world mutations inside a tick are atomic.
            return new Update(result == SkillResult.RUNNING ? Status.RUNNING : Status.WAITING,
                    advanced, true);
        }
    }

    static final class Supply extends Owned {
        final Map<String, Integer> targets = new LinkedHashMap<>();
        final Map<String, Integer> highStock = new HashMap<>();
        final Map<String, UUID> lastWorkers = new HashMap<>();
        final Map<String, Integer> workerStock = new HashMap<>();
        final Map<String, MotionProgress> movements = new HashMap<>();
        Supply(Context c) { targets.put(c.target.getRegistryName().toString(), c.desired); }
        public String id() { return "cooperative_supply"; }
        public double estimate(Context c) {
            return ModConfig.ENABLE_ECONOMY.get() && ModConfig.ENABLE_WORLD_INTERACTION.get()
                    && ResourceWorkSystem.hasSupplier(c.world, c.villager, c.target.getRegistryName().toString())
                    ? 0 : Double.POSITIVE_INFINITY;
        }
        public void start(Context c, UUID attempt) {
            super.start(c, attempt);
            for (String item : targets.keySet()) highStock.put(item, ResourceWorkSystem.count(c.agent, item));
        }
        public Update tick(Context c) {
            if (!Double.isFinite(estimate(c))) return new Update(Status.FAILED, false, true);
            boolean done = true, advanced = false;
            for (Map.Entry<String, Integer> target : targets.entrySet()) {
                String item = target.getKey(); int stock = ResourceWorkSystem.count(c.agent, item);
                int previousStock = highStock.getOrDefault(item, 0);
                if (stock > previousStock) { highStock.put(item, stock); advanced = true; }
                if (stock >= target.getValue()) continue;
                done = false;
                WorkOrderBook.Order order = ResourceWorkSystem.requestOwned(c.world, c.agent, owner, item, target.getValue());
                if (order == null || order.worker == null) continue;
                if (!order.worker.equals(lastWorkers.put(item, order.worker))) advanced = true;
                VillagerAgentData worker = VillagerAgentManager.getAgent(order.worker);
                Entity entity = c.world.getEntity(order.worker);
                if (worker != null) {
                    int gathered = ResourceWorkSystem.count(worker, item);
                    if (gathered > workerStock.getOrDefault(item, 0)) {
                        workerStock.put(item, gathered); advanced = true;
                    }
                }
                if (worker != null && worker.getCurrentAction() != null)
                    advanced |= movements.computeIfAbsent(item, key -> new MotionProgress())
                            .toward(entity, worker.getCurrentAction().getTargetBlockPos());
            }
            return done ? new Update(Status.DONE, true, true) : Update.waiting(advanced);
        }
    }

    /** Prerequisite acquisition and execution are shared by crafting, logging, mining, quarrying and harvesting. */
    static final class Produce extends Owned {
        final boolean extraction;
        AgentGoal child;
        long progress;
        int highStock;
        String methodId = "craft_inputs";
        Produce(boolean extraction) { this.extraction = extraction; }
        Produce bind(Context c) { contract(c); return this; }
        public String id() { return methodId; }
        TaskContract contract(Context c) {
            if (extraction) {
                ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
                methodId = source.methodId();
                return ItemTaskCatalog.extractionContract(c);
            }
            return ItemTaskCatalog.craftContract(c);
        }
        public double estimate(Context c) {
            if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return Double.POSITIVE_INFINITY;
            if (extraction && !ResourceCatalog.get(c.target.getRegistryName().toString()).skilled(c.agent)
                    || !extraction && !c.unlocked()) return Double.POSITIVE_INFINITY;
            if (child != null && !"acquire_item".equals(child.getGoalType())) return 0;
            if (extraction && !ResourceSensor.available(c.world, c.villager, c.agent,
                    ResourceCatalog.get(c.target.getRegistryName().toString()))) return Double.POSITIVE_INFINITY;
            if (!extraction) {
                ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(c.world, c.target);
                if (recipe == null || (recipe.vanilla != null && !recipe.vanilla.canCraftInDimensions(2, 2)
                        && WorkbenchMemory.path(c.agent, c.villager, c.world) == null)) return Double.POSITIVE_INFINITY;
            }
            TaskContract requirements = contract(c);
            double cost = 0;
            for (TaskContract.ItemNeed need : requirements.items) {
                Item item = ResourceCatalog.item(need.item);
                if (item == Items.AIR || item == c.target) return Double.POSITIVE_INFINITY;
                if (c.count(item) >= need.quantity) continue;
                if (child != null && need.item.equals(child.getTargetItem()))
                    cost += c.agent.brain().acquisitions.remainingCost(owner, item);
                else cost += ItemTaskCatalog.minimumCost(new Context(c.agent, c.villager, c.world, item, need.quantity, false));
            }
            // All purchases in this route must fit the same wallet, not separate hypothetical wallets.
            return cost <= c.count(Items.EMERALD) ? cost : Double.POSITIVE_INFINITY;
        }
        public Update tick(Context c) {
            if (!ModConfig.ENABLE_WORLD_INTERACTION.get()
                    || extraction && !ResourceCatalog.get(c.target.getRegistryName().toString()).skilled(c.agent)
                    || !extraction && !c.unlocked()) return new Update(Status.FAILED, false, true);
            if (child == null) {
                TaskContract requirements = contract(c);
                for (TaskContract.ItemNeed need : requirements.items) {
                    if (ResourceWorkSystem.count(c.agent, need.item) >= need.quantity) continue;
                    child = new AgentGoal("acquire_item", "Acquire prerequisite " + need.item, 6);
                    child.setTargetItem(need.item); child.setTargetQuantity(need.quantity);
                    break;
                }
                if (child == null) {
                    child = new AgentGoal(extraction ? "extract_item" : "craft", "Produce " + c.target.getRegistryName(), 6);
                    child.setTargetItem(c.target.getRegistryName().toString());
                    child.setTargetQuantity(extraction ? c.desired : Math.max(1, c.desired - c.count(c.target)));
                }
                child.setMethodAttempt(owner);
                progress = child.getProgressVersion();
                highStock = ResourceWorkSystem.count(c.agent, child.getTargetItem());
            }
            SkillResult result = BehaviorExecutor.executeChild(c.agent, c.villager, c.world, () -> child);
            int stock = ResourceWorkSystem.count(c.agent, child.getTargetItem());
            boolean advanced = child.getProgressVersion() != progress || stock > highStock;
            highStock = Math.max(stock, highStock); progress = child.getProgressVersion();
            if (advanced) c.craftingContract = null;
            VillagerAction action = c.agent.getCurrentAction();
            if (action != null) advanced |= motion.toward(c.villager, action.getTargetBlockPos());
            if (result == SkillResult.SUCCESS) {
                boolean prerequisite = "acquire_item".equals(child.getGoalType()); child = null;
                return new Update(prerequisite ? Status.RUNNING : Status.DONE, true, true);
            }
            if (result == SkillResult.FAIL || result == SkillResult.BLOCKED || result == SkillResult.DELETE)
                return new Update(Status.FAILED, advanced, true);
            return new Update(result == SkillResult.RUNNING ? Status.RUNNING : Status.WAITING, advanced, true);
        }
    }

    /** Monotone distance improvement toward a concrete target, not mere navigation activity. */
    private static final class MotionProgress {
        BlockPos target;
        double best = Double.POSITIVE_INFINITY;
        boolean toward(Entity entity, BlockPos next) {
            if (entity == null || next == null) return false;
            double distance = entity.position().distanceToSqr(net.minecraft.util.math.vector.Vector3d.atCenterOf(next));
            if (!next.equals(target)) { target = next.immutable(); best = distance; return false; }
            if (distance + 1 < best) { best = distance; return true; }
            return false;
        }
        void reset() { target = null; best = Double.POSITIVE_INFINITY; }
    }
    private AcquisitionMethods() {}
}
