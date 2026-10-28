package com.github.AaronAA0721.villageragent.ai;

import java.util.*;

/** Server-thread two-party admission barrier, independent of Minecraft. */
public final class TradeAppointments {
    public static final class Appointment {
        public final UUID id, buyer, seller;
        public final long deadline;
        public boolean buyerReady, sellerReady, started;
        public long firstReady=-1;
        Appointment(UUID id,UUID buyer,UUID seller,long deadline) {
            this.id=id; this.buyer=buyer; this.seller=seller; this.deadline=deadline;
        }
    }
    private final Map<UUID,Appointment> sessions=new LinkedHashMap<>();
    private final Map<UUID,UUID> owners=new HashMap<>();
    public boolean reserve(UUID id,UUID buyer,UUID seller,long deadline) {
        Appointment old=sessions.get(id);
        if(old!=null) return old.buyer.equals(buyer)&&old.seller.equals(seller);
        if(buyer.equals(seller)||owners.containsKey(buyer)||owners.containsKey(seller)) return false;
        sessions.put(id,new Appointment(id,buyer,seller,deadline));
        owners.put(buyer,id); owners.put(seller,id); return true;
    }
    public Appointment forResident(UUID id) { return sessions.get(owners.get(id)); }
    public Appointment get(UUID id) { return sessions.get(id); }
    public Collection<Appointment> all() { return new ArrayList<>(sessions.values()); }
    public boolean ready(UUID id,UUID participant,long now) {
        Appointment s=sessions.get(id); if(s==null) return false;
        if(s.buyer.equals(participant)) s.buyerReady=true;
        else if(s.seller.equals(participant)) s.sellerReady=true;
        else return false;
        if(s.firstReady<0) s.firstReady=now;
        return s.buyerReady&&s.sellerReady;
    }
    public void release(UUID id) {
        Appointment s=sessions.remove(id); if(s==null) return;
        owners.remove(s.buyer,id); owners.remove(s.seller,id);
    }
}
