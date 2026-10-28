package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.work.*;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;

public final class MethodRequestChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        VillageSignalBoard board = VillageSignalBoard.get();
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ResourceLocation log = new ResourceLocation("minecraft:oak_log");
        UUID ordinary = board.post(buyer, BlockPos.ZERO, VillageSignalBoard.MsgType.TRADE_WANT, log, 1, 0);
        UUID first = board.postOwnedWant(a, buyer, BlockPos.ZERO, log, 2, 0);
        UUID second = board.postOwnedWant(b, buyer, BlockPos.ZERO, log, 3, 0);
        check(!ordinary.equals(first) && !first.equals(second), "independent ownership for same buyer and item");
        board.post(buyer, BlockPos.ZERO, VillageSignalBoard.MsgType.TRADE_WANT, log, 4, 1);
        check(board.find(first).quantity == 2 && board.find(second).quantity == 3, "ordinary scan cannot overwrite method quantities");
        UUID offer = board.post(seller, BlockPos.ZERO, VillageSignalBoard.MsgType.TRADE_HAVE, log, 8, 0);
        check(board.matchTrade(first, offer), "owned request can reserve an offer");
        board.removeOwned(a);
        check(board.find(first) == null && board.find(offer).matchedWith == null, "cancel releases owned request and matching supplier");
        check(board.find(second) != null && board.find(ordinary) != null, "other task and ordinary demand survive cancellation");
        board.removeByAuthor(buyer, VillageSignalBoard.MsgType.TRADE_WANT, null);
        check(board.find(ordinary) == null && board.find(second) != null, "legacy broad cleanup cannot delete method-owned requests");
        check(board.matchTrade(second, offer), "released supplier can serve another task");
        board.resolveTrade(second);
        VillageSignalBoard.VillageMessage receipt = board.find(second);
        board.clearExpiredMessages(1);
        check(board.find(second) == null && receipt.resolved, "receipt survives board pruning during WAITING cooldown");
        board.removeOwned(b); board.removeOwned(b);

        UUID expiring = board.postOwnedWant(a, buyer, BlockPos.ZERO, log, 1, 10);
        UUID refreshedOffer = board.post(seller, BlockPos.ZERO, VillageSignalBoard.MsgType.TRADE_HAVE, log, 2, 10);
        check(board.matchTrade(expiring, refreshedOffer), "reserve offer for an expiring request");
        board.find(expiring).expireTick = 11;
        board.find(refreshedOffer).expireTick = 100;
        board.clearExpiredMessages(11);
        check(board.find(expiring) == null && board.find(refreshedOffer).matchedWith == null,
                "expired request releases a longer-lived supplier offer");

        WorkOrderSavedData data = new WorkOrderSavedData();
        data.book.request(buyer, log.toString(), ResourceProfession.LUMBERJACK, 3, 0, 0);
        data.book.requestOwned(a, buyer, log.toString(), ResourceProfession.LUMBERJACK, 4, 0, 0);
        CompoundNBT saved = data.save(new CompoundNBT());
        check(saved.getList("Orders", 10).size() == 1, "transient attempts are not saved as orphaned demand");
        WorkOrderSavedData restored = new WorkOrderSavedData(); restored.load(saved);
        check(restored.book.all().size() == 1 && restored.book.all().iterator().next().ownerAttempt == null,
                "ordinary demand survives reload while attempts must replan");
        System.out.println("MethodRequestChecks: " + assertions + " assertions passed");
    }
}
