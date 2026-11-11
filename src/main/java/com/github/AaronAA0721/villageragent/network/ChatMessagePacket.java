package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.TradeProposal;

import com.github.AaronAA0721.villageragent.ai.ChatReplyProcessor;
import com.github.AaronAA0721.villageragent.ai.LLMService;
import com.github.AaronAA0721.villageragent.ai.LLMSetupReminder;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerVisionSystem;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Packet sent from client to server when player sends a chat message
 */
public class ChatMessagePacket {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final UUID villagerId;
    private final String message;
    
    public ChatMessagePacket(UUID villagerId, String message) {
        this.villagerId = villagerId;
        this.message = message;
    }
    
    public static void encode(ChatMessagePacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
        buffer.writeUtf(packet.message, 500);
    }
    
    public static ChatMessagePacket decode(PacketBuffer buffer) {
        return new ChatMessagePacket(buffer.readUUID(), buffer.readUtf(500));
    }
    
    public static void handle(ChatMessagePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity player = ctx.get().getSender();
            if (player == null) return;
            
            VillagerAgentData agent = VillagerAgentManager.getAgent(packet.villagerId);
            if (agent == null) {
                LOGGER.warn("No agent found for villager: " + packet.villagerId);
                return;
            }

            // P1 fix: if the LLM is not configured, don't call it (it would just fail and
            // leave the villager "lost in thought"). Send the setup hint to the player's
            // chat bar instead, and a short pointer into the dialog so it isn't stuck on "...".
            if (!LLMSetupReminder.isConfigured()) {
                for (ITextComponent line : LLMSetupReminder.buildReminder()) {
                    player.sendMessage(line, Util.NIL_UUID);
                }
                ModNetworking.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new VillagerResponsePacket(packet.villagerId, agent.getName(),
                                agent.getName() + "：LLM 还没配置好，设置方法已经发到聊天栏了。"));
                return;
            }

            String playerName = player.getName().getString();
            com.github.AaronAA0721.villageragent.ai.PlayerConversation.touch(packet.villagerId, player.getUUID(),
                    player.level.getGameTime());
            LOGGER.info("Chat from " + playerName + " to " + agent.getName() + ": " + packet.message);

            // Resolve the live villager entity (may be null if it isn't loaded right now).
            // Threaded into generateChatResponse so the chat prompt can see equipped armor,
            // profession level, health and location — state that lives on the entity, not the
            // data bag, and was previously invisible to the LLM.
            VillagerEntity villagerEntity = null;
            if (player.level instanceof ServerWorld) {
                ServerWorld serverWorld = (ServerWorld) player.level;
                Entity rawEntity = serverWorld.getEntity(packet.villagerId);
                if (rawEntity instanceof VillagerEntity) {
                    villagerEntity = (VillagerEntity) rawEntity;
                    // Pass agent so chunk memory and in-sight chunks are included
                    String envSummary = VillagerVisionSystem.buildEnvironmentSummary(
                            villagerEntity, serverWorld, agent);
                    agent.setEnvironmentSummary(envSummary);
                    LOGGER.debug("Environment snapshot for {}: {}", agent.getName(), envSummary);
                }
            }

            // Generate LLM response asynchronously (pass game tick for conversation memory)
            long gameTick = player.level.getGameTime();
            agent.generateChatResponse(playerName, packet.message, gameTick, villagerEntity, player.getUUID())
                    // The LLM answers off-thread; everything that touches the world or the brain runs on the server thread.
                    .thenAccept(response -> player.getServer().execute(() -> handleReply(packet, player, agent, response, gameTick)));
        });
        ctx.get().setPacketHandled(true);
    }

    /** Apply a chat reply: enqueue its goals and agendas, then show the spoken text. Server thread. */
    private static void handleReply(ChatMessagePacket packet, ServerPlayerEntity player, VillagerAgentData agent,
                                    String response, long gameTick) {
        // P0 fix (defense in depth): never broadcast a raw failure sentinel to the player.
        if (LLMService.isFailure(response)) {
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new VillagerResponsePacket(packet.villagerId, agent.getName(),
                            agent.getName() + " seems momentarily lost in thought."));
            return;
        }

        // Parse, ground/validate/enqueue goals, record agendas and thought (shared with /va ask).
        VillagerEntity ve = VillagerAgentManager.findVillagerEntity(agent.getVillagerId());
        ServerWorld sw = ve != null ? (ServerWorld) ve.level : (ServerWorld) player.level;
        ChatReplyProcessor.Result result = ChatReplyProcessor.process(agent, response, gameTick, ve, sw, "chat", true);

        // The villager offered a trade: check it against both inventories and the price floor, then open the
        // pre-filled trade panel for the player to accept or decline.
        String reply = result.parsed.reply;
        TradeProposal offer = null;
        if (result.parsed.trade != null) {
            if (ve == null || !ve.isAlive() || player.distanceToSqr(ve) > 64.0) {
                reply = reply + " [No trade offered: you are too far away]";
            } else {
                // After a trade-check round the LLM saw the exact values: its price stands (only stock/space checked).
                boolean priceDecided = agent.consumeReplyPriceDecided();
                TradeProposal.Result made = TradeProposal.create(result.parsed.trade, agent, ve, player, sw.getGameTime(), priceDecided);
                if (made.ok()) {
                    offer = made.proposal;
                    agent.addMemory("Offered " + player.getName().getString() + " a trade: " + offer.describe());
                    LOGGER.info("{} offers {} a trade: {}", agent.getName(), player.getName().getString(), offer.describe());
                } else if (made.tooCheap) {
                    // The LLM agreed to a price below what this villager can accept (it does the sums loosely).
                    // The villager corrects itself out loud, with a fair counter-offer when one exists, and the
                    // correction goes into the conversation history so the next reply builds on it.
                    offer = made.counter;
                    reply = offer != null
                            ? "Hmm, wait, let me count that up... that's too little for me. I can let you have "
                              + offer.getsText() + " for " + offer.givesText() + " — here's my offer."
                            : "Hmm, wait, let me count that up... that's too little for me: " + made.error + ".";
                    agent.addConversation(agent.getName() + ": " + reply, gameTick);
                    agent.addMemory("Corrected my price for " + player.getName().getString()
                            + (offer != null ? ": countered with " + offer.describe() : ": " + made.error));
                    LOGGER.info("{} trade offer to {} was too cheap ({}); counter: {}", agent.getName(),
                            player.getName().getString(), made.error, offer == null ? "none" : offer.describe());
                } else {
                    reply = reply + " [No trade offered: " + made.error + "]";
                    LOGGER.info("{} trade offer to {} rejected: {}", agent.getName(), player.getName().getString(), made.error);
                }
            }
        }

        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new VillagerResponsePacket(packet.villagerId, agent.getName(), reply));
        if (offer != null) {
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new TradeProposalPacket(packet.villagerId, offer.give1, offer.give2, offer.get1, offer.get2,
                            TradeRequestPacket.collectInventorySnapshot(agent), TradeRequestPacket.collectArmorSnapshot(ve)));
        }
    }
}
