package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.network.ChatMessagePacket;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Visual Novel style communication screen.
 *
 * Layout:
 * - Bottom of screen: Dialog box with speaker name and message
 * - Text input for player messages
 * - "End Talk" and "Trade" buttons
 */
public class VillagerChatScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();

    private final UUID villagerId;
    private final String villagerName;
    private final String profession;
    private final String personality;
    private List<ItemStack> villagerInventory;
    private List<ItemStack> villagerArmor;

    // Chat components
    private TextFieldWidget chatInput;
    private String currentMessage = "";
    private String currentSpeaker = "";
    private boolean isPlayerSpeaking = false;
    private boolean isWaitingForResponse = false;

    // Layout constants
    private static final int DIALOG_BOX_HEIGHT = 120;
    private static final int DIALOG_BOX_MARGIN = 40;
    private static final int NAME_TAG_HEIGHT = 24;

    public VillagerChatScreen(UUID villagerId, String villagerName, String profession, String personality,
                              List<ItemStack> inventory, List<ItemStack> armorItems) {
        super(new StringTextComponent("Chat with " + villagerName));
        this.villagerId = villagerId;
        this.villagerName = villagerName;
        this.profession = profession;
        this.personality = personality;
        this.villagerInventory = new ArrayList<>(inventory);
        this.villagerArmor = new ArrayList<>(armorItems);
    }

    public String getProfession() {
        return profession;
    }
    
    @Override
    protected void init() {
        super.init();

        // Dialog box position (bottom of screen)
        int dialogBoxY = this.height - DIALOG_BOX_HEIGHT - DIALOG_BOX_MARGIN;
        int dialogBoxWidth = this.width - DIALOG_BOX_MARGIN * 2;

        // Chat input field (below dialog box)
        int inputY = this.height - DIALOG_BOX_MARGIN + 5;
        int inputWidth = dialogBoxWidth - 70;  // More space since no Send button

        this.chatInput = new TextFieldWidget(this.font, DIALOG_BOX_MARGIN, inputY, inputWidth, 18, new StringTextComponent(""));
        this.chatInput.setMaxLength(200);
        this.chatInput.setVisible(true);
        this.addWidget(this.chatInput);
        this.setInitialFocus(this.chatInput);

        // Trade button - opens trade screen
        this.addButton(new Button(DIALOG_BOX_MARGIN + inputWidth + 5, inputY - 1, 55, 20,
            new StringTextComponent("Trade"), button -> openTradeScreen()));

        // End Talk button (top right corner)
        this.addButton(new Button(this.width - DIALOG_BOX_MARGIN - 80, dialogBoxY - 30, 80, 20,
            new StringTextComponent("End Talk"), button -> onClose()));
    }

    private void openTradeScreen() {
        // Open the trade screen, passing villager info including armor
        Minecraft.getInstance().setScreen(new VillagerTradeScreen(
            villagerId, villagerName, profession, personality, villagerInventory, villagerArmor, this));
    }

    private void sendMessage() {
        String message = chatInput.getValue().trim();
        if (!message.isEmpty() && !isWaitingForResponse) {
            // Show player's message briefly
            currentSpeaker = "You";
            currentMessage = message;
            isPlayerSpeaking = true;

            // Send to server
            ModNetworking.CHANNEL.sendToServer(new ChatMessagePacket(villagerId, message));

            // Clear input and wait for response
            chatInput.setValue("");
            isWaitingForResponse = true;

            // Show thinking indicator
            currentSpeaker = villagerName;
            currentMessage = "...";
            isPlayerSpeaking = false;
        }
    }

    public void addMessage(String sender, String message, boolean isPlayer) {
        // Update the current displayed message (visual novel style - one message at a time)
        currentSpeaker = sender;
        currentMessage = message;
        isPlayerSpeaking = isPlayer;
        isWaitingForResponse = false;
    }

    public void showTradeResult(boolean accepted, String message) {
        // Show the trade result as dialog
        currentSpeaker = villagerName;
        currentMessage = message;
        isPlayerSpeaking = false;
        isWaitingForResponse = false;
    }

    public UUID getVillagerId() {
        return villagerId;
    }

    public String getVillagerName() {
        return villagerName;
    }

    public String getPersonality() {
        return personality;
    }

    public List<ItemStack> getVillagerInventory() {
        return villagerInventory;
    }

    /**
     * Refresh the cached villager inventory/armor in place (e.g. after a trade),
     * so re-opening the trade screen shows the updated contents immediately.
     */
    public void updateVillagerData(List<ItemStack> inventory, List<ItemStack> armor) {
        this.villagerInventory = new ArrayList<>(inventory);
        this.villagerArmor = new ArrayList<>(armor);
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        // Semi-transparent background (player can still see world)
        fill(matrixStack, 0, 0, this.width, this.height, 0x60000000);

        // === VISUAL NOVEL STYLE DIALOG BOX (bottom of screen) ===
        int dialogBoxY = this.height - DIALOG_BOX_HEIGHT - DIALOG_BOX_MARGIN - 30;
        int dialogBoxWidth = this.width - DIALOG_BOX_MARGIN * 2;

        // Dialog box background (dark semi-transparent with slight blue tint)
        fill(matrixStack, DIALOG_BOX_MARGIN, dialogBoxY,
             DIALOG_BOX_MARGIN + dialogBoxWidth, dialogBoxY + DIALOG_BOX_HEIGHT, 0xE01a1a2e);

        // Dialog box border (golden, like Genshin)
        drawBorder(matrixStack, DIALOG_BOX_MARGIN, dialogBoxY, dialogBoxWidth, DIALOG_BOX_HEIGHT, 0xFFc9a227);

        // Inner border for depth
        drawBorder(matrixStack, DIALOG_BOX_MARGIN + 2, dialogBoxY + 2,
                   dialogBoxWidth - 4, DIALOG_BOX_HEIGHT - 4, 0x40c9a227);

        // Name tag (top-left corner of dialog box, overlapping border)
        if (!currentSpeaker.isEmpty()) {
            int nameTagWidth = this.font.width(currentSpeaker) + 24;
            int nameTagX = DIALOG_BOX_MARGIN + 10;
            int nameTagY = dialogBoxY - NAME_TAG_HEIGHT / 2;

            // Name tag background
            fill(matrixStack, nameTagX, nameTagY, nameTagX + nameTagWidth, nameTagY + NAME_TAG_HEIGHT, 0xE01a1a2e);
            drawBorder(matrixStack, nameTagX, nameTagY, nameTagWidth, NAME_TAG_HEIGHT, 0xFFc9a227);

            // Name text (cyan for player, gold for villager)
            int nameColor = isPlayerSpeaking ? 0xFF55FFFF : 0xFFFFD700;
            this.font.draw(matrixStack, currentSpeaker, nameTagX + 12, nameTagY + 7, nameColor);
        }

        // Message text with word wrap
        if (!currentMessage.isEmpty()) {
            List<String> lines = wrapText(currentMessage, dialogBoxWidth - 40);
            int textY = dialogBoxY + 20;
            for (String line : lines) {
                this.font.draw(matrixStack, line, DIALOG_BOX_MARGIN + 20, textY, 0xFFFFFFFF);
                textY += 14;
            }
        }

        // Profession and personality indicator (small text in corner)
        String infoText = profession + " - " + personality;
        int infoWidth = this.font.width(infoText);
        this.font.draw(matrixStack, infoText,
            DIALOG_BOX_MARGIN + dialogBoxWidth - infoWidth - 10,
            dialogBoxY + 8, 0x80AAAAAA);

        // Render chat input
        this.chatInput.render(matrixStack, mouseX, mouseY, partialTicks);

        // Hint text for input
        if (chatInput.getValue().isEmpty() && !chatInput.isFocused()) {
            this.font.draw(matrixStack, "Press Enter to send...",
                DIALOG_BOX_MARGIN + 5, this.height - DIALOG_BOX_MARGIN + 10, 0x80AAAAAA);
        }

        // Render buttons
        super.render(matrixStack, mouseX, mouseY, partialTicks);
    }

    private void drawBorder(MatrixStack matrixStack, int x, int y, int width, int height, int color) {
        fill(matrixStack, x, y, x + width, y + 1, color);
        fill(matrixStack, x, y + height - 1, x + width, y + height, color);
        fill(matrixStack, x, y, x + 1, y + height, color);
        fill(matrixStack, x + width - 1, y, x + width, y + height, color);
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.length() > 0 ? currentLine + " " + word : word;
            if (this.font.width(testLine) <= maxWidth) {
                currentLine = new StringBuilder(testLine);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key sends message
        if (keyCode == 257 && chatInput.isFocused()) { // 257 = Enter
            sendMessage();
            return true;
        }

        // Escape closes screen
        if (keyCode == 256) { // 256 = Escape
            this.onClose();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
