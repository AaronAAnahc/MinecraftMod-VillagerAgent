package com.github.AaronAA0721.villageragent.ai;

import java.util.*;
import java.util.function.*;

/** Read-only public projection of free requests and shareable stock. No prices or payment terms exist. */
public final class NoticeBoardView {
    public static final int PAGE_SIZE = 4;
    public static final class Row {
        public final String item, author, assignee;
        public final int quantity;
        public Row(String item, int quantity, String author, String assignee) {
            this.item=item; this.quantity=quantity; this.author=author; this.assignee=assignee;
        }
    }
    public final int page, total;
    public final List<Row> rows;
    public NoticeBoardView(int page, int total, List<Row> rows) {
        this.page=page; this.total=total; this.rows=Collections.unmodifiableList(new ArrayList<>(rows));
    }
    public int pages() { return Math.max(1,(total+PAGE_SIZE-1)/PAGE_SIZE); }
    private static boolean active(VillageSignalBoard.VillageMessage message, long now) {
        return message != null && !message.resolved && message.expireTick>now;
    }
    /**
     * Requests show their exact item or {@code #category}, quantity and the provider whose
     * claimed delivery is in progress. Non-exclusive production has no unique assigned producer, so it is not shown.
     */
    public static NoticeBoardView read(VillageSignalBoard board, boolean buying, long now, int requestedPage,
                                      Function<UUID,String> name) {
        List<VillageSignalBoard.VillageMessage> messages=new ArrayList<>();
        if(board!=null) for(VillageSignalBoard.VillageMessage message:board.messagesOfType(buying
                ? VillageSignalBoard.MsgType.TRADE_WANT : VillageSignalBoard.MsgType.TRADE_HAVE))
            if(active(message,now) && !message.consumed() && (message.item!=null || message.spec!=null) && message.quantity>0) messages.add(message);
        messages.sort(Comparator.comparingLong((VillageSignalBoard.VillageMessage m)->m.postedTick).reversed()
                .thenComparing(m->m.id));
        int page=Math.max(0,Math.min(requestedPage,Math.max(0,(messages.size()-1)/PAGE_SIZE)));
        List<Row> rows=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(messages.size(),(page+1)*PAGE_SIZE);i++) {
            VillageSignalBoard.VillageMessage message=messages.get(i);
            String delivering=message.claim==null?"":safeName(name.apply(message.claim.provider));
            String key=message.spec!=null?message.spec.key():message.item.toString();
            rows.add(new Row(key,message.quantity,safeName(name.apply(message.author)),delivering));
        }
        return new NoticeBoardView(page,messages.size(),rows);
    }
    private static String safeName(String name) {
        if(name==null || name.isEmpty()) return "?";
        return name.length()>100?name.substring(0,100):name;
    }
    private NoticeBoardView() { this(0,0,Collections.emptyList()); }
}
