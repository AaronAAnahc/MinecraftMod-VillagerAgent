package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;

/** Server-thread transfers. Inspect all slots before debiting the agent. */
public final class FurnaceInventory {
    public static boolean fits(ItemStack slot, ItemStack offered, int limit) {
        return !offered.isEmpty() && offered.getCount() <= Math.min(limit, offered.getMaxStackSize())
                && (slot.isEmpty() || (ItemStack.isSame(slot, offered) && ItemStack.tagMatches(slot, offered)
                && slot.getCount() + offered.getCount() <= Math.min(limit, slot.getMaxStackSize())));
    }

    public static boolean canLoad(AbstractFurnaceTileEntity furnace, ItemStack ore, ItemStack result) {
        return !ore.isEmpty() && !result.isEmpty()
                && fits(furnace.getItem(0), ore, furnace.getMaxStackSize())
                && fits(furnace.getItem(2), result, furnace.getMaxStackSize());
    }

    /** Largest batch loaded at once; a blast furnace needs 5 s and a furnace 10 s per item. */
    public static final int MAX_BATCH = 16;
    /** Items smelted by one coal or charcoal. */
    public static final int ITEMS_PER_FUEL = 8;

    /** A batch of ore (count = batch size) and enough compatible fuel for it, in one loading interaction. */
    public static boolean load(AgentInventory inventory, AbstractFurnaceTileEntity furnace,
                               ItemStack ore, ItemStack result) {
        if (!canLoad(furnace, ore, result)) return false;
        ItemStack source = null;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && ItemStack.isSame(stack, ore) && ItemStack.tagMatches(stack, ore)) {
                source = stack; break;
            }
        }
        if (source == null || carried(inventory, ore) < ore.getCount()) return false;
        if (!ensureFuel(inventory, furnace)) return false;
        ItemStack input = furnace.getItem(0);
        if (input.isEmpty()) furnace.setItem(0, ore.copy()); else input.grow(ore.getCount());
        // The batch may span several carried stacks.
        int left = ore.getCount();
        for (ItemStack stack : inventory.getItems()) {
            if (left <= 0) break;
            if (stack.isEmpty() || !ItemStack.isSame(stack, ore) || !ItemStack.tagMatches(stack, ore)) continue;
            int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n;
        }
        ensureFuel(inventory, furnace);
        furnace.setChanged();
        return true;
    }

    public static boolean burning(AbstractFurnaceTileEntity furnace) {
        return furnace.getBlockState().getValue(net.minecraft.block.AbstractFurnaceBlock.LIT);
    }

    public static boolean carriedFuel(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() == Items.COAL || stack.getItem() == Items.CHARCOAL);
    }

    static int carried(AgentInventory inventory, ItemStack type) {
        int n = 0;
        for (ItemStack stack : inventory.getItems())
            if (!stack.isEmpty() && ItemStack.isSame(stack, type) && ItemStack.tagMatches(stack, type)) n += stack.getCount();
        return n;
    }

    /**
     * Existing fuel fixes the type; otherwise prefer coal over charcoal, independent of slot order. Tops the fuel
     * slot up to what the queued input needs (one fuel per eight items, the burning fuel counting as one).
     */
    public static boolean ensureFuel(AgentInventory inventory, AbstractFurnaceTileEntity furnace) {
        ItemStack current = furnace.getItem(1);
        if (!current.isEmpty() && !AbstractFurnaceTileEntity.isFuel(current)) return false;
        ItemStack fuel = ItemStack.EMPTY;
        if (!current.isEmpty()) {
            for (ItemStack stack : inventory.getItems()) {
                if (!stack.isEmpty() && ItemStack.isSame(current, stack) && ItemStack.tagMatches(current, stack)) {
                    fuel = stack; break;
                }
            }
        } else {
            for (net.minecraft.item.Item type : new net.minecraft.item.Item[]{Items.COAL, Items.CHARCOAL}) {
                for (ItemStack stack : inventory.getItems()) {
                    if (!stack.isEmpty() && stack.getItem() == type) { fuel = stack; break; }
                }
                if (!fuel.isEmpty()) break;
            }
        }
        int queued = furnace.getItem(0).getCount();
        int needed = (queued + ITEMS_PER_FUEL - 1) / ITEMS_PER_FUEL - (burning(furnace) ? 1 : 0) - current.getCount();
        if (current.isEmpty() && !burning(furnace)) needed = Math.max(1, needed);
        if (!fuel.isEmpty() && needed > 0 && furnace.canPlaceItem(1, fuel)) {
            int give = Math.min(needed, fuel.getCount());
            ItemStack some = fuel.copy(); some.setCount(give);
            if (fits(current, some, furnace.getMaxStackSize())) {
                if (current.isEmpty()) furnace.setItem(1, some); else current.grow(give);
                fuel.shrink(give); furnace.setChanged();
            }
        }
        return !furnace.getItem(1).isEmpty() || burning(furnace);
    }

    /**
     * Explicit rarity policy for the supported ore set: gold before iron. The result is a whole batch: every carried
     * ore of that type, up to {@link #MAX_BATCH} and the space left in the furnace input.
     */
    public static ItemStack selectOre(AgentInventory inventory, ItemStack current) {
        for (net.minecraft.item.Item type : new net.minecraft.item.Item[]{Items.GOLD_ORE, Items.IRON_ORE}) {
            for (ItemStack stack : inventory.getItems()) {
                if (stack.isEmpty() || stack.getItem() != type) continue;
                ItemStack one = stack.copy(); one.setCount(1);
                if (!fits(current, one, 64)) continue;
                return batch(inventory, one, current);
            }
        }
        return ItemStack.EMPTY;
    }

    /** {@code ore} sized to the carried amount, capped by {@link #MAX_BATCH} and the input slot's free space. */
    public static ItemStack batch(AgentInventory inventory, ItemStack ore, ItemStack currentInput) {
        int room = Math.min(64, ore.getMaxStackSize()) - (currentInput.isEmpty() ? 0 : currentInput.getCount());
        int n = Math.max(1, Math.min(Math.min(MAX_BATCH, room), carried(inventory, ore)));
        ItemStack out = ore.copy(); out.setCount(n);
        return out;
    }

    public static int collect(AgentInventory inventory, AbstractFurnaceTileEntity furnace) {
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) return 0;
        int moved = InventoryTransfer.insert(inventory, output.copy());
        if (moved > 0) {
            output.shrink(moved);
            if (output.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
        }
        return moved;
    }
    private FurnaceInventory() {}
}
