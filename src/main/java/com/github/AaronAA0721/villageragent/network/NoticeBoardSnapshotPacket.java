package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.NoticeBoardView;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;
import java.util.*;
import java.util.function.Supplier;

/** Server-to-client page of public orders, deliberately without economic prices. */
public final class NoticeBoardSnapshotPacket {
    public final BlockPos pos;
    public final ResourceLocation dimension;
    public final boolean open, buying, village;
    public final NoticeBoardView view;
    public NoticeBoardSnapshotPacket(BlockPos pos,ResourceLocation dimension,boolean open,boolean buying,boolean village,NoticeBoardView view) {
        this.pos=pos.immutable();this.dimension=dimension;this.open=open;this.buying=buying;this.village=village;this.view=view;
    }
    public static void encode(NoticeBoardSnapshotPacket p,PacketBuffer b) {
        b.writeBlockPos(p.pos);b.writeResourceLocation(p.dimension);b.writeBoolean(p.open);b.writeBoolean(p.buying);b.writeBoolean(p.village);
        b.writeVarInt(p.view.page);b.writeVarInt(p.view.total);b.writeVarInt(p.view.rows.size());
        for(NoticeBoardView.Row row:p.view.rows) {
            b.writeUtf(row.item,256);b.writeVarInt(row.quantity);b.writeUtf(row.author,100);b.writeUtf(row.assignee,100);
        }
    }
    public static NoticeBoardSnapshotPacket decode(PacketBuffer b) {
        BlockPos pos=b.readBlockPos();ResourceLocation dimension=b.readResourceLocation();
        boolean open=b.readBoolean(),buying=b.readBoolean(),village=b.readBoolean();
        int page=b.readVarInt(),total=b.readVarInt(),count=b.readVarInt();
        if(page<0 || total<0 || count<0 || count>NoticeBoardView.PAGE_SIZE) throw new IllegalArgumentException("Invalid notice page");
        List<NoticeBoardView.Row> rows=new ArrayList<>();
        for(int i=0;i<count;i++) rows.add(new NoticeBoardView.Row(b.readUtf(256),b.readVarInt(),b.readUtf(100),b.readUtf(100)));
        return new NoticeBoardSnapshotPacket(pos,dimension,open,buying,village,new NoticeBoardView(page,total,rows));
    }
    public static void handle(NoticeBoardSnapshotPacket p,Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.github.AaronAA0721.villageragent.client.NoticeBoardScreen.receive(p)));
        context.get().setPacketHandled(true);
    }
}
