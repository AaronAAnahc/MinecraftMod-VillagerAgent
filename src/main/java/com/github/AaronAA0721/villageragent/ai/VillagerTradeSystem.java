package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Villager-to-villager trade, fully LLM-driven for realism:
 * <ol>
 *   <li>The <b>initiator</b> (A) proposes a concrete barter — item-for-item, or emeralds as a
 *       common currency — by reading both inventories and its own needs.</li>
 *   <li>The <b>receiver</b> (B) freely decides ACCEPT / REJECT / COUNTER, guided by its own
 *       needs, relationship to A, and personality.</li>
 *   <li>If B counters, the counter is the final price and the exchange happens directly.</li>
 *   <li>The item transfer is executed by code (never trusting the LLM): both sides are verified
 *       to actually hold what they're giving, then the stacks are moved.</li>
 * </ol>
 *
 * <p>Entered from {@code VillagerAgentManager.executeTradeGoal} once the initiator has walked to
 * the target villager. A trade is a "social" activity: both villagers are marked socializing so
 * {@code VillagerSocialSystem} won't interrupt them mid-negotiation.
 */
public final class VillagerTradeSystem {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Relationship change on a completed trade (accept or counter). */
    private static final int REL_GAIN_ACCEPT = 5;
    /** Relationship change on a rejection. */
    private static final int REL_GAIN_REJECT = -2;

    private VillagerTradeSystem() {}

    /** Called by {@code executeTradeGoal} after the initiator reaches the target villager. */
    public static void initiate(ServerWorld world, VillagerEntity a, VillagerAgentData agentA,
                                VillagerEntity b, VillagerAgentData agentB) {
        agentA.setSocializing(true);
        agentB.setSocializing(true);
        agentA.setLastSocialTick(world.getGameTime());
        agentB.setLastSocialTick(world.getGameTime());
        proposeTrade(world, a, agentA, b, agentB);
    }

    // ── Stage 1: initiator A proposes ────────────────────────────────────────

    private static void proposeTrade(ServerWorld world, VillagerEntity a, VillagerAgentData agentA,
                                     VillagerEntity b, VillagerAgentData agentB) {
        String sys = "You are " + agentA.getName() + ", a " + agentA.getProfession()
                + " villager. You want to trade with " + agentB.getName() + " ("
                + agentB.getProfession() + "). You may barter item-for-item, or use emeralds as a "
                + "common currency. Propose ONE concrete trade. Output EXACTLY one line:\n"
                + "GIVE minecraft:<item> x<qty> WANT minecraft:<item> x<qty>";

        String user = "Your inventory: " + describeInventory(agentA) + "\n"
                + agentB.getName() + "'s inventory: " + describeInventory(agentB) + "\n"
                + "Your current needs: " + VillagerNeedsSystem.buildNeedsDescription(agentA) + "\n"
                + "Your personality: " + agentA.getPersonality() + "\n"
                + "Propose a trade that benefits you but is fair enough that " + agentB.getName()
                + " might accept:";

        long tick = world.getGameTime();
        LLMGuard.query(agentA.getVillagerId(), tick, sys, user).thenAccept(proposal -> {
            TradeOffer offer = parseOffer(proposal);
            if (offer == null || !offerValid(agentA, agentB, offer)) {
                agentA.addMemory("Wanted to trade with " + agentB.getName()
                        + " but couldn't settle on terms");
                release(agentA, agentB);
                return;
            }
            evaluateTrade(world, a, agentA, b, agentB, offer);
        }).exceptionally(e -> {
            LOGGER.debug("Trade proposal failed: {}", e.getMessage());
            release(agentA, agentB);
            return null;
        });
    }

    // ── Stage 2: receiver B decides ──────────────────────────────────────────

