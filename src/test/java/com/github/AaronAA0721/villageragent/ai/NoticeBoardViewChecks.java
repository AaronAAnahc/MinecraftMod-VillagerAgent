package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.network.NoticeBoardSnapshotPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Real board projection and wire round trips; no Minecraft world/client required. */
public final class NoticeBoardViewChecks {
    private static int assertions;
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        VillageSignalBoard board=new VillageSignalBoard(),other=new VillageSignalBoard();
        UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID(),worker=UUID.randomUUID();
        Map<UUID,String> names=new HashMap<>();names.put(buyer,"Alice");names.put(seller,"Bob");names.put(worker,"Carol");
        ResourceLocation log=new ResourceLocation("minecraft:oak_log");
        UUID want=board.post(buyer,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,log,3,0);
        UUID have=board.post(seller,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_HAVE,log,5,0);
        other.post(worker,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,log,9,0);
        NoticeBoardView buys=NoticeBoardView.read(board,true,1,0,names::get,(a,m)->false);
        check(buys.total==1 && buys.rows.get(0).quantity==3,"buy page excludes sells and other village orders");
        check("Alice".equals(buys.rows.get(0).author) && buys.rows.get(0).assignee.isEmpty(),"unclaimed order keeps publisher");
        board.find(want).productionWorker=worker;
        buys=NoticeBoardView.read(board,true,1,0,names::get,(a,m)->a.equals(worker)&&m.equals(want));
        check("Carol".equals(buys.rows.get(0).assignee),"active production worker shown as assignee");
        check(NoticeBoardView.read(board,true,1,0,names::get,(a,m)->false).rows.get(0).assignee.isEmpty(),"stale production pointer is not a claim");
        check(board.matchTrade(want,have),"test matched trade created");
        buys=NoticeBoardView.read(board,true,1,0,names::get,(a,m)->true);
        NoticeBoardView sells=NoticeBoardView.read(board,false,1,0,names::get,(a,m)->true);
        check("Bob".equals(buys.rows.get(0).assignee),"matched seller takes precedence over production worker");
        check("Alice".equals(sells.rows.get(0).assignee),"sell order shows matched buyer");
        board.find(have).expireTick=1;
        check(NoticeBoardView.read(board,false,1,0,names::get,(a,m)->false).total==0,"expired listings hidden at exact expiry");
        check(NoticeBoardView.read(board,true,1,0,names::get,(a,m)->false).rows.get(0).assignee.isEmpty(),"expired counterpart not shown as claim");
        board.find(want).resolved=true;
        check(NoticeBoardView.read(board,true,1,0,names::get,(a,m)->true).rows.isEmpty(),"resolved orders hidden");
        for(int i=0;i<9;i++) board.post(UUID.randomUUID(),BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,log,i+1,10+i);
        NoticeBoardView first=NoticeBoardView.read(board,true,20,-3,names::get,(a,m)->false);
        check(first.page==0 && first.total==9 && first.rows.size()==4 && first.rows.get(0).quantity==9,"stable newest-first first page");
        NoticeBoardView last=NoticeBoardView.read(board,true,20,Integer.MAX_VALUE,names::get,(a,m)->false);
        check(last.page==2 && last.rows.size()==1 && last.rows.get(0).quantity==1,"out-of-range page clamps without losing last order");
        check(board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT).size()==10,"view is read-only");
        NoticeBoardSnapshotPacket packet=new NoticeBoardSnapshotPacket(BlockPos.ZERO,new ResourceLocation("minecraft:overworld"),true,true,true,buys);
        PacketBuffer buffer=new PacketBuffer(Unpooled.buffer());
        try {
            NoticeBoardSnapshotPacket.encode(packet,buffer);
            NoticeBoardSnapshotPacket copy=NoticeBoardSnapshotPacket.decode(buffer);
            check(copy.open && copy.buying && copy.village && copy.view.total==1,"snapshot flags survive wire round trip");
            check("Bob".equals(copy.view.rows.get(0).assignee) && copy.view.rows.get(0).quantity==3,"snapshot displays accepted name and quantity");
            check(buffer.readableBytes()==0,"wire payload fully consumed");
        } finally {buffer.release();}
        UUID fresh=board.post(buyer,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_WANT,log,7,30);
        UUID foreign=other.post(seller,BlockPos.ZERO,VillageSignalBoard.MsgType.TRADE_HAVE,log,7,30);
        board.find(fresh).matchedWith=foreign;other.find(foreign).matchedWith=fresh;
        check(NoticeBoardView.read(board,true,31,0,names::get,(a,m)->false).rows.get(0).assignee.isEmpty(),
                "foreign-board counterpart cannot leak into accepted name");
        PacketBuffer invalid=new PacketBuffer(Unpooled.buffer());
        try {
            invalid.writeBlockPos(BlockPos.ZERO);invalid.writeResourceLocation(new ResourceLocation("minecraft:overworld"));
            invalid.writeBoolean(true);invalid.writeBoolean(true);invalid.writeBoolean(true);
            invalid.writeVarInt(0);invalid.writeVarInt(5);invalid.writeVarInt(5);
            boolean rejected=false;
            try {NoticeBoardSnapshotPacket.decode(invalid);} catch(IllegalArgumentException expected) {rejected=true;}
            check(rejected,"oversized wire page rejected before allocating rows");
        } finally {invalid.release();}
        board.clearMessages();other.clearMessages();
        System.out.println("NoticeBoardViewChecks: "+assertions+" assertions passed");
    }
}
