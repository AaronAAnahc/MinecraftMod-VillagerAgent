package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.AgendaParser;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.LLMService;
import com.github.AaronAA0721.villageragent.ai.LLMSetupReminder;
import com.github.AaronAA0721.villageragent.ai.LongTermAgenda;
import com.github.AaronAA0721.villageragent.ai.GoalParser;
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
                    .thenAccept(response -> {
                // P0 fix (defense in depth): never broadcast a raw failure sentinel to the player.
                if (LLMService.isFailure(response)) {
                    ModNetworking.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new VillagerResponsePacket(packet.villagerId, agent.getName(),
                                    agent.getName() + " seems momentarily lost in thought."));
                    return;
                }

                // Parse any hidden TODO: directives the LLM embedded into executable goals.
                // Capped to MAX_GOALS so a chatty villager can't grow the agenda without bound.
                List<AgentGoal> parsed = GoalParser.parse(response, agent);
                if (!parsed.isEmpty()) {
                    for (AgentGoal g : parsed) {
                        if (agent.getGoals().size() < VillagerAgentManager.getMaxGoals()) {
                            agent.getGoals().add(g);
                            agent.addMemory("New goal from chat: " + g.getDescription()
                                    + " (importance " + g.getImportance() + ")");
                            BrainLog.goalAdded(agent, g, "chat_todo");
                        } else {
                            BrainLog.goalSkipped(agent, g, "chat_todo");
                        }
                    }
                    LOGGER.info("{} parsed {} TODO directive(s) from chat into goals",
                            agent.getName(), parsed.size());
                }

                // Parse any REMEMBER: directives the LLM embedded into long-term agendas
                // (debts, deals, acquisitions, or free-form generic intentions).
                List<LongTermAgenda> agendas = AgendaParser.parse(response, agent, gameTick);
                if (!agendas.isEmpty()) {
                    for (LongTermAgenda a : agendas) {
                        agent.getAgendas().add(a);
                        agent.addMemory("Long-term commitment recorded: " + a.getTitle());
                    }
                    LOGGER.info("{} parsed {} REMEMBER directive(s) into agendas",
                            agent.getName(), agendas.size());
                }

                // The player only sees the spoken reply — TODO / REMEMBER lines stay hidden.
                String visible = GoalParser.stripTodoLines(response);
                visible = AgendaParser.stripRememberLines(visible);
                VillagerResponsePacket responsePacket = new VillagerResponsePacket(
                        packet.villagerId,
                        agent.getName(),
                        visible
                );
                ModNetworking.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        responsePacket
                );
            });
        });
        ctx.get().setPacketHandled(true);
    }
}

