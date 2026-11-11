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
        public boolean productionOnly;
        /** Generic tool category satisfying this acquisition (any suitable tier); null for exact items. */
        public String requestCategory;
        /**
         * Items this acquisition tree may not ask the board for: while serving a village request, the request's
         * own item and its "or" alternatives (asking for them would just post the same request again).
         */
        public Set<Item> forbiddenRequests = Collections.emptySet();
        public boolean forbidsRequest(Item item) { return forbiddenRequests.contains(item); }
        /**
         * True while answering someone else's request: the responder may not ask the board for a work tool to
         * do it (an unemployed villager without an axe does not answer a log request by requesting an axe).
         */
        public boolean noToolRequests;
        /** Nested estimate for an input; inherits the forbidden-request set, never production-only. */
        public Context child(Item item, int quantity) {
            Context child = new Context(agent, villager, world, item, quantity, false);
            child.forbiddenRequests = forbiddenRequests;
            child.noToolRequests = noToolRequests;
            return child;
        }
        TaskContract craftingContract;
        private boolean furnaceChecked;
        private BlockPos furnace;
        BlockPos furnace() {
            if (!furnaceChecked) {
                furnace = SmeltSkill.findFurnace(agent, villager, world, SmeltSkill.oreFor(target));
                furnaceChecked = true;
            }
            return furnace;
        }
        public Context(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                       Item target, int desired, boolean executing) {
            this.agent = agent; this.villager = villager; this.world = world;
            this.target = target; this.desired = desired; this.executing = executing;
            this.units = desired;
        }
        int count(Item item) { return agent.getInventory().countItem(new ItemStack(item)); }
        public SupplyPolicy.Spec spec() {
            return requestCategory != null && SupplyPolicy.isCategory(requestCategory)
                    ? SupplyPolicy.Spec.category(requestCategory) : SupplyPolicy.Spec.exact(target.getRegistryName().toString());
        }
        /** Units already held: exact item count, or matching category units. */
        public int satisfiedUnits() {
            return requestCategory == null ? count(target) : SupplyPolicy.units(spec(), agent.getInventory().getItems());
        }
        /** Units that satisfy this acquisition; equals {@code desired} except for food-point categories. */
        public int units;
        public boolean satisfied() { return satisfiedUnits() >= units; }
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
        List<TaskMethod<Context>> methods=ItemTaskCatalog.discover(c);
        if(c.productionOnly) methods.removeIf(method->RequestSupply.ID.equals(method.id()));
        return methods;
    }

    private abstract static class Owned implements TaskMethod<Context> {
        UUID owner;
        final MotionProgress motion = new MotionProgress();
        public void start(Context c, UUID attempt) { owner = attempt; }
        /** Ownership-scoped cleanup: nested attempts, owned requests and work orders, never by item type. */
        public void close(Context c) {
            if (owner == null) return;
            c.agent.brain().acquisitions.cancelChildren(owner);
            VillageSignalBoard.get().removeOwned(owner);
            WorkOrderSavedData data = WorkOrderSavedData.get(c.world);
            data.book.cancelOwned(owner); data.setDirty();
            c.discardChild();
        }
    }

    /**
     * Method {@code request_supply} of compound task {@code acquire_item} — the requester side:
     *
     * <pre>
     * request_supply(item | category, units)
     *   0. adopt    — this villager already has a live request covering the objective: wait on it (no walk)
     *   1. post     — walk to the board, read it (60 ticks), post one request for the shortage; when a
     *                 neighbour's "for sale" notice covers (part of) it, ask for that part and claim the offer
     *                 at once — the requester then walks to the provider (paired meet_trade goals)
     *   2. await    — wait while the request is open or its delivery meeting (paired meet_trade goals) runs
     *   3. verify   — after delivery: objective met → DONE; otherwise retire the consumed request and post again
     * </pre>
     *
     * The request belongs to the villager ({@link OwnRequests}), not to this method: if a cheaper method (e.g.
     * farming) takes over, the request stays on the board. It is withdrawn only once the need is met some other
     * way, and only by walking to the board ({@code withdraw_request}).
     */
    static final class RequestSupply extends Owned {
        static final String ID = "request_supply";
        private static final double WAIT_EFFORT = 4.0;
        UUID request;
        UUID claim;
        /** What was posted, including "or" alternatives. */
        SupplyPolicy.Spec posted;
        AgentGoal visit;
        final VillageNoticeBoards.Reading reading = new VillageNoticeBoards.Reading();
        int high;
        public String id() { return ID; }
        /** Asking others when this villager can make the item itself (it has the skill/recipe) is a last resort. */
        private static final double OWN_PRODUCTION_PENALTY = 8.0;
        public double estimate(Context c) {
            if (!ModConfig.ENABLE_ECONOMY.get() || VillageNoticeBoards.position(c.world, c.villager) == null)
                return Double.POSITIVE_INFINITY;
            // Serving a request for this very item (or one of its alternatives): never re-post it.
            if (c.forbidsRequest(c.target)) return Double.POSITIVE_INFINITY;
            // Answering a request never starts by asking others for a work tool.
            if (c.noToolRequests && (SupplyPolicy.isToolCategory(c.requestCategory)
                    || SupplyPolicy.toolCategory(c.target) != null)) return Double.POSITIVE_INFINITY;
            // Already asked (by this method or an earlier one): waiting costs nothing new.
            if (request != null || OwnRequests.covering(c, c.world.getGameTime()) != null) return WAIT_EFFORT * 0.5;
            double cost = WAIT_EFFORT + VillageNoticeBoards.distance(c.world, c.villager) * 0.05;
            Context own = c.child(c.target, c.desired);
            own.productionOnly = true;
            // Makers prefer making: crafting (even with inputs to gather first) must score better than a request.
            if (ItemTaskCatalog.canProduce(own)) cost += OWN_PRODUCTION_PENALTY;
            return cost;
        }
        @Override public void start(Context c, UUID attempt) {
            super.start(c, attempt);
            request = null; claim = null; posted = null;
            reading.reset();
            visit = new AgentGoal("trade", "Post village request", 6);
            high = c.satisfiedUnits();
            // 0. adopt the villager's existing request for the same need instead of posting a duplicate.
            VillageSignalBoard.VillageMessage existing = OwnRequests.covering(c, c.world.getGameTime());
            if (existing != null) {
                request = existing.id; posted = existing.spec;
                OwnRequests.attach(c, request);
                OwnRequests.hold(c.agent, request, true);
                BrainLog.diagnostic(c.agent, "supply-request-adopt", "request=" + request + " spec=" + posted.key()
                        + " target=" + c.target.getRegistryName());
            }
        }
        public Update tick(Context c) {
            int have = c.satisfiedUnits();
            boolean progressed = have > high;
            high = Math.max(high, have);
            long now = c.world.getGameTime();
            // 2b/3. a booked delivery: the paired meet_trade goals do the walking and the handoff.
            if (claim == null && request != null) claim = TradeCoordinator.deliveryFor(request, c.agent.getVillagerId());
            if (claim != null) {
                if (TradeCoordinator.active(claim)) return new Update(Status.WAITING, true, false);
                VillageSignalBoard.VillageMessage live = VillageSignalBoard.get().find(request);
                if (live != null && live.state(c.world.getGameTime()) == VillageSignalBoard.RequestState.OPEN) {
                    // The meeting fell through before any handoff and the request was reopened: keep waiting on it.
                    claim = null;
                    return new Update(Status.WAITING, true, true);
                }
                if (c.satisfied()) {
                    BrainLog.diagnostic(c.agent, "supply-request-filled", "request=" + request + " units=" + have);
                    VillageSignalBoard.get().retire(request);
                    OwnRequests.hold(c.agent, request, false);
                    request = null; claim = null;
                    return new Update(Status.DONE, true, true);
                }
                if (RequestAlternatives.holdsAlternative(c, posted)) {
                    // An alternative arrived (planks for sticks): convert it ourselves instead of asking again.
                    BrainLog.diagnostic(c.agent, "supply-alternative-received", "request=" + request + " spec=" + posted);
                    VillageSignalBoard.get().retire(request);
                    OwnRequests.hold(c.agent, request, false);
                    request = null; claim = null; posted = null; reading.reset();
                    return new Update(Status.FAILED, true, true);
                }
                BrainLog.diagnostic(c.agent, "supply-request-retry", "request=" + request + " claim=" + claim
                        + " shortage=" + (c.units - have));
                VillageSignalBoard.get().retire(request);
                OwnRequests.hold(c.agent, request, false);
                request = null; claim = null; reading.reset();
                return new Update(Status.RUNNING, progressed, true);
            }
            if (c.satisfied()) return new Update(Status.DONE, true, true); // An open request is withdrawn at the board.
            // 1. post
            if (request == null) {
                SkillResult result = VillageNoticeBoards.read(c.world, c.villager, c.agent, visit, reading);
                if (result != SkillResult.SUCCESS)
                    return new Update(result == SkillResult.RUNNING ? Status.RUNNING : Status.FAILED,
                            motion.toward(c.villager, visit.getTargetLocation()) || result == SkillResult.RUNNING
                                    && VillageNoticeBoards.atBoard(c.world, c.villager), true);
                VillageSignalBoard board = VillageMarkets.board(c.world, c.agent.getVillagerId());
                if (board == null) return new Update(Status.FAILED, false, true);
                if (c.executing) ActionLifecycle.clearBody(c.agent, c.villager, c.world);
                int shortage = Math.max(1, c.units - have);
                // Exact needs list what this villager can finish itself: "2 sticks or 2 planks or 1 log".
                posted = c.requestCategory == null ? RequestAlternatives.build(c, shortage) : c.spec();
                boolean existed = false;
                for (VillageSignalBoard.VillageMessage m : VillageSignalBoard.requestsBy(c.agent.getVillagerId()))
                    if (m.spec.key().equals(posted.key()) && m.state(now) != VillageSignalBoard.RequestState.EXPIRED
                            && m.state(now) != VillageSignalBoard.RequestState.CONSUMED) existed = true;
                // A neighbour already offers it on the board: ask only for what that offer covers and claim the
                // offer at once (the requester then walks to the provider). The rest is asked for after delivery.
                VillageSignalBoard.VillageMessage offer = existed ? null
                        : CooperativeSupply.findOffer(c.world, c.agent, board, posted, shortage);
                int units = offer == null ? shortage
                        : Math.max(1, Math.min(shortage, CooperativeSupply.offerUnits(c.world, offer, posted, c.agent.getVillagerId())));
                // Villager-owned: the request outlives this method (see OwnRequests); the board de-duplicates.
                request = board.postOwnedRequest(c.agent.getVillagerId(), c.agent.getVillagerId(),
                        c.villager.blockPosition(), posted, units, now);
                OwnRequests.attach(c, request);
                OwnRequests.hold(c.agent, request, true);
                BrainLog.diagnostic(c.agent, existed ? "supply-request-merged" : "supply-request", "request=" + request
                        + " spec=" + posted + " units=" + units + " target=" + c.target.getRegistryName()
                        + (offer == null ? "" : " offer=" + offer.id));
                if (offer != null) CooperativeSupply.claimOffer(c.world, c.agent, board.find(request), offer);
                // Being at the board, also read other residents' requests.
                VillagerEconomySystem.visitMarket(c.world, c.villager, c.agent);
                return Update.waiting(true);
            }
            // 2. await. An open request is a live dependency; other methods may run meanwhile (the request stays).
            VillageSignalBoard.VillageMessage m = VillageSignalBoard.get().find(request);
            if (m == null || m.state(now) == VillageSignalBoard.RequestState.EXPIRED) {
                BrainLog.diagnostic(c.agent, "supply-request-lapsed", "request=" + request
                        + " reason=" + (m == null ? "gone" : "expired"));
                if (m != null) VillageSignalBoard.get().retire(request);
                OwnRequests.hold(c.agent, request, false);
                request = null;
                return new Update(Status.FAILED, progressed, true);
            }
            if (m.state(now) == VillageSignalBoard.RequestState.CONSUMED) {
                // Its meeting ended: verify on the next tick.
                claim = m.claim().id;
                return new Update(Status.RUNNING, progressed, true);
            }
            return new Update(Status.WAITING, true, true);
        }
        @Override public void close(Context c) {
            if (request == null && c.executing) ActionLifecycle.clearBody(c.agent, c.villager, c.world);
            // Never withdraws the request: it belongs to the villager and is reconciled by OwnRequests.
            OwnRequests.hold(c.agent, request, false);
            super.close(c);
            request = null; claim = null;
        }
    }

    static final class Craft extends Owned {
        AcquisitionPlanner.Choice quote;
        AgentGoal child;
        long progress;
        boolean legacy;
        public String id() { return "craft"; }
        public double estimate(Context c) {
            if (child != null) return 0; // In-progress production has no new purchase commitment.
            legacy = !CraftingPlan.supports(c.world, c.target);
            if (legacy) {
                ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(c.world, c.target);
                return c.unlocked() && recipe != null && NativeRecipeResolver.missingMaterialQuantities(
                        c.agent.getInventory(), recipe, Math.max(1, c.desired - c.count(c.target))).isEmpty()
                        ? 0 : Double.POSITIVE_INFINITY;
            }
            quote = AcquisitionPlanner.choose(c.agent, c.villager, c.world, c.target, c.desired, true);
            return quote == null ? Double.POSITIVE_INFINITY : quote.buyerCost;
        }
        public Update tick(Context c) {
            boolean advanced = false;
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

    /** Prerequisite acquisition and execution are shared by crafting, logging, mining, quarrying and harvesting. */
    /** Work routines an acquisition may run as its production step (see {@code Produce.workRoutineFor}). */
    static final Set<String> WORK_ROUTINES = new HashSet<>(Arrays.asList("farm", "lumberjack_work", "miner_work"));

    static final class Produce extends Owned {
        final boolean extraction;
        final boolean smelting;
        AgentGoal child;
        long progress;
        int highStock;
        String methodId = "craft_inputs";
        Produce(boolean extraction) { this(extraction, false); }
        Produce(boolean extraction, boolean smelting) {
            this.extraction = extraction; this.smelting = smelting;
            if (smelting) methodId = "smelt";
        }
        Produce bind(Context c) { contract(c); return this; }
        public String id() { return methodId; }
        TaskContract contract(Context c) {
            if (smelting) return ItemTaskCatalog.smeltContract(c);
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
                    || !extraction && !smelting && !c.unlocked()) return Double.POSITIVE_INFINITY;
            if (child != null && !"acquire_item".equals(child.getGoalType())) return 0;
            // Crops: a farmer who remembers a field farms it (travel, harvest, replant, till); ripe crops in sight are
            // not required. Other resources still need a reachable source now.
            BlockPos field = fieldFor(c);
            if (extraction && field == null && !ResourceSensor.available(c.world, c.villager, c.agent,
                    ResourceCatalog.get(c.target.getRegistryName().toString()))) return Double.POSITIVE_INFINITY;
            double furnaceCost = 0;
            if (smelting && c.furnace() == null) {
                // No furnace at all known: a smith builds one (craft it, place it beside itself). A known but
                // busy/incompatible furnace is waited for instead.
                if (!SmeltSkill.mayBuildFurnace(c.agent, c.villager, c.world)) return Double.POSITIVE_INFINITY;
                furnaceCost = 2 + (c.count(Items.FURNACE) > 0 ? 0 : ItemTaskCatalog.minimumCost(c.child(Items.FURNACE, 1)));
                if (!Double.isFinite(furnaceCost)) return Double.POSITIVE_INFINITY;
            }
            if (!extraction && !smelting) {
                ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(c.world, c.target);
                // The crafting table is handled inside crafting (CraftSkill makes and places one when needed).
                if (recipe == null) return Double.POSITIVE_INFINITY;
            }
            TaskContract requirements = contract(c);
            // This producer owns its travel/work estimate; the scheduler does not price resources.
            double cost = extraction ? 0.05 * (field != null ? Math.sqrt(field.distSqr(c.villager.blockPosition()))
                    : ResourceSensor.travelDistance(c.world, c.villager, c.agent,
                    ResourceCatalog.get(c.target.getRegistryName().toString())))
                    + 0.1 * Math.max(1, c.desired - c.count(c.target)) : smelting ? (c.furnace() == null ? furnaceCost
                    : 1 + 0.05 * SmeltSkill.approachDistance(c.villager, c.world, c.furnace())) : 0;
            for (TaskContract.ItemNeed need : requirements.items) {
                Item item = ResourceCatalog.item(need.item);
                if (item == Items.AIR || item == c.target) return Double.POSITIVE_INFINITY;
                if (c.count(item) >= need.quantity) continue;
                // Circular input (a hay bale for wheat): it may be unpacked when carried, never acquired.
                if (!extraction && !smelting && VillageProducible.onlyMadeFrom(c.world, item, c.target))
                    return Double.POSITIVE_INFINITY;
                if (child != null && need.item.equals(child.getTargetItem()))
                    cost += c.agent.brain().acquisitions.remainingCost(owner, item);
                else cost += ItemTaskCatalog.minimumCost(c.child(item, need.quantity));
            }
            // Individual barter baskets revalidate real stock; costs are subjective, not cash.
            return cost; // Each proposed barter validates the actual inventory basket.
        }
        /**
         * The work routine that produces this resource for this villager, or null: farm (crop with a remembered
         * field), lumberjack_work (logs), miner_work (ores, stone). Only routines this villager can actually run.
         */
        String workRoutineFor(Context c) {
            if (!extraction || c.villager == null) return null;
            if (fieldFor(c) != null) return "farm";
            ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
            if (source == null) return null;
            String type = source.profession == ResourceProfession.LUMBERJACK ? "lumberjack_work"
                    : source.profession == ResourceProfession.MINER ? "miner_work" : null;
            if (type == null) return null;
            AgentGoal probe = new AgentGoal(type, "probe", 6);
            return BehaviorExecutor.findHandler(c.agent, probe, c.villager, c.world) != null ? type : null;
        }
        /** True while the contract still wants what this prerequisite child is fetching. */
        private boolean needed(Context c, AgentGoal prerequisite) {
            String item = prerequisite.getTargetItem();
            if (item == null || item.isEmpty()) return true;
            for (TaskContract.ItemNeed need : contract(c).items)
                if (need.item.equals(item)) return ResourceWorkSystem.count(c.agent, need.item) < need.quantity;
            return true; // Not one of this contract's inputs: someone else's business, leave it alone.
        }
        /** Nearest remembered field when this is crop extraction by a villager able to farm; else null. */
        BlockPos fieldFor(Context c) {
            if (!extraction || c.villager == null) return null;
            ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
            if (source == null || source.capability != com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId.FARMING
                    || !source.skilled(c.agent)) return null;
            return FarmMemory.nearestField(c.agent, c.villager.blockPosition());
        }
        public Update tick(Context c) {
            if (!ModConfig.ENABLE_WORLD_INTERACTION.get()
                    || extraction && !ResourceCatalog.get(c.target.getRegistryName().toString()).skilled(c.agent)
                    || !extraction && !smelting && !c.unlocked()) return new Update(Status.FAILED, false, true);
            // A prerequisite that arrived some other way — a delivery, a gift, the player — makes this child
            // moot. Without this the method keeps descending into an input it no longer needs (waiting for a
            // log it has long since turned into sticks) and never reaches the production step.
            if (child != null && "acquire_item".equals(child.getGoalType()) && !needed(c, child)) {
                BrainLog.diagnostic(c.agent, "acquire-prerequisite-met", "item=" + child.getTargetItem()
                        + " target=" + c.target.getRegistryName());
                c.discardChild();
                child = null;
            }
            if (child == null) {
                TaskContract requirements = contract(c);
                for (TaskContract.ItemNeed need : requirements.items) {
                    if (ResourceWorkSystem.count(c.agent, need.item) >= need.quantity) continue;
                    child = new AgentGoal("acquire_item", "Acquire prerequisite " + need.item, 6);
                    child.setTargetItem(need.item); child.setTargetQuantity(need.quantity);
                    if (extraction) {
                        // A missing work tool is a generic requirement: any suitable tier satisfies it.
                        ResourceCatalog.Source source = ResourceCatalog.get(c.target.getRegistryName().toString());
                        if (source != null && source.tool != Items.AIR
                                && need.item.equals(source.tool.getRegistryName().toString()))
                            child.setRequestCategory(SupplyPolicy.toolCategory(source.tool));
                    }
                    break;
                }
                if (child == null) {
                    // Resources come from the villager's own work routine when it has one: crops from a remembered
                    // field run a farm session, logs a lumberjack session, ores a mining session. Anything else
                    // is extracted locally.
                    String routine = extraction ? workRoutineFor(c) : null;
                    String type = routine != null ? routine : extraction ? "extract_item" : smelting ? "smelt" : "craft";
                    child = new AgentGoal(type, routine != null ? "Work session for " + c.target.getRegistryName()
                            : "Produce " + c.target.getRegistryName(), 6);
                    child.setTargetItem(c.target.getRegistryName().toString());
                    child.setTargetQuantity(extraction ? c.desired : Math.max(1, c.desired - c.count(c.target)));
                    if (routine != null) BrainLog.diagnostic(c.agent, "acquire-redirect", "item=" + c.target.getRegistryName()
                            + " routine=" + routine + " desired=" + c.desired + " have=" + c.count(c.target));
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
            if (result == SkillResult.WAITING && WORK_ROUTINES.contains(child.getGoalType())) {
                // The work session found nothing (more) to do: the method has ended; the parent re-checks stock.
                BrainLog.diagnostic(c.agent, "acquire-redirect-end", "item=" + c.target.getRegistryName()
                        + " routine=" + child.getGoalType() + " have=" + c.count(c.target) + "/" + c.desired);
                child = null;
                return new Update(Status.DONE, advanced, true);
            }
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
