package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;

public final class TradeCoordinationChecks {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++; if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        TradeAppointments appointments = new TradeAppointments();
        UUID buyer=UUID.randomUUID(), seller=UUID.randomUUID(), third=UUID.randomUUID();
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        check(appointments.reserve(first,buyer,seller,100), "first pair reserves both slots");
        check(!appointments.reserve(second,buyer,third,100), "pending buyer cannot accept another seller");
        check(!appointments.reserve(second,third,seller,100), "pending seller cannot accept another buyer");
        check(!appointments.reserve(second,seller,third,100), "changing role cannot bypass exclusivity");
        check(!appointments.ready(first,third,1), "outsider cannot satisfy readiness");
        check(!appointments.ready(first,buyer,2), "one ready party cannot start exchange");
        check(appointments.ready(first,seller,3), "both ready parties pass barrier");
        check(appointments.get(first).firstReady==2, "first readiness anchors timeout");
        appointments.release(first);
        check(appointments.forResident(buyer)==null && appointments.forResident(seller)==null, "release frees both parties");
        check(appointments.reserve(second,buyer,third,100), "released resident can accept next trade");
        appointments.release(first);
        check(appointments.forResident(buyer).id.equals(second), "late cleanup cannot release new session");
        appointments.release(second);

        VillageSignalBoard a=new VillageSignalBoard(), b=new VillageSignalBoard();
        ResourceLocation item=new ResourceLocation("minecraft:iron_ingot");
        UUID want=a.post(buyer,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,item,2,0);
        UUID foreign=b.post(seller,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_HAVE,item,8,0);
        check(a.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE).isEmpty(), "local query never scans foreign offers, even at same coordinates");
        check(!a.matchTrade(want,foreign), "cross-village pairing is rejected");
        UUID offer=a.post(seller,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_HAVE,item,8,0);
        check(a.matchTrade(want,offer), "same village can pair");
        UUID want2=a.postOwnedWant(UUID.randomUUID(),buyer,BlockPos.ZERO,item,1,0);
        UUID offer2=a.post(third,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_HAVE,item,4,0);
        check(!a.matchTrade(want2,offer2), "different request cannot take an already booked buyer");
        a.post(buyer,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,item,7,1);
        check(a.find(want).quantity==2, "scan refresh cannot rewrite agreed quantity");
        a.removeResident(seller);
        check(a.find(want).matchedWith==null && VillageSignalBoard.APPOINTMENTS.forResident(buyer)==null,
                "unloaded or migrating seller releases buyer and pairing");
        check(a.matchTrade(want2,offer2), "canceled pair can be replaced");
        a.find(want2).expireTick=2;
        a.clearExpiredMessages(2);
        check(VillageSignalBoard.APPOINTMENTS.forResident(buyer)==null && a.find(offer2).matchedWith==null,
                "expiration releases both appointment and offer");
        a.clearMessages(); b.clearMessages();
        check(a.find(foreign)==null, "unloading board removes global ID lookup");

        AgentGoal meeting=new AgentGoal("meet_trade","test",8);
        check(!GoalScheduler.shouldPreempt(new AgentGoal("craft","test",7),meeting), "meeting cannot interrupt work");
        check(!GoalScheduler.shouldPreempt(new AgentGoal("wander","test",1),meeting), "meeting waits even for fallback boundary");
        check(GoalScheduler.shouldPreempt(null,meeting), "idle resident may select meeting");
        check(!GoalScheduler.canYieldBetweenActions(meeting), "ready participant retains composite root while waiting");
        check(GoalScheduler.shouldPreempt(meeting,new AgentGoal("flee","test",10)), "emergency can interrupt meeting");
        System.out.println("TradeCoordinationChecks: "+assertions+" assertions passed");
    }
}
