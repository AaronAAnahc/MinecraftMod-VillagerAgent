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
 * Server -> client: the villager offers the player a trade it agreed to in chat. The client opens the trade panel
 * pre-filled with it (Accept / Decline). Accept sends an ordinary {@link TradeRequestPacket} with these stacks;
 * Decline sends {@link TradeProposalDeclinePacket}.
 */
public class TradeProposalPacket {
    final UUID villagerId;
    final ItemStack give1, give2, get1, get2;
    final List<ItemStack> inventory, armor;

    public TradeProposalPacket(UUID villagerId, ItemStack give1, ItemStack give2, ItemStack get1, ItemStack get2,
                               List<ItemStack> inventory, List<ItemStack> armor) {
        this.villagerId = villagerId;
        this.give1 = give1; this.give2 = give2; this.get1 = get1; this.get2 = get2;
        this.inventory = inventory != null ? inventory : new ArrayList<>();
        this.armor = armor != null ? armor : new ArrayList<>();
    }

    public static void encode(TradeProposalPacket p, PacketBuffer buf) {
        buf.writeUUID(p.villagerId);
        buf.writeItem(p.give1); buf.writeItem(p.give2); buf.writeItem(p.get1); buf.writeItem(p.get2);
        buf.writeInt(p.inventory.size());
        for (ItemStack s : p.inventory) buf.writeItem(s);
        for (int i = 0; i < 4; i++) buf.writeItem(i < p.armor.size() ? p.armor.get(i) : ItemStack.EMPTY);
    }

    public static TradeProposalPacket decode(PacketBuffer buf) {
        UUID id = buf.readUUID();
        ItemStack g1 = buf.readItem(), g2 = buf.readItem(), r1 = buf.readItem(), r2 = buf.readItem();
        int n = buf.readInt();
        List<ItemStack> inv = new ArrayList<>();
        for (int i = 0; i < n; i++) inv.add(buf.readItem());
        List<ItemStack> armor = new ArrayList<>();
        for (int i = 0; i < 4; i++) armor.add(buf.readItem());
        return new TradeProposalPacket(id, g1, g2, r1, r2, inv, armor);
    }

    public static void handle(TradeProposalPacket p, Supplier<NetworkEvent.Context> ctx) {
        // Client side, same pattern as TradeResultPacket.
        ctx.get().enqueueWork(() ->
                VillagerChatHandler.receiveTradeProposal(p.villagerId, p.give1, p.give2, p.get1, p.get2, p.inventory, p.armor));
        ctx.get().setPacketHandled(true);
    }
}
