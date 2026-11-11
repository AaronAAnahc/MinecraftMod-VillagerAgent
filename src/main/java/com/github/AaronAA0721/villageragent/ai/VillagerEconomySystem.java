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
 * <p>Villager-to-villager supply is free and demand-driven: villagers physically visit the notice board,
 * publish requests for their actual shortage, and read other residents' requests. A reader delivers
 * shareable stock or creates non-exclusive request-linked production ({@link CooperativeSupply}). No price,
 * payment or affordability check controls cooperation. The value tables below remain for the isolated
 * player barter path ({@code TradeRequestPacket}).
 */
public final class VillagerEconomySystem {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    // ── Loaded content data (item_values / profession_essentials / item_sources) ──
    /** profession -> (item -> subjective value). Missing entries are not tradable. */
    private static final Map<String, Map<String, Double>> ITEM_VALUES = new HashMap<>();
    private static final Map<String,Double> JUNK=new HashMap<>(), MODIFIERS=new HashMap<>();
    private static final Map<String,Map<String,PaymentRule>> PAYMENT=new HashMap<>();
    private static final Map<String,List<String>> ACTIVE_OFFERS=new HashMap<>();
    public static final class PaymentRule { public int priority; public boolean retain; }
    private static final Map<String, List<Essential>> PROFESSION_ESSENTIALS = new HashMap<>();
    private static final Map<String, String> ITEM_SOURCES = new HashMap<>();
    private static boolean loaded = false;

    /** A single profession-essential entry: an item the villager must keep at least {@code minQty} of. */
    public static final class Essential {
        public String item;   // registry name, e.g. "minecraft:wooden_hoe"
        public int minQty;
    }

