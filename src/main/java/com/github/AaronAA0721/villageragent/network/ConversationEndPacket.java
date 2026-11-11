package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.PlayerConversation;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: the player closed the chat panel; the villager may get back to work. */
public class ConversationEndPacket {
    private final UUID villagerId;

    public ConversationEndPacket(UUID villagerId) { this.villagerId = villagerId; }

    public static void encode(ConversationEndPacket p, PacketBuffer buf) { buf.writeUUID(p.villagerId); }

    public static ConversationEndPacket decode(PacketBuffer buf) { return new ConversationEndPacket(buf.readUUID()); }

    public static void handle(ConversationEndPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player != null) PlayerConversation.end(p.villagerId, player.getUUID());
        });
        ctx.get().setPacketHandled(true);
    }
}
