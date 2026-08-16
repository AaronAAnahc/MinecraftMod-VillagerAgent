package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.DebtAgenda;
import com.github.AaronAA0721.villageragent.ai.LLMService;
import com.github.AaronAA0721.villageragent.ai.LongTermAgenda;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerEquipmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ArmorItem;
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
                return;
            }

            // Find the villager entity
            ServerWorld serverWorld = (ServerWorld) player.level;
            Entity entity = serverWorld.getEntity(packet.villagerId);
            VillagerEntity villager = (entity instanceof VillagerEntity) ? (VillagerEntity) entity : null;

            // Build trade description for LLM
            String tradeDescription = buildTradeDescription(packet, agent);
            LOGGER.info("Trade request: " + tradeDescription);

            // Ask LLM to evaluate the trade (pass villager for armor-aware inventory description)
            evaluateTradeWithLLM(player, agent, packet, tradeDescription, villager);
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

    private static void evaluateTradeWithLLM(ServerPlayerEntity player, VillagerAgentData agent,
                                              TradeRequestPacket packet, String tradeDescription,
                                              VillagerEntity villager) {
        String profession = agent.getProfession();
        String inventoryDesc = buildInventoryDescription(agent, villager);

        // Flexible, personality-driven prompt
        String systemPrompt = "You are " + agent.getName() + ", a " + profession + " villager in Minecraft. " +
                "Your personality: " + agent.getPersonality() + ".\n\n" +
                "A player wants to trade with you.\n\n" +
                "YOUR CURRENT INVENTORY (items you own and can trade away):\n" + inventoryDesc + "\n\n" +
                "TRADE VALUE GUIDE:\n" +
                "- Emeralds, gold ingots, and diamonds are valuable currencies.\n" +
                "- Rare items (diamonds, enchanted gear, netherite) are very precious.\n" +
                "- Common blocks (dirt, cobblestone, grass, sand) have little value.\n" +
                "- Food and tools have moderate value depending on quality.\n" +
                "- You can only give away items you actually HAVE (including equipped armor).\n\n" +
                "Let your personality guide your decision. Think freely about:\n" +
                "- Is this trade fair in terms of value?\n" +
                "- Do you want or need what they're giving you?\n" +
                "- Does this feel like a good deal for YOU?\n\n" +
                "Respond with EXACTLY: ACCEPT or REJECT followed by a short in-character reason (1 sentence).";

        String userPrompt = "TRADE PROPOSAL:\n" + tradeDescription +
                "\n\nDo you accept this trade? Respond in character, starting with ACCEPT or REJECT.";

        LLMService.queryLLM(systemPrompt, userPrompt).thenAccept(response -> {
            boolean accepted = response.toUpperCase().startsWith("ACCEPT");
            String reason = response.length() > 7 ? response.substring(7).trim() : response;

            // Clean up the reason - remove leading punctuation
            if (reason.startsWith(":") || reason.startsWith("-") || reason.startsWith(".")) {
                reason = reason.substring(1).trim();
            }
            if (reason.startsWith("!")) {
                reason = reason.substring(1).trim();
            }

            LOGGER.info("Trade " + (accepted ? "ACCEPTED" : "REJECTED") + ": " + reason);

            // Execute trade if accepted, otherwise return items to player
            if (accepted) {
                boolean success = executeTrade(player, agent, packet, villager);
                if (!success) {
                    // Trade failed (items not available) - return items to player
                    reason = "Wait, I don't actually have those items. Sorry!";
                    accepted = false;
                    returnItemsToPlayer(player, packet);
                }
            } else {
                // Trade rejected - return the offered items to player
                returnItemsToPlayer(player, packet);
            }

            // Send result to client, including the post-trade inventory/armor snapshot
            // so the client can refresh its cached display immediately.
            List<ItemStack> updatedInventory = collectInventorySnapshot(agent);
            List<ItemStack> updatedArmor = collectArmorSnapshot(villager);
            TradeResultPacket resultPacket = new TradeResultPacket(
                    packet.villagerId,
                    accepted,
                    reason,
                    updatedInventory,
                    updatedArmor
            );
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), resultPacket);
        });
    }

    /**
     * Return offered items to player when trade is rejected or fails
     */
    private static void returnItemsToPlayer(ServerPlayerEntity player, TradeRequestPacket packet) {
        if (!packet.offerItem1.isEmpty()) {
            player.addItem(packet.offerItem1.copy());
            LOGGER.info("Returned to player: " + packet.offerItem1.getCount() + "x " + packet.offerItem1.getItem().getRegistryName());
        }
        if (!packet.offerItem2.isEmpty()) {
            player.addItem(packet.offerItem2.copy());
            LOGGER.info("Returned to player: " + packet.offerItem2.getCount() + "x " + packet.offerItem2.getItem().getRegistryName());
        }
    }

    /**
     * Execute the trade - transfer items between player and villager.
     * Items can come from either the {@link com.github.AaronAA0721.villageragent.ai.AgentInventory}
     * or the entity's equipment slots (equipped armor).
     * @return true if trade was successful, false if items weren't available
     */
    private static boolean executeTrade(ServerPlayerEntity player, VillagerAgentData agent,
                                         TradeRequestPacket packet, VillagerEntity villager) {
        // Verify villager has requested items (check inventory + equipped armor)
        if (!packet.requestItem1.isEmpty()) {
            if (!villagerHasItem(agent, villager, packet.requestItem1)) {
                LOGGER.warn("Villager doesn't have: " + packet.requestItem1);
                return false;
            }
        }
        if (!packet.requestItem2.isEmpty()) {
            if (!villagerHasItem(agent, villager, packet.requestItem2)) {
                LOGGER.warn("Villager doesn't have: " + packet.requestItem2);
                return false;
            }
        }

        // Add player's offered items to villager inventory
        if (!packet.offerItem1.isEmpty()) {
            agent.getInventory().addItem(packet.offerItem1.copy());
            LOGGER.info("Villager received: " + packet.offerItem1.getCount() + "x " + packet.offerItem1.getItem().getRegistryName());
        }
        if (!packet.offerItem2.isEmpty()) {
            agent.getInventory().addItem(packet.offerItem2.copy());
            LOGGER.info("Villager received: " + packet.offerItem2.getCount() + "x " + packet.offerItem2.getItem().getRegistryName());
        }

        // Remove requested items from villager (inventory first, then equipped armor) and give to player
        if (!packet.requestItem1.isEmpty()) {
            removeFromVillager(agent, villager, packet.requestItem1);
            player.addItem(packet.requestItem1.copy());
            LOGGER.info("Player received: " + packet.requestItem1.getCount() + "x " + packet.requestItem1.getItem().getRegistryName());
        }
        if (!packet.requestItem2.isEmpty()) {
            removeFromVillager(agent, villager, packet.requestItem2);
            player.addItem(packet.requestItem2.copy());
            LOGGER.info("Player received: " + packet.requestItem2.getCount() + "x " + packet.requestItem2.getItem().getRegistryName());
        }

        // After trade, re-evaluate armor (received items may include armor to equip)
        if (villager != null) {
            VillagerEquipmentHelper.refreshEquipment(villager, agent);
        }

        // If the player just handed over items they owed us, settle the matching DebtAgenda
        // so the "collect payment" intention is cleared — guaranteed by code, not the LLM.
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
     * Check whether the villager has the requested item — first in inventory, then in equipped armor slots.
     */
    private static boolean villagerHasItem(VillagerAgentData agent, VillagerEntity villager, ItemStack requested) {
        // Check inventory
        if (agent.getInventory().hasItem(requested, requested.getCount())) {
            return true;
        }
        // Check equipped armor slots
        if (villager != null) {
            EquipmentSlotType[] armorSlots = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST,
                    EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
            for (EquipmentSlotType slot : armorSlots) {
                ItemStack equipped = villager.getItemBySlot(slot);
                if (!equipped.isEmpty() && ItemStack.isSame(equipped, requested)
                        && equipped.getCount() >= requested.getCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove the requested item from the villager. Tries inventory first, then equipped armor.
     */
    private static void removeFromVillager(VillagerAgentData agent, VillagerEntity villager, ItemStack requested) {
        // Try inventory first
        if (agent.getInventory().hasItem(requested, requested.getCount())) {
            agent.getInventory().removeItem(requested, requested.getCount());
            return;
        }
        // Try equipped armor slots
        if (villager != null) {
            EquipmentSlotType[] armorSlots = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST,
                    EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
            for (EquipmentSlotType slot : armorSlots) {
                ItemStack equipped = villager.getItemBySlot(slot);
                if (!equipped.isEmpty() && ItemStack.isSame(equipped, requested)
                        && equipped.getCount() >= requested.getCount()) {
                    villager.setItemSlot(slot, ItemStack.EMPTY);
                    LOGGER.info("Removed equipped armor from {}: {}", slot.getName(),
                            requested.getItem().getRegistryName());
                    return;
                }
            }
        }
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

