package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A trade the villager offered a player in conversation (the LLM reply's {@code "trade"} field).
 *
 * <p>Flow: the chat reply carries a {@link Draft}; {@link #create} checks it on the server thread against both
 * real inventories, the free space on both sides and the price floor ({@link VillagerEconomySystem#DEAL_FLOOR}),
 * then stores it as pending. The client opens the trade panel pre-filled with it. If the player presses Accept,
 * the normal {@code TradeRequestPacket} arrives with exactly these stacks and {@link #take} lets it through at the
 * agreed price. Decline, a new offer, or {@link #TTL_TICKS} without an answer drops it.
 * One pending offer per villager and player. Server thread only.
 */
public final class TradeProposal {

    /** How long an offer stays open (2 minutes). */
    public static final long TTL_TICKS = 2400;

    /** One line of the LLM's offer: an item id and a count. */
    public static final class Line {
        public final String item;
        public final int qty;
        public Line(String item, int qty) { this.item = item; this.qty = qty; }
    }

    /** The unchecked offer as parsed from the LLM reply, from the player's point of view. */
    public static final class Draft {
        public final List<Line> playerGives = new ArrayList<>();
        public final List<Line> playerGets = new ArrayList<>();
        public boolean isEmpty() { return playerGives.isEmpty() && playerGets.isEmpty(); }
    }

    /** Result of {@link #create}: the stored proposal, or why the offer could not be made. */
    public static final class Result {
        public final TradeProposal proposal;
        public final String error;
        /**
         * Set when the offer was too cheap but a fair version exists (more payment, or fewer goods): it is stored
         * as the pending offer instead, and the villager should present it as a counter-offer.
         */
        public TradeProposal counter;
        /** True when the offer failed only on price (the villager must correct what it said). */
        public boolean tooCheap;
        /** Points the villager receives / gives up (set once both inventories checked out). */
        public double offered, requested;
        private Result(TradeProposal proposal, String error) { this.proposal = proposal; this.error = error; }
        public boolean ok() { return proposal != null; }
    }

    private static final Map<UUID, Map<UUID, TradeProposal>> PENDING = new HashMap<>();

    public final UUID villagerId, playerId;
    /** Slots as shown on the panel: what the player gives (left) and gets (right). Never null; EMPTY when unused. */
    public final ItemStack give1, give2, get1, get2;
    public final long expiresAt;

    private TradeProposal(UUID villagerId, UUID playerId, ItemStack[] gives, ItemStack[] gets, long expiresAt) {
        this.villagerId = villagerId;
        this.playerId = playerId;
        this.give1 = gives[0]; this.give2 = gives[1];
        this.get1 = gets[0];   this.get2 = gets[1];
        this.expiresAt = expiresAt;
    }

    /**
     * Check {@code draft} and store it as the pending offer. Errors are short and player-facing.
     * The stacks are copied from the real inventories (same NBT), so the trade check matches them exactly.
     */
    public static Result create(Draft draft, VillagerAgentData agent, VillagerEntity villager,
                                ServerPlayerEntity player, long now) {
        return create(draft, agent, villager, player, now, false);
    }

    /**
     * @param priceDecided true when the LLM wrote this trade after seeing the exact values of the check round:
     *                     its decision stands, so the price floor is skipped (inventories and space are still checked)
     */
    public static Result create(Draft draft, VillagerAgentData agent, VillagerEntity villager,
                                ServerPlayerEntity player, long now, boolean priceDecided) {
        return evaluate(draft, agent, villager, player, now, true, true, priceDecided);
    }

    /**
     * Check a candidate trade without offering it (the LLM's {@code check_trade} query): facts only, no verdict.
     * "player offers 3 pumpkin for 1 salmon: your side is worth 2.6 points, the player's side is worth 2.4 points;
     * the player's side is 8% lower than yours", or "NOT POSSIBLE (reason)" for stock/space. Nothing is stored.
     */
    public static String check(Draft draft, VillagerAgentData agent, VillagerEntity villager, ServerPlayerEntity player) {
        if (draft == null || draft.isEmpty()) return "(empty trade)";
        String what = describeDraft(draft);
        // Facts only (no verdict): values of both sides as this villager sees them, and how they compare.
        Result r = evaluate(draft, agent, villager, player, 0, false, false, true);
        if (!r.ok()) return what + ": NOT POSSIBLE (" + r.error + ")";
        double yours = r.requested, theirs = r.offered;
        double diff = yours <= 0 ? 0 : (theirs - yours) / yours * 100;
        String compare = Math.abs(diff) < 0.5 ? "the same value as yours"
                : String.format(Locale.ROOT, "%.0f%% %s than yours", Math.abs(diff), diff > 0 ? "higher" : "lower");
        return what + ": your side is worth " + fmt(yours) + " points, the player's side is worth " + fmt(theirs)
                + " points; the player's side is " + compare;
    }

    /** True when the trade would pass as offered (both inventories, space and the price floor). Stores nothing. */
    public static boolean acceptable(Draft draft, VillagerAgentData agent, VillagerEntity villager, ServerPlayerEntity player) {
        return evaluate(draft, agent, villager, player, 0, false, false, false).ok();
    }

    /** "player offers 3 pumpkin and 1 carrot for 1 salmon" */
    private static String describeDraft(Draft d) {
        return "player offers " + lines(d.playerGives) + " for " + lines(d.playerGets);
    }

    private static String lines(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Item item = resolve(l.item);
            sb.append(i == 0 ? "" : i == lines.size() - 1 ? " and " : ", ")
              .append(l.qty).append(" ").append(item != null ? name(item) : l.item);
        }
        return sb.toString();
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static Result evaluate(Draft draft, VillagerAgentData agent, VillagerEntity villager,
                                   ServerPlayerEntity player, long now, boolean allowCounter, boolean store) {
        return evaluate(draft, agent, villager, player, now, allowCounter, store, false);
    }

    private static Result evaluate(Draft draft, VillagerAgentData agent, VillagerEntity villager,
                                   ServerPlayerEntity player, long now, boolean allowCounter, boolean store,
                                   boolean skipPriceFloor) {
        if (draft == null || draft.isEmpty()) return new Result(null, "empty offer");
        if (draft.playerGets.isEmpty()) return new Result(null, "nothing for you to get");
        if (draft.playerGives.isEmpty()) return new Result(null, "nothing for you to pay");
        Map<Item, Integer> gives = merge(draft.playerGives), gets = merge(draft.playerGets);
        if (gives == null || gets == null) return new Result(null, "unknown item in the offer");
        if (gives.size() > 2 || gets.size() > 2) return new Result(null, "more than two kinds of item per side");

        List<ItemStack> armor = armorOf(villager);
        ItemStack[] giveStacks = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        ItemStack[] getStacks = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        int i = 0;
        for (Map.Entry<Item, Integer> e : gives.entrySet()) {
            ItemStack stack = stackFrom(player.inventory.items, null, e.getKey(), e.getValue());
            if (stack == null) return new Result(null, "you don't have " + e.getValue() + "x " + name(e.getKey()));
            giveStacks[i++] = stack;
        }
        i = 0;
        for (Map.Entry<Item, Integer> e : gets.entrySet()) {
            ItemStack stack = stackFrom(agent.getInventory().getItems(), armor, e.getKey(), e.getValue());
            if (stack == null) return new Result(null, "I don't have " + e.getValue() + "x " + name(e.getKey()));
            getStacks[i++] = stack;
        }
        if (TradeInventory.plan(player.inventory.items, agent.getInventory().getItems(), armor, giveStacks, getStacks) == null)
            return new Result(null, "not enough room in someone's inventory");

        double offered = 0, requested = 0;
        for (ItemStack s : giveStacks) if (!s.isEmpty()) offered += VillagerEconomySystem.buyPriceForPlayer(agent, id(s)) * s.getCount();
        for (ItemStack s : getStacks) if (!s.isEmpty()) requested += VillagerEconomySystem.sellPriceForPlayer(agent, id(s)) * s.getCount();
        if (!Double.isFinite(requested) || requested <= 0 || !Double.isFinite(offered))
            return new Result(null, "I can't put a price on that");
        if (!skipPriceFloor && offered < requested * VillagerEconomySystem.DEAL_FLOOR) {
            Result low = new Result(null, String.format(Locale.ROOT,
                    "that's worth %.1f to me, and what you ask is worth at least %.1f",
                    offered, requested * VillagerEconomySystem.DEAL_FLOOR));
            low.tooCheap = true;
            low.offered = offered; low.requested = requested;
            if (allowCounter) low.counter = counterOffer(draft, giveStacks, getStacks, agent, villager, player, now, store);
            return low;
        }

        TradeProposal p = new TradeProposal(agent.getVillagerId(), player.getUUID(), giveStacks, getStacks, now + TTL_TICKS);
        if (store) PENDING.computeIfAbsent(p.villagerId, k -> new HashMap<>()).put(p.playerId, p);
        Result ok = new Result(p, null);
        ok.offered = offered; ok.requested = requested;
        return ok;
    }

    /**
     * Remove and return the pending offer if the submitted trade is exactly it (same items, NBT and counts in any
     * slot order) and it has not expired; null otherwise. A non-matching submission leaves the offer pending.
     */
    public static TradeProposal take(UUID villagerId, UUID playerId, long now,
                                     ItemStack give1, ItemStack give2, ItemStack get1, ItemStack get2) {
        TradeProposal p = peek(villagerId, playerId, now);
        if (p == null || !sameSide(p.give1, p.give2, give1, give2) || !sameSide(p.get1, p.get2, get1, get2)) return null;
        PENDING.get(villagerId).remove(playerId);
        return p;
    }

    /** The open offer for this pair, or null (expired offers are dropped). */
    public static TradeProposal peek(UUID villagerId, UUID playerId, long now) {
        Map<UUID, TradeProposal> byPlayer = PENDING.get(villagerId);
        if (byPlayer == null) return null;
        TradeProposal p = byPlayer.get(playerId);
        if (p != null && now > p.expiresAt) { byPlayer.remove(playerId); return null; }
        return p;
    }

    /** Drop the open offer (player declined). Returns it, or null if there was none. */
    public static TradeProposal cancel(UUID villagerId, UUID playerId) {
        Map<UUID, TradeProposal> byPlayer = PENDING.get(villagerId);
        return byPlayer == null ? null : byPlayer.remove(playerId);
    }

    /** "2x iron ingot + 1x stick for 1x chicken" — for memories and logs. */
    public String describe() {
        return side(give1, give2) + " for " + side(get1, get2);
    }

    /** What the player pays, e.g. "3x pumpkin". */
    public String givesText() { return side(give1, give2); }

    /** What the player gets, e.g. "1x salmon". */
    public String getsText() { return side(get1, get2); }

    /**
     * The cheapest fair version of a too-cheap offer: first ask for more of the player's most valuable payment item
     * (as little more as needed), else give fewer goods. Null if neither works (player lacks the items, stack
     * limits). The counter is checked by {@link #create} like any offer and stored as the pending one.
     */
    private static TradeProposal counterOffer(Draft draft, ItemStack[] gives, ItemStack[] gets, VillagerAgentData agent,
                                              VillagerEntity villager, ServerPlayerEntity player, long now, boolean store) {
        double need = 0, offered = 0;
        for (ItemStack s : gets) if (!s.isEmpty()) need += VillagerEconomySystem.sellPriceForPlayer(agent, id(s)) * s.getCount();
        need *= VillagerEconomySystem.DEAL_FLOOR;
        for (ItemStack s : gives) if (!s.isEmpty()) offered += VillagerEconomySystem.buyPriceForPlayer(agent, id(s)) * s.getCount();
        // 1) More payment: raise the item worth most per unit.
        int best = -1;
        double bestValue = 0;
        for (int i = 0; i < gives.length; i++) {
            if (gives[i].isEmpty()) continue;
            double v = VillagerEconomySystem.buyPriceForPlayer(agent, id(gives[i]));
            if (Double.isFinite(v) && v > bestValue) { bestValue = v; best = i; }
        }
        if (best >= 0) {
            int extra = (int) Math.ceil((need - offered) / bestValue - 1e-9);
            Draft more = copyWith(gives, gets, best, gives[best].getCount() + extra, -1, 0);
            Result r = evaluate(more, agent, villager, player, now, false, store);
            if (r.ok()) return r.proposal;
        }
        // 2) Fewer goods: lower the quantity of the single item asked for until the payment covers it.
        for (int i = 0; i < gets.length; i++) {
            if (gets[i].isEmpty() || gets[i].getCount() <= 1) continue;
            double unit = VillagerEconomySystem.sellPriceForPlayer(agent, id(gets[i])) * VillagerEconomySystem.DEAL_FLOOR;
            double other = 0;
            for (int j = 0; j < gets.length; j++)
                if (j != i && !gets[j].isEmpty())
                    other += VillagerEconomySystem.sellPriceForPlayer(agent, id(gets[j])) * gets[j].getCount() * VillagerEconomySystem.DEAL_FLOOR;
            int n = (int) Math.floor((offered - other) / unit + 1e-9);
            if (n < 1 || n >= gets[i].getCount()) continue;
            Result r = evaluate(copyWith(gives, gets, -1, 0, i, n), agent, villager, player, now, false, store);
            if (r.ok()) return r.proposal;
        }
        return null;
    }

    /** A draft of these stacks with one give count and/or one get count replaced (index -1 = unchanged). */
    private static Draft copyWith(ItemStack[] gives, ItemStack[] gets, int giveIdx, int giveQty, int getIdx, int getQty) {
        Draft d = new Draft();
        for (int i = 0; i < gives.length; i++)
            if (!gives[i].isEmpty()) d.playerGives.add(new Line(id(gives[i]), i == giveIdx ? giveQty : gives[i].getCount()));
        for (int i = 0; i < gets.length; i++)
            if (!gets[i].isEmpty()) d.playerGets.add(new Line(id(gets[i]), i == getIdx ? getQty : gets[i].getCount()));
        return d;
    }

    // ── Helpers ──

    private static boolean sameSide(ItemStack a1, ItemStack a2, ItemStack b1, ItemStack b2) {
        return (same(a1, b1) && same(a2, b2)) || (same(a1, b2) && same(a2, b1));
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return ItemStack.isSame(a, b) && ItemStack.tagMatches(a, b) && a.getCount() == b.getCount();
    }

    /** Merge duplicate lines; null if an id is unknown or a count is not positive. */
    private static Map<Item, Integer> merge(List<Line> lines) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Line l : lines) {
            Item item = resolve(l.item);
            if (item == null || l.qty <= 0) return null;
            out.merge(item, l.qty, Integer::sum);
        }
        return out;
    }

    static Item resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) return null;
        return ForgeRegistries.ITEMS.getValue(rl);
    }

    /**
     * A stack of {@code qty} x {@code item} copied from the first matching stack in {@code items} (then {@code extra}),
     * so its NBT matches what will be removed; null if the owner holds fewer than {@code qty} of that exact kind or
     * {@code qty} exceeds one stack.
     */
    private static ItemStack stackFrom(List<ItemStack> items, List<ItemStack> extra, Item item, int qty) {
        ItemStack template = null;
        for (ItemStack s : items) if (!s.isEmpty() && s.getItem() == item) { template = s; break; }
        if (template == null && extra != null)
            for (ItemStack s : extra) if (!s.isEmpty() && s.getItem() == item) { template = s; break; }
        if (template == null || qty > template.getMaxStackSize()) return null;
        ItemStack stack = template.copy();
        stack.setCount(qty);
        int have = TradeInventory.count(items, stack) + (extra == null ? 0 : TradeInventory.count(extra, stack));
        return have >= qty ? stack : null;
    }

    private static List<ItemStack> armorOf(VillagerEntity villager) {
        List<ItemStack> armor = new ArrayList<>();
        EquipmentSlotType[] slots = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST, EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
        for (EquipmentSlotType slot : slots) armor.add(villager == null ? ItemStack.EMPTY : villager.getItemBySlot(slot).copy());
        return armor;
    }

    private static String id(ItemStack s) { return s.getItem().getRegistryName().toString(); }

    private static String name(Item item) {
        ResourceLocation rl = item.getRegistryName();
        return rl == null ? "?" : rl.getPath().replace('_', ' ');
    }

    private static String side(ItemStack a, ItemStack b) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : new ItemStack[]{a, b}) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" + ");
            sb.append(s.getCount()).append("x ").append(name(s.getItem()));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }
}
