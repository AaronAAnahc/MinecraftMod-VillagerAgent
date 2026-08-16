package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.github.AaronAA0721.villageragent.network.TradeRequestPacket;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft-style trade screen with drag-and-drop.
 *
 * Layout:
 * - Left side: Player's inventory
 * - Right side: Villager's inventory (read-only, shows what they have)
 * - Center: Sell slots (what you give), Buy slots (what you want)
 *
 * Controls:
 * - Left-click: Pick up entire stack / Place entire held stack
 * - Right-click: Pick up half stack / Place one item from held stack
 * - Shift+click: Quick move entire stack to trade slot
 */
public class VillagerTradeScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();

    private final UUID villagerId;
    private final String villagerName;
    private final String profession;
    private final String personality;
    private List<ItemStack> villagerInventory;
    /** Equipped armor: HEAD(0), CHEST(1), LEGS(2), FEET(3) */
    private List<ItemStack> villagerArmor;
    private final VillagerChatScreen parentScreen;

    // Trade slots - what player offers to give and wants to receive
    private final ItemStack[] sellSlots = new ItemStack[2];  // What player offers TO villager
    private final ItemStack[] buySlots = new ItemStack[2];   // What player wants FROM villager

    // Cursor held item (for drag and drop)
    private ItemStack heldItem = ItemStack.EMPTY;

    // Trade result
    private String tradeResultMessage = null;
    private boolean tradeAccepted = false;
    private long tradeResultTime = 0;

    // Layout constants
    private static final int SLOT_SIZE = 18;

    // Armor slot labels
    private static final String[] ARMOR_LABELS = {"Head", "Chest", "Legs", "Feet"};

    public VillagerTradeScreen(UUID villagerId, String villagerName, String profession, String personality,
                               List<ItemStack> villagerInventory, List<ItemStack> villagerArmor,
                               VillagerChatScreen parentScreen) {
        super(new StringTextComponent("Trade with " + villagerName));
        this.villagerId = villagerId;
        this.villagerName = villagerName;
        this.profession = profession;
        this.personality = personality;
        this.villagerInventory = new ArrayList<>(villagerInventory);
        this.villagerArmor = new ArrayList<>(villagerArmor);
        this.parentScreen = parentScreen;

        // Initialize empty slots
        for (int i = 0; i < 2; i++) {
            sellSlots[i] = ItemStack.EMPTY;
            buySlots[i] = ItemStack.EMPTY;
        }
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        // Trade area starts below the inventory/armor panels
        int tradeY = getTradeAreaY();

        // Back button (top left)
        this.addButton(new Button(10, 10, 60, 20,
            new StringTextComponent("< Back"), button -> goBack()));

        // Propose Trade button (below trade slots)
        this.addButton(new Button(centerX - 50, tradeY + 40, 100, 20,
            new StringTextComponent("Propose Trade"), button -> proposeTrade()));

        // Clear Sell button
        this.addButton(new Button(centerX - 90, tradeY + 22, 50, 16,
            new StringTextComponent("Clear"), button -> clearSellSlots()));

        // Clear Buy button
        this.addButton(new Button(centerX + 40, tradeY + 22, 50, 16,
            new StringTextComponent("Clear"), button -> clearBuySlots()));
    }

    /** Y coordinate where the trade area begins (below all panels). */
    private int getTradeAreaY() {
        // Armor panel bottom = PANEL_TOP + PANEL_HEIGHT + gap + ARMOR_HEIGHT
        int panelBottom = 35 + 100 + 5 + 40;  // = 180
        // Ensure at least 10px gap below panels
        return Math.max(panelBottom + 10, this.height / 2 + 10);
    }

    private void goBack() {
        // Return any held items to player inventory before closing
        if (!heldItem.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.inventory.add(heldItem);
            }
            heldItem = ItemStack.EMPTY;
        }
        // Return items in sell slots back to player
        returnSellSlotsToPlayer();
        Minecraft.getInstance().setScreen(parentScreen);
    }

    private void returnSellSlotsToPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (int i = 0; i < sellSlots.length; i++) {
                if (!sellSlots[i].isEmpty()) {
                    mc.player.inventory.add(sellSlots[i]);
                    sellSlots[i] = ItemStack.EMPTY;
                }
            }
        }
    }

    private void clearSellSlots() {
        // Return items to player inventory
        returnSellSlotsToPlayer();
    }

    private void clearBuySlots() {
        buySlots[0] = ItemStack.EMPTY;
        buySlots[1] = ItemStack.EMPTY;
    }

    private void proposeTrade() {
        boolean hasSell = !sellSlots[0].isEmpty() || !sellSlots[1].isEmpty();
        boolean hasBuy = !buySlots[0].isEmpty() || !buySlots[1].isEmpty();

        if (!hasSell && !hasBuy) {
            tradeResultMessage = "Put items in the trade slots first!";
            tradeAccepted = false;
            tradeResultTime = System.currentTimeMillis();
            return;
        }

        // Return held item before proposing
        if (!heldItem.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.inventory.add(heldItem);
            }
            heldItem = ItemStack.EMPTY;
        }

        // Send trade request to server (items in sellSlots will be taken if accepted)
        ModNetworking.CHANNEL.sendToServer(new TradeRequestPacket(
            villagerId,
            sellSlots[0].copy(),
            sellSlots[1].copy(),
            buySlots[0].copy(),
            buySlots[1].copy()
        ));

        // Go back to chat screen to show the response
        if (parentScreen != null) {
            parentScreen.addMessage(villagerName, "Hmm, let me think about this trade...", false);
            Minecraft.getInstance().setScreen(parentScreen);
        }
    }

    public void showTradeResult(boolean accepted, String message) {
        this.tradeAccepted = accepted;
        this.tradeResultMessage = message;
        this.tradeResultTime = System.currentTimeMillis();

        // Clear trade slots - server handles returning items if rejected
        for (int i = 0; i < sellSlots.length; i++) {
            sellSlots[i] = ItemStack.EMPTY;
        }
        clearBuySlots();

        // Also update parent screen
        if (parentScreen != null) {
            parentScreen.showTradeResult(accepted, message);
        }
    }

    public UUID getVillagerId() {
        return villagerId;
    }

    /**
     * Refresh the displayed villager inventory/armor in place (e.g. after a trade).
     * Replaces the cached snapshot rather than mutating the list identity.
     */
    public void updateVillagerData(List<ItemStack> inventory, List<ItemStack> armor) {
        this.villagerInventory = new ArrayList<>(inventory);
        this.villagerArmor = new ArrayList<>(armor);
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        // Dark background
        this.renderBackground(matrixStack);

        int centerX = this.width / 2;

        // Title with profession
        drawCenteredString(matrixStack, this.font,
            TextFormatting.GOLD + "Trade with " + villagerName + TextFormatting.GRAY + " (" + profession + ")",
            centerX, 20, 0xFFFFFF);

        // === LEFT PANEL: Player Inventory (top area) ===
        int playerPanelX = 20;
        int playerPanelY = 35;
        renderPanel(matrixStack, playerPanelX, playerPanelY, 175, 100, "Your Inventory");
        renderPlayerInventory(matrixStack, playerPanelX + 5, playerPanelY + 18, mouseX, mouseY);

        // === RIGHT PANEL: Villager Inventory (top area, same Y) ===
        int villagerPanelX = this.width - 195;
        int villagerPanelY = 35;
        renderPanel(matrixStack, villagerPanelX, villagerPanelY, 175, 100, villagerName + "'s Items (reference)");
        renderVillagerInventory(matrixStack, villagerPanelX + 5, villagerPanelY + 18, mouseX, mouseY);

        // === ARMOR PANEL: Equipped armor (below villager inventory) ===
        int armorPanelX = villagerPanelX;
        int armorPanelY = villagerPanelY + 105;
        renderPanel(matrixStack, armorPanelX, armorPanelY, 175, 40, "Equipped Armor");
        renderArmorSlots(matrixStack, armorPanelX + 5, armorPanelY + 18, mouseX, mouseY);

        // === CENTER: Trade Slots (below all panels) ===
        int tradeY = getTradeAreaY();
        int tradeCenterX = centerX;

        // Sell section (what you offer TO villager)
        this.font.draw(matrixStack, TextFormatting.GREEN + "You Give:", tradeCenterX - 75, tradeY - 12, 0xFFFFFF);
        renderTradeSlot(matrixStack, tradeCenterX - 65, tradeY + 2, sellSlots[0], mouseX, mouseY, true);
        renderTradeSlot(matrixStack, tradeCenterX - 40, tradeY + 2, sellSlots[1], mouseX, mouseY, true);

        // Arrow
        drawCenteredString(matrixStack, this.font, "==>", tradeCenterX, tradeY + 9, 0xFFFFFF);

        // Buy section (what you want FROM villager)
        this.font.draw(matrixStack, TextFormatting.YELLOW + "You Get:", tradeCenterX + 20, tradeY - 12, 0xFFFFFF);
        renderTradeSlot(matrixStack, tradeCenterX + 20, tradeY + 2, buySlots[0], mouseX, mouseY, true);
        renderTradeSlot(matrixStack, tradeCenterX + 45, tradeY + 2, buySlots[1], mouseX, mouseY, true);

        // Trade result message
        if (tradeResultMessage != null && System.currentTimeMillis() - tradeResultTime < 5000) {
            int msgColor = tradeAccepted ? 0xFF00FF00 : 0xFFFF5555;
            drawCenteredString(matrixStack, this.font, tradeResultMessage, centerX, tradeY + 65, msgColor);
        }

        // Instructions
        String instructions = heldItem.isEmpty()
            ? "Left-click: pick up stack | Right-click: pick up half | Shift+click: quick move"
            : "Left-click: place all | Right-click: place one";
        drawCenteredString(matrixStack, this.font, TextFormatting.GRAY + instructions, centerX, this.height - 25, 0xAAAAAA);
        drawCenteredString(matrixStack, this.font, TextFormatting.GRAY + "Click villager items to specify what you want", centerX, this.height - 12, 0xAAAAAA);

        super.render(matrixStack, mouseX, mouseY, partialTicks);

        // Render held item following cursor (must be after super.render)
        if (!heldItem.isEmpty()) {
            this.itemRenderer.renderGuiItem(heldItem, mouseX - 8, mouseY - 8);
            this.itemRenderer.renderGuiItemDecorations(this.font, heldItem, mouseX - 8, mouseY - 8);
        }
    }

    private void renderPanel(MatrixStack matrixStack, int x, int y, int width, int height, String title) {
        // Panel background (Minecraft inventory style)
        fill(matrixStack, x, y, x + width, y + height, 0xFFC6C6C6);

        // Border
        fill(matrixStack, x, y, x + width, y + 2, 0xFFFFFFFF);
        fill(matrixStack, x, y, x + 2, y + height, 0xFFFFFFFF);
        fill(matrixStack, x + width - 2, y, x + width, y + height, 0xFF555555);
        fill(matrixStack, x, y + height - 2, x + width, y + height, 0xFF555555);

        // Title
        this.font.draw(matrixStack, TextFormatting.DARK_GRAY + title, x + 5, y + 5, 0x404040);
    }

    private void renderPlayerInventory(MatrixStack matrixStack, int startX, int startY, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Render all 36 inventory slots (9 hotbar + 27 main)
        for (int i = 0; i < 36; i++) {
            ItemStack item = mc.player.inventory.getItem(i);
            int col = i % 9;
            int row = i / 9;
            int slotX = startX + col * (SLOT_SIZE + 1);
            int slotY = startY + row * (SLOT_SIZE + 1);

            renderInventorySlot(matrixStack, slotX, slotY, item, mouseX, mouseY);
        }
    }

    private void renderVillagerInventory(MatrixStack matrixStack, int startX, int startY, int mouseX, int mouseY) {
        for (int i = 0; i < villagerInventory.size(); i++) {
            ItemStack item = villagerInventory.get(i);
            int col = i % 9;
            int row = i / 9;
            int slotX = startX + col * (SLOT_SIZE + 1);
            int slotY = startY + row * (SLOT_SIZE + 1);

            renderInventorySlot(matrixStack, slotX, slotY, item, mouseX, mouseY);
        }
    }

    /**
     * Render 4 armor slots (HEAD, CHEST, LEGS, FEET) in a horizontal row
     * with small labels. These are read-only display slots.
     */
    private void renderArmorSlots(MatrixStack matrixStack, int startX, int startY, int mouseX, int mouseY) {
        for (int i = 0; i < 4; i++) {
            ItemStack armor = (i < villagerArmor.size()) ? villagerArmor.get(i) : ItemStack.EMPTY;
            int slotX = startX + i * (SLOT_SIZE + 22); // extra space for label
            int slotY = startY;

            // Label above slot
            this.font.draw(matrixStack, TextFormatting.GRAY + ARMOR_LABELS[i],
                    slotX, slotY - 10, 0xAAAAAA);

            // Slot with slightly different color to distinguish from inventory
            fill(matrixStack, slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFF6B6BAA);
            fill(matrixStack, slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0xFF373755);

            if (!armor.isEmpty()) {
                this.itemRenderer.renderGuiItem(armor, slotX + 1, slotY + 1);
                this.itemRenderer.renderGuiItemDecorations(this.font, armor, slotX + 1, slotY + 1);
            }

            // Hover highlight
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                fill(matrixStack, slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0x80FFFFFF);
            }
        }
    }


    private void renderInventorySlot(MatrixStack matrixStack, int x, int y, ItemStack stack, int mouseX, int mouseY) {
        // Slot background
        fill(matrixStack, x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF8B8B8B);
        fill(matrixStack, x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xFF373737);

        if (!stack.isEmpty()) {
            this.itemRenderer.renderGuiItem(stack, x + 1, y + 1);
            this.itemRenderer.renderGuiItemDecorations(this.font, stack, x + 1, y + 1);
        }

        // Hover highlight
        if (mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE) {
            fill(matrixStack, x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x80FFFFFF);
        }
    }

    private void renderTradeSlot(MatrixStack matrixStack, int x, int y, ItemStack stack, int mouseX, int mouseY, boolean canInteract) {
        // Larger slot for trade
        int size = SLOT_SIZE + 4;
        fill(matrixStack, x, y, x + size, y + size, 0xFF555555);
        fill(matrixStack, x + 1, y + 1, x + size - 1, y + size - 1, canInteract ? 0xFF8B8B8B : 0xFF6B6B6B);

        if (!stack.isEmpty()) {
            this.itemRenderer.renderGuiItem(stack, x + 3, y + 3);
            this.itemRenderer.renderGuiItemDecorations(this.font, stack, x + 3, y + 3);
        }

        // Hover highlight
        if (mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size) {
            fill(matrixStack, x + 1, y + 1, x + size - 1, y + size - 1, 0x80FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return super.mouseClicked(mouseX, mouseY, button);

        int centerX = this.width / 2;
        int tradeY = getTradeAreaY();
        int tradeSlotSize = SLOT_SIZE + 4;

        boolean isLeftClick = (button == 0);
        boolean isRightClick = (button == 1);
        boolean isShiftHeld = hasShiftDown();

        // === SELL SLOTS (player items to give) ===
        for (int i = 0; i < 2; i++) {
            int slotX = (i == 0) ? centerX - 65 : centerX - 40;
            int slotY = tradeY + 2;
            if (checkSlotClick(mouseX, mouseY, slotX, slotY, tradeSlotSize)) {
                handleSlotInteraction(sellSlots, i, isLeftClick, isRightClick, true);
                return true;
            }
        }

        // === BUY SLOTS (items player wants - just set quantity, don't take from inventory) ===
        for (int i = 0; i < 2; i++) {
            int slotX = (i == 0) ? centerX + 20 : centerX + 45;
            int slotY = tradeY + 2;
            if (checkSlotClick(mouseX, mouseY, slotX, slotY, tradeSlotSize)) {
                handleBuySlotInteraction(i, isLeftClick, isRightClick);
                return true;
            }
        }

        // === PLAYER INVENTORY ===
        int playerPanelX = 20;
        int playerPanelY = 35;
        for (int i = 0; i < 36; i++) {
            int col = i % 9;
            int row = i / 9;
            int slotX = playerPanelX + 5 + col * (SLOT_SIZE + 1);
            int slotY = playerPanelY + 18 + row * (SLOT_SIZE + 1);

            if (checkSlotClick(mouseX, mouseY, slotX, slotY, SLOT_SIZE)) {
                if (isShiftHeld && heldItem.isEmpty()) {
                    ItemStack invItem = mc.player.inventory.getItem(i);
                    if (!invItem.isEmpty()) {
                        if (sellSlots[0].isEmpty()) {
                            sellSlots[0] = invItem.copy();
                            mc.player.inventory.setItem(i, ItemStack.EMPTY);
                        } else if (sellSlots[1].isEmpty()) {
                            sellSlots[1] = invItem.copy();
                            mc.player.inventory.setItem(i, ItemStack.EMPTY);
                        }
                    }
                } else {
                    handlePlayerInventoryClick(i, isLeftClick, isRightClick);
                }
                return true;
            }
        }

        // === VILLAGER INVENTORY (read-only, click to add to buy slots) ===
        int villagerPanelX = this.width - 195;
        int villagerPanelY = 35;
        for (int i = 0; i < villagerInventory.size(); i++) {
            int col = i % 9;
            int row = i / 9;
            int slotX = villagerPanelX + 5 + col * (SLOT_SIZE + 1);
            int slotY = villagerPanelY + 18 + row * (SLOT_SIZE + 1);

            if (checkSlotClick(mouseX, mouseY, slotX, slotY, SLOT_SIZE)) {
                ItemStack villagerItem = villagerInventory.get(i);
                if (!villagerItem.isEmpty()) {
                    ItemStack toAdd = villagerItem.copy();
                    if (isRightClick) {
                        toAdd.setCount(1);
                    }
                    addToBuySlots(toAdd);
                }
                return true;
            }
        }

        // === ARMOR SLOTS (read-only, click to add to buy slots) ===
        int armorPanelX = villagerPanelX;
        int armorPanelY = villagerPanelY + 105;
        for (int i = 0; i < 4; i++) {
            ItemStack armor = (i < villagerArmor.size()) ? villagerArmor.get(i) : ItemStack.EMPTY;
            int slotX = armorPanelX + 5 + i * (SLOT_SIZE + 22);
            int slotY = armorPanelY + 18;
            if (checkSlotClick(mouseX, mouseY, slotX, slotY, SLOT_SIZE)) {
                if (!armor.isEmpty()) {
                    ItemStack toAdd = armor.copy();
                    if (isRightClick) {
                        toAdd.setCount(1);
                    }
                    addToBuySlots(toAdd);
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Helper to add an item to the first available buy slot. */
    private void addToBuySlots(ItemStack toAdd) {
        if (buySlots[0].isEmpty()) {
            buySlots[0] = toAdd;
        } else if (buySlots[0].sameItem(toAdd) && buySlots[0].getCount() < buySlots[0].getMaxStackSize()) {
            buySlots[0].grow(toAdd.getCount());
        } else if (buySlots[1].isEmpty()) {
            buySlots[1] = toAdd;
        } else if (buySlots[1].sameItem(toAdd) && buySlots[1].getCount() < buySlots[1].getMaxStackSize()) {
            buySlots[1].grow(toAdd.getCount());
        }
    }

    private void handlePlayerInventoryClick(int slotIndex, boolean leftClick, boolean rightClick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack slotItem = mc.player.inventory.getItem(slotIndex);

        if (heldItem.isEmpty()) {
            // Pick up from inventory
            if (!slotItem.isEmpty()) {
                if (leftClick) {
                    // Pick up entire stack
                    heldItem = slotItem.copy();
                    mc.player.inventory.setItem(slotIndex, ItemStack.EMPTY);
                } else if (rightClick) {
                    // Pick up half
                    int half = (slotItem.getCount() + 1) / 2;
                    heldItem = slotItem.copy();
                    heldItem.setCount(half);
                    slotItem.shrink(half);
                    if (slotItem.isEmpty()) {
                        mc.player.inventory.setItem(slotIndex, ItemStack.EMPTY);
                    }
                }
            }
        } else {
            // Place into inventory
            if (slotItem.isEmpty()) {
                if (leftClick) {
                    mc.player.inventory.setItem(slotIndex, heldItem.copy());
                    heldItem = ItemStack.EMPTY;
                } else if (rightClick) {
                    ItemStack toPlace = heldItem.copy();
                    toPlace.setCount(1);
                    mc.player.inventory.setItem(slotIndex, toPlace);
                    heldItem.shrink(1);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                }
            } else if (slotItem.sameItem(heldItem)) {
                // Stack items
                int canAdd = slotItem.getMaxStackSize() - slotItem.getCount();
                if (leftClick) {
                    int toAdd = Math.min(canAdd, heldItem.getCount());
                    slotItem.grow(toAdd);
                    heldItem.shrink(toAdd);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                } else if (rightClick && canAdd > 0) {
                    slotItem.grow(1);
                    heldItem.shrink(1);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                }
            } else {
                // Swap items
                if (leftClick) {
                    mc.player.inventory.setItem(slotIndex, heldItem.copy());
                    heldItem = slotItem.copy();
                }
            }
        }
    }

    private void handleSlotInteraction(ItemStack[] slots, int index, boolean leftClick, boolean rightClick, boolean isSellSlot) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack slotItem = slots[index];

        if (heldItem.isEmpty()) {
            // Pick up from slot
            if (!slotItem.isEmpty()) {
                if (leftClick) {
                    heldItem = slotItem.copy();
                    slots[index] = ItemStack.EMPTY;
                } else if (rightClick) {
                    int half = (slotItem.getCount() + 1) / 2;
                    heldItem = slotItem.copy();
                    heldItem.setCount(half);
                    slotItem.shrink(half);
                    if (slotItem.isEmpty()) slots[index] = ItemStack.EMPTY;
                }
            }
        } else {
            // Place into slot
            if (slotItem.isEmpty()) {
                if (leftClick) {
                    slots[index] = heldItem.copy();
                    heldItem = ItemStack.EMPTY;
                } else if (rightClick) {
                    ItemStack toPlace = heldItem.copy();
                    toPlace.setCount(1);
                    slots[index] = toPlace;
                    heldItem.shrink(1);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                }
            } else if (slotItem.sameItem(heldItem)) {
                int canAdd = slotItem.getMaxStackSize() - slotItem.getCount();
                if (leftClick) {
                    int toAdd = Math.min(canAdd, heldItem.getCount());
                    slotItem.grow(toAdd);
                    heldItem.shrink(toAdd);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                } else if (rightClick && canAdd > 0) {
                    slotItem.grow(1);
                    heldItem.shrink(1);
                    if (heldItem.isEmpty()) heldItem = ItemStack.EMPTY;
                }
            } else {
                // Swap
                if (leftClick) {
                    ItemStack temp = slots[index].copy();
                    slots[index] = heldItem.copy();
                    heldItem = temp;
                }
            }
        }
    }

    private void handleBuySlotInteraction(int index, boolean leftClick, boolean rightClick) {
        ItemStack slotItem = buySlots[index];

        if (heldItem.isEmpty()) {
            // Pick up from buy slot (just remove the request)
            if (!slotItem.isEmpty()) {
                if (leftClick) {
                    buySlots[index] = ItemStack.EMPTY;
                } else if (rightClick) {
                    slotItem.shrink(1);
                    if (slotItem.isEmpty()) buySlots[index] = ItemStack.EMPTY;
                }
            }
        } else {
            // Cannot place player items in buy slot - buy slot is for specifying what you WANT
            // Just clear held item back to inventory
        }
    }

    private boolean checkSlotClick(double mouseX, double mouseY, int slotX, int slotY, int size) {
        return mouseX >= slotX && mouseX < slotX + size && mouseY >= slotY && mouseY < slotY + size;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Escape
            goBack();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // Return held item and sell slot items to player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            if (!heldItem.isEmpty()) {
                mc.player.inventory.add(heldItem);
                heldItem = ItemStack.EMPTY;
            }
            returnSellSlotsToPlayer();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

