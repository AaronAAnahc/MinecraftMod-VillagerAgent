package com.github.AaronAA0721.villageragent.network;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client snapshot for promotional recording: per-villager cards (job, task, step, goal
 * weights, request tag) and the notice board of the village the player is in.
 */
public final class ShowcasePacket {
    public static final int MAX_CARDS = 128;
    public static final int MAX_ROWS = 10;

    public static final class Weight {
        public final String goalType;
        public final float score;
        public Weight(String goalType, float score) { this.goalType = goalType; this.score = score; }
    }

    public static final class Card {
        public final int entityId;
        public final String name, job, task, step, tag;
        public final int tagColor;
        public final List<Weight> weights;
        public Card(int entityId, String name, String job, String task, String step, String tag, int tagColor,
                    List<Weight> weights) {
            this.entityId = entityId; this.name = name; this.job = job; this.task = task; this.step = step;
            this.tag = tag; this.tagColor = tagColor; this.weights = weights;
        }
    }

    public static final class Row {
        public final String item, author, status;
        public final int quantity, color;
        public Row(String item, int quantity, String author, String status, int color) {
            this.item = item; this.quantity = quantity; this.author = author; this.status = status; this.color = color;
        }
    }

    public final boolean labels, board, hasVillage;
    public final List<Card> cards;
    public final String boardTitle;
    public final int open, claimed, delivered;
    public final List<Row> rows;

    public ShowcasePacket(boolean labels, boolean board, List<Card> cards, boolean hasVillage, String boardTitle,
                          int open, int claimed, int delivered, List<Row> rows) {
        this.labels = labels; this.board = board; this.cards = cards; this.hasVillage = hasVillage;
        this.boardTitle = boardTitle; this.open = open; this.claimed = claimed; this.delivered = delivered;
        this.rows = rows;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void encode(ShowcasePacket p, PacketBuffer b) {
        b.writeBoolean(p.labels);
        b.writeBoolean(p.board);
        int cards = Math.min(MAX_CARDS, p.cards.size());
        b.writeVarInt(cards);
        for (int i = 0; i < cards; i++) {
            Card c = p.cards.get(i);
            b.writeInt(c.entityId);
            b.writeUtf(clip(c.name, 64), 64);
            b.writeUtf(clip(c.job, 64), 64);
            b.writeUtf(clip(c.task, 128), 128);
            b.writeUtf(clip(c.step, 128), 128);
            b.writeUtf(clip(c.tag, 128), 128);
            b.writeInt(c.tagColor);
            int weights = Math.min(12, c.weights.size());
            b.writeVarInt(weights);
            for (int j = 0; j < weights; j++) {
                b.writeUtf(clip(c.weights.get(j).goalType, 64), 64);
                b.writeFloat(c.weights.get(j).score);
            }
        }
        b.writeBoolean(p.hasVillage);
        b.writeUtf(clip(p.boardTitle, 128), 128);
        b.writeVarInt(p.open);
        b.writeVarInt(p.claimed);
        b.writeVarInt(p.delivered);
        int rows = Math.min(MAX_ROWS, p.rows.size());
        b.writeVarInt(rows);
        for (int i = 0; i < rows; i++) {
            Row r = p.rows.get(i);
            b.writeUtf(clip(r.item, 128), 128);
            b.writeVarInt(r.quantity);
            b.writeUtf(clip(r.author, 64), 64);
            b.writeUtf(clip(r.status, 128), 128);
            b.writeInt(r.color);
        }
    }

    public static ShowcasePacket decode(PacketBuffer b) {
        boolean labels = b.readBoolean(), board = b.readBoolean();
        int cardCount = b.readVarInt();
        if (cardCount < 0 || cardCount > MAX_CARDS) throw new IllegalArgumentException("Invalid showcase card count");
        List<Card> cards = new ArrayList<>(cardCount);
        for (int i = 0; i < cardCount; i++) {
            int id = b.readInt();
            String name = b.readUtf(64), job = b.readUtf(64), task = b.readUtf(128), step = b.readUtf(128),
                    tag = b.readUtf(128);
            int tagColor = b.readInt();
            int weightCount = b.readVarInt();
            if (weightCount < 0 || weightCount > 12) throw new IllegalArgumentException("Invalid weight count");
            List<Weight> weights = new ArrayList<>(weightCount);
            for (int j = 0; j < weightCount; j++) weights.add(new Weight(b.readUtf(64), b.readFloat()));
            cards.add(new Card(id, name, job, task, step, tag, tagColor, weights));
        }
        boolean hasVillage = b.readBoolean();
        String title = b.readUtf(128);
        int open = b.readVarInt(), claimed = b.readVarInt(), delivered = b.readVarInt();
        int rowCount = b.readVarInt();
        if (rowCount < 0 || rowCount > MAX_ROWS) throw new IllegalArgumentException("Invalid board row count");
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++)
            rows.add(new Row(b.readUtf(128), b.readVarInt(), b.readUtf(64), b.readUtf(128), b.readInt()));
        return new ShowcasePacket(labels, board, cards, hasVillage, title, open, claimed, delivered, rows);
    }

    public static void handle(ShowcasePacket p, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.github.AaronAA0721.villageragent.client.ShowcaseOverlay.receive(p)));
        context.get().setPacketHandled(true);
    }
}
