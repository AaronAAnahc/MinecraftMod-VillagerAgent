package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server: "show me this villager's info". Sent by the Info button in
 * {@code VillagerChatScreen} (and periodically while the info panel stays open).
 * The server answers with a {@link VillagerInfoPacket}.
 */
public class VillagerInfoRequestPacket {
    /** Players farther than this from the villager get no answer (the chat UI is only opened up close). */
    private static final double MAX_DIST_SQ = 64.0 * 64.0;

    private final UUID villagerId;

    public VillagerInfoRequestPacket(UUID villagerId) {
        this.villagerId = villagerId;
    }

    public static void encode(VillagerInfoRequestPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
    }

    public static VillagerInfoRequestPacket decode(PacketBuffer buffer) {
        return new VillagerInfoRequestPacket(buffer.readUUID());
    }

    public static void handle(VillagerInfoRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player == null) return;
            VillagerAgentData agent = VillagerAgentManager.getAgent(packet.villagerId);
            if (agent == null) return;
            net.minecraft.entity.merchant.villager.VillagerEntity v =
                    VillagerAgentManager.findVillagerEntity(packet.villagerId);
            if (v != null && v.distanceToSqr(player) > MAX_DIST_SQ) return;
            long now = player.level.getGameTime();
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new VillagerInfoPacket(packet.villagerId, VillagerInfoPacket.describeInfo(agent, player, now)));
        });
        ctx.get().setPacketHandled(true);
    }
}
