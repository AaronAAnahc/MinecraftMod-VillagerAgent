package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.server.ServerWorld;

/** Bridges live material deficits to leased jobs; never drives navigation itself. */
public final class ResourceWorkSystem {
    public static WorkOrderBook.Order request(ServerWorld world, VillagerAgentData requester, String item, int desiredStock) {
        ResourceCatalog.Source source = ResourceCatalog.get(item);
        if (source == null || source.profession == ResourceProfession.NONE || !ModConfig.ENABLE_ECONOMY.get()) return null;
        WorkOrderSavedData data = WorkOrderSavedData.get(world);
        WorkOrderBook.Order o = data.book.request(requester.getVillagerId(), item, source.profession,
                Math.min(256, desiredStock), count(requester, item), world.getGameTime());
        data.setDirty(); return o;
    }
    public static int count(VillagerAgentData agent, String item) {
        return agent.getInventory().countItem(new ItemStack(ResourceCatalog.item(item)));
    }
    public static WorkOrderBook.Order requestOwned(ServerWorld world, VillagerAgentData requester,
                                                   java.util.UUID owner, String item, int desired) {
        ResourceCatalog.Source source = ResourceCatalog.get(item);
        if (source == null || source.profession == ResourceProfession.NONE || !ModConfig.ENABLE_ECONOMY.get() || !ModConfig.ENABLE_WORLD_INTERACTION.get()) return null;
        WorkOrderSavedData data = WorkOrderSavedData.get(world);
        WorkOrderBook.Order order = data.book.requestOwned(owner, requester.getVillagerId(), item, source.profession,
                Math.min(256, desired), count(requester, item), world.getGameTime());
        data.setDirty(); return order;
    }
    public static void assign(VillagerAgentData agent, ResourceProfession role) {
        agent.setResourceProfession(role);
        // Same one-time starter convention as existing professions; changing role cannot mint kits.
        if (role != ResourceProfession.NONE && !agent.hasResourceStarter()) {
            agent.getInventory().addItem(new ItemStack(role == ResourceProfession.MINER ? Items.STONE_PICKAXE : Items.WOODEN_AXE));
            agent.markResourceStarterGiven();
        }
    }
    public static void offerWork(ServerWorld world, VillagerEntity self, VillagerAgentData agent) {
        if (!ModConfig.ENABLE_ECONOMY.get() || !ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        WorkOrderSavedData data = WorkOrderSavedData.get(world);
        long now = world.getGameTime();
        data.book.tick(now);
        for (WorkOrderBook.Order o : data.book.all()) {
            VillagerAgentData requester = VillagerAgentManager.getAgent(o.requester);
            Entity entity = world.getEntity(o.requester);
            if (requester != null && entity != null) {
                data.book.reconcile(o.id, count(requester, o.item));
                if (o.status == WorkOrderBook.Status.DELIVERED) clearSatisfiedWant(o);
            }
        }
        data.setDirty();
        for (AgentGoal g : agent.getGoals()) if ("fulfill_order".equals(g.getGoalType())) return;
        WorkOrderBook.Order best = null;
        double nearest = 64 * 64;
        int active = 0, ownedByOther = 0, wrongRole = 0, requesterUnavailable = 0, outOfRange = 0;
        for (WorkOrderBook.Order o : data.book.all()) {
            if (!o.active()) continue;
            active++;
            if (o.requester.equals(agent.getVillagerId())) continue;
            if (o.worker != null && !o.worker.equals(agent.getVillagerId())) { ownedByOther++; continue; }
            Entity requester = world.getEntity(o.requester);
            if (!(requester instanceof VillagerEntity) || !requester.isAlive()) { requesterUnavailable++; continue; }
            ResourceProfession role = agent.getResourceProfession();
            ResourceCatalog.Source source = ResourceCatalog.get(o.item);
            if (role == ResourceProfession.NONE || role != o.profession || source == null
                    || agent.getCapabilityPool().levelOf(source.capability) < 1) { wrongRole++; continue; }
            double d = self.distanceToSqr(requester);
            if (d < nearest) { nearest = d; best = o; }
            else if (d >= 64 * 64) outOfRange++;
        }
        if (best == null) {
            if (active > 0 && (agent.brain().lastOfferLogTick == Long.MIN_VALUE
                    || now - agent.brain().lastOfferLogTick >= 200)) {
                agent.brain().lastOfferLogTick = now;
                BrainLog.diagnostic(agent, "order-offer-empty", "active=" + active
                        + " heldByOthers=" + ownedByOther + " wrongRole=" + wrongRole
                        + " requesterUnavailable=" + requesterUnavailable + " outOfRange=" + outOfRange
                        + " role=" + agent.getResourceProfession() + " position=" + self.blockPosition());
            }
            return;
        }
        BrainLog.diagnostic(agent, "order-offer", "id=" + best.id + " item=" + best.item
                + " remaining=" + best.remaining + " distanceSq=" + nearest
                + " currentRole=" + agent.getResourceProfession());
        // Workstation professions are established before accepting an order.
        if (!data.book.claim(best.id, agent.getVillagerId(), now)) return;
        data.setDirty();
        AgentGoal goal = new AgentGoal("fulfill_order", "Supply " + best.remaining + " " + best.item, 7);
        goal.setWorkOrderId(best.id); goal.setTargetItem(best.item); goal.setTargetQuantity(best.remaining);
        goal.setTargetEntityId(best.requester); goal.setLifecycle(AgentGoal.Lifecycle.EXPIRING);
        goal.setDeadlineTick(best.expiresAt);
        agent.getGoals().add(goal);
        BrainLog.goalAdded(agent, goal, "material-demand");
    }
    public static boolean hasSupplier(ServerWorld world, VillagerEntity buyer, String item) {
        ResourceCatalog.Source source = ResourceCatalog.get(item);
        if (source == null || source.profession == ResourceProfession.NONE) return false;
        for (VillagerAgentData worker : VillagerAgentManager.getAllAgents()) {
            if (worker.getVillagerId().equals(buyer.getUUID()) || worker.getResourceProfession() != source.profession
                    || !source.skilled(worker) || !source.hasTool(worker)) continue;
            Entity entity = world.getEntity(worker.getVillagerId());
            if (entity instanceof VillagerEntity && entity.isAlive() && buyer.distanceToSqr(entity) <= 4096) return true;
        }
        return false;
    }
    public static void clearSatisfiedWant(WorkOrderBook.Order order) {
        if (order.status == WorkOrderBook.Status.DELIVERED)
            VillageSignalBoard.get().removeByAuthor(order.requester, VillageSignalBoard.MsgType.TRADE_WANT,
                    new net.minecraft.util.ResourceLocation(order.item));
    }
    private static boolean jobless(VillagerAgentData a) {
        String p = String.valueOf(a.getProfession()).toLowerCase(java.util.Locale.ROOT);
        return p.equals("none") || p.equals("villager") || p.contains("unemployed");
    }
    public static void release(ServerWorld world, VillagerAgentData agent, AgentGoal goal) {
        if (goal == null || goal.getWorkOrderId() == null) return;
        WorkOrderSavedData data = WorkOrderSavedData.get(world);
        data.book.release(goal.getWorkOrderId(), agent.getVillagerId()); data.setDirty();
    }
    private ResourceWorkSystem() {}
}
