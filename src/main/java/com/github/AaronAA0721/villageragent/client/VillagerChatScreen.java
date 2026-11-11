package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.network.ChatMessagePacket;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.github.AaronAA0721.villageragent.network.VillagerGoalsRequestPacket;
import com.github.AaronAA0721.villageragent.network.VillagerInfoRequestPacket;
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
 * Genshin-style borderless dialogue screen.
 *
 * Layout (bottom-up):
 * - Player input box: borderless, semi-transparent solid background, MC-vanilla style
 * - Villager dialog area: semi-transparent gradient (opaque at top → transparent at bottom)
 * - Top-left: villager name    Top-right: Trade button
 * - Exit button: small "X" next to the trade button
 * - Goals button: left of X; toggles a scrollable list of every goal in the villager's brain
 * - Info button: left of Goals; toggles needs / skills / inventory / relationships / chunk memory
 * - While Goals or Info is open the game is paused (singleplayer)
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
    private static final int SIDE_MARGIN = 60;
    private static final int BOTTOM_MARGIN = 24;
    private static final int INPUT_HEIGHT = 20;
    private static final int GAP = 6;
    // Name row sits above dialog; dialog fills from screen midpoint down to input box.
    private static final int NAME_ROW_HEIGHT = 16;

    // Side panels (Goals / Info): toggled by their buttons, fill the upper half of the screen.
    // Only one is open at a time. While either is open the game is paused (singleplayer).
    private enum Panel { NONE, GOALS, INFO }
    private Panel panel = Panel.NONE;
    private Button goalsButton;
    private Button infoButton;
    private List<String> goalLines = new ArrayList<>();
    private List<String> infoLines = new ArrayList<>();
    private boolean goalsLoading = false;
    private boolean infoLoading = false;
    private int goalScroll = 0;      // first visible row
    private int infoScroll = 0;
    private int panelRefreshTicks = 0;
    private static final int PANEL_REFRESH_TICKS = 40; // re-query every 2 s while open
    private static final int PANEL_TOP = 16;
    private static final int LINE_H = 10;
    private static final int HEADER_H = 15;
    private static final int BAR_LABEL_W = 92;

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

        int contentWidth = this.width - SIDE_MARGIN * 2;

        // All UI lives in the bottom half of the screen.
        // dialogTop = screen midpoint; dialogBottom sits just above input box.
        int inputY = this.height - BOTTOM_MARGIN - INPUT_HEIGHT;
        int dialogTop = this.height / 2;

        // ── Player input field (bottom, borderless MC style) ──
        this.chatInput = new TextFieldWidget(this.font, SIDE_MARGIN + 4, inputY + 1, contentWidth - 8, INPUT_HEIGHT - 2,
                new StringTextComponent(""));
        this.chatInput.setMaxLength(200);
        this.chatInput.setVisible(true);
        this.chatInput.setBordered(false);
        this.addWidget(this.chatInput);
        this.setInitialFocus(this.chatInput);

        // ── Trade button (top-right, just above dialog area) ──
        int btnW = 50;
        int btnH = 16;
        this.addButton(new Button(
                SIDE_MARGIN + contentWidth - btnW, dialogTop - btnH - 2,
                btnW, btnH,
                new StringTextComponent("\u00A76Trade"),
                button -> openTradeScreen()));

        // ── Exit button ("X", left of Trade) ──
        this.addButton(new Button(
                SIDE_MARGIN + contentWidth - btnW - 22, dialogTop - btnH - 2,
                20, btnH,
                new StringTextComponent("\u00A7cX"),
                button -> onClose()));

        // ── Goals button (left of X): toggles the brain's goal list ──
        this.goalsButton = this.addButton(new Button(
                SIDE_MARGIN + contentWidth - btnW - 22 - 52, dialogTop - btnH - 2,
                50, btnH,
                goalsLabel(),
                button -> togglePanel(Panel.GOALS)));

        // ── Info button (left of Goals): toggles the villager state panel ──
        this.infoButton = this.addButton(new Button(
                SIDE_MARGIN + contentWidth - btnW - 22 - 52 - 46, dialogTop - btnH - 2,
                44, btnH,
                infoLabel(),
                button -> togglePanel(Panel.INFO)));
    }

    private StringTextComponent goalsLabel() {
        return new StringTextComponent(panel == Panel.GOALS ? "\u00A7bGoals \u25B4" : "\u00A7bGoals");
    }

    private StringTextComponent infoLabel() {
        return new StringTextComponent(panel == Panel.INFO ? "\u00A7aInfo \u25B4" : "\u00A7aInfo");
    }

    /** Open {@code which} (closing the other one), or close it if it is already open. */
    private void togglePanel(Panel which) {
        panel = panel == which ? Panel.NONE : which;
        if (goalsButton != null) goalsButton.setMessage(goalsLabel());
        if (infoButton != null) infoButton.setMessage(infoLabel());
        if (panel == Panel.GOALS) goalScroll = 0;
        if (panel == Panel.INFO) infoScroll = 0;
        if (panel != Panel.NONE) requestPanel();
    }

    private void requestPanel() {
        panelRefreshTicks = 0;
        if (panel == Panel.GOALS) {
            goalsLoading = goalLines.isEmpty();
            ModNetworking.CHANNEL.sendToServer(new VillagerGoalsRequestPacket(villagerId));
        } else if (panel == Panel.INFO) {
            infoLoading = infoLines.isEmpty();
            ModNetworking.CHANNEL.sendToServer(new VillagerInfoRequestPacket(villagerId));
        }
    }

    /** Called by {@link VillagerChatHandler} when the server answers. */
    public void setGoalLines(List<String> lines) {
        this.goalLines = new ArrayList<>(lines);
        this.goalsLoading = false;
    }

    /** Called by {@link VillagerChatHandler} when the server answers the Info request. */
    public void setInfoLines(List<String> lines) {
        this.infoLines = new ArrayList<>(lines);
        this.infoLoading = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (chatInput != null) chatInput.tick();
        if (panel != Panel.NONE && ++panelRefreshTicks >= PANEL_REFRESH_TICKS) requestPanel();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (panel != Panel.NONE && mouseY < this.height / 2.0 - NAME_ROW_HEIGHT) {
            int step = (int) Math.signum(delta) * 3;
            if (panel == Panel.GOALS) goalScroll = Math.max(0, goalScroll - step);
            else infoScroll = Math.max(0, infoScroll - step);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static boolean isHeader(String row) { return row.startsWith("#H|"); }
    private static boolean isBar(String row)    { return row.startsWith("#B|"); }
    private static int rowHeight(String row)    { return isHeader(row) ? HEADER_H : LINE_H; }

    /** Draw the open panel in the upper half of the screen (above the name row). */
    private void renderPanel(MatrixStack matrixStack, int contentWidth, int dialogTop) {
        boolean goals = panel == Panel.GOALS;
        int top = PANEL_TOP;
        int bottom = dialogTop - NAME_ROW_HEIGHT - 6;
        if (bottom - top < 30) return;
        int left = SIDE_MARGIN, right = SIDE_MARGIN + contentWidth;
        int accent = goals ? 0xFF55FFFF : 0xFF55FF55;
        fill(matrixStack, left, top, right, bottom, 0xC0101018);
        fill(matrixStack, left, top, right, top + 1, accent);

        String title = goals ? villagerName + "'s goals" : villagerName + " \u2014 villager info";
        this.font.drawShadow(matrixStack, title, left + 6, top + 4, accent);
        String hint = "scroll \u2195  \u00B7  auto-refresh 2s";
        if (Minecraft.getInstance().isPaused()) hint = "\u00A7eGAME PAUSED\u00A7r  \u00B7  " + hint;
        this.font.drawShadow(matrixStack, hint, right - 6 - this.font.width(hint), top + 4, 0x80AAAAAA);

        // Flatten into rows: plain text is word-wrapped, headers / bars are single rows.
        List<String> rows = new ArrayList<>();
        if (goals ? goalsLoading : infoLoading) {
            rows.add("\u00A77Loading...");
        } else {
            for (String line : goals ? goalLines : infoLines) {
                if (isHeader(line) || isBar(line)) { rows.add(line); continue; }
                String color = line.startsWith("\u00A7") && line.length() > 1 ? line.substring(0, 2) : "";
                boolean first = true;
                for (String w : wrapText(line, contentWidth - 16)) {
                    rows.add(first ? w : color + "     " + w);
                    first = false;
                }
            }
        }

        int textTop = top + 16;
        int avail = bottom - 4 - textTop;
        // Largest start row such that the tail still fills the panel.
        int maxScroll = rows.size();
        int acc = 0;
        while (maxScroll > 0 && acc + rowHeight(rows.get(maxScroll - 1)) <= avail) {
            acc += rowHeight(rows.get(--maxScroll));
        }
        int scroll = Math.min(goals ? goalScroll : infoScroll, maxScroll);
        if (goals) goalScroll = scroll; else infoScroll = scroll;

        int y = textTop;
        int visibleRows = 0;
        for (int i = scroll; i < rows.size(); i++) {
            String row = rows.get(i);
            int h = rowHeight(row);
            if (y + h > bottom - 4) break;
            if (isHeader(row)) drawHeaderRow(matrixStack, row, left, right, y, i == scroll);
            else if (isBar(row)) drawBarRow(matrixStack, row, left, right, y);
            else this.font.drawShadow(matrixStack, row, left + 8, y, 0xFFFFFFFF);
            y += h;
            visibleRows++;
        }
        if (maxScroll > 0) {
            int barH = Math.max(8, avail * visibleRows / Math.max(1, rows.size()));
            int barY = textTop + (avail - barH) * scroll / maxScroll;
            fill(matrixStack, right - 4, barY, right - 2, barY + barH, 0xA0AAAAAA);
        }
    }

    /** {@code #H|Title|right text} — section title with a thin rule under it. */
    private void drawHeaderRow(MatrixStack ms, String row, int left, int right, int y, boolean firstVisible) {
        String[] p = row.split("\\|", -1);
        String title = p.length > 1 ? p[1] : "";
        String rt = p.length > 2 ? p[2] : "";
        int ty = y + (firstVisible ? 1 : 4);
        this.font.drawShadow(ms, "\u00A7l" + title, left + 6, ty, 0xFFFFD27F);
        if (!rt.isEmpty()) this.font.drawShadow(ms, rt, right - 8 - this.font.width(rt), ty, 0xFF888888);
        fill(ms, left + 6, ty + 9, right - 8, ty + 10, 0x50FFFFFF);
    }

    /** {@code #B|label|value|max|RRGGBB|right text} — label, progress bar, then text. */
    private void drawBarRow(MatrixStack ms, String row, int left, int right, int y) {
        String[] p = row.split("\\|", -1);
        if (p.length < 6) { this.font.drawShadow(ms, row, left + 8, y, 0xFFFFFFFF); return; }
        String label = p[1];
        float value, max;
        int rgb;
        try {
            value = Float.parseFloat(p[2]);
            max = Float.parseFloat(p[3]);
            rgb = Integer.parseInt(p[4], 16);
        } catch (NumberFormatException e) {
            value = 0; max = 1; rgb = 0xAAAAAA;
        }
        String rt = p.length > 5 ? p[5] : "";

        this.font.drawShadow(ms, label, left + 12, y, 0xFFDDDDDD);
        int barX = left + 12 + BAR_LABEL_W;
        int barW = Math.max(40, Math.min(150, (right - left) / 3));
        float frac = max <= 0 ? 0 : Math.max(0f, Math.min(1f, value / max));
        fill(ms, barX - 1, y, barX + barW + 1, y + 8, 0xFF000000);
        fill(ms, barX, y + 1, barX + barW, y + 7, 0xFF2A2A36);
        int filled = Math.round(barW * frac);
        if (filled > 0) {
            fill(ms, barX, y + 1, barX + filled, y + 7, 0xFF000000 | rgb);
            fill(ms, barX, y + 1, barX + filled, y + 2, 0x60FFFFFF); // highlight
        }
        if (!rt.isEmpty()) this.font.drawShadow(ms, rt, barX + barW + 8, y, 0xFFFFFFFF);
    }

    private void openTradeScreen() {
        Minecraft.getInstance().setScreen(new VillagerTradeScreen(
                villagerId, villagerName, profession, personality, villagerInventory, villagerArmor, this));
    }

    private void sendMessage() {
        String message = chatInput.getValue().trim();
        if (!message.isEmpty() && !isWaitingForResponse) {
            currentSpeaker = "You";
            currentMessage = message;
            isPlayerSpeaking = true;

            ModNetworking.CHANNEL.sendToServer(new ChatMessagePacket(villagerId, message));

            chatInput.setValue("");
            isWaitingForResponse = true;

            currentSpeaker = villagerName;
            currentMessage = "...";
            isPlayerSpeaking = false;
        }
    }

    public void addMessage(String sender, String message, boolean isPlayer) {
        currentSpeaker = sender;
        currentMessage = message;
        isPlayerSpeaking = isPlayer;
        isWaitingForResponse = false;
    }

    public void showTradeResult(boolean accepted, String message) {
        currentSpeaker = villagerName;
        currentMessage = message;
        isPlayerSpeaking = false;
        isWaitingForResponse = false;
    }

    public UUID getVillagerId() { return villagerId; }
    public String getVillagerName() { return villagerName; }
    public String getPersonality() { return personality; }
    public List<ItemStack> getVillagerInventory() { return villagerInventory; }
    public String getProfessionName() { return profession; }
    public List<ItemStack> getVillagerArmor() { return villagerArmor; }

    /** Closing the panel (X / Escape) ends the conversation: the villager gets back to work. */
    @Override
    public void onClose() {
        ModNetworking.CHANNEL.sendToServer(new com.github.AaronAA0721.villageragent.network.ConversationEndPacket(villagerId));
        super.onClose();
    }

    public void updateVillagerData(List<ItemStack> inventory, List<ItemStack> armor) {
        this.villagerInventory = new ArrayList<>(inventory);
        this.villagerArmor = new ArrayList<>(armor);
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        int contentWidth = this.width - SIDE_MARGIN * 2;
        int inputY = this.height - BOTTOM_MARGIN - INPUT_HEIGHT;
        int dialogTop = this.height / 2;           // topmost edge = screen midpoint
        int dialogBottom = inputY - GAP;            // bottom edge = just above input
        int dialogHeight = dialogBottom - dialogTop;

        // ═══════════════════════════════════════════════════════════
        //  Villager dialog area: gradient (top semi-opaque → bottom transparent)
        // ═══════════════════════════════════════════════════════════
        int steps = 24;
        int stepH = Math.max(1, dialogHeight / steps);
        for (int i = 0; i < steps; i++) {
            int alpha = (int) (0xA0 * (1.0 - (double) i / steps));
            int color = (alpha << 24) | 0x1a1a2e;
            int y0 = dialogTop + i * stepH;
            int y1 = (i == steps - 1) ? dialogBottom : dialogTop + (i + 1) * stepH;
            fill(matrixStack, SIDE_MARGIN, y0, SIDE_MARGIN + contentWidth, y1, color);
        }

        // ── Villager name (top-left, above dialog area) ──
        String nameDisplay = villagerName;
        int nameColor = 0xFFFFD700; // gold
        this.font.drawShadow(matrixStack, nameDisplay, SIDE_MARGIN + 4, dialogTop - 14, nameColor);

        // ── Profession tag (smaller, next to name) ──
        int nameW = this.font.width(nameDisplay);
        this.font.drawShadow(matrixStack, " " + profession, SIDE_MARGIN + 4 + nameW, dialogTop - 14, 0x80AAAAAA);

        // ── Dialog text (inside gradient area) ──
        if (!currentMessage.isEmpty()) {
            // Speaker indicator for multi-line clarity
            int speakerColor = isPlayerSpeaking ? 0xFF55FFFF : 0xFFFFD700;
            String speakerTag = isPlayerSpeaking ? "" : ""; // no prefix needed — name is already above

            List<String> lines = wrapText(currentMessage, contentWidth - 24);
            int textY = dialogTop + 12;
            for (String line : lines) {
                this.font.drawShadow(matrixStack, line, SIDE_MARGIN + 12, textY, 0xFFFFFFFF);
                textY += 12;
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  Player input area: semi-transparent solid background, no border
        //  Like MC vanilla chat input
        // ═══════════════════════════════════════════════════════════
        fill(matrixStack, SIDE_MARGIN, inputY - 2, SIDE_MARGIN + contentWidth, inputY + INPUT_HEIGHT + 2, 0x80000000);

        // Render the text field
        this.chatInput.render(matrixStack, mouseX, mouseY, partialTicks);

        // Placeholder hint
        if (chatInput.getValue().isEmpty() && !chatInput.isFocused()) {
            this.font.drawShadow(matrixStack, "Type a message...",
                    SIDE_MARGIN + 6, inputY + 5, 0x60AAAAAA);
        }

        if (panel != Panel.NONE) renderPanel(matrixStack, contentWidth, dialogTop);

        // Render buttons (Trade, Goals, Info, X)
        super.render(matrixStack, mouseX, mouseY, partialTicks);
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
        if (keyCode == 257 && chatInput.isFocused()) { // Enter
            sendMessage();
            return true;
        }
        if (keyCode == 256) { // Escape
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Plain chat keeps the world running; while the Goals or Info panel is open the game is
     * frozen so the snapshot can be read calmly. Minecraft re-checks this every tick, so the
     * pause follows the panel toggles. Only effective in singleplayer (vanilla never pauses a
     * multiplayer or LAN-published world).
     */
    @Override
    public boolean isPauseScreen() {
        return panel != Panel.NONE;
    }
}
