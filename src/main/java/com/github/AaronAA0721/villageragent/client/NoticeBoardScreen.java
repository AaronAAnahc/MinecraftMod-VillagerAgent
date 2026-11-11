package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.ai.NoticeBoardView;
import com.github.AaronAA0721.villageragent.content.Workstations;
import com.github.AaronAA0721.villageragent.network.*;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.item.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Read-only, paged village orders. Data always comes from a server snapshot. */
public final class NoticeBoardScreen extends Screen {
    private NoticeBoardSnapshotPacket data;
    private boolean pending, requestedBuying;
    private int ticks;
    private Button buy, sell, previous, next, refresh;
    private int left, top, panelWidth, panelHeight, rowHeight;
    public NoticeBoardScreen(NoticeBoardSnapshotPacket data) {
        super(text("title")); this.data=data; this.requestedBuying=data.buying;
    }
    private static TranslationTextComponent text(String key,Object... args) {
        return new TranslationTextComponent("screen.villageragent.notice_board."+key,args);
    }
    public static void receive(NoticeBoardSnapshotPacket packet) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || !mc.level.dimension().location().equals(packet.dimension)) return;
        if(packet.open) { mc.setScreen(new NoticeBoardScreen(packet)); return; }
        if(mc.screen instanceof NoticeBoardScreen) {
            NoticeBoardScreen screen=(NoticeBoardScreen)mc.screen;
            if(screen.data.pos.equals(packet.pos) && screen.requestedBuying==packet.buying) {
                screen.data=packet;screen.pending=false;screen.ticks=0;screen.updateButtons();
            }
        }
    }
    @Override protected void init() {
        panelWidth=Math.min(420,width-16); panelHeight=Math.min(238,height-12);
        left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        rowHeight=Math.max(20,(panelHeight-108)/NoticeBoardView.PAGE_SIZE);
        int half=(panelWidth-28)/2;
        buy=addButton(new Button(left+10,top+28,half,20,text("buy"),b->request(true,0)));
        sell=addButton(new Button(left+18+half,top+28,half,20,text("sell"),b->request(false,0)));
        int bottom=top+panelHeight-28;
        previous=addButton(new Button(left+10,bottom,24,20,new StringTextComponent("<"),b->request(data.buying,data.view.page-1)));
        next=addButton(new Button(left+38,bottom,24,20,new StringTextComponent(">"),b->request(data.buying,data.view.page+1)));
        refresh=addButton(new Button(left+panelWidth-142,bottom,62,20,text("refresh"),b->request(data.buying,data.view.page)));
        addButton(new Button(left+panelWidth-74,bottom,64,20,new TranslationTextComponent("gui.done"),b->onClose()));
        updateButtons();
    }
    private void updateButtons() {
        if(buy==null) return;
        buy.active=!pending && !data.buying;sell.active=!pending && data.buying;
        previous.active=!pending && data.view.page>0;
        next.active=!pending && data.view.page+1<data.view.pages();refresh.active=!pending;
    }
    private void request(boolean buying,int page) {
        if(pending) return;
        pending=true;requestedBuying=buying;ticks=0;updateButtons();
        ModNetworking.CHANNEL.sendToServer(new NoticeBoardPagePacket(data.pos,buying,Math.max(0,page)));
    }
    @Override public void tick() {
        if(minecraft==null || minecraft.player==null || minecraft.level==null
                || !minecraft.level.dimension().location().equals(data.dimension)
                || !minecraft.level.getBlockState(data.pos).is(Workstations.NOTICE_BOARD.get())
                || minecraft.player.position().distanceToSqr(Vector3d.atCenterOf(data.pos))>64) {onClose();return;}
        ticks++;
        if(pending && ticks>=100) {pending=false;updateButtons();}
        if(!pending && ticks>=40) request(data.buying,data.view.page);
    }
    @Override public boolean isPauseScreen() {return false;}
    private String name(String value) {return "?".equals(value)?text("unknown").getString():value;}
    private String clipped(String value,int pixels) {return font.plainSubstrByWidth(value,Math.max(1,pixels));}
    @Override public void render(MatrixStack matrix,int mouseX,int mouseY,float partialTicks) {
        renderBackground(matrix);
        fill(matrix,left-1,top-1,left+panelWidth+1,top+panelHeight+1,0xffb08b56);
        fill(matrix,left,top,left+panelWidth,top+panelHeight,0xff28231d);
        drawCenteredString(matrix,font,title,width/2,top+10,0xffecd9b5);
        font.draw(matrix,text("count",data.view.total),left+12,top+55,0xffbfb39f);
        ITextComponent tooltip=null;
        if(data.view.rows.isEmpty()) drawCenteredString(matrix,font,text(data.village?"empty":"no_village"),width/2,top+94,0xffc9c1b3);
        for(int i=0;i<data.view.rows.size();i++) {
            NoticeBoardView.Row row=data.view.rows.get(i);int y=top+70+i*rowHeight;
            fill(matrix,left+10,y,left+panelWidth-10,y+rowHeight-3,i%2==0?0xff393228:0xff322c24);
            boolean category=row.item.startsWith("#");
            // "Or" requests ("minecraft:stick*2|minecraft:oak_planks*2|..."): show the first item, list the rest.
            int bar=row.item.indexOf('|');
            String primary=bar>=0?row.item.substring(0,bar):row.item;
            if(primary.indexOf('*')>=0) primary=primary.substring(0,primary.lastIndexOf('*'));
            ResourceLocation id=category?null:ResourceLocation.tryParse(primary);
            Item item=id==null?null:ForgeRegistries.ITEMS.getValue(id);
            ItemStack stack=item==null||item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
            String itemName=category?text("category",row.item.substring(1).replace('_',' ')).getString()
                    :stack.isEmpty()?primary:stack.getHoverName().getString();
            if(bar>=0) itemName=itemName+" ("+alternatives(row.item.substring(bar+1))+")";
            if(!stack.isEmpty()) itemRenderer.renderAndDecorateItem(stack,left+15,y+5);
            String heading=itemName+" × "+row.quantity+("#food".equals(row.item)?" "+text("food_points").getString():"");
            font.draw(matrix,clipped(heading,panelWidth-60),left+38,y+4,0xfff1e7d5);
            String author=text("author",name(row.author)).getString();
            String assignment=row.assignee.isEmpty()?text("unclaimed").getString():text("claimed",name(row.assignee)).getString();
            int column=(panelWidth-54)/2;
            font.draw(matrix,clipped(author,column),left+38,y+15,0xffbcb4a7);
            font.draw(matrix,clipped(assignment,column),left+38+column,y+15,row.assignee.isEmpty()?0xffc8b273:0xff99c88d);
            if(mouseX>=left+10 && mouseX<left+panelWidth-10 && mouseY>=y && mouseY<y+rowHeight-3)
                tooltip=new StringTextComponent(heading+" | "+author+" | "+assignment);
        }
        font.draw(matrix,text("page",data.view.page+1,data.view.pages()),left+70,top+panelHeight-22,0xffd7c7aa);
        super.render(matrix,mouseX,mouseY,partialTicks);
        if(tooltip!=null) renderTooltip(matrix,tooltip,mouseX,mouseY);
    }

    /** "minecraft:oak_planks*2|minecraft:oak_log*1" -> "or 2 Oak Planks / 1 Oak Log". */
    private static String alternatives(String raw) {
        StringBuilder sb=new StringBuilder("or ");
        String[] parts=raw.split("\\|");
        for(int i=0;i<parts.length;i++) {
            int star=parts[i].lastIndexOf('*');
            String key=star>0?parts[i].substring(0,star):parts[i];
            String qty=star>0?parts[i].substring(star+1):"1";
            ResourceLocation rl=ResourceLocation.tryParse(key);
            Item it=rl==null?null:ForgeRegistries.ITEMS.getValue(rl);
            String name=it==null||it==Items.AIR?key:new ItemStack(it).getHoverName().getString();
            if(i>0) sb.append(" / ");
            sb.append(qty).append(' ').append(name);
        }
        return sb.toString();
    }
}
