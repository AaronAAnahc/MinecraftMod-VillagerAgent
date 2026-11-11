package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.TradeProposal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: the player declined the trade the villager offered in chat. */
public class TradeProposalDeclinePacket {
    private final UUID villagerId;

    public TradeProposalDeclinePacket(UUID villagerId) { this.villagerId = villagerId; }

    public static void encode(TradeProposalDeclinePacket p, PacketBuffer buf) { buf.writeUUID(p.villagerId); }

    public static TradeProposalDeclinePacket decode(PacketBuffer buf) { return new TradeProposalDeclinePacket(buf.readUUID()); }

    public static void handle(TradeProposalDeclinePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player == null) return;
            TradeProposal offer = TradeProposal.cancel(p.villagerId, player.getUUID());
            VillagerAgentData agent = VillagerAgentManager.getAgent(p.villagerId);
            if (offer != null && agent != null)
                agent.addMemory(player.getName().getString() + " declined my offer (" + offer.describe() + ")");
        });
        ctx.get().setPacketHandled(true);
    }
}