    /** Compatibility DTO: both fields hold the same subjective value; player prices are derived. */
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
            VillageNoticeBoards.ensure(world, market);
            market.board.clearExpiredMessages(now);
        }

        if (now - lastScanTicks.getOrDefault(world, -10000L) < ModConfig.ECONOMY_TICK_INTERVAL.get()) return;
        lastScanTicks.put(world, now);
        for (VillageMarkets.Market market : markets) runScan(world, market, now);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Scan: post supply/demand, then match
    // ─────────────────────────────────────────────────────────────────────────────

    public static void visitMarket(ServerWorld w,VillagerEntity v,VillagerAgentData a) {
        if(!ModConfig.ENABLE_ECONOMY.get() || !VillageNoticeBoards.atBoard(w,v)) return;
        VillageSignalBoard board=VillageMarkets.board(w,a.getVillagerId());if(board==null) return;
        VillageMarkets.market(w,a.getVillagerId()).lastNoticeVisits.put(a.getVillagerId(),w.getGameTime());
        // Requests first: an open request of this villager may be filled by a neighbour's offer right now.
        CooperativeSupply.claimOffersForOwnRequests(w,a,board);
        advertiseSurplus(w,v,a);
        CooperativeSupply.inspect(w,v,a,board);
    }
    private static void runScan(ServerWorld world, VillageMarkets.Market market, long now) {
        VillageSignalBoard board = market.board;
        pruneStaleOffers(market);
        for (VillagerAgentData agent : market.members()) {
            if (agent == null) continue;
            VillagerEntity villager = entityOf(world, agent);
            if (villager == null || !villager.isAlive()) continue;
            // Board checks are a standing routine goal (RoutineScheduler "trade"); no visit is queued here.
            // Profession tools are protected by category: any suitable tool satisfies the requirement.
            for (String category : CooperativeSupply.requiredToolCategories(agent)) {
                SupplyPolicy.Spec spec = SupplyPolicy.Spec.category(category);
                if (agent.getInventory().getItems().stream().anyMatch(spec::matches)) continue;
                String item = null;
                for (String id : essentialItems(agent.getProfession()))
                    if (category.equals(SupplyPolicy.toolCategory(registryItem(id)))) { item = id; break; }
                if (item == null || !VillageProducible.producible(world, item)) continue;
                final String target = item;
                boolean queued = agent.getGoals().stream().anyMatch(g -> !g.isCompleted()
                        && "acquire_item".equals(g.getGoalType()) && target.equals(g.getTargetItem()));
                if (!queued && agent.getGoals().size() < 10) {
                    AgentGoal goal = new AgentGoal("acquire_item", "Acquire profession tool " + category, 6);
                    goal.setTargetItem(target); goal.setTargetQuantity(1);
                    goal.setRequestCategory(category);
                    goal.setOutcomeItems(java.util.Collections.singleton(target));
                    goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
                    agent.getGoals().add(goal);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Item transfer helpers
    // ─────────────────────────────────────────────────────────────────────────────

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
        return agent != null && villagerValue(agent,item)>0;
    }

    private static double unitPrice(VillagerAgentData a,String item,boolean buying) {
        return villagerValue(a,item);
    }
    /** Informational stock notice (no price): what this villager could share right now. */
    public static void postOffer(ServerWorld world,VillagerEntity v,VillagerAgentData a,ResourceLocation item,int quantity) {
        if (!VillageNoticeBoards.atBoard(world,v) || quantity<=0) return;
        VillageSignalBoard board=VillageMarkets.board(world,a.getVillagerId());
        if(board==null) return;
        board.post(a.getVillagerId(),v.blockPosition(),VillageSignalBoard.MsgType.TRADE_HAVE,item,quantity,world.getGameTime());
    }
    /** Publish actual shareable stock (tools beyond the protected one, uncommitted goods) while at the board. */
    /**
     * Post this villager's "for sale" notices: only items its profession produces ({@code active_offers.json}),
     * only stock it can share right now ({@link CooperativeSupply#shareable}, which already holds back stock
     * reserved for claimed offers and claimed requests), minus what it plans to hand over for requests it has
     * taken on ({@code supply_request} responses). Its own notices for items no longer on offer are removed, so
     * the board never shows stock that is promised elsewhere.
     */
    private static void advertiseSurplus(ServerWorld world,VillagerEntity v,VillagerAgentData a) {
        VillageSignalBoard board=VillageMarkets.board(world,a.getVillagerId());
        if(board==null) return;
        java.util.Set<ResourceLocation> advertised=new java.util.HashSet<>();
        java.util.Set<Item> seen=new java.util.HashSet<>();
        for(ItemStack stack:a.getInventory().getItems()) {
            if(stack.isEmpty()||stack.getItem()==Items.EMERALD||!seen.add(stack.getItem())) continue;
            ResourceLocation id=stack.getItem().getRegistryName();
            if(id==null||!offers(a,id.toString())) continue;
            SupplyPolicy.Spec spec=SupplyPolicy.Spec.exact(id.toString());
            int available=SupplyPolicy.units(spec,CooperativeSupply.shareable(a,spec,null))-promisedToRequests(a,stack.getItem());
            if(available>0) { postOffer(world,v,a,id,available); advertised.add(id); }
        }
        for(VillageSignalBoard.VillageMessage m:board.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE)) {
            if(m.resolved||m.ownerAttempt!=null||!m.author.equals(a.getVillagerId())||m.item==null) continue;
            if(!advertised.contains(m.item)) board.removeByAuthor(a.getVillagerId(),VillageSignalBoard.MsgType.TRADE_HAVE,m.item);
        }
    }

    /** Common things a player pays with; always listed in the chat price block. */
    private static final String[] PLAYER_CURRENCIES = {
            "minecraft:emerald", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"};

    /**
     * Price block for the chat prompt: the exact values the trade screen ({@code TradeRequestPacket}) uses, in
     * "points", so what the villager quotes in conversation matches what the trade screen will accept.
     * Lists the sell price of every item it holds and the value it gives to the usual player payments and to
     * the items it is currently trying to get.
     */
    public static String describePricesForPlayer(VillagerAgentData a) {
        return describePricesForPlayer(a, java.util.Collections.emptyList());
    }

    /**
     * As above, plus a value for every kind of item {@code playerItems} (the player's inventory) holds, so the
     * villager can price whatever the player offers instead of guessing about unlisted items.
     */
    public static String describePricesForPlayer(VillagerAgentData a, List<ItemStack> playerItems) {
        ensurePersonalValueTable(a);
        java.util.LinkedHashMap<String, String> sells = new java.util.LinkedHashMap<>();
        for (ItemStack stack : a.getInventory().getItems()) {
            if (stack.isEmpty() || stack.getItem().getRegistryName() == null) continue;
            String id = stack.getItem().getRegistryName().toString();
            if (sells.containsKey(id)) continue;
            double v = sellPriceForPlayer(a, id);
            if (Double.isFinite(v) && v > 0) sells.put(id, fmt(v));
        }
        java.util.LinkedHashSet<String> payIds = new java.util.LinkedHashSet<>(java.util.Arrays.asList(PLAYER_CURRENCIES));
        for (AgentGoal g : a.getGoals())
            if (!g.isCompleted() && "acquire_item".equals(g.getGoalType()) && g.getTargetItem() != null
                    && g.getTargetItem().contains(":")) payIds.add(g.getTargetItem());
        java.util.List<String> worthless = new ArrayList<>();
        java.util.Set<String> playerIds = new java.util.LinkedHashSet<>();
        for (ItemStack stack : playerItems)
            if (!stack.isEmpty() && stack.getItem().getRegistryName() != null && playerIds.size() < 16)
                playerIds.add(stack.getItem().getRegistryName().toString());
        payIds.addAll(playerIds);
        java.util.LinkedHashMap<String, String> buys = new java.util.LinkedHashMap<>();
        for (String id : payIds) {
            double v = buyPriceForPlayer(a, id);
            if (Double.isFinite(v) && v > 0) buys.put(id, fmt(v));
            else if (playerIds.contains(id)) worthless.add(LongTermAgenda.shortName(id));
        }
        if (sells.isEmpty() && buys.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("YOUR PRICES (points per item; the trade screen enforces exactly these): ");
        if (!sells.isEmpty()) {
            sb.append("you SELL ");
            sells.forEach((id, v) -> sb.append(LongTermAgenda.shortName(id)).append("=").append(v).append(", "));
            sb.setLength(sb.length() - 2);
            sb.append(". ");
        }
        if (!buys.isEmpty()) {
            sb.append("Payment you RECEIVE is worth: ");
            buys.forEach((id, v) -> sb.append(LongTermAgenda.shortName(id)).append("=").append(v).append(", "));
            sb.setLength(sb.length() - 2);
            sb.append(". ");
        }
        if (!playerIds.isEmpty()) {
            sb.append("The player is carrying: ");
            for (String id : playerIds) sb.append(LongTermAgenda.shortName(id)).append(", ");
            sb.setLength(sb.length() - 2);
            sb.append(". ");
        }
        if (!worthless.isEmpty()) sb.append("Worth nothing to you: ").append(String.join(", ", worthless)).append(". ");
        sb.append(minimumQuotes(a, sells.keySet(), playerIds.isEmpty() ? payIds : playerIds));
        sb.append("Normally do not agree to less than these minimums; if the player offers less, haggle or name the ")
          .append("minimum. For trades with several items, or anything you are unsure about, use \"check_trade\" ")
          .append("to get the exact values of both sides before you answer. ");
        return sb.toString();
    }

    /**
     * "MINIMUM PRICES" lines with the arithmetic already done: for each item the villager sells, the least of each
     * payment item it accepts (at {@link #DEAL_FLOOR}). Cheap goods are quoted as "up to N per payment item".
     * At most 10 goods x 4 payment items, so the LLM picks a number instead of multiplying.
     */
    private static String minimumQuotes(VillagerAgentData a, java.util.Collection<String> goods, java.util.Collection<String> payments) {
        StringBuilder sb = new StringBuilder();
        int goodsListed = 0;
        for (String good : goods) {
            if (goodsListed >= 10) break;
            double need = sellPriceForPlayer(a, good) * DEAL_FLOOR;
            if (!Double.isFinite(need) || need <= 0) continue;
            Item goodItem = registryItem(good);
            if (goodItem == null || goodItem == Items.AIR) continue;
            int have = a.getInventory().countItem(new ItemStack(goodItem));
            List<String> options = new ArrayList<>();
            for (String pay : payments) {
                if (pay.equals(good) || options.size() >= 4) continue;
                double value = buyPriceForPlayer(a, pay);
                if (!Double.isFinite(value) || value <= 0) continue;
                String payName = LongTermAgenda.shortName(pay), goodName = LongTermAgenda.shortName(good);
                if (value >= need) {
                    int upTo = Math.max(1, Math.min(have, (int) Math.floor(value / need + 1e-9)));
                    options.add("1 " + payName + " buys up to " + upTo + " " + goodName);
                } else {
                    options.add((int) Math.ceil(need / value - 1e-9) + " " + payName + " per 1 " + goodName);
                }
            }
            if (options.isEmpty()) continue;
            if (goodsListed++ == 0) sb.append("MINIMUM PRICES (already calculated; use these numbers): ");
            sb.append(LongTermAgenda.shortName(good)).append(": ").append(String.join(" | ", options)).append("; ");
        }
        return sb.length() == 0 ? "" : sb.append(". ").toString();
    }

    /** A negotiated deal may undercut the value table, but never below this fraction of the fair price. */
    public static final double DEAL_FLOOR = 0.7;

    private static String fmt(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * Keep "for sale" notices honest between board visits: a notice whose author no longer holds the stock
     * (cooked it, ate it, used it, handed it over) or whose item is no longer on the profession's sell list is
     * removed, and an over-stated quantity is lowered. Runs on the economy scan, so the author does not have
     * to walk back to the board for the notice to disappear.
     */
    private static void pruneStaleOffers(VillageMarkets.Market market) {
        VillageSignalBoard board = market.board;
        for (VillageSignalBoard.VillageMessage m : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE)) {
            if (m.resolved || m.ownerAttempt != null || m.item == null) continue;
            VillagerAgentData author = VillagerAgentManager.getAgent(m.author);
            if (author == null) continue;
            String id = m.item.toString();
            int available = 0;
            if (offers(author, id)) {
                SupplyPolicy.Spec spec = SupplyPolicy.Spec.exact(id);
                Item item = registryItem(id);
                available = SupplyPolicy.units(spec, CooperativeSupply.shareable(author, spec, null))
                        - (item == null ? 0 : promisedToRequests(author, item));
            }
            if (available <= 0) board.removeByAuthor(m.author, VillageSignalBoard.MsgType.TRADE_HAVE, m.item);
            else if (available < m.quantity) m.quantity = available;
        }
    }

    /** Items this villager's profession sells on the board (what it produces); empty for other professions. */
    public static boolean offers(VillagerAgentData a,String item) {
        ensureLoaded();
        String key=normalizeProfession(a.getProfession());
        List<String> list=key==null?null:ACTIVE_OFFERS.get(professionRegistryKey(key));
        return list!=null&&list.contains(item);
    }

    /** Output this villager still plans to deliver for requests it has taken on (unclaimed supply_request goals). */
    private static int promisedToRequests(VillagerAgentData a,Item item) {
        int n=0;
        for(AgentGoal g:a.getGoals()) {
            if(g.isCompleted()||!CooperativeSupply.RESPONSE_GOAL.equals(g.getGoalType())||g.getClaimId()!=null) continue;
            Item target=registryItem(g.getTargetItem()==null?"minecraft:air":g.getTargetItem());
            if(target!=item) continue;
            SupplyPolicy.Spec spec=SupplyPolicy.Spec.parse(g.getRequestSpec());
            n+=spec==null?g.getRequestUnits():Math.max(1,SupplyPolicy.itemsToCover(spec,item,g.getRequestUnits()));
        }
        return n;
    }

    private static VillagerEntity entityOf(ServerWorld world, VillagerAgentData agent) {
        if (agent == null || TradeDemo.isActive(agent.getVillagerId())) return null;
        Entity e = world.getEntity(agent.getVillagerId());
        return (e instanceof VillagerEntity) ? (VillagerEntity) e : null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Value / registry helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /** Missing prices are absent, never implicitly cheap. */
    public static double villagerValue(VillagerAgentData agent,String item) {
        ensurePersonalValueTable(agent);
        Price p=personalPriceOf(agent,item);
        return p!=null&&Double.isFinite(p.buy)&&p.buy>0?p.buy:0;
    }
    public static double buyValue(VillagerAgentData agent,String item) { return buyPriceForPlayer(agent,item); }
    public static double sellValue(VillagerAgentData agent,String item) { return sellPriceForPlayer(agent,item); }
    public static double buyPriceForPlayer(VillagerAgentData agent,String item) { return villagerValue(agent,item)/modifier("playerMargin",1.8); }
    public static double sellPriceForPlayer(VillagerAgentData agent,String item) {
        double value=villagerValue(agent,item);return value>0?value*modifier("playerMargin",1.8):Double.POSITIVE_INFINITY;
    }
    public static int reserve(VillagerAgentData a,String item) {
        List<Essential> list=getEssentials(a.getProfession());int n=0;
        if(list!=null) for(Essential e:list) if(item.equals(e.item)) n=Math.max(n,e.minQty);
        return n;
    }
    /** Configured profession essentials; tool entries define the protected tool categories. */
    public static List<String> essentialItems(String profession) {
        List<String> result=new ArrayList<>();
        List<Essential> list=getEssentials(profession);
        if(list!=null) for(Essential e:list) if(e!=null&&e.item!=null) result.add(e.item);
        return result;
    }
    /** Non-tool essentials keep their configured minimum; tools are protected per category instead. */
    public static Map<String,Integer> nonToolReserves(VillagerAgentData a) {
        Map<String,Integer> result=new HashMap<>();
        List<Essential> list=getEssentials(a.getProfession());
        if(list!=null) for(Essential e:list) {
            if(e==null||e.item==null||e.minQty<=0) continue;
            Item item=registryItem(e.item);
            if(item==null||item==Items.AIR||SupplyPolicy.toolCategory(item)!=null) continue;
            result.merge(e.item,e.minQty,Math::max);
        }
        return result;
    }
    private static PaymentRule paymentRule(VillagerAgentData a,String item) {
        ensureLoaded();Map<String,PaymentRule> rules=PAYMENT.get(professionRegistryKey(normalizeProfession(a.getProfession())));
        PaymentRule rule=rules==null?null:rules.get(item);
        if(rule==null&&PAYMENT.containsKey("default")) rule=PAYMENT.get("default").get(item);
        return rule;
    }
    public static boolean retained(VillagerAgentData a,String item) {
        PaymentRule rule=paymentRule(a,item);return rule!=null?rule.retain:reserve(a,item)>0;
    }
    public static int paymentPriority(VillagerAgentData a,String item) { PaymentRule r=paymentRule(a,item);return r==null?0:r.priority; }
    private static double modifier(String key,double fallback) {
        ensureLoaded();Double n=MODIFIERS.get(key);return n!=null&&Double.isFinite(n)&&n>0?n:fallback;
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
        Map<String, Double> tpl = new HashMap<>(JUNK);
        Map<String,Double> defaults=ITEM_VALUES.get("default");if(defaults!=null) tpl.putAll(defaults);
        Map<String,Double> profession=getValueTable(agent.getProfession());if(profession!=null) tpl.putAll(profession);
        Map<String,Price> copy=new HashMap<>();
        for(Map.Entry<String,Double> e:tpl.entrySet()) {
            if(e.getValue()==null||!Double.isFinite(e.getValue())||e.getValue()<=0) continue;
            double value=e.getValue();
            com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.Source source=
                com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.get(e.getKey());
            if((source!=null&&source.skilled(agent))||SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(),agent.getProfession(),e.getKey()))
                value*=modifier("selfProduceDiscount",0.7);
            Price price=new Price();price.buy=value;price.sell=value;copy.put(e.getKey(),price);
        }
        agent.setPersonalValueTable(copy);
    }

    /** Discard personal overrides and rebuild from shared defaults plus the current profession. */
    public static void resetPersonalValueTable(VillagerAgentData agent) {
        agent.setPersonalValueTable(null);
        ensurePersonalValueTable(agent);
    }

    /** Legacy setter: the first value is authoritative; a separate selling value is no longer stored. */
    public static void setPrice(VillagerAgentData agent, String regName, double buy, double sell) {
        ensurePersonalValueTable(agent);
        Price p = new Price();
        if(!Double.isFinite(buy)||buy<=0) return;
        p.buy = buy;
        p.sell = buy;
        agent.getPersonalValueTable().put(regName, p);
    }

    /** Resolve the profession template map for a profession string (any case / prefix form). */
    private static Map<String, Double> getValueTable(String profession) {
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
        ensureLoaded();
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
                new TypeToken<Map<String, Map<String, Double>>>() {}, ITEM_VALUES);
        loadJson("junk_values.json",new TypeToken<Map<String,Double>>() {},JUNK);
        loadJson("price_modifiers.json",new TypeToken<Map<String,Double>>() {},MODIFIERS);
        loadJson("payment_accepts.json",new TypeToken<Map<String,Map<String,PaymentRule>>>() {},PAYMENT);
        loadJson("active_offers.json",new TypeToken<Map<String,List<String>>>() {},ACTIVE_OFFERS);
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
