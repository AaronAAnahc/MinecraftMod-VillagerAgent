package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/**
 * Free, demand-driven village cooperation. Requests live on the village board; responders read them
 * physically, deliver shareable stock, or create non-exclusive request-linked production. Only an
 * imminent delivery reserves demand. No price, payment or affordability check is involved.
 */
public final class CooperativeSupply {
    public static final String RESPONSE_GOAL = "supply_request";
    private static final int MAX_DELIVERY_STACKS = 9;
    private static final int MAX_NEW_RESPONSES_PER_VISIT = 2;
    /** Time for the provider to walk over and for the requester to finish its task and join (4 minutes). */
    private static final long DELIVERY_WINDOW_TICKS = 4800;
    private static final long RESPONSE_LIFETIME_TICKS = 24000;

    /** Bounded delivery reservation against one request and one provider's concrete stacks. */
    public static final class Reservation {
        public final UUID id = UUID.randomUUID();
        public final UUID request, provider, requester;
        public final List<ItemStack> stacks;
        /** Request-unit credit reserved; the final food item may exceed it. */
        public final int units;
        public int receivedUnits;
        boolean thrown, released;
        boolean released() { return released; }
        Reservation(UUID request, UUID provider, UUID requester, List<ItemStack> stacks, int units) {
            this.request = request; this.provider = provider; this.requester = requester;
            this.stacks = Collections.unmodifiableList(stacks); this.units = units;
        }
        public boolean thrown() { return thrown; }
    }


    private static final Map<UUID, Reservation> OPEN = new LinkedHashMap<>();

    public static boolean active(VillageSignalBoard.VillageMessage m, long now) {
        return m != null && m.isRequest() && !m.resolved && m.expireTick > now && m.quantity > 0;
    }

    // ── Tool retention and shareable stock ────────────────────────────────────