    private static void evaluateTrade(ServerWorld world, VillagerEntity a, VillagerAgentData agentA,
                                      VillagerEntity b, VillagerAgentData agentB, TradeOffer offer) {
        int relBtoA = agentB.getRelationships().getOrDefault(agentA.getVillagerId().toString(), 0);

        String sys = "You are " + agentB.getName() + ", a " + agentB.getProfession() + " villager. "
                + agentA.getName() + " proposes a trade. Decide freely. Output EXACTLY one of:\n"
                + "ACCEPT <short reason>\n"
                + "REJECT <short reason>\n"
                + "COUNTER GIVE minecraft:<item> x<qty> WANT minecraft:<item> x<qty>  (your counter, from YOUR side)";

        String user = agentA.getName() + " offers to GIVE you " + offer.giveQty + "x " + offer.give
                + " and WANTS " + offer.wantQty + "x " + offer.want + " from you.\n"
                + "Your inventory: " + describeInventory(agentB) + "\n"
                + "Your needs: " + VillagerNeedsSystem.buildNeedsDescription(agentB) + "\n"
                + "Your relationship with " + agentA.getName() + ": " + relBtoA + "/100\n"
                + "Your personality: " + agentB.getPersonality() + "\n"
                + "Do you accept, reject, or counter?";

        long tick = world.getGameTime();
        LLMGuard.query(agentB.getVillagerId(), tick, sys, user).thenAccept(decision -> {
            DecisionJournal.record(agentB.getVillagerId(), DecisionJournal.Kind.SOCIAL, tick,
                    user, decision, DecisionJournal.outcome(decision));
            if (decision == null || LLMService.isFailure(decision)) { release(agentA, agentB); return; }

            String d = decision.trim();
            if (d.toUpperCase().startsWith("ACCEPT")) {
                executeExchange(a, agentA, b, agentB, offer, REL_GAIN_ACCEPT);
            } else if (d.toUpperCase().startsWith("COUNTER")) {
                TradeOffer counter = parseOffer(d); // counter is from B's side: B gives counter.give, wants counter.want
                if (counter != null && counterValid(agentB, agentA, counter)) {
                    executeCounter(a, agentA, b, agentB, counter, REL_GAIN_ACCEPT);
                } else {
                    agentA.addMemory(agentB.getName() + " made an unclear counter-offer — trade fell through");
                    release(agentA, agentB);
                }
            } else {
                applyRelation(agentA, agentB, REL_GAIN_REJECT);
                agentA.addMemory(agentB.getName() + " rejected my trade offer");
                agentB.addMemory("Rejected a trade offer from " + agentA.getName());
                LOGGER.info("{} rejected a trade from {}", agentB.getName(), agentA.getName());
                release(agentA, agentB);
            }
        }).exceptionally(e -> {
            LOGGER.debug("Trade decision failed: {}", e.getMessage());
            release(agentA, agentB);
            return null;
        });
    }

    // ── Exchange ─────────────────────────────────────────────────────────────

    /** A's original offer accepted: A gives {@code offer.give}, B gives {@code offer.want}. */
    private static void executeExchange(VillagerEntity a, VillagerAgentData agentA,
                                        VillagerEntity b, VillagerAgentData agentB,
                                        TradeOffer offer, int relDelta) {
        boolean ok = removeById(agentA.getInventory(), offer.give, offer.giveQty)
                && removeById(agentB.getInventory(), offer.want, offer.wantQty);
        if (!ok) {
            agentA.addMemory("Trade with " + agentB.getName() + " fell through — items went missing");
            release(agentA, agentB);
            return;
        }
        addById(agentB.getInventory(), offer.give, offer.giveQty);
        addById(agentA.getInventory(), offer.want, offer.wantQty);
        applyRelation(agentA, agentB, relDelta);
        agentA.addMemory("Traded with " + agentB.getName() + ": gave " + offer.giveQty + "x "
                + offer.give + " for " + offer.wantQty + "x " + offer.want);
        agentB.addMemory("Traded with " + agentA.getName() + ": gave " + offer.wantQty + "x "
                + offer.want + " for " + offer.giveQty + "x " + offer.give);
        LOGGER.info("{} and {} traded: {} x{} <-> {} x{}", agentA.getName(), agentB.getName(),
                offer.give, offer.giveQty, offer.want, offer.wantQty);
        release(agentA, agentB);
    }

    /** B's counter accepted as the final price: B gives {@code counter.give}, A gives {@code counter.want}. */
    private static void executeCounter(VillagerEntity a, VillagerAgentData agentA,
                                       VillagerEntity b, VillagerAgentData agentB,
                                       TradeOffer counter, int relDelta) {
        boolean ok = removeById(agentB.getInventory(), counter.give, counter.giveQty)
                && removeById(agentA.getInventory(), counter.want, counter.wantQty);
        if (!ok) {
            agentA.addMemory("Counter-trade with " + agentB.getName() + " fell through — items went missing");
            release(agentA, agentB);
            return;
        }
        addById(agentA.getInventory(), counter.give, counter.giveQty);
        addById(agentB.getInventory(), counter.want, counter.wantQty);
        applyRelation(agentA, agentB, relDelta);
        agentA.addMemory("Bargained with " + agentB.getName() + ": settled on " + counter.wantQty + "x "
                + counter.want + " for " + counter.giveQty + "x " + counter.give);
        agentB.addMemory("Bargained with " + agentA.getName() + ": got " + counter.wantQty + "x "
                + counter.want + " for " + counter.giveQty + "x " + counter.give);
        LOGGER.info("{} and {} bargained: {} x{} <-> {} x{}", agentB.getName(), agentA.getName(),
                counter.give, counter.giveQty, counter.want, counter.wantQty);
        release(agentA, agentB);
    }

