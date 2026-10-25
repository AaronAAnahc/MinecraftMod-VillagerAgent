package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.DebtAgenda;
import com.github.AaronAA0721.villageragent.ai.LongTermAgenda;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerEconomySystem;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import com.github.AaronAA0721.villageragent.ai.TradeInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Packet sent from client to server when player requests a trade
 */
public class TradeRequestPacket {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final UUID villagerId;
    private final ItemStack offerItem1;
    private final ItemStack offerItem2;
    private final ItemStack requestItem1;
    private final ItemStack requestItem2;
    
    public TradeRequestPacket(UUID villagerId, ItemStack offer1, ItemStack offer2, ItemStack request1, ItemStack request2) {
        this.villagerId = villagerId;
        this.offerItem1 = offer1;
        this.offerItem2 = offer2;
        this.requestItem1 = request1;
        this.requestItem2 = request2;
    }
    
    public static void encode(TradeRequestPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
        buffer.writeItem(packet.offerItem1);
        buffer.writeItem(packet.offerItem2);
        buffer.writeItem(packet.requestItem1);
        buffer.writeItem(packet.requestItem2);
    }
    
    public static TradeRequestPacket decode(PacketBuffer buffer) {
        return new TradeRequestPacket(
                buffer.readUUID(),
                buffer.readItem(),
                buffer.readItem(),
                buffer.readItem(),
                buffer.readItem()
        );
    }
    
    public static void handle(TradeRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player == null) return;

            VillagerAgentData agent = VillagerAgentManager.getAgent(packet.villagerId);
            if (agent == null) {
                LOGGER.warn("No agent found for villager: " + packet.villagerId);
                sendResult(player, packet.villagerId, false, "This villager is no longer available.", null, null);
                return;
            }

            // Find the villager entity
            ServerWorld serverWorld = (ServerWorld) player.level;
            Entity entity = serverWorld.getEntity(packet.villagerId);
            VillagerEntity villager = (entity instanceof VillagerEntity) ? (VillagerEntity) entity : null;
            if (villager == null || !villager.isAlive() || !player.isAlive()
                    || player.distanceToSqr(villager) > 64.0) {
                sendResult(player, packet.villagerId, false, "The villager is no longer within trading distance.", agent, villager);
                return;
            }


            // Build trade description for logging
            String tradeDescription = buildTradeDescription(packet, agent);
            LOGGER.info("Trade request: " + tradeDescription);

