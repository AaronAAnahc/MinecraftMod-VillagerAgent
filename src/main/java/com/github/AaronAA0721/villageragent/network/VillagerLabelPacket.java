package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.client.DebugOverlay;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: per-villager head-top debug label data.
 *
 * Each entry carries one villager's entity ID, current goal type,
 * and a list of (goalType, utilityScore) pairs for the stacked bar.
 */
public class VillagerLabelPacket {

    public final List<VillagerLabel> labels;

    public VillagerLabelPacket(List<VillagerLabel> labels) {
        this.labels = labels;
    }

    public static class VillagerLabel {
        public final int entityId;
        public final String name;
        public final String currentGoal;
        public final List<GoalScore> scores;

        public VillagerLabel(int entityId, String name, String currentGoal, List<GoalScore> scores) {
            this.entityId = entityId;
            this.name = name;
            this.currentGoal = currentGoal;
            this.scores = scores;
        }
    }

    public static class GoalScore {
        public final String goalType;
        public final float score;

        public GoalScore(String goalType, float score) {
            this.goalType = goalType;
            this.score = score;
        }
    }

    // ── Encode / Decode ──

    public static void encode(VillagerLabelPacket pkt, PacketBuffer b) {
        b.writeInt(pkt.labels.size());
        for (VillagerLabel lbl : pkt.labels) {
            b.writeInt(lbl.entityId);
            b.writeUtf(lbl.name);
            b.writeUtf(lbl.currentGoal);
            b.writeInt(lbl.scores.size());
            for (GoalScore gs : lbl.scores) {
                b.writeUtf(gs.goalType);
                b.writeFloat(gs.score);
            }
        }
    }

    public static VillagerLabelPacket decode(PacketBuffer b) {
        int count = b.readInt();
        List<VillagerLabel> labels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int eid = b.readInt();
            String name = b.readUtf();
            String currentGoal = b.readUtf();
            int sc = b.readInt();
            List<GoalScore> scores = new ArrayList<>(sc);
            for (int j = 0; j < sc; j++) {
                scores.add(new GoalScore(b.readUtf(), b.readFloat()));
            }
            labels.add(new VillagerLabel(eid, name, currentGoal, scores));
        }
        return new VillagerLabelPacket(labels);
    }

    public static void handle(VillagerLabelPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DebugOverlay.setLabels(pkt.labels));
        ctx.get().setPacketHandled(true);
    }
}