    private static void applyRelation(VillagerAgentData agentA, VillagerAgentData agentB, int delta) {
        agentA.updateRelationship(agentB.getVillagerId().toString(), delta);
        agentB.updateRelationship(agentA.getVillagerId().toString(), delta);
    }

    private static void release(VillagerAgentData agentA, VillagerAgentData agentB) {
        agentA.setSocializing(false);
        agentB.setSocializing(false);
    }

    // ── Parse / validate ─────────────────────────────────────────────────────

    /** Parse a {@code GIVE minecraft:item xN WANT minecraft:item xM} line (extra text tolerated). */
    private static TradeOffer parseOffer(String text) {
        if (text == null || text.isEmpty()) return null;
        String up = text.toUpperCase();
        int giveIdx = up.indexOf("GIVE");
        int wantIdx = up.indexOf("WANT", giveIdx < 0 ? 0 : giveIdx + 4);
        if (giveIdx < 0 || wantIdx < 0) return null;
        String givePart = text.substring(giveIdx + 4, wantIdx).trim();
        String wantPart = text.substring(wantIdx + 4).trim();
        String[] g = parseItemQty(givePart);
        String[] w = parseItemQty(wantPart);
        if (g == null || w == null) return null;
        try {
            return new TradeOffer(g[0], Integer.parseInt(g[1]), w[0], Integer.parseInt(w[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse {@code minecraft:emerald x3} → {@code ["minecraft:emerald", "3"]}. */
    private static String[] parseItemQty(String part) {
        String[] toks = part.split("\\s+");
        String id = null;
        int qty = 1;
        for (String t : toks) {
            if (t.contains(":") && id == null) id = t;
            else if (t.toLowerCase().startsWith("x")) {
                try { qty = Integer.parseInt(t.substring(1)); } catch (NumberFormatException ignored) {}
            }
        }
        if (id == null) return null;
        return new String[]{id, String.valueOf(qty)};
    }

    private static boolean offerValid(VillagerAgentData agentA, VillagerAgentData agentB, TradeOffer o) {
        return o.giveQty > 0 && o.wantQty > 0
                && countById(agentA.getInventory(), o.give) >= o.giveQty
                && countById(agentB.getInventory(), o.want) >= o.wantQty
                && itemExists(o.give) && itemExists(o.want);
    }

    private static boolean counterValid(VillagerAgentData agentB, VillagerAgentData agentA, TradeOffer o) {
        return o.giveQty > 0 && o.wantQty > 0
                && countById(agentB.getInventory(), o.give) >= o.giveQty
                && countById(agentA.getInventory(), o.want) >= o.wantQty
                && itemExists(o.give) && itemExists(o.want);
    }

    private static boolean itemExists(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item != null && item != Items.AIR;
    }

    /** Count stacks matching a registry id exactly (ignores NBT/damage). */
    private static int countById(AgentInventory inv, String id) {
        int c = 0;
        for (ItemStack s : inv.getItems()) {
            if (!s.isEmpty() && s.getItem().getRegistryName().toString().equals(id)) c += s.getCount();
        }
        return c;
    }

    /** Remove {@code qty} items matching a registry id. Returns false if there aren't enough. */
    private static boolean removeById(AgentInventory inv, String id, int qty) {
        int remaining = qty;
        List<ItemStack> items = inv.getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty() && s.getItem().getRegistryName().toString().equals(id)) {
                int toRemove = Math.min(remaining, s.getCount());
                s.shrink(toRemove);
                remaining -= toRemove;
                if (s.isEmpty()) items.set(i, ItemStack.EMPTY);
                if (remaining <= 0) return true;
            }
        }
        return remaining == 0;
    }

    private static void addById(AgentInventory inv, String id, int qty) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null || item == Items.AIR) return;
        inv.addItem(new ItemStack(item, qty));
    }

    private static String describeInventory(VillagerAgentData agent) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ItemStack s : agent.getInventory().getItems()) {
            if (s.isEmpty()) continue;
            if (!first) sb.append(", ");
            sb.append(s.getCount()).append("x ").append(s.getItem().getRegistryName());
            first = false;
        }
        return first ? "empty" : sb.toString();
    }

    /** A concrete barter offer, from the offerer's perspective. */
    private static final class TradeOffer {
        final String give;
        final int giveQty;
        final String want;
        final int wantQty;
        TradeOffer(String give, int giveQty, String want, int wantQty) {
            this.give = give;
            this.giveQty = giveQty;
            this.want = want;
            this.wantQty = wantQty;
        }
    }
}
