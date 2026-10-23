package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Exact-count insertion/transfer: partial or full inventories never delete or duplicate goods. */
public final class InventoryTransfer {
    public static int spaceFor(AgentInventory inv, ItemStack input) {
        int space = 0;
        for (ItemStack slot : inv.getItems()) {
            if (slot.isEmpty()) space += input.getMaxStackSize();
            else if (ItemStack.isSame(slot, input) && ItemStack.tagMatches(slot, input))
                space += Math.max(0, slot.getMaxStackSize() - slot.getCount());
        }
        return space;
    }
    public static int insert(AgentInventory inv, ItemStack input) {
        int remaining = input.getCount();
        for (int i = 0; i < inv.getItems().size() && remaining > 0; i++) {
            ItemStack slot = inv.getStackInSlot(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSame(slot, input) || !ItemStack.tagMatches(slot, input)) continue;
            int n = Math.min(remaining, Math.max(0, slot.getMaxStackSize() - slot.getCount()));
            slot.grow(n); remaining -= n;
        }
        for (int i = 0; i < inv.getItems().size() && remaining > 0; i++) {
            if (!inv.getStackInSlot(i).isEmpty()) continue;
            int n = Math.min(remaining, input.getMaxStackSize());
            ItemStack placed = input.copy(); placed.setCount(n); inv.setStackInSlot(i, placed); remaining -= n;
        }
        return input.getCount() - remaining;
    }
    public static int move(AgentInventory from, AgentInventory to, Item item, int count) {
        int moved = 0;
        for (ItemStack stack : from.getItems()) {
            if (stack.isEmpty() || stack.getItem() != item || moved >= count) continue;
            ItemStack offered = stack.copy(); offered.setCount(Math.min(stack.getCount(), count - moved));
            int n = insert(to, offered); stack.shrink(n); moved += n;
        }
        return moved;
    }
    private InventoryTransfer() {}
}
