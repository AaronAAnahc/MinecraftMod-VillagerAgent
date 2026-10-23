package com.github.AaronAA0721.villageragent.ai.work;

import java.util.UUID;

/** Dependency-free regression checks, run by Gradle check / workOrderChecks. */
public final class WorkOrderBookChecks {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        UUID customer = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        WorkOrderBook book = new WorkOrderBook();
        WorkOrderBook.Order o = book.request(customer, "minecraft:oak_log", ResourceProfession.LUMBERJACK, 12, 2, 0);
        check(o.remaining == 10, "subtract existing stock");
        check(book.request(customer, "minecraft:oak_log", ResourceProfession.LUMBERJACK, 12, 2, 10) == o,
                "repeated sensor events must not duplicate demand");
        check(book.all().size() == 1, "one live request per requester/item");
        check(!book.claim(o.id, customer, 20), "cannot fulfil own external order");
        check(book.claim(o.id, a, 20), "first worker claims");
        check(!book.claim(o.id, b, 21), "competing worker cannot duplicate claim");
        WorkOrderBook.Order other = book.request(UUID.randomUUID(), "minecraft:coal", ResourceProfession.MINER, 8, 0, 20);
        check(!book.claim(other.id, a, 21), "one worker cannot hold two active leases");
        check(!book.renew(o.id, b, 22), "non-owner cannot renew");
        check(book.renew(o.id, a, 500), "active work renews lease");
        book.tick(800);
        check(a.equals(o.worker), "renewed lease survives original expiry");
        book.reconcile(o.id, 7);
        check(o.remaining == 5 && o.active(), "partial delivery never closes the order");
        book.tick(1100);
        check(o.status == WorkOrderBook.Status.OPEN && o.worker == null, "offline worker lease is released");
        check(!book.renew(o.id, a, 1101), "expired ownership cannot be silently renewed");
        check(book.claim(o.id, b, 1101), "another worker can recover orphaned order");
        book.release(o.id, a);
        check(b.equals(o.worker), "old worker cannot release new owner's claim");
        book.reconcile(o.id, 12);
        check(o.status == WorkOrderBook.Status.DELIVERED && o.remaining == 0, "verified stock completes demand");
        check(!book.claim(o.id, a, 1200), "completed order cannot be reclaimed");
        WorkOrderBook.Order again = book.request(customer, "minecraft:oak_log", ResourceProfession.LUMBERJACK, 12, 3, 1200);
        check(!again.id.equals(o.id) && again.remaining == 9, "new consumption creates fresh demand");
        book.tick(again.expiresAt);
        check(again.status == WorkOrderBook.Status.EXPIRED, "expired request cannot occupy workers forever");
        check(book.request(customer, "minecraft:coal", ResourceProfession.MINER, 4, 4, 0) == null, "no deficit means no order");
        WorkOrderBook isolated = new WorkOrderBook();
        check(isolated.get(o.id) == null, "dimension-local ledgers do not share claims");
        System.out.println("WorkOrderBook: " + assertions + " assertions passed");
    }
}
