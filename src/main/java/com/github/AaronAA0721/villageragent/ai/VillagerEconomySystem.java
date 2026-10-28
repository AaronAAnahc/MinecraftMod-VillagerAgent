package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM-free village economy.
 *
 * <p>Each villager broadcasts supply/demand onto the shared {@link VillageSignalBoard} (a pure-Java
 * message pipe): surplus of a profession-essential above its minimum becomes a {@code TRADE_HAVE},
 * a deficit becomes a {@code TRADE_WANT}. The system pairs wants with haves, then makes both
 * villagers walk to each other and exchange items on contact — an emergent "villager goes to trade"
 * behaviour that needs no LLM. Player↔villager barter uses the same value table (see
 * {@code TradeRequestPacket}).
 */
public final class VillagerEconomySystem {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    // ── Loaded content data (item_values / profession_essentials / item_sources) ──
    /** profession -> (item -> buy/sell price). Only items the profession actually trades appear here. */
    private static final Map<String, Map<String, Price>> ITEM_VALUES = new HashMap<>();
    private static final Map<String, List<Essential>> PROFESSION_ESSENTIALS = new HashMap<>();
    private static final Map<String, String> ITEM_SOURCES = new HashMap<>();
    private static boolean loaded = false;

    /** A single profession-essential entry: an item the villager must keep at least {@code minQty} of. */
    public static final class Essential {
        public String item;   // registry name, e.g. "minecraft:wooden_hoe"
        public int minQty;
    }

    /**
     * Per-profession buy/sell price of one item (anchor: 1 emerald = 10.0).
     * {@code buy} is what this profession pays to acquire one unit, {@code sell} what it charges
     * to part with one unit; sell > buy except for emerald itself (currency trades at par).
     */
    public static final class Price {
        public double buy;
        public double sell;
    }

    private static final java.util.Map<ServerWorld, Long> lastScanTicks = new java.util.WeakHashMap<>();

    private VillagerEconomySystem() {}

    // ─────────────────────────────────────────────────────────────────────────────
    //  Public tick entry
    // ─────────────────────────────────────────────────────────────────────────────

