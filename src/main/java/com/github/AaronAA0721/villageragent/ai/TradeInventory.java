package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Plans a barter on copies. A failed plan never changes either owner's inventory. */
public final class TradeInventory {
    private TradeInventory() {}

    public static boolean matches(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSame(a, b) && ItemStack.tagMatches(a, b);
    }

    public static List<ItemStack> copy(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : items) result.add(stack.copy());
        return result;
    }

    public static int count(List<ItemStack> items, ItemStack target) {
        int result = 0;
        for (ItemStack stack : items) if (matches(stack, target)) result += stack.getCount();
        return result;
    }

    public static final class Plan {
        public final List<ItemStack> player, villager, armor;
        private Plan(List<ItemStack> player, List<ItemStack> villager, List<ItemStack> armor) {
            this.player = player; this.villager = villager; this.armor = armor;
        }
    }

    public static Plan plan(List<ItemStack> player, List<ItemStack> villager, List<ItemStack> armor,
                            ItemStack[] offers, ItemStack[] requests) {
        Plan plan = new Plan(copy(player), copy(villager), copy(armor));
        // Debit both sides before adding receipts: incoming goods cannot fund the same trade.
        for (ItemStack stack : offers) {
            if (!valid(stack) || remove(plan.player, stack, stack.getCount()) != 0) return null;
        }
        for (ItemStack stack : requests) {
            if (!valid(stack)) return null;
            int remaining = remove(plan.villager, stack, stack.getCount());
            if (remove(plan.armor, stack, remaining) != 0) return null;
        }
        for (ItemStack stack : offers) if (!insert(plan.villager, stack)) return null;
        for (ItemStack stack : requests) if (!insert(plan.player, stack)) return null;
        return plan;
    }

    private static boolean valid(ItemStack stack) {
        return stack.isEmpty() || (stack.getCount() > 0 && stack.getCount() <= stack.getMaxStackSize());
    }

    private static int remove(List<ItemStack> items, ItemStack target, int remaining) {
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (!matches(stack, target)) continue;
            int n = Math.min(remaining, stack.getCount());
            stack.shrink(n); remaining -= n;
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
        }
        return remaining;
    }

    private static boolean insert(List<ItemStack> items, ItemStack input) {
        int remaining = input.getCount();
        for (ItemStack stack : items) {
            if (!matches(stack, input)) continue;
            int n = Math.min(remaining, Math.max(0, stack.getMaxStackSize() - stack.getCount()));
            stack.grow(n); remaining -= n;
        }
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            if (!items.get(i).isEmpty()) continue;
            int n = Math.min(remaining, input.getMaxStackSize());
            ItemStack stack = input.copy(); stack.setCount(n);
            items.set(i, stack); remaining -= n;
        }
        return remaining == 0;
    }
}
