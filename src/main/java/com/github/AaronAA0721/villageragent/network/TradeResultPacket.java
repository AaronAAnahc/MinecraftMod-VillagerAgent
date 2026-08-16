package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.client.VillagerChatHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Packet sent from server to client with trade result
 */
public class TradeResultPacket {
    private final UUID villagerId;
    private final boolean accepted;
    private final String message;
    /** Updated villager inventory after the trade (so the client can refresh its snapshot). */
    private final List<ItemStack> inventoryItems;
    /** Updated equipped armor after the trade (HEAD, CHEST, LEGS, FEET). */
    private final List<ItemStack> armorItems;

    public TradeResultPacket(UUID villagerId, boolean accepted, String message,
                             List<ItemStack> inventoryItems, List<ItemStack> armorItems) {
        this.villagerId = villagerId;
        this.accepted = accepted;
        this.message = message;
        this.inventoryItems = inventoryItems != null ? inventoryItems : new ArrayList<>();
        this.armorItems = armorItems != null ? armorItems : new ArrayList<>();
    }
    
    public static void encode(TradeResultPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
        buffer.writeBoolean(packet.accepted);
        buffer.writeUtf(packet.message, 500);
        buffer.writeInt(packet.inventoryItems.size());
        for (ItemStack item : packet.inventoryItems) {
            buffer.writeItem(item);
        }
        for (int i = 0; i < 4; i++) {
            buffer.writeItem(i < packet.armorItems.size() ? packet.armorItems.get(i) : ItemStack.EMPTY);
        }
    }
    
    public static TradeResultPacket decode(PacketBuffer buffer) {
        UUID villagerId = buffer.readUUID();
        boolean accepted = buffer.readBoolean();
        String message = buffer.readUtf(500);
        int itemCount = buffer.readInt();
        List<ItemStack> inventoryItems = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            inventoryItems.add(buffer.readItem());
        }
        List<ItemStack> armorItems = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            armorItems.add(buffer.readItem());
        }
        return new TradeResultPacket(villagerId, accepted, message, inventoryItems, armorItems);
    }
    
    public static void handle(TradeResultPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Handle on client side, forwarding the post-trade inventory snapshot
            VillagerChatHandler.receiveTradeResult(packet.villagerId, packet.accepted, packet.message,
                    packet.inventoryItems, packet.armorItems);
        });
        ctx.get().setPacketHandled(true);
    }
    
    public UUID getVillagerId() {
        return villagerId;
    }
    
    public boolean isAccepted() {
        return accepted;
    }
    
    public String getMessage() {
        return message;
    }

    public List<ItemStack> getInventoryItems() {
        return inventoryItems;
    }

    public List<ItemStack> getArmorItems() {
        return armorItems;
    }
}