    public static void tickEconomy(ServerWorld world) {
        TradeCoordinator.tick(world);
        if (!ModConfig.ENABLE_ECONOMY.get()) return;
        ensureLoaded();

        long now = world.getGameTime();
        List<VillageMarkets.Market> markets = VillageMarkets.loaded(world);
        for (VillageMarkets.Market market : markets) {
            market.board.clearExpiredMessages(now);
            runMatchedExchanges(world, market.board);
        }

        if (now - lastScanTicks.getOrDefault(world, -10000L) < ModConfig.ECONOMY_TICK_INTERVAL.get()) return;
        lastScanTicks.put(world, now);
        for (VillageMarkets.Market market : markets) runScan(world, market, now);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Scan: post supply/demand, then match
    // ─────────────────────────────────────────────────────────────────────────────

    private static void runScan(ServerWorld world, VillageMarkets.Market market, long now) {
        VillageSignalBoard board = market.board;
        for (VillagerAgentData agent : market.members()) {
            if (agent == null) continue;
            VillagerEntity villager = entityOf(world, agent);
            if (villager == null || !villager.isAlive()) continue;
            advertiseSurplus(world, villager, agent);

            List<Essential> essentials = getEssentials(agent.getProfession());
            if (essentials == null) continue;

            for (Essential e : essentials) {
                ItemStack stack = new ItemStack(registryItem(e.item), 1);
                int count = agent.getInventory().countItem(stack);
                if (count > e.minQty) {
                    postOffer(world, villager, agent, rl(e.item), count - e.minQty);
                } else if (count < e.minQty) {
                    boolean queued = agent.getGoals().stream().anyMatch(g -> !g.isCompleted()
                            && "acquire_item".equals(g.getGoalType()) && e.item.equals(g.getTargetItem()));
                    if (!queued && agent.getGoals().size() < 10) {
                        AgentGoal goal = new AgentGoal("acquire_item", "Acquire essential " + e.item, 6);
                        goal.setTargetItem(e.item); goal.setTargetQuantity(e.minQty);
                        goal.setOutcomeItems(java.util.Collections.singleton(e.item));
                        goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
                        agent.getGoals().add(goal);
                    }
                }
            }
        }
        offerDemandProduction(world, board, now);
        runMatching(world, board);
    }

    /** Turn unmet demand into production opportunities and advertise non-essential surplus too. */
    private static void offerDemandProduction(ServerWorld world, VillageSignalBoard board, long now) {
        for (VillageSignalBoard.VillageMessage want : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT)) {
            if (want.resolved || want.item == null || want.matchedWith != null || want.ownerAttempt != null) continue;
            VillagerAgentData requester = VillagerAgentManager.getAgent(want.author);
            VillagerEntity customer = entityOf(world, requester);
            if (customer == null) continue;
            String itemId = want.item.toString();
            int stock = requester.getInventory().countItem(new ItemStack(registryItem(itemId)));
            boolean pendingExtraction = false;
            for (com.github.AaronAA0721.villageragent.ai.work.WorkOrderBook.Order o :
                    com.github.AaronAA0721.villageragent.ai.work.WorkOrderSavedData.get(world).book.all()) {
                if (o.active() && o.requester.equals(want.author) && o.item.equals(itemId)) { pendingExtraction = true; break; }
            }
            if (!pendingExtraction) com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, requester, itemId, stock + want.quantity);
            boolean productionQueued = false;
            for (VillagerAgentData producer : VillageMarkets.residents(world, want.author)) {
                if (producer == requester) continue;
                VillagerEntity worker = entityOf(world, producer);
                if (worker == null || worker.distanceToSqr(customer) > 4096) continue;
                int available = producer.getInventory().countItem(new ItemStack(registryItem(itemId)));
                java.util.List<Essential> reserves = getEssentials(producer.getProfession());
                if (reserves != null) for (Essential reserve : reserves)
                    if (itemId.equals(reserve.item)) available = Math.max(0, available - reserve.minQty);
                if (available > 0) {
                    postOffer(world, worker, producer, want.item, available);
                }
                if (available >= want.quantity || want.matchedWith != null || productionQueued) continue;
                for (AgentGoal g : producer.getGoals()) {
                    if (!g.isCompleted() && "craft".equals(g.getGoalType()) && itemId.equals(g.getTargetItem())) {
                        productionQueued = true; break;
                    }
                }
                if (productionQueued || producer.getGoals().size() >= 10
                        || !SkillCraftCatalog.isUnlockedFor(producer.getCapabilityPool(), producer.getProfession(), itemId)) continue;
                ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, itemId);
                if (recipe == null) continue;
                int output = recipe.kind == ResolvedRecipe.Kind.VANILLA ? recipe.vanilla.getResultItem().getCount() : recipe.custom.outputCount;
                AgentGoal craft = new AgentGoal("craft", "Produce requested " + itemId, 6);
                craft.setTargetItem(itemId); craft.setTargetQuantity(Math.max(1, (want.quantity + Math.max(1, output) - 1) / Math.max(1, output)));
                craft.setLifecycle(AgentGoal.Lifecycle.EXPIRING); craft.setDeadlineTick(now + 2400);
                producer.getGoals().add(craft); productionQueued = true;
                BrainLog.goalAdded(producer, craft, "material-demand:production");
            }
        }
    }

    private static void runMatching(ServerWorld world, VillageSignalBoard board) {
        List<VillageSignalBoard.VillageMessage> wants = board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT);
        List<VillageSignalBoard.VillageMessage> haves = board.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE);
        for (VillageSignalBoard.VillageMessage want : wants) {
            if (want.matchedWith != null || want.resolved || want.ownerAttempt != null) continue;
            if (TradeCoordinator.busy(want.author) || want.unitPrice <= 0) continue;
            VillagerEntity buyer = entityOf(world, VillagerAgentManager.getAgent(want.author));
            if (buyer == null) continue;
            VillageSignalBoard.VillageMessage best = null;
            for (VillageSignalBoard.VillageMessage have : haves) {
                if (have.matchedWith != null || have.resolved || TradeCoordinator.busy(have.author)
                        || have.unitPrice <= 0 || have.unitPrice > want.unitPrice) continue;
                VillagerEntity seller = entityOf(world, VillagerAgentManager.getAgent(have.author));
                if (seller == null || buyer.distanceToSqr(seller) > 4096) continue;
                if (want.item.equals(have.item) && have.quantity >= want.quantity
                        && !want.author.equals(have.author)
                        && quoteCost(want,want.quantity)>0
                        && TradeInventory.count(VillagerAgentManager.getAgent(want.author).getInventory().getItems(),
                            new ItemStack(Items.EMERALD))>=quoteCost(want,want.quantity)) {
                    if (best == null || have.unitPrice < best.unitPrice) best = have;
                }
            }
            if (best != null) acceptTrade(world, want, best, want.unitPrice);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Walk-to-trade + exchange
    // ─────────────────────────────────────────────────────────────────────────────

    private static void runMatchedExchanges(ServerWorld world, VillageSignalBoard board) {
        for (VillageSignalBoard.VillageMessage want : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT)) {
            if (want.matchedWith == null || want.resolved) continue;
            VillageSignalBoard.VillageMessage have = board.find(want.matchedWith);
            if (have == null || have.resolved) continue;
            if (!validateTradeFunds(want, have)) continue;
            if (!TradeCoordinator.enqueue(world, want, have)) board.unmatch(want.id);
        }
    }

    public static int completeMatchedExchange(ServerWorld world, java.util.UUID wantId) {
        if (!TradeCoordinator.canSettle(wantId)) return 0;
        VillageSignalBoard board = VillageSignalBoard.get();
        VillageSignalBoard.VillageMessage want = board.find(wantId);
        if (want == null || want.resolved || want.matchedWith == null) return 0;
        VillageSignalBoard.VillageMessage have = board.find(want.matchedWith);
        if (have == null || have.resolved) return 0;
        VillagerAgentData buyer = VillagerAgentManager.getAgent(want.author), seller = VillagerAgentManager.getAgent(have.author);
        VillagerEntity b = entityOf(world, buyer), s = entityOf(world, seller);
        if (b == null || s == null || b.distanceToSqr(s) > 9) return 0;
        if (!validateTradeFunds(want, have)) return 0;
        int moved = want.quantity;
        int payment = want.agreedPayment;
        Item goods = registryItem(want.item.toString());
        if (goods == Items.AIR) return 0;
        TradeInventory.Plan plan = TradeInventory.plan(buyer.getInventory().getItems(),
                seller.getInventory().getItems(), java.util.Collections.emptyList(),
                tradeStacks(Items.EMERALD, payment), tradeStacks(goods, moved));
        if (plan == null) return 0;
        for (int i = 0; i < plan.player.size(); i++) buyer.getInventory().setStackInSlot(i, plan.player.get(i));
        for (int i = 0; i < plan.villager.size(); i++) seller.getInventory().setStackInSlot(i, plan.villager.get(i));
        want.quantity -= moved;
        if (want.quantity <= 0) board.resolveTrade(want.id);
        else board.unmatch(want.id);
        return moved;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Item transfer helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /** Move up to {@code qty} of {@code regName} from {@code from} to {@code to}; returns amount moved. */
    private static int transfer(VillagerAgentData from, VillagerAgentData to, String regName, int qty) {
        Item item = registryItem(regName);
        if (item == Items.AIR) return 0;
        return InventoryTransfer.move(from.getInventory(), to.getInventory(), item, qty);
    }

    public static int emeraldCost(VillagerAgentData seller, String item, int quantity) {
        if (seller == null || quantity <= 0) return -1;
        double value = sellValue(seller, item) * quantity;
        double currency = sellValue(seller, "minecraft:emerald");
        double cost = Math.ceil(value / currency);
        return currency > 0 && value > 0 && Double.isFinite(cost)
                && cost > 0 && cost <= Integer.MAX_VALUE ? (int) cost : -1;
    }

    public static boolean hasPrice(VillagerAgentData agent, String item) {
        ensurePersonalValueTable(agent);
        return agent != null && personalPriceOf(agent, item) != null
                && personalPriceOf(agent, "minecraft:emerald") != null;
    }

    /** Read nearby surplus as quotes without posting a demand or committing a purchase. */
    public static void advertiseAcquisitionStock(ServerWorld world, VillagerEntity buyer, java.util.Set<Item> items) {
        VillageSignalBoard local = VillageMarkets.board(world, buyer.getUUID());
        for (VillagerAgentData seller : VillageMarkets.residents(world, buyer.getUUID())) {
            VillagerEntity entity = entityOf(world, seller);
            if (entity == null || !entity.isAlive() || entity == buyer || buyer.distanceToSqr(entity) > 4096) continue;
            for (Item item : items) {
                String id = item.getRegistryName().toString();
                if (!hasPrice(seller, id)) continue;
                int available = TradeInventory.count(seller.getInventory().getItems(), new ItemStack(item));
                List<Essential> reserves = getEssentials(seller.getProfession());
                if (reserves != null) for (Essential essential : reserves)
                    if (id.equals(essential.item)) available = Math.max(0, available - essential.minQty);
                if (available <= 0) continue;
                boolean matched = local.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE).stream()
                        .anyMatch(m -> !m.resolved && m.author.equals(seller.getVillagerId())
                                && m.item.toString().equals(id) && m.matchedWith != null);
                if (!matched) postOffer(world, entity, seller, item.getRegistryName(), available);
            }
        }
    }

    private static boolean canAffordTrade(VillagerAgentData buyer, VillagerAgentData seller,
                                         String item, int quantity) {
        int cost = emeraldCost(seller, item, quantity);
        return buyer != null && cost > 0 && TradeInventory.count(buyer.getInventory().getItems(),
                new ItemStack(Items.EMERALD)) >= cost;
    }

    /** Recheck before movement and settlement; stale matches must not start unfunded deliveries. */
    public static boolean validateTradeFunds(VillageSignalBoard.VillageMessage want,
                                             VillageSignalBoard.VillageMessage have) {
        VillagerAgentData buyer = VillagerAgentManager.getAgent(want.author), seller = VillagerAgentManager.getAgent(have.author);
        if (want.market == have.market && want.agreedPayment > 0 && buyer != null && seller != null
                && TradeInventory.count(buyer.getInventory().getItems(), new ItemStack(Items.EMERALD)) >= want.agreedPayment
                && TradeInventory.count(seller.getInventory().getItems(), new ItemStack(registryItem(want.item.toString()))) >= want.quantity) return true;
        VillageSignalBoard.get().unmatch(want.id);
        return false;
    }

    public static int quoteCost(VillageSignalBoard.VillageMessage offer, int quantity) {
        double price = Math.ceil(offer.unitPrice * quantity);
        return Double.isFinite(price) && price > 0 && price <= Integer.MAX_VALUE ? (int) price : -1;
    }
    public static boolean acceptTrade(ServerWorld world, VillageSignalBoard.VillageMessage want,
                                      VillageSignalBoard.VillageMessage have, double unitPrice) {
        VillagerAgentData buyer=VillagerAgentManager.getAgent(want.author), seller=VillagerAgentManager.getAgent(have.author);
        VillagerEntity b=entityOf(world,buyer), s=entityOf(world,seller);
        double payment=Math.ceil(unitPrice*want.quantity);
        if (b==null||s==null||buyer.isSocializing()||seller.isSocializing()||TradeDemo.isActive(want.author)||TradeDemo.isActive(have.author)
                || !VillageMarkets.same(world,want.author,have.author) || want.market!=VillageMarkets.board(world,want.author)
                || have.market!=want.market || want.expireTick<=world.getGameTime() || have.expireTick<=world.getGameTime()
                || have.unitPrice<=0 || want.unitPrice<=0 || unitPrice<have.unitPrice || unitPrice>want.unitPrice
                || !Double.isFinite(payment)||payment<=0||payment>Integer.MAX_VALUE
                || TradeInventory.count(buyer.getInventory().getItems(),new ItemStack(Items.EMERALD))<payment
                || TradeInventory.count(seller.getInventory().getItems(),new ItemStack(registryItem(have.item.toString())))<want.quantity) return false;
        if (!want.market.matchTrade(want.id,have.id)) return false;
        want.agreedPayment=(int)payment;
        if (!TradeCoordinator.enqueue(world,want,have)) { want.market.unmatch(want.id); return false; }
        return true;
    }
    private static double unitPrice(VillagerAgentData a,String item,boolean buying) {
        if (!hasPrice(a,item)) return -1;
        double currency=sellValue(a,"minecraft:emerald");
        double value=(buying?buyValue(a,item):sellValue(a,item))/currency;
        return currency>0 && Double.isFinite(value) && value>0 ? value : -1;
    }
    public static void postOffer(ServerWorld world,VillagerEntity v,VillagerAgentData a,ResourceLocation item,int quantity) {
        double price=unitPrice(a,item.toString(),false); if(price<=0||quantity<=0) return;
        VillageSignalBoard board=VillageMarkets.board(world,a.getVillagerId());
        java.util.UUID id=board.post(a.getVillagerId(),v.blockPosition(),VillageSignalBoard.MsgType.TRADE_HAVE,item,quantity,world.getGameTime());
        VillageSignalBoard.VillageMessage m=board.find(id); if(m.matchedWith==null) m.unitPrice=price;
    }
    public static java.util.UUID postDemand(ServerWorld world,VillagerEntity v,VillagerAgentData a,ResourceLocation item,int quantity) {
        VillageSignalBoard board=VillageMarkets.board(world,a.getVillagerId());
        java.util.UUID id=board.post(a.getVillagerId(),v.blockPosition(),VillageSignalBoard.MsgType.TRADE_WANT,item,quantity,world.getGameTime());
        VillageSignalBoard.VillageMessage m=board.find(id); if(m.matchedWith==null) m.unitPrice=unitPrice(a,item.toString(),true);
        return id;
    }
    /** Listing is bookkeeping, not a SOCIAL-gated walk-to-barter routine. */
    private static void advertiseSurplus(ServerWorld world,VillagerEntity v,VillagerAgentData a) {
        java.util.Set<Item> seen=new java.util.HashSet<>();
        for(ItemStack stack:a.getInventory().getItems()) {
            if(stack.isEmpty()||stack.getItem()==Items.EMERALD||stack.hasTag()||!seen.add(stack.getItem())) continue;
            String id=stack.getItem().getRegistryName().toString();
            int available=TradeInventory.count(a.getInventory().getItems(),new ItemStack(stack.getItem()));
            List<Essential> reserves=getEssentials(a.getProfession());
            if(reserves!=null) for(Essential e:reserves) if(id.equals(e.item)) available=Math.max(0,available-e.minQty);
            if(available>0) postOffer(world,v,a,stack.getItem().getRegistryName(),available);
        }
    }

    private static ItemStack[] tradeStacks(Item item, int quantity) {
        List<ItemStack> stacks = new ArrayList<>();
        while (quantity > 0) {
            int count = Math.min(quantity, item.getMaxStackSize());
            stacks.add(new ItemStack(item, count));
            quantity -= count;
        }
        return stacks.toArray(new ItemStack[0]);
    }

    private static void steer(VillagerEntity villager, net.minecraft.util.math.BlockPos target) {
        // A sleeping villager is not steered to a trading partner (would drag it while lying).
        if (villager.isSleeping()) return;
        villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.4);
    }

    private static VillagerEntity entityOf(ServerWorld world, VillagerAgentData agent) {
        if (agent == null || TradeDemo.isActive(agent.getVillagerId())) return null;
        Entity e = world.getEntity(agent.getVillagerId());
        return (e instanceof VillagerEntity) ? (VillagerEntity) e : null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Value / registry helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * What this individual villager is willing to pay to acquire one unit of the item, per its
     * <b>personal</b> value table (initialised as a deep copy of its profession template, then
     * individually editable). Items absent from that personal table are worth 0.01 — this villager
     * simply does not deal in them.
     */
    public static double buyValue(VillagerAgentData agent, String regName) {
        ensurePersonalValueTable(agent);
        Price p = personalPriceOf(agent, regName);
        return p != null ? p.buy : 0.01;
    }

    /** What this individual villager charges to sell one unit of the item (0.01 if it doesn't trade it). */
    public static double sellValue(VillagerAgentData agent, String regName) {
        ensurePersonalValueTable(agent);
        Price p = personalPriceOf(agent, regName);
        return p != null ? p.sell : 0.01;
    }

    private static Price personalPriceOf(VillagerAgentData agent, String regName) {
        if (agent == null || regName == null) return null;
        Map<String, Price> table = agent.getPersonalValueTable();
        return table != null ? table.get(regName) : null;
    }

    /**
     * Ensure the agent carries its own value table — a deep copy of its profession's template —
     * so the template is never mutated by per-villager edits. Shared defaults apply first,
     * then profession entries override them. Existing personal tables remain unchanged.
     */
    public static void ensurePersonalValueTable(VillagerAgentData agent) {
        if (agent == null || agent.hasPersonalValueTable()) return;
        ensureLoaded();
        Map<String, Price> tpl = new HashMap<>();
        Map<String, Price> defaults = ITEM_VALUES.get("default");
        if (defaults != null) tpl.putAll(defaults);
        Map<String, Price> profession = getValueTable(agent.getProfession());
        if (profession != null) tpl.putAll(profession);
        Map<String, Price> copy = new HashMap<>();
        if (tpl != null) {
            for (Map.Entry<String, Price> e : tpl.entrySet()) {
                Price p = new Price();
                p.buy = e.getValue().buy;
                p.sell = e.getValue().sell;
                copy.put(e.getKey(), p);
            }
        }
        agent.setPersonalValueTable(copy);
    }

    /** Discard personal overrides and rebuild from shared defaults plus the current profession. */
    public static void resetPersonalValueTable(VillagerAgentData agent) {
        agent.setPersonalValueTable(null);
        ensurePersonalValueTable(agent);
    }

    /**
     * Override one item's buy/sell price on this villager's personal table (creating the table first
     * if needed). Use to model individual preferences / learned prices; trades then use the personal
     * value, never the profession template.
     */
    public static void setPrice(VillagerAgentData agent, String regName, double buy, double sell) {
        ensurePersonalValueTable(agent);
        Price p = new Price();
        p.buy = buy;
        p.sell = sell;
        agent.getPersonalValueTable().put(regName, p);
    }

    /** Resolve the profession template map for a profession string (any case / prefix form). */
    private static Map<String, Price> getValueTable(String profession) {
        String key = normalizeProfession(profession);
        return key != null ? ITEM_VALUES.get(professionRegistryKey(key)) : null;
    }

    /**
     * Normalise a profession string to the bare lowercase registry key used by the JSON content:
     * "Farmer", "farmer", "minecraft:FARMER" → "farmer"; "None"/"Unemployed Villager" → "unemployed".
     */
    private static String professionRegistryKey(String key) {
        return ("lumberjack".equals(key) || "miner".equals(key) ? "villageragent:" : "minecraft:") + key;
    }

    private static String normalizeProfession(String profession) {
        if (profession == null) return null;
        String p = profession;
        if (p.contains(":")) p = p.substring(p.indexOf(':') + 1);
        p = p.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || p.equals("none") || p.equals("villager") || p.contains("unemployed")) return "unemployed";
        return p;
    }

    /** True if the item can be obtained without trade (farm/mine/craft/smelt/gather). */
    public static boolean isSelfObtainable(String regName) {
        String src = ITEM_SOURCES.get(regName);
        if (src == null) return false;
        return src.contains("CRAFT") || src.contains("SMELT") || src.contains("FARM")
                || src.contains("MINED") || src.contains("FISH") || src.contains("GATHER");
    }

    private static Item registryItem(String regName) {
        return ForgeRegistries.ITEMS.getValue(rl(regName));
    }

    private static ResourceLocation rl(String regName) {
        return new ResourceLocation(regName);
    }

    private static List<Essential> getEssentials(String profession) {
        String key = normalizeProfession(profession);
        return key != null ? PROFESSION_ESSENTIALS.get(professionRegistryKey(key)) : null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Data loading (Gson, from packaged resources)
    // ─────────────────────────────────────────────────────────────────────────────

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true; // set early so a failure doesn't retry-spam every tick
        loadJson("item_values.json",
                new TypeToken<Map<String, Map<String, Price>>>() {}, ITEM_VALUES);
        loadJson("profession_essentials.json",
                new TypeToken<Map<String, List<Essential>>>() {}, PROFESSION_ESSENTIALS);
        loadJson("item_sources.json",
                new TypeToken<Map<String, String>>() {}, ITEM_SOURCES);
        int pricedItems = ITEM_VALUES.values().stream().mapToInt(Map::size).sum();
        LOGGER.info("Villager economy data loaded: {} professions with price tables ({} priced items), "
                        + "{} essentials professions, {} sources",
                ITEM_VALUES.size(), pricedItems, PROFESSION_ESSENTIALS.size(), ITEM_SOURCES.size());
    }

    private static <T> void loadJson(String file, TypeToken<T> type, Map<?, ?> target) {
        String path = "/data/villageragent/economy/" + file;
        try (InputStream is = VillagerEconomySystem.class.getResourceAsStream(path)) {
            if (is == null) {
                LOGGER.warn("Economy data not found on classpath: {}", path);
                return;
            }
            T data = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), type.getType());
            if (data instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> src = (Map<Object, Object>) data;
                ((Map<Object, Object>) target).putAll(src);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load economy data {}: {}", path, e.getMessage());
        }
    }
}