            // Evaluate the trade deterministically from the value table (LLM-free).
            evaluateTradeWithValueTable(player, agent, packet, villager);
        });
        ctx.get().setPacketHandled(true);
    }
    
    private static String buildTradeDescription(TradeRequestPacket packet, VillagerAgentData agent) {
        StringBuilder sb = new StringBuilder();

        // What the player is GIVING TO the villager (villager receives)
        sb.append("The player is GIVING YOU: ");
        if (!packet.offerItem1.isEmpty()) {
            sb.append(packet.offerItem1.getCount()).append("x ").append(getItemName(packet.offerItem1));
        }
        if (!packet.offerItem2.isEmpty()) {
            if (!packet.offerItem1.isEmpty()) sb.append(" and ");
            sb.append(packet.offerItem2.getCount()).append("x ").append(getItemName(packet.offerItem2));
        }
        if (packet.offerItem1.isEmpty() && packet.offerItem2.isEmpty()) {
            sb.append("nothing");
        }

        // What the player WANTS FROM the villager (villager must give)
        sb.append("\nIn exchange, the player WANTS FROM YOU: ");
        if (!packet.requestItem1.isEmpty()) {
            sb.append(packet.requestItem1.getCount()).append("x ").append(getItemName(packet.requestItem1));
        }
        if (!packet.requestItem2.isEmpty()) {
            if (!packet.requestItem1.isEmpty()) sb.append(" and ");
            sb.append(packet.requestItem2.getCount()).append("x ").append(getItemName(packet.requestItem2));
        }
        if (packet.requestItem1.isEmpty() && packet.requestItem2.isEmpty()) {
            sb.append("nothing");
        }
        return sb.toString();
    }

    /**
     * Get a readable item name from an ItemStack
     */
    private static String getItemName(ItemStack stack) {
        String regName = stack.getItem().getRegistryName().toString();
        // Convert "minecraft:diamond_hoe" to "diamond hoe"
        if (regName.contains(":")) {
            regName = regName.substring(regName.indexOf(":") + 1);
        }
        return regName.replace("_", " ");
    }

    /**
     * Build a description of the villager's current inventory AND equipped armor.
     */
    private static String buildInventoryDescription(VillagerAgentData agent, VillagerEntity villager) {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, Integer> itemCounts = new java.util.HashMap<>();

        for (ItemStack stack : agent.getInventory().getItems()) {
            if (!stack.isEmpty()) {
                String itemName = getItemName(stack);
                itemCounts.merge(itemName, stack.getCount(), Integer::sum);
            }
        }

        // Include equipped armor
        StringBuilder armorDesc = new StringBuilder();
        if (villager != null) {
            EquipmentSlotType[] armorSlots = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST,
                    EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
            for (EquipmentSlotType slot : armorSlots) {
                ItemStack equipped = villager.getItemBySlot(slot);
                if (!equipped.isEmpty()) {
                    armorDesc.append(", wearing ").append(getItemName(equipped))
                             .append(" (").append(slot.getName()).append(")");
                }
            }
        }

        if (itemCounts.isEmpty() && armorDesc.length() == 0) {
            return "Your inventory is empty and you have no armor equipped.";
        }

        sb.append("Your current inventory: ");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            first = false;
        }
        if (armorDesc.length() > 0) {
            sb.append(". Equipped armor: ").append(armorDesc.substring(2)); // skip leading ", "
        }
        return sb.toString();
    }

    /**
     * Evaluate a player-initiated trade deterministically from the per-profession value table — no LLM needed.
     * The villager values what the player offers at its own <b>buy</b> price (what it would pay to acquire
     * those goods) and what the player requests at its own <b>sell</b> price (what it charges to part with
     * them). Accepted when offered value >= requested value × tolerance. Items absent from this profession's
     * trade table count as 0.01 (physically received but not valued), so a player cannot trade junk for
     * valuables — nor trade goods this profession simply does not deal in.
     */
    private static void evaluateTradeWithValueTable(ServerPlayerEntity player, VillagerAgentData agent,
                                                   TradeRequestPacket packet, VillagerEntity villager) {
        double offered = buyValueOf(packet.offerItem1, agent) + buyValueOf(packet.offerItem2, agent);
        double requested = sellValueOf(packet.requestItem1, agent) + sellValueOf(packet.requestItem2, agent);
        boolean accepted = offered >= requested * ModConfig.BARTER_FAIRNESS_TOLERANCE.get();

        String reason;
        if (accepted) {
            reason = "That's a fair trade.";
            if (!executeTrade(player, agent, packet, villager)) {
                reason = "Items changed or there is not enough inventory space. Nothing was taken.";
                accepted = false;
            }
        } else {
            reason = "That's not worth it for me.";
        }

        LOGGER.info("Trade " + (accepted ? "ACCEPTED" : "REJECTED")
                + " (offered " + offered + " >= requested " + requested + "): " + reason);

        sendResult(player, packet.villagerId, accepted, reason, agent, villager);
    }

    private static void sendResult(ServerPlayerEntity player, UUID villagerId, boolean accepted,
                                   String reason, VillagerAgentData agent, VillagerEntity villager) {
        List<ItemStack> inventory = agent == null ? new ArrayList<>() : collectInventorySnapshot(agent);
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new TradeResultPacket(villagerId, accepted, reason, inventory, collectArmorSnapshot(villager)));
    }

    /** What this villager would pay for the stack, per its personal value table (0.01/unit if it doesn't trade in it). */
    private static double buyValueOf(ItemStack stack, VillagerAgentData agent) {
        if (stack.isEmpty()) return 0.0;
        return VillagerEconomySystem.buyValue(agent,
                stack.getItem().getRegistryName().toString()) * stack.getCount();
    }

    /** What this villager would charge for the stack, per its personal value table (0.01/unit if it doesn't trade in it). */
    private static double sellValueOf(ItemStack stack, VillagerAgentData agent) {
        if (stack.isEmpty()) return 0.0;
        return VillagerEconomySystem.sellValue(agent,
                stack.getItem().getRegistryName().toString()) * stack.getCount();
    }

    /** Server-thread commit after all stock, NBT and capacity checks succeed on copies. */
    private static boolean executeTrade(ServerPlayerEntity player, VillagerAgentData agent,
                                        TradeRequestPacket packet, VillagerEntity villager) {
        TradeInventory.Plan plan = TradeInventory.plan(player.inventory.items,
                agent.getInventory().getItems(), collectArmorSnapshot(villager),
                new ItemStack[]{packet.offerItem1, packet.offerItem2},
                new ItemStack[]{packet.requestItem1, packet.requestItem2});
        if (plan == null) return false;

        for (int i = 0; i < plan.player.size(); i++) player.inventory.items.set(i, plan.player.get(i));
        for (int i = 0; i < plan.villager.size(); i++) agent.getInventory().setStackInSlot(i, plan.villager.get(i));
        EquipmentSlotType[] slots = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST,
                EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
        for (int i = 0; i < slots.length; i++) villager.setItemSlot(slots[i], plan.armor.get(i));
        player.inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        // Keep remaining armor where it is; auto-equipping here could overflow a full inventory.
        settleDebts(agent, player, packet);
        agent.addMemory("Traded with player " + player.getName().getString());
        return true;
    }

    /**
     * Settle any DebtAgenda whose debtor is this player and whose owed item was just handed
     * over in the trade. Payment is matched by registry id + quantity, so a promise of
     * "64 emeralds" is cleared the moment those emeralds actually change hands.
     */
    private static void settleDebts(VillagerAgentData agent, ServerPlayerEntity player, TradeRequestPacket packet) {
        String playerId = player.getUUID().toString();
        for (LongTermAgenda agenda : agent.getAgendas()) {
            if (agenda.isResolved() || !(agenda instanceof DebtAgenda)) continue;
            DebtAgenda debt = (DebtAgenda) agenda;
            if (debt.getDebtorId() == null || !debt.getDebtorId().equals(playerId)) continue;

            int paid = countMatchingOffer(packet, debt.getItemId());
            if (paid >= debt.getQuantity()) {
                debt.setResolved(true);
                debt.touch(player.level.getGameTime());
                // Keeping a promise earns goodwill.
                agent.getPlayerReputation().merge(player.getUUID(), 10, Integer::sum);
                agent.addMemory("Player " + player.getName().getString() + " settled their debt: "
                        + debt.getQuantity() + "x " + debt.getItemId());
                LOGGER.info("{} settled debt '{}' with {}", agent.getName(), debt.getTitle(),
                        player.getName().getString());
            }
        }
    }

    private static int countMatchingOffer(TradeRequestPacket packet, String debtItemId) {
        int count = 0;
        if (!packet.offerItem1.isEmpty()
                && packet.offerItem1.getItem().getRegistryName().toString().equals(debtItemId)) {
            count += packet.offerItem1.getCount();
        }
        if (!packet.offerItem2.isEmpty()
                && packet.offerItem2.getItem().getRegistryName().toString().equals(debtItemId)) {
            count += packet.offerItem2.getCount();
        }
        return count;
    }

    /**
     * Snapshot the villager's current inventory (post-trade) for client display refresh.
     */
    private static List<ItemStack> collectInventorySnapshot(VillagerAgentData agent) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : agent.getInventory().getItems()) {
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        return items;
    }

    /**
     * Snapshot the villager's currently equipped armor (HEAD, CHEST, LEGS, FEET).
     */
    private static List<ItemStack> collectArmorSnapshot(VillagerEntity villager) {
        List<ItemStack> armor = new ArrayList<>();
        if (villager != null) {
            armor.add(villager.getItemBySlot(EquipmentSlotType.HEAD).copy());
            armor.add(villager.getItemBySlot(EquipmentSlotType.CHEST).copy());
            armor.add(villager.getItemBySlot(EquipmentSlotType.LEGS).copy());
            armor.add(villager.getItemBySlot(EquipmentSlotType.FEET).copy());
        } else {
            for (int i = 0; i < 4; i++) armor.add(ItemStack.EMPTY);
        }
        return armor;
    }
}