    /** Tool categories the profession actually works with, derived from its configured essentials. */
    public static Set<String> requiredToolCategories(VillagerAgentData agent) {
        Set<String> categories = new LinkedHashSet<>();
        for (String id : VillagerEconomySystem.essentialItems(agent.getProfession())) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Item item = rl == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            String category = SupplyPolicy.toolCategory(item);
            if (category != null) categories.add(category);
        }
        // The hobby tool is kept too, so a hobby lumberjack/miner/farmer never gives its only tool away.
        Item hobby = agent.hobbyTool();
        if (hobby != null) categories.add(SupplyPolicy.toolCategory(hobby));
        return categories;
    }

    /** Exactly one best actual tool stack per required category; recomputed from live inventory. */
    public static Set<Integer> protectedToolSlots(VillagerAgentData agent) {
        Set<Integer> slots = new HashSet<>();
        List<ItemStack> items = agent.getInventory().getItems();
        for (String category : requiredToolCategories(agent)) {
            int best = -1;
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty() || !category.equals(SupplyPolicy.toolCategory(stack.getItem()))) continue;
                if (best < 0 || SupplyPolicy.compareTools(stack, i, items.get(best), best) > 0) best = i;
            }
            if (best >= 0) slots.add(best);
        }
        return slots;
    }

    /**
     * Temporary commitments: own active acquisition/production targets (other than the request being
     * served) and outgoing reservations not yet thrown. Separate from permanent tool protection.
     */
    private static Map<Item, Integer> committed(VillagerAgentData agent, UUID serving, Reservation exclude) {
        Map<Item, Integer> result = new HashMap<>();
        for (AgentGoal g : agent.getGoals()) {
            if (g.isCompleted() || g.getTargetItem() == null || g.getTargetQuantity() <= 0) continue;
            String type = g.getGoalType();
            if (RESPONSE_GOAL.equals(type)) continue; // Non-exclusive production output is shareable.
            if (!"acquire_item".equals(type) && !"craft".equals(type) && !"smelt".equals(type)
                    && !"extract_item".equals(type)) continue;
            ResourceLocation rl = ResourceLocation.tryParse(g.getTargetItem());
            Item item = rl == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != Items.AIR) result.merge(item, g.getTargetQuantity(), Math::max);
        }
        for (Reservation r : OPEN.values()) {
            if (r == exclude || r.thrown || r.released || !r.provider.equals(agent.getVillagerId())) continue;
            for (ItemStack s : r.stacks) result.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        for (Map.Entry<String, Integer> e : VillagerEconomySystem.nonToolReserves(agent).entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            Item item = rl == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != Items.AIR) result.merge(item, e.getValue(), Integer::sum);
        }
        return result;
    }

    /** Matching stacks this villager may give away now. Item NBT and damage are preserved. */
    public static List<ItemStack> shareable(VillagerAgentData agent, SupplyPolicy.Spec spec, UUID serving) {
        return shareable(agent, spec, serving, null);
    }

    /**
     * Everything this villager can spare as a gift right now: the same rule as supplying a request (the working
     * tool of each profession category, active acquisition targets, profession reserves and stock promised to a
     * delivery stay), for any item.
     */
    public static List<ItemStack> giftable(VillagerAgentData agent) {
        return shareable(agent, s -> true, null, null);
    }

    /** Giftable units of one item. */
    public static int giftable(VillagerAgentData agent, Item item) {
        int n = 0;
        for (ItemStack s : giftable(agent)) if (s.getItem() == item) n += s.getCount();
        return n;
    }

    private static List<ItemStack> shareable(VillagerAgentData agent, SupplyPolicy.Spec spec, UUID serving, Reservation exclude) {
        return shareable(agent, spec::matches, serving, exclude);
    }

    private static List<ItemStack> shareable(VillagerAgentData agent, java.util.function.Predicate<ItemStack> wanted,
                                             UUID serving, Reservation exclude) {
        List<ItemStack> items = agent.getInventory().getItems();
        Set<Integer> protectedSlots = protectedToolSlots(agent);
        Map<Item, Integer> hold = committed(agent, serving, exclude);
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || protectedSlots.contains(i) || !wanted.test(stack)) continue;
            int held = hold.getOrDefault(stack.getItem(), 0);
            int give = stack.getCount() - held;
            hold.put(stack.getItem(), Math.max(0, held - stack.getCount()));
            if (give <= 0) continue;
            ItemStack copy = stack.copy(); copy.setCount(give);
            result.add(copy);
        }
        return result;
    }

    // ── Board observation and response ───────────────────────────────────────

    /** True when a provider other than {@code provider} claimed this request. */
    public static boolean claimedByOther(VillageSignalBoard.VillageMessage req, UUID provider) {
        return req.claim != null && !req.claim.provider.equals(provider);
    }

    /**
     * Compound task {@code check_board}, run after standing at the board: refresh this villager's board
     * memory, drop its own unclaimed responses whose request is no longer open to it, and add responses for
     * open requests it can fill from stock or produce. It never claims; claiming belongs to
     * {@code supply_request}, which reads the board again before committing.
     */
    public static void inspect(ServerWorld world, VillagerEntity villager, VillagerAgentData agent, VillageSignalBoard board) {
        long now = world.getGameTime();
        BoardMemory memory = agent.brain().boardMemory;
        memory.observe(board, now);
        reconcile(agent, memory);
        int created = 0;
        for (BoardMemory.Entry e : memory.entries()) {
            if (created >= MAX_NEW_RESPONSES_PER_VISIT || agent.getGoals().size() >= 10) break;
            if (!e.open() || e.author.equals(agent.getVillagerId()) || hasResponse(agent, e.id)) continue;
            SupplyPolicy.Spec spec = SupplyPolicy.Spec.parse(e.spec);
            if (spec == null) continue;
            // Stock that already covers the whole request needs no production; otherwise pick a producible output.
            List<ItemStack> stock = shareable(agent, spec, e.id);
            // Stock is checked alternative by alternative (sticks, then planks, then logs); otherwise the first
            // alternative this villager can make without asking the board for any of them.
            List<ItemStack> covering = SupplyPolicy.select(spec, stock, e.quantity, MAX_DELIVERY_STACKS);
            Item output = !covering.isEmpty() && SupplyPolicy.units(spec, covering) >= e.quantity
                    ? covering.get(0).getItem() : chooseOutput(world, villager, agent, spec, e.quantity);
            if (output == null) continue;
            AgentGoal goal = new AgentGoal(RESPONSE_GOAL, "Supply village request " + e.spec, 6);
            goal.setTradeMessageId(e.id);
            goal.setRequestSpec(e.spec);
            goal.setRequestUnits(e.quantity);
            goal.setTargetItem(output.getRegistryName().toString());
            int have = agent.getInventory().countItem(new ItemStack(output));
            // Plan enough output to satisfy the whole request.
            int need = Math.max(1, SupplyPolicy.itemsToCover(spec, output, e.quantity));
            goal.setTargetQuantity(have + need);
            goal.setLifecycle(AgentGoal.Lifecycle.EXPIRING);
            goal.setDeadlineTick(now + RESPONSE_LIFETIME_TICKS);
            if (agent.getGoals().add(goal)) {
                created++;
                BrainLog.diagnostic(agent, "supply-response", "request=" + e.id + " spec=" + e.spec
                        + " output=" + output.getRegistryName() + " planned=" + need);
            }
        }
    }

    /** Drop own unclaimed responses whose request is gone, closed or claimed by another, per board memory. */
    private static void reconcile(VillagerAgentData agent, BoardMemory memory) {
        AgentGoal running = agent.getRunningGoal();
        for (AgentGoal g : new ArrayList<>(agent.getGoals())) {
            // A response that already claimed its request is delivering; it ends with its meeting.
            if (g == running || g.isCompleted() || !RESPONSE_GOAL.equals(g.getGoalType()) || g.getClaimId() != null) continue;
            String reason = invalidReason(memory.get(g.getTradeMessageId()), agent.getVillagerId());
            if (reason == null) continue;
            agent.getGoals().remove(g);
            BrainLog.diagnostic(agent, "supply-cancel", "request=" + g.getTradeMessageId() + " reason=" + reason);
        }
    }

    /** Why a remembered request may no longer be pursued by {@code provider}; null while it is open. */
    public static String invalidReason(BoardMemory.Entry e, UUID provider) {
        if (e == null) return "request_gone";
        switch (e.state) {
            case OPEN: return null;
            case EXPIRED: return "request_expired";
            default: return provider.equals(e.claimer) ? null : "request_claimed";
        }
    }

    public static boolean hasResponse(VillagerAgentData agent, UUID request) {
        for (AgentGoal g : agent.getGoals())
            if (!g.isCompleted() && RESPONSE_GOAL.equals(g.getGoalType()) && request.equals(g.getTradeMessageId())) return true;
        return false;
    }

    /**
     * A concrete output backed by registered methods and capabilities, never by profession label alone, that
     * this villager can make without posting the same request again: no input path may ask the board for the
     * request's item or any of its alternatives (a smith holding no logs cannot answer "sticks or planks or
     * logs" — its only route would be a log request). Inputs of a different item (iron and sticks for a
     * requested pickaxe) may still be requested.
     */
    public static Item chooseOutput(ServerWorld world, VillagerEntity villager, VillagerAgentData agent,
                                    SupplyPolicy.Spec spec, int units) {
        Set<Item> forbidden = RequestAlternatives.forbidden(spec);
        for (Item item : SupplyPolicy.candidateOutputs(spec)) {
            int quantity = Math.max(1, SupplyPolicy.itemsToCover(spec, item, units));
            AcquisitionMethods.Context context = new AcquisitionMethods.Context(agent, villager, world, item,
                    agent.getInventory().countItem(new ItemStack(item)) + quantity, false);
            context.productionOnly = true;
            context.noToolRequests = true;
            Set<Item> blocked = new HashSet<>(forbidden);
            blocked.add(item); // A category request's chosen output is never re-requested either.
            context.forbiddenRequests = blocked;
            if (!ItemTaskCatalog.canProduce(context)) continue;
            double cost = ItemTaskCatalog.minimumCost(context);
            if (Double.isFinite(cost)) return item;
            BrainLog.diagnostic(agent, "supply-skip", "spec=" + spec + " output=" + item.getRegistryName()
                    + " reason=only_by_requesting_it_or_a_tool");
        }
        return null;
    }

    // ── Delivery reservation and accounting ──────────────────────────────────

    /**
     * Atomically reserve a bounded quantity against both the request's remaining demand and the
     * provider's shareable stock, then arrange a meeting. Returns false without side effects on failure.
     */
    /**
     * The claim transition OPEN -> CLAIMED, made by a provider standing at the board. Requires shareable stock
     * covering the whole request and receiving capacity, then books the joint meeting: both participants get a
     * {@code meet_trade} goal. On any failure the request is left exactly as posted.
     */
    public static boolean startDelivery(ServerWorld world, VillagerAgentData provider,
                                        VillageSignalBoard.VillageMessage req) {
        return book(world, provider, req, false);
    }

    /**
     * Shared claim/booking for both directions. {@code requesterWalks}: the requester claimed a provider's
     * offer and walks to the provider (the provider is invited when it arrives); otherwise the provider claimed
     * the request and walks to the requester.
     */
    private static boolean book(ServerWorld world, VillagerAgentData provider,
                                VillageSignalBoard.VillageMessage req, boolean requesterWalks) {
        long now = world.getGameTime();
        if (req == null || !req.isRequest() || req.state(now) != VillageSignalBoard.RequestState.OPEN
                || req.author.equals(provider.getVillagerId())) return skip(provider, req, "not_open");
        if (TradeCoordinator.busy(provider.getVillagerId())) return skip(provider, req, "provider_booked");
        if (TradeCoordinator.busy(req.author)) return skip(provider, req, "requester_booked");
        if (!VillageMarkets.same(world, provider.getVillagerId(), req.author)
                || req.market != VillageMarkets.board(world, req.author)) return skip(provider, req, "different_market");
        VillagerAgentData requester = VillagerAgentManager.getAgent(req.author);
        if (requester == null) return skip(provider, req, "requester_missing");
        if (requester.isSocializing() || provider.isSocializing()) return skip(provider, req, "socializing");
        // A meeting booked at bedtime would be cancelled at once by sleep; claim it in the morning instead.
        if (GoalScheduler.curfewNight(requester) || GoalScheduler.curfewNight(provider)) return skip(provider, req, "curfew");
        net.minecraft.entity.Entity requesterEntity = world.getEntity(req.author);
        if (!(requesterEntity instanceof VillagerEntity)) return skip(provider, req, "requester_not_loaded");
        if (((VillagerEntity) requesterEntity).isSleeping()) return skip(provider, req, "requester_sleeping");
        net.minecraft.entity.Entity providerEntity = world.getEntity(provider.getVillagerId());
        if (!(providerEntity instanceof VillagerEntity)) return skip(provider, req, "provider_not_loaded");
        if (((VillagerEntity) providerEntity).isSleeping()) return skip(provider, req, "provider_sleeping");
        int units = req.quantity;
        if (units <= 0) return skip(provider, req, "no_units");
        List<ItemStack> goods = SupplyPolicy.select(req.spec, shareable(provider, req.spec, req.id), units, MAX_DELIVERY_STACKS);
        int offered = SupplyPolicy.units(req.spec, goods);
        // Food may exceed the hunger requirement on the final item; it may never fall short of it.
        if (goods.isEmpty() || offered < units) return skip(provider, req, "stock_short offered=" + offered + "/" + units);
        if (transferPlan(requester, provider, goods) == null) return skip(provider, req, "requester_inventory_full");
        Reservation r = new Reservation(req.id, provider.getVillagerId(), req.author, goods, units);
        long deadline = now + DELIVERY_WINDOW_TICKS;
        if (!VillageSignalBoard.APPOINTMENTS.reserve(r.id, r.requester, r.provider, deadline))
            return skip(provider, req, "appointment_unavailable");
        req.claim = r;
        OPEN.put(r.id, r);
        if (!TradeCoordinator.enqueueDelivery(world, r, deadline, requesterWalks)) {
            // CLAIMED -> OPEN: the meeting could not be booked, so the claim never took effect.
            release(r); req.claim = null; return false;
        }
        BrainLog.diagnostic(provider, requesterWalks ? "offer-claimed-by" : "supply-claim", "request=" + req.id
                + " session=" + r.id + " requester=" + r.requester + " units=" + r.units + " stacks=" + goods);
        return true;
    }

    // ── Offers ("for sale" notices) claimed by a requester ───────────────────

    /**
     * Request units of {@code spec} the offer's author can hand over from the offered item right now (its live
     * shareable stock, not the possibly stale number on the notice); 0 when the offer does not fit.
     */
    public static int offerUnits(ServerWorld world, VillageSignalBoard.VillageMessage offer, SupplyPolicy.Spec spec,
                                 UUID requester) {
        long now = world.getGameTime();
        if (offer == null || offer.type != VillageSignalBoard.MsgType.TRADE_HAVE || offer.resolved
                || offer.expireTick <= now || offer.item == null || offer.quantity <= 0
                || offer.author.equals(requester)) return 0;
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(offer.item);
        if (item == null || item == Items.AIR || !spec.matches(new ItemStack(item))) return 0;
        VillagerAgentData provider = VillagerAgentManager.getAgent(offer.author);
        if (provider == null || TradeCoordinator.busy(offer.author) || !VillageMarkets.same(world, offer.author, requester)) return 0;
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack s : shareable(provider, spec, null)) if (s.getItem() == item) stock.add(s);
        return Math.min(offer.quantity, SupplyPolicy.units(spec, stock));
    }

    /** The best open offer for {@code spec}: one covering the whole shortage if any, else the largest; null if none. */
    public static VillageSignalBoard.VillageMessage findOffer(ServerWorld world, VillagerAgentData requester,
                                                              VillageSignalBoard board, SupplyPolicy.Spec spec, int shortage) {
        if (board == null || spec == null || TradeCoordinator.busy(requester.getVillagerId())) return null;
        VillageSignalBoard.VillageMessage best = null;
        int bestUnits = 0;
        for (VillageSignalBoard.VillageMessage m : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE)) {
            int units = Math.min(shortage, offerUnits(world, m, spec, requester.getVillagerId()));
            if (units > bestUnits) { best = m; bestUnits = units; }
        }
        return best;
    }

    /**
     * The requester claims (part of) an offer for its own open request {@code req}: the provider's stock is
     * reserved exactly as for a claimed request (so the provider neither re-offers it nor uses it for another
     * request), the notice's quantity is reduced, and the meeting is booked with the requester walking to the
     * provider. {@code req.quantity} must not exceed what the offer can cover.
     */
    public static boolean claimOffer(ServerWorld world, VillagerAgentData requester,
                                     VillageSignalBoard.VillageMessage req, VillageSignalBoard.VillageMessage offer) {
        if (req == null || offer == null || !req.isRequest() || !req.author.equals(requester.getVillagerId())) return false;
        VillagerAgentData provider = VillagerAgentManager.getAgent(offer.author);
        if (provider == null) return false;
        int units = offerUnits(world, offer, req.spec, requester.getVillagerId());
        if (units < req.quantity) {
            BrainLog.diagnostic(requester, "offer-claim-skip", "offer=" + offer.id + " request=" + req.id
                    + " reason=offer_short " + units + "/" + req.quantity);
            return false;
        }
        if (!book(world, provider, req, true)) return false;
        offer.quantity -= req.quantity;
        offer.responders.add(requester.getVillagerId());
        if (offer.quantity <= 0) offer.resolved = true;
        BrainLog.diagnostic(requester, "offer-claim", "offer=" + offer.id + " provider=" + offer.author
                + " item=" + offer.item + " units=" + req.quantity + " request=" + req.id + " left=" + Math.max(0, offer.quantity));
        return true;
    }

    /**
     * At the board: every open, unclaimed request of this villager that a neighbour's offer can fill completely is
     * claimed against that offer (a request posted before the offer appeared).
     */
    public static void claimOffersForOwnRequests(ServerWorld world, VillagerAgentData requester, VillageSignalBoard board) {
        long now = world.getGameTime();
        for (VillageSignalBoard.VillageMessage req : VillageSignalBoard.requestsBy(requester.getVillagerId())) {
            if (req.market != board || req.state(now) != VillageSignalBoard.RequestState.OPEN) continue;
            if (TradeCoordinator.busy(requester.getVillagerId())) return;
            VillageSignalBoard.VillageMessage offer = findOffer(world, requester, board, req.spec, req.quantity);
            if (offer != null && offerUnits(world, offer, req.spec, requester.getVillagerId()) >= req.quantity)
                claimOffer(world, requester, req, offer);
        }
    }


    private static boolean skip(VillagerAgentData provider, VillageSignalBoard.VillageMessage req, String reason) {
        BrainLog.diagnostic(provider, "supply-claim-skip", "request=" + (req == null ? null : req.id) + " reason=" + reason);
        return false;
    }

    /** One-way plan on copies: debit provider, insert into requester. Null when stock or space is missing. */
    private static TradeInventory.Plan transferPlan(VillagerAgentData requester, VillagerAgentData provider, List<ItemStack> goods) {
        return TradeInventory.plan(requester.getInventory().getItems(), provider.getInventory().getItems(),
                Collections.emptyList(), new ItemStack[0], copies(goods));
    }

    public static ItemStack[] copies(List<ItemStack> goods) {
        ItemStack[] result = new ItemStack[goods.size()];
        for (int i = 0; i < result.length; i++) result[i] = goods.get(i).copy();
        return result;
    }

    /** Revalidate request, protected tools, shareable stock and receiving capacity before throwing. */
    public static boolean revalidate(ServerWorld world, Reservation r) {
        if (r == null || r.released) return false;
        VillageSignalBoard.VillageMessage req = VillageSignalBoard.get().find(r.request);
        if (req == null || req.claim != r) return false;
        VillagerAgentData provider = VillagerAgentManager.getAgent(r.provider);
        VillagerAgentData requester = VillagerAgentManager.getAgent(r.requester);
        if (provider == null || requester == null) return false;
        List<ItemStack> available = shareable(provider, req.spec, req.id, r);
        for (ItemStack wanted : r.stacks) {
            int left = wanted.getCount();
            for (ItemStack s : available) {
                if (!TradeInventory.matches(s, wanted)) continue;
                int n = Math.min(left, s.getCount()); s.shrink(n); left -= n;
                if (left == 0) break;
            }
            if (left > 0) return false;
        }
        return transferPlan(requester, provider, r.stacks) != null;
    }

    /** Outgoing stock has been debited and is physically on the ground. */
    public static void markThrown(Reservation r) { if (r != null) r.thrown = true; }

    /** Record what the requester actually picked up (diagnostics only; the request itself is never edited). */
    public static void credit(Reservation r, ItemStack type, int count) {
        if (r == null || count <= 0) return;
        VillageSignalBoard.VillageMessage req = VillageSignalBoard.get().find(r.request);
        int units = req == null ? count : req.spec.units(type, count);
        r.receivedUnits += units;
    }

    /**
     * End a claim's delivery exactly once. The claimed request stays consumed for its owner; the requester's
     * acquisition decides from its real inventory whether another request is needed.
     */
    public static void release(Reservation r) {
        if (r == null || r.released) return;
        r.released = true;
        OPEN.remove(r.id);
        VillageSignalBoard.APPOINTMENTS.release(r.id);
        VillagerAgentData provider = VillagerAgentManager.getAgent(r.provider);
        if (provider != null) BrainLog.diagnostic(provider, "supply-delivery-end", "request=" + r.request
                + " reservation=" + r.id + " thrown=" + r.thrown + " received=" + r.receivedUnits + "/" + r.units);
    }

    private CooperativeSupply() {}
}
