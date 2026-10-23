package com.github.AaronAA0721.villageragent.ai.work;

import java.util.*;

/** Single-threaded demand/lease ledger. No Minecraft or LLM dependency. */
public final class WorkOrderBook {
    public enum Status { OPEN, CLAIMED, DELIVERED, CANCELLED, EXPIRED }
    public static final long LEASE_TICKS = 600;
    public static final long ORDER_TICKS = 24000;

    public static final class Order {
        public final UUID id, requester;
        public final String item;
        public final ResourceProfession profession;
        public int targetStock, remaining;
        public long expiresAt, leaseUntil;
        public UUID worker;
        public Status status = Status.OPEN;

        public Order(UUID id, UUID requester, String item, ResourceProfession profession,
                     int targetStock, int remaining, long expiresAt) {
            this.id = id; this.requester = requester; this.item = item;
            this.profession = profession; this.targetStock = targetStock;
            this.remaining = remaining; this.expiresAt = expiresAt;
        }
        public boolean active() { return status == Status.OPEN || status == Status.CLAIMED; }
    }

    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    public Collection<Order> all() { return Collections.unmodifiableCollection(orders.values()); }
    public Order get(UUID id) { return orders.get(id); }
    public void restore(Order order) { orders.put(order.id, order); }
    public Order request(UUID requester, String item, ResourceProfession profession,
                         int desiredStock, int currentStock, long now) {
        if (desiredStock <= currentStock || desiredStock <= 0) return null;
        for (Order o : orders.values()) {
            if (o.active() && o.requester.equals(requester) && o.item.equals(item)) {
                o.targetStock = Math.max(o.targetStock, desiredStock);
                o.remaining = Math.max(0, o.targetStock - currentStock);
                o.expiresAt = now + ORDER_TICKS;
                return o;
            }
        }
        Order o = new Order(UUID.randomUUID(), requester, item, profession,
                desiredStock, desiredStock - currentStock, now + ORDER_TICKS);
        orders.put(o.id, o);
        return o;
    }
    public boolean claim(UUID orderId, UUID worker, long now) {
        Order o = get(orderId);
        if (o == null || !o.active() || o.expiresAt <= now || o.requester.equals(worker)) return false;
        if (o.worker != null && !o.worker.equals(worker) && o.leaseUntil > now) return false;
        // A worker cannot reserve two jobs while only executing one.
        for (Order other : orders.values()) {
            if (other != o && other.active() && worker.equals(other.worker) && other.leaseUntil > now) return false;
        }
        o.worker = worker; o.leaseUntil = now + LEASE_TICKS; o.status = Status.CLAIMED;
        return true;
    }
    public boolean renew(UUID orderId, UUID worker, long now) {
        Order o = get(orderId);
        if (o == null || o.status != Status.CLAIMED || !worker.equals(o.worker)
                || o.leaseUntil <= now || o.expiresAt <= now) return false;
        o.leaseUntil = now + LEASE_TICKS;
        return true;
    }
    public void release(UUID orderId, UUID worker) {
        Order o = get(orderId);
        if (o != null && o.active() && worker.equals(o.worker)) {
            o.worker = null; o.leaseUntil = 0; o.status = Status.OPEN;
        }
    }
    /** Inventory is authoritative: deliveries, gifts and trades may all satisfy a request. */
    public void reconcile(UUID orderId, int stock) {
        Order o = get(orderId);
        if (o == null || !o.active()) return;
        o.remaining = Math.max(0, o.targetStock - stock);
        if (o.remaining == 0) { o.status = Status.DELIVERED; o.worker = null; }
    }
    public void tick(long now) {
        for (Order o : orders.values()) {
            if (!o.active()) continue;
            if (now >= o.expiresAt) { o.status = Status.EXPIRED; o.worker = null; }
            else if (o.worker != null && now >= o.leaseUntil) {
                o.worker = null; o.status = Status.OPEN;
            }
        }
        orders.values().removeIf(o -> !o.active() && now > o.expiresAt + ORDER_TICKS);
    }
}
