package com.github.AaronAA0721.villageragent.network;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.GoalScheduler;
import com.github.AaronAA0721.villageragent.ai.GoalText;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.client.VillagerChatHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> Client: a read-only dump of every goal in a villager's brain, pre-formatted as
 * display lines (with § colour codes) so the client needs no knowledge of {@link AgentGoal}.
 */
public class VillagerGoalsPacket {
    private static final int MAX_LINES = 160;
    private static final int MAX_LINE_LEN = 400;

    private final UUID villagerId;
    private final List<String> lines;

    public VillagerGoalsPacket(UUID villagerId, List<String> lines) {
        this.villagerId = villagerId;
        this.lines = lines;
    }

    public static void encode(VillagerGoalsPacket packet, PacketBuffer buffer) {
        buffer.writeUUID(packet.villagerId);
        int n = Math.min(packet.lines.size(), MAX_LINES);
        buffer.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            String s = packet.lines.get(i);
            buffer.writeUtf(s.length() > MAX_LINE_LEN ? s.substring(0, MAX_LINE_LEN) : s, MAX_LINE_LEN * 4);
        }
    }

    public static VillagerGoalsPacket decode(PacketBuffer buffer) {
        UUID id = buffer.readUUID();
        int n = Math.min(buffer.readVarInt(), MAX_LINES);
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(buffer.readUtf(MAX_LINE_LEN * 4));
        return new VillagerGoalsPacket(id, lines);
    }

    public static void handle(VillagerGoalsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VillagerChatHandler.receiveGoals(packet.villagerId, packet.lines));
        ctx.get().setPacketHandled(true);
    }

    // ── Server-side formatting ─────────────────────────────────────────────

    /** Build the display lines for {@code agent}'s goal list. Server thread only. */
    public static List<String> describeGoals(VillagerAgentData agent, ServerPlayerEntity viewer, long now) {
        List<String> out = new ArrayList<>();
        String path = agent.brain().tasks.path();
        AgentGoal running = agent.brain().tasks.root() == null ? null : agent.brain().tasks.root().goal;
        out.add("§7Running: §f" + (path.isEmpty() ? "(nothing)" : path)
                + "  §7hunger §f" + Math.round(agent.getHunger())
                + "  §7fatigue §f" + Math.round(agent.getFatigue()));
        List<AgentGoal> goals = new ArrayList<>(agent.getGoals());
        if (goals.isEmpty()) {
            out.add("§8(no goals)");
            return out;
        }
        int i = 1;
        for (AgentGoal g : goals) {
            boolean isRunning = g == running;
            String head = (isRunning ? "§a▶ " : "§7" + i + ". ")
                    + (g.isCompleted() ? "§8" : "§e") + "[" + g.getGoalType() + "] §f";
            String title = GoalText.title(g, agent.getVillagerId());
            if (title != null) {
                out.add(head + "§l" + title);           // the plan's human name, e.g. "Dinner with Bram"
                out.add("§7   " + safe(g.getDescription())); // what it consists of
            } else {
                out.add(head + safe(g.getDescription()));
            }

            StringBuilder d = new StringBuilder("§8   ");
            int eff = g.effectivePriority(now);
            d.append("prio ").append(g.getPriority());
            if (eff != g.getPriority()) d.append("→").append(eff);
            d.append(" | imp ").append(g.getImportance());
            d.append(" | ").append(g.getSource().name().toLowerCase());
            d.append(" | ").append(g.getLifecycle().name().toLowerCase());
            double u = 0;
            try { u = GoalScheduler.utility(agent, g, now); } catch (RuntimeException ignored) {}
            d.append(" | score ").append(String.format(java.util.Locale.ROOT, "%.1f", u));
            out.add(d.toString());

            List<String> notes = new ArrayList<>();
            if (g.isCompleted()) notes.add("done");
            if (g.isSuspended()) notes.add("suspended");
            if (g.isOnCooldown(now)) notes.add("cooldown " + secs(g.getCooldownUntilTick() - now));
            if (g.getScheduledTick() != Long.MAX_VALUE) {
                long dt = g.getScheduledTick() - now;
                if (g.isHoldUntilScheduled()) {
                    notes.add(dt > 0 ? "§dappointment: starts in " + secs(dt) + "§8"
                                     : "§dappointment: due§8");
                } else {
                    notes.add(dt > 0 ? "timed: boost to " + g.getScheduledPriority() + " in " + secs(dt)
                                     : "timed: due (prio " + g.getScheduledPriority() + ")");
                }
            }
            if (g.hasEndTime()) {
                long left = g.getEndTick() - now;
                notes.add(left > 0 ? "\u00A7dends in " + secs(left) + "\u00A78" : "\u00A7dended\u00A78");
            }
            if (g.getGroupId() != null) notes.add("group plan" + (g.getLeaderId() != null
                    ? " (host " + entityName(g.getLeaderId(), viewer) + ")" : ""));
            if (g.getLifecycle() == AgentGoal.Lifecycle.EXPIRING && g.getDeadlineTick() != Long.MAX_VALUE) {
                long dt = g.getDeadlineTick() - now;
                notes.add(dt > 0 ? "expires in " + secs(dt) : "expired");
            }
            if (g.getRoutineWindowId() != null) notes.add("routine " + g.getRoutineWindowId());
            if (g.hasChildDependency()) notes.add("waits on " + g.getChildGoalType()
                    + (g.getChildTargetItem() != null ? " " + shortId(g.getChildTargetItem()) : ""));
            if (g.getTargetItem() != null) notes.add("item " + shortId(g.getTargetItem())
                    + (g.getTargetQuantity() > 0 ? " x" + g.getTargetQuantity() : ""));
            BlockPos p = g.getTargetLocation();
            if (p != null) notes.add("at " + p.getX() + "," + p.getY() + "," + p.getZ());
            if (g.getTargetBuildingType() != null) notes.add("building " + g.getTargetBuildingType());
            if (g.getTargetEntityId() != null) notes.add("with " + entityName(g.getTargetEntityId(), viewer));
            else if (g.getTargetKind() != null) notes.add("with any " + g.getTargetKind()
                    + (g.getTargetProfession() != null ? " (" + g.getTargetProfession() + ")" : ""));
            if (g.getFailureCount() > 0) notes.add("failed x" + g.getFailureCount());
            if (g.getLastFailure() != null) notes.add("last fail: " + g.getLastFailure());
            if (!notes.isEmpty()) out.add("§8   " + String.join(" | ", notes));
            out.addAll(GoalText.planLines(g, "   "));
            i++;
        }
        if (running != null && !goals.contains(running)) {
            out.add("§a▶ §e[" + running.getGoalType() + "] §f" + safe(running.getDescription())
                    + " §8(running, no longer in the list)");
        }
        return out;
    }

    private static String entityName(UUID id, PlayerEntity viewer) {
        VillagerAgentData other = VillagerAgentManager.getAgent(id);
        if (other != null) return other.getName();
        if (viewer != null && viewer.getServer() != null) {
            ServerPlayerEntity p = viewer.getServer().getPlayerList().getPlayer(id);
            if (p != null) return p.getName().getString();
        }
        return id.toString().substring(0, 8);
    }

    private static String secs(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : s + "s";
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
