package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Packet sent from client to server to request opening chat with a villager
 */
public class OpenChatPacket {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final UUID villagerId;
    
    public OpenChatPacket(UUID villagerId) {
        this.villagerId = villagerId;
    }
    
    public static void encode(OpenChatPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
    }
    
    public static OpenChatPacket decode(PacketBuffer buffer) {
        return new OpenChatPacket(buffer.readUUID());
    }
    
    public static void handle(OpenChatPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player == null) return;
            
            VillagerAgentData agent = VillagerAgentManager.getAgent(packet.villagerId);
            if (agent == null) {
                LOGGER.warn("No agent found for villager: " + packet.villagerId);
                return;
            }
            
            // Collect villager inventory items for display
            List<ItemStack> inventoryItems = new ArrayList<>();
            for (ItemStack item : agent.getInventory().getItems()) {
                if (!item.isEmpty()) {
                    inventoryItems.add(item.copy());
                }
            }

            // Collect equipped armor from the villager entity
            List<ItemStack> armorItems = new ArrayList<>();
            net.minecraft.world.server.ServerWorld serverWorld = (net.minecraft.world.server.ServerWorld) player.level;
            Entity entity = serverWorld.getEntity(packet.villagerId);
            if (entity instanceof VillagerEntity) {
                VillagerEntity villager = (VillagerEntity) entity;
                armorItems.add(villager.getItemBySlot(EquipmentSlotType.HEAD).copy());
                armorItems.add(villager.getItemBySlot(EquipmentSlotType.CHEST).copy());
                armorItems.add(villager.getItemBySlot(EquipmentSlotType.LEGS).copy());
                armorItems.add(villager.getItemBySlot(EquipmentSlotType.FEET).copy());
            } else {
                for (int i = 0; i < 4; i++) armorItems.add(ItemStack.EMPTY);
            }

            // Send villager data to client (including profession)
            SyncVillagerDataPacket syncPacket = new SyncVillagerDataPacket(
                    packet.villagerId,
                    agent.getName(),
                    agent.getProfession(),
                    agent.getPersonality(),
                    inventoryItems,
                    armorItems
            );
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), syncPacket);

            // NO automatic greeting - wait for player to say something first
            // Only reply when player sends a message

            // The villager stops what it is doing and faces the player while the panel is open (PlayerConversation).
            com.github.AaronAA0721.villageragent.ai.PlayerConversation.begin(packet.villagerId, player.getUUID(),
                    serverWorld.getGameTime());
            LOGGER.info("Player " + player.getName().getString() + " opened chat with " + agent.getName());
        });
        ctx.get().setPacketHandled(true);
    }
}

