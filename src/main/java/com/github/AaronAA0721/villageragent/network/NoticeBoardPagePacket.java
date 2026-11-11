package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.content.Workstations;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.*;
import java.util.*;
import java.util.function.Supplier;

/** Refresh/page requests are scoped to the clicked block in the sender's dimension. */
public final class NoticeBoardPagePacket {
    private final BlockPos pos; private final boolean buying; private final int page;
    public NoticeBoardPagePacket(BlockPos pos,boolean buying,int page) {this.pos=pos;this.buying=buying;this.page=page;}
    public static void encode(NoticeBoardPagePacket p,PacketBuffer b) {b.writeBlockPos(p.pos);b.writeBoolean(p.buying);b.writeVarInt(p.page);}
    public static NoticeBoardPagePacket decode(PacketBuffer b) {return new NoticeBoardPagePacket(b.readBlockPos(),b.readBoolean(),b.readVarInt());}
    public static void handle(NoticeBoardPagePacket p,Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> send(context.get().getSender(),p.pos,p.buying,p.page,false));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayerEntity player,BlockPos pos,boolean buying,int page,boolean open) {
        if(player==null || page<0 || !player.isAlive()) return;
        ServerWorld world=player.getLevel();
        if(!world.isLoaded(pos) || !world.getBlockState(pos).is(Workstations.NOTICE_BOARD.get())
                || player.position().distanceToSqr(Vector3d.atCenterOf(pos))>64) return;
        VillageRegistry registry=VillageRegistry.get(world);
        List<VillageMarkets.Market> markets=VillageMarkets.loaded(world);
        Long village=null;
        for(VillageMarkets.Market market:markets) if(pos.equals(registry.noticeBoard(market.id))) {village=market.id;break;}
        if(village==null) village=registry.discover(world,pos);
        VillageSignalBoard board=null;
        for(VillageMarkets.Market market:markets) if(village!=null && market.id==village.longValue()) {board=market.board;break;}
        final VillageSignalBoard local=board;
        long now=world.getGameTime();
        NoticeBoardView view=NoticeBoardView.read(local,buying,now,page,id -> {
            VillagerAgentData agent=VillagerAgentManager.getAgent(id);
            return agent==null?"?":agent.getName();
        });
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new NoticeBoardSnapshotPacket(pos,world.dimension().location(),open,buying,village!=null,view));
    }
}
