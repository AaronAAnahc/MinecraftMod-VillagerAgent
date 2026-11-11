package com.github.AaronAA0721.villageragent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Client-side handler for villager chat interactions
 */
public class VillagerChatHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    private static VillagerChatScreen currentChatScreen = null;

    /**
     * Open the chat screen with villager data
     */
    public static void openChatScreen(UUID villagerId, String villagerName, String profession,
                                       String personality, List<ItemStack> inventory, List<ItemStack> armorItems) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        LOGGER.info("Opening chat screen for " + profession + " " + villagerName);

        currentChatScreen = new VillagerChatScreen(villagerId, villagerName, profession, personality, inventory, armorItems);
        mc.setScreen(currentChatScreen);
    }

    /**
     * Receive a chat response from the villager
     */
    public static void receiveResponse(UUID villagerId, String villagerName, String response) {
        LOGGER.info("Received response from " + villagerName + ": " + response);

        if (currentChatScreen != null && currentChatScreen.getVillagerId().equals(villagerId)) {
            currentChatScreen.addMessage(villagerName, response, false);
        }
    }

    /** Receive the villager's goal list (answer to the chat panel's Goals button). */
    public static void receiveGoals(UUID villagerId, List<String> lines) {
        if (currentChatScreen != null && currentChatScreen.getVillagerId().equals(villagerId)) {
            currentChatScreen.setGoalLines(lines);
        }
    }

    /** Receive the villager's info snapshot (answer to the chat panel's Info button). */
    public static void receiveInfo(UUID villagerId, List<String> lines) {
        if (currentChatScreen != null && currentChatScreen.getVillagerId().equals(villagerId)) {
            currentChatScreen.setInfoLines(lines);
        }
    }

    /**
     * Receive a trade result, including the post-trade inventory/armor snapshot
     * so the client UI refreshes immediately instead of showing stale data.
     */
    public static void receiveTradeResult(UUID villagerId, boolean accepted, String message,
                                           List<ItemStack> inventory, List<ItemStack> armor) {
        LOGGER.info("Trade result: " + (accepted ? "ACCEPTED" : "REJECTED") + " - " + message);

        // Refresh the cached snapshot on the chat screen (source of truth for re-opening trade)
        if (currentChatScreen != null && currentChatScreen.getVillagerId().equals(villagerId)) {
            currentChatScreen.updateVillagerData(inventory, armor);
        }

        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.screen;

        // Check if trade screen is open
        if (currentScreen instanceof VillagerTradeScreen) {
            VillagerTradeScreen tradeScreen = (VillagerTradeScreen) currentScreen;
            if (tradeScreen.getVillagerId().equals(villagerId)) {
                tradeScreen.updateVillagerData(inventory, armor);
                tradeScreen.showTradeResult(accepted, message);
                return;
            }
        }

        // Otherwise check chat screen
        if (currentChatScreen != null && currentChatScreen.getVillagerId().equals(villagerId)) {
            currentChatScreen.showTradeResult(accepted, message);
        }
    }

    /**
     * The villager offers a trade it agreed to in chat: open the trade panel pre-filled with it, so the player
     * only has to press Accept or Decline. Ignored if the chat with that villager is no longer open.
     */
    public static void receiveTradeProposal(UUID villagerId, ItemStack give1, ItemStack give2,
                                            ItemStack get1, ItemStack get2,
                                            List<ItemStack> inventory, List<ItemStack> armor) {
        if (currentChatScreen == null || !currentChatScreen.getVillagerId().equals(villagerId)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != currentChatScreen && !(mc.screen instanceof VillagerTradeScreen)) return;
        currentChatScreen.updateVillagerData(inventory, armor);
        LOGGER.info("Trade offer from " + currentChatScreen.getVillagerName());
        mc.setScreen(VillagerTradeScreen.proposal(currentChatScreen, inventory, armor,
                new ItemStack[]{give1, give2}, new ItemStack[]{get1, get2}));
    }

    /**
     * Close the current chat screen
     */
    public static void closeScreen() {
        if (currentChatScreen != null) {
            Minecraft.getInstance().setScreen(null);
            currentChatScreen = null;
        }
    }

    /**
     * Check if chat screen is currently open
     */
    public static boolean isScreenOpen() {
        Minecraft mc = Minecraft.getInstance();
        return currentChatScreen != null &&
               (mc.screen == currentChatScreen || mc.screen instanceof VillagerTradeScreen);
    }

    /**
     * Get the current chat screen (if open)
     */
    public static VillagerChatScreen getCurrentChatScreen() {
        return currentChatScreen;
    }
}

