package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.server.ServerWorld;

/** Bridges live material deficits to leased jobs; never drives navigation itself. */
public final class ResourceWorkSystem {
    /**
     * Record a material need as an {@code acquire_item} objective. The caller never decides whether the
     * item is produced or requested from the village; acquisition method selection does.
     */
    public static AgentGoal request(ServerWorld world, VillagerAgentData requester, String item, int desiredStock) {
        return FailureRecoveryPlanner.requireItem(requester, item, desiredStock);
    }
    public static int count(VillagerAgentData agent, String item) {
        return agent.getInventory().countItem(new ItemStack(ResourceCatalog.item(item)));
    }
    public static WorkOrderBook.Order requestOwned(ServerWorld world, VillagerAgentData requester,
                                                   java.util.UUID owner, String item, int desired) {
        return null; // Legacy free orders are no longer admitted.
    }
    /**
     * Assign the resource profession only. No tool is granted: not every profession needs one, and
     * tools are bought through the economy. The one-time currency grant lives with village join.
     */
    public static void assign(VillagerAgentData agent, ResourceProfession role) {
        agent.setResourceProfession(role);
    }
    public static void offerWork(ServerWorld world, VillagerEntity self, VillagerAgentData agent) {
        // Production is voluntarily accepted from the local paid market by the economy scan.
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
