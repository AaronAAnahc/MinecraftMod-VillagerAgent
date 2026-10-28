package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Compare cash expenditure on live offers against ordinary crafting from a simulated basket. */
public final class AcquisitionPlanner {
    public static final class Choice {
        public final List<VillageSignalBoard.VillageMessage> offers;
        public final int[] quantities;
        public final int emeralds;
        Choice(List<VillageSignalBoard.VillageMessage> offers, int[] quantities, int emeralds) {
            this.offers = offers; this.quantities = quantities; this.emeralds = emeralds;
        }
        public boolean needsPurchase() { return Arrays.stream(quantities).anyMatch(q -> q > 0); }
    }

    public static Choice choose(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                Item target, int desired, boolean crafting) {
        String id = target.getRegistryName().toString();
        boolean craftable = crafting && CraftingPlan.supports(world, target) && (agent.getUnlockedRecipes().contains(id)
                || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), id));
        if (crafting && !craftable) return null;
        Set<Item> relevant = craftable ? CraftingPlan.acquisitionItems(world, target) : Collections.singleton(target);
        if (crafting) relevant.remove(target); // This method buys ingredients, never the finished product.
        // One path query per selection, never one per candidate basket.
        boolean table = WorkbenchMemory.path(agent, villager, world) != null;
        List<VillageSignalBoard.VillageMessage> offers = new ArrayList<>();
        if (ModConfig.ENABLE_ECONOMY.get()) VillagerEconomySystem.advertiseAcquisitionStock(world, villager, relevant);
        if (ModConfig.ENABLE_ECONOMY.get())
            for (VillageSignalBoard.VillageMessage offer : VillageMarkets.board(world, agent.getVillagerId()).messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE)) {
                if (offer.resolved || offer.matchedWith != null || offer.expireTick <= world.getGameTime()
                        || offer.author.equals(agent.getVillagerId()) || offer.item == null || offer.unitPrice <= 0
                        || TradeCoordinator.busy(offer.author)) continue;
                Item item = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(offer.item.toString());
                VillagerAgentData seller = VillagerAgentManager.getAgent(offer.author);
                Entity entity = world.getEntity(offer.author);
                if (!relevant.contains(item) || seller == null || !(entity instanceof VillagerEntity)
                        || !entity.isAlive() || TradeDemo.isActive(offer.author) || villager.distanceToSqr(entity) > 4096
                        || !VillagerEconomySystem.hasPrice(seller, offer.item.toString())) continue;
                offers.add(offer);
            }
        offers.sort(Comparator.comparing((VillageSignalBoard.VillageMessage m) -> !m.item.toString().equals(id))
                .thenComparingDouble(m -> m.unitPrice).thenComparing(m -> m.author.toString()));
        if (offers.size() > 8) offers = new ArrayList<>(offers.subList(0, 8));
        final List<VillageSignalBoard.VillageMessage> selected = offers;
        int[] limits = new int[offers.size()];
        for (int i = 0; i < limits.length; i++) {
            VillageSignalBoard.VillageMessage offer = offers.get(i);
            Item item = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(offer.item.toString());
            limits[i] = Math.max(0, Math.min(32, Math.min(offer.quantity, TradeInventory.count(
                    VillagerAgentManager.getAgent(offer.author).getInventory().getItems(), new ItemStack(item)))));
        }
        int funds = TradeInventory.count(agent.getInventory().getItems(), new ItemStack(Items.EMERALD));
        java.util.function.ToIntFunction<int[]> price = q -> cost(selected, q);
        java.util.function.Predicate<int[]> feasible = q -> {
            AgentInventory trial = new AgentInventory();
            for (int i = 0; i < agent.getInventory().getItems().size(); i++)
                trial.setStackInSlot(i, agent.getInventory().getStackInSlot(i).copy());
            // Purchases are simulated as the same atomic inventory exchanges used at settlement.
            Map<UUID, List<ItemStack>> sellerStocks = new HashMap<>();
            for (int i = 0; i < q.length; i++) {
                if (q[i] == 0) continue;
                VillageSignalBoard.VillageMessage offer = selected.get(i);
                VillagerAgentData seller = VillagerAgentManager.getAgent(offer.author);
                int payment = VillagerEconomySystem.quoteCost(offer, q[i]);
                Item item = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(offer.item.toString());
                List<ItemStack> stock = sellerStocks.computeIfAbsent(offer.author,
                        key -> TradeInventory.copy(seller.getInventory().getItems()));
                TradeInventory.Plan exchange = TradeInventory.plan(trial.getItems(), stock,
                        Collections.emptyList(), stacks(Items.EMERALD, payment), stacks(item, q[i]));
                if (exchange == null) return false;
                sellerStocks.put(offer.author, exchange.villager);
                for (int slot = 0; slot < exchange.player.size(); slot++) trial.setStackInSlot(slot, exchange.player.get(slot));
            }
            int shortage = desired - trial.countItem(new ItemStack(target));
            if (shortage <= 0) return true;
            if (!craftable) return false;
            CraftingPlan plan = CraftingPlan.build(world, trial, target, shortage);
            return plan.complete && (table || plan.steps.stream().noneMatch(CraftingPlan.Step::needsTable));
        };
        int[] quantities = AcquisitionSearch.choose(limits, funds, 128, price, feasible);
        return quantities == null ? null : new Choice(selected, quantities, price.applyAsInt(quantities));
    }

    private static int cost(List<VillageSignalBoard.VillageMessage> offers, int[] quantities) {
        long total = 0;
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] == 0) continue;
            VillageSignalBoard.VillageMessage offer = offers.get(i);
            int price = VillagerEconomySystem.quoteCost(offer, quantities[i]);
            if (price < 0) return Integer.MAX_VALUE;
            total += price;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Start just the first purchase; after delivery, reevaluate using actual stock and prices. */
    public static UUID purchase(Choice choice, VillagerAgentData agent, VillagerEntity villager, ServerWorld world, UUID owner) {
        for (int i = 0; i < choice.quantities.length; i++) {
            if (choice.quantities[i] == 0) continue;
            VillageSignalBoard board = VillageMarkets.board(world, agent.getVillagerId());
            VillageSignalBoard.VillageMessage offer = choice.offers.get(i);
            UUID want = board.postOwnedWant(owner, agent.getVillagerId(), villager.blockPosition(),
                    offer.item, choice.quantities[i], world.getGameTime());
            board.find(want).unitPrice = offer.unitPrice;
            if (VillagerEconomySystem.acceptTrade(world, board.find(want), offer, offer.unitPrice)) return want;
            board.removeOwned(owner);
            return null;
        }
        return null;
    }

    private static ItemStack[] stacks(Item item, int count) {
        List<ItemStack> result = new ArrayList<>();
        while (count > 0) {
            ItemStack stack = new ItemStack(item, Math.min(count, new ItemStack(item).getMaxStackSize()));
            result.add(stack); count -= stack.getCount();
        }
        return result.toArray(new ItemStack[0]);
    }
    private AcquisitionPlanner() {}
}
