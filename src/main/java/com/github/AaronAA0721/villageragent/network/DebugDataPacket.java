package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.client.DebugOverlay;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client debug snapshot for the in-game visualization overlay.
 *
 * <p>Carries everything a developer needs to judge whether the perception / agent
 * systems are actually working: the looked-at villager's live environment summary,
 * its chunk memory tags, goals, memories, mood, current action, the live vision
 * scan result, and the buildings detected near the player.
 */
public class DebugDataPacket {

    // ── Looked-at villager (target) ──
    public boolean hasTarget = false;
    public String targetName = "";
    public String targetProfession = "";
    public String targetPersonality = "";
    public String targetMood = "";
    public String targetActivity = "";
    public String targetScheduled = "";
    public String targetAction = "";
    public String targetEnv = "";

    public int goalsCount = 0;
    public List<String> goals = new ArrayList<>();
    public int memoriesCount = 0;
    public List<String> memories = new ArrayList<>();
    public int chunkMemoryCount = 0;
    public List<String> chunkTags = new ArrayList<>();
    public int nearbyBuildings = 0;
    public float hunger = 0f;
    public float fatigue = 0f;
    public int agentCount = 0;

    // ── Live vision scan (DetailedViewRecorder) for the target ──
    public int frustumBlocks = 0;
    public int frustumEntities = 0;
    public List<String> frustumSamples = new ArrayList<>();

    // ── World debug geometry near the player ──
    public List<BuildingBox> buildings = new ArrayList<>();

    /** Distance-field seed markers (regional-maximum plateaus) of nearby buildings. */
    public List<SeedPoint> seeds = new ArrayList<>();

    /** Watershed-collection field cells (block resolution, world coords) of nearby buildings. */
    public List<FieldCell> field = new ArrayList<>();

    public static class SeedPoint {
        public BlockPos pos;
        /** true = interior / room-candidate (non-atmosphere) seed; false = atmosphere seed. */
        public boolean interior;
        public SeedPoint(BlockPos pos, boolean interior) {
            this.pos = pos; this.interior = interior;
        }
    }

    /** A single watershed-collection cell. flags: bit0=atmospheric, bit1=boundary; bits8.. = setId. */
    public static class FieldCell {
        public final int wx, wy, wz;
        public final int flags;
        public FieldCell(int wx, int wy, int wz, int flags) {
            this.wx = wx; this.wy = wy; this.wz = wz; this.flags = flags;
        }
        public boolean atmospheric() { return (flags & 1) != 0; }
        public boolean boundary()    { return (flags & 2) != 0; }
        public int setId()           { return flags >> 8; }
    }

    public static class BuildingBox {
        public long id;
        public BlockPos min, max;
        public String type;
        public BuildingBox(long id, BlockPos min, BlockPos max, String type) {
            this.id = id; this.min = min; this.max = max; this.type = type;
        }
    }

    public DebugDataPacket() {}

    // ── Encode / Decode ──

    public static void encode(DebugDataPacket p, PacketBuffer b) {
        b.writeBoolean(p.hasTarget);
        b.writeUtf(p.targetName);
        b.writeUtf(p.targetProfession);
        b.writeUtf(p.targetPersonality);
        b.writeUtf(p.targetMood);
        b.writeUtf(p.targetActivity);
        b.writeUtf(p.targetScheduled);
        b.writeUtf(p.targetAction);
        b.writeUtf(p.targetEnv);

        b.writeInt(p.goalsCount);
        b.writeInt(p.goals.size());
        for (String s : p.goals) b.writeUtf(s);

        b.writeInt(p.memoriesCount);
        b.writeInt(p.memories.size());
        for (String s : p.memories) b.writeUtf(s);

        b.writeInt(p.chunkMemoryCount);
        b.writeInt(p.chunkTags.size());
        for (String s : p.chunkTags) b.writeUtf(s);

        b.writeInt(p.nearbyBuildings);
        b.writeFloat(p.hunger);
        b.writeFloat(p.fatigue);
        b.writeInt(p.agentCount);

        b.writeInt(p.frustumBlocks); b.writeInt(p.frustumEntities);
        b.writeInt(p.frustumSamples.size());
        for (String s : p.frustumSamples) b.writeUtf(s);

        b.writeInt(p.buildings.size());
        for (BuildingBox bb : p.buildings) {
            b.writeLong(bb.id);
            b.writeLong(bb.min.asLong());
            b.writeLong(bb.max.asLong());
            b.writeUtf(bb.type);
        }

        b.writeInt(p.seeds.size());
        for (SeedPoint sp : p.seeds) {
            b.writeLong(sp.pos.asLong());
            b.writeBoolean(sp.interior);
        }

        b.writeInt(p.field.size());
        for (FieldCell fc : p.field) {
            b.writeInt(fc.wx); b.writeInt(fc.wy); b.writeInt(fc.wz); b.writeInt(fc.flags);
        }
    }

    public static DebugDataPacket decode(PacketBuffer b) {
        DebugDataPacket p = new DebugDataPacket();
        p.hasTarget = b.readBoolean();
        p.targetName = b.readUtf();
        p.targetProfession = b.readUtf();
        p.targetPersonality = b.readUtf();
        p.targetMood = b.readUtf();
        p.targetActivity = b.readUtf();
        p.targetScheduled = b.readUtf();
        p.targetAction = b.readUtf();
        p.targetEnv = b.readUtf();

        p.goalsCount = b.readInt();
        int gc = b.readInt();
        for (int i = 0; i < gc; i++) p.goals.add(b.readUtf());

        p.memoriesCount = b.readInt();
        int mc = b.readInt();
        for (int i = 0; i < mc; i++) p.memories.add(b.readUtf());

        p.chunkMemoryCount = b.readInt();
        int tc = b.readInt();
        for (int i = 0; i < tc; i++) p.chunkTags.add(b.readUtf());

        p.nearbyBuildings = b.readInt();
        p.hunger = b.readFloat();
        p.fatigue = b.readFloat();
        p.agentCount = b.readInt();

        p.frustumBlocks = b.readInt(); p.frustumEntities = b.readInt();
        int fs = b.readInt();
        for (int i = 0; i < fs; i++) p.frustumSamples.add(b.readUtf());

        int bc = b.readInt();
        for (int i = 0; i < bc; i++) {
            long id = b.readLong();
            BlockPos min = BlockPos.of(b.readLong());
            BlockPos max = BlockPos.of(b.readLong());
            String type = b.readUtf();
            p.buildings.add(new BuildingBox(id, min, max, type));
        }

        int sc = b.readInt();
        for (int i = 0; i < sc; i++) {
            BlockPos pos = BlockPos.of(b.readLong());
            boolean interior = b.readBoolean();
            p.seeds.add(new SeedPoint(pos, interior));
        }

        int fc = b.readInt();
        for (int i = 0; i < fc; i++) {
            int wx = b.readInt(), wy = b.readInt(), wz = b.readInt(), fl = b.readInt();
            p.field.add(new FieldCell(wx, wy, wz, fl));
        }

        return p;
    }

    public static void handle(DebugDataPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DebugOverlay.setLatest(p));
        ctx.get().setPacketHandled(true);
    }
}
