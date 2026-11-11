package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.Villageragent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

/**
 * Network handler for client-server communication
 */
public class ModNetworking {
    private static final String PROTOCOL_VERSION = "5";
    
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Villageragent.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    
    private static int packetId = 0;
    
    private static int nextId() {
        return packetId++;
    }
    
    public static void register() {
        // Client -> Server: Player sends chat message
        CHANNEL.registerMessage(nextId(),
                ChatMessagePacket.class,
                ChatMessagePacket::encode,
                ChatMessagePacket::decode,
                ChatMessagePacket::handle);
        
        // Server -> Client: Villager response
        CHANNEL.registerMessage(nextId(),
                VillagerResponsePacket.class,
                VillagerResponsePacket::encode,
                VillagerResponsePacket::decode,
                VillagerResponsePacket::handle);
        
        // Client -> Server: Trade request
        CHANNEL.registerMessage(nextId(),
                TradeRequestPacket.class,
                TradeRequestPacket::encode,
                TradeRequestPacket::decode,
                TradeRequestPacket::handle);
        
        // Server -> Client: Trade result
        CHANNEL.registerMessage(nextId(),
                TradeResultPacket.class,
                TradeResultPacket::encode,
                TradeResultPacket::decode,
                TradeResultPacket::handle);
        
        // Client -> Server: Open chat GUI request
        CHANNEL.registerMessage(nextId(),
                OpenChatPacket.class,
                OpenChatPacket::encode,
                OpenChatPacket::decode,
                OpenChatPacket::handle);
        
        // Server -> Client: Sync villager data for GUI
        CHANNEL.registerMessage(nextId(),
                SyncVillagerDataPacket.class,
                SyncVillagerDataPacket::encode,
                SyncVillagerDataPacket::decode,
                SyncVillagerDataPacket::handle);

        // Server -> Client: Crafting request
        CHANNEL.registerMessage(nextId(),
                CraftingRequestPacket.class,
                CraftingRequestPacket::encode,
                CraftingRequestPacket::decode,
                CraftingRequestPacket::handle);

        // Server -> Client: Debug overlay snapshot (visualization debugging)
        CHANNEL.registerMessage(nextId(),
                DebugDataPacket.class,
                DebugDataPacket::encode,
                DebugDataPacket::decode,
                DebugDataPacket::handle);

        // Server -> Client: Per-villager head-top debug labels (utility bar)
        CHANNEL.registerMessage(nextId(),
                VillagerLabelPacket.class,
                VillagerLabelPacket::encode,
                VillagerLabelPacket::decode,
                VillagerLabelPacket::handle);
        CHANNEL.registerMessage(nextId(), NoticeBoardPagePacket.class, NoticeBoardPagePacket::encode,
                NoticeBoardPagePacket::decode, NoticeBoardPagePacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), NoticeBoardSnapshotPacket.class, NoticeBoardSnapshotPacket::encode,
                NoticeBoardSnapshotPacket::decode, NoticeBoardSnapshotPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_CLIENT));
        // Server -> Client: showcase cards, request tags and notice-board panel
        CHANNEL.registerMessage(nextId(), ShowcasePacket.class, ShowcasePacket::encode,
                ShowcasePacket::decode, ShowcasePacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_CLIENT));
        // Client -> Server: chat-panel Goals button asks for the villager's goal list
        CHANNEL.registerMessage(nextId(), VillagerGoalsRequestPacket.class, VillagerGoalsRequestPacket::encode,
                VillagerGoalsRequestPacket::decode, VillagerGoalsRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_SERVER));
        // Server -> Client: formatted goal list for the chat-panel goals view
        CHANNEL.registerMessage(nextId(), VillagerGoalsPacket.class, VillagerGoalsPacket::encode,
                VillagerGoalsPacket::decode, VillagerGoalsPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_CLIENT));
        // Server -> Client: the villager offers a trade it agreed to in chat (opens the pre-filled trade panel)
        CHANNEL.registerMessage(nextId(), TradeProposalPacket.class, TradeProposalPacket::encode,
                TradeProposalPacket::decode, TradeProposalPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_CLIENT));
        // Client -> Server: the player closed the chat panel (the villager stops facing them)
        CHANNEL.registerMessage(nextId(), ConversationEndPacket.class, ConversationEndPacket::encode,
                ConversationEndPacket::decode, ConversationEndPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_SERVER));
        // Client -> Server: the player declined that offer
        CHANNEL.registerMessage(nextId(), TradeProposalDeclinePacket.class, TradeProposalDeclinePacket::encode,
                TradeProposalDeclinePacket::decode, TradeProposalDeclinePacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_SERVER));
        // Client -> Server: chat-panel Info button asks for the villager's state snapshot
        CHANNEL.registerMessage(nextId(), VillagerInfoRequestPacket.class, VillagerInfoRequestPacket::encode,
                VillagerInfoRequestPacket::decode, VillagerInfoRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_SERVER));
        // Server -> Client: formatted info lines (needs, skills, inventory, chunk memory ...)
        CHANNEL.registerMessage(nextId(), VillagerInfoPacket.class, VillagerInfoPacket::encode,
                VillagerInfoPacket::decode, VillagerInfoPacket::handle,
                java.util.Optional.of(net.minecraftforge.fml.network.NetworkDirection.PLAY_TO_CLIENT));
    }
}

