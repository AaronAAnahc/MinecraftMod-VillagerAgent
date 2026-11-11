package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * NBT form of an {@link AgentGoal}: its intent (type, targets, timing, steps, invitation) — not its
 * transient execution state (cooldowns, failure records, task frames), which is rebuilt at runtime.
 *
 * <p>Only goals someone asked for ({@link AgentGoal.Source#LLM} / {@link AgentGoal.Source#PLAYER})
 * are saved; rule goals are re-seeded by routines and needs after a load. A write/read round trip
 * is also the deep copy used to hand one plan to several villagers ({@link #copy}).
 */
public final class GoalNbt {
    private GoalNbt() {}

    /** Should this goal survive a save/load? */
    public static boolean isPersistent(AgentGoal g) {
        return g != null && !g.isCompleted() && g.getSource() != AgentGoal.Source.RULE;
    }

    public static CompoundNBT write(AgentGoal g) {
        CompoundNBT t = new CompoundNBT();
        t.putString("Type", g.getGoalType());
        t.putString("Desc", g.getDescription() == null ? "" : g.getDescription());
        t.putInt("Priority", g.getPriority());
        t.putInt("Importance", g.getImportance());
        t.putString("Source", g.getSource().name());
        t.putString("Lifecycle", g.getLifecycle().name());
        if (g.getDeadlineTick() != Long.MAX_VALUE) t.putLong("Deadline", g.getDeadlineTick());
        if (g.getScheduledTick() != Long.MAX_VALUE) t.putLong("Scheduled", g.getScheduledTick());
        t.putInt("ScheduledPriority", g.getScheduledPriority());
        t.putBoolean("Hold", g.isHoldUntilScheduled());
        if (g.getTargetLocation() != null) t.putLong("Pos", g.getTargetLocation().asLong());
        t.putDouble("ArriveSq", g.getArrivalDistanceSq());
        if (g.getTargetItem() != null) t.putString("Item", g.getTargetItem());
        t.putInt("Qty", g.getTargetQuantity());
        if (g.getTargetEntityId() != null) t.putUUID("Entity", g.getTargetEntityId());
        if (g.getTargetProfession() != null) t.putString("Profession", g.getTargetProfession());
        if (g.getTargetKind() != null) t.putString("Kind", g.getTargetKind());
        if (g.getTargetBuildingType() != null) t.putString("Building", g.getTargetBuildingType());
        if (g.getGroupId() != null) t.putUUID("Group", g.getGroupId());
        if (g.getLeaderId() != null) t.putUUID("Leader", g.getLeaderId());
        if (g.getTitle() != null) t.putString("Title", g.getTitle());
        if (g.isShared()) t.putBoolean("Shared", true);
        if (g.hasEndTime()) t.putLong("End", g.getEndTick());

        if (g.hasSteps()) {
            ListNBT steps = new ListNBT();
            for (AgentGoal s : g.getSteps()) steps.add(write(s));
            t.put("Steps", steps);
            t.putInt("StepIndex", g.getStepIndex());
        }
        if (!g.getInvitees().isEmpty()) {
            ListNBT ids = new ListNBT();
            for (UUID id : g.getInvitees()) ids.add(NBTUtil.createUUID(id));
            t.put("Invitees", ids);
            t.putInt("InviteIndex", g.getInviteIndex());
        }
        if (g.getInvitePlan() != null) t.put("Plan", write(g.getInvitePlan()));
        if (g.getInviteMessage() != null) t.putString("Message", g.getInviteMessage());
        return t;
    }

    public static AgentGoal read(CompoundNBT t) {
        AgentGoal g = new AgentGoal(t.getString("Type"), t.getString("Desc"), t.getInt("Priority"));
        g.setImportance(t.getInt("Importance"));
        g.setSource(parseEnum(AgentGoal.Source.class, t.getString("Source"), AgentGoal.Source.LLM));
        g.setLifecycle(parseEnum(AgentGoal.Lifecycle.class, t.getString("Lifecycle"), AgentGoal.Lifecycle.ONE_SHOT));
        if (t.contains("Deadline")) g.setDeadlineTick(t.getLong("Deadline"));
        if (t.contains("Scheduled")) g.setScheduledTick(t.getLong("Scheduled"));
        if (t.contains("ScheduledPriority")) g.setScheduledPriority(t.getInt("ScheduledPriority"));
        g.setHoldUntilScheduled(t.getBoolean("Hold"));
        if (t.contains("Pos")) g.setTargetLocation(BlockPos.of(t.getLong("Pos")));
        if (t.contains("ArriveSq")) g.setArrivalDistanceSq(t.getDouble("ArriveSq"));
        if (t.contains("Item")) g.setTargetItem(t.getString("Item"));
        g.setTargetQuantity(t.getInt("Qty"));
        if (t.hasUUID("Entity")) g.setTargetEntityId(t.getUUID("Entity"));
        if (t.contains("Profession")) g.setTargetProfession(t.getString("Profession"));
        if (t.contains("Kind")) g.setTargetKind(t.getString("Kind"));
        if (t.contains("Building")) g.setTargetBuildingType(t.getString("Building"));
        if (t.hasUUID("Group")) g.setGroupId(t.getUUID("Group"));
        if (t.hasUUID("Leader")) g.setLeaderId(t.getUUID("Leader"));
        if (t.contains("Title")) g.setTitle(t.getString("Title"));
        g.setShared(t.getBoolean("Shared"));
        if (t.contains("End")) g.setEndTick(t.getLong("End"));

        if (t.contains("Steps")) {
            ListNBT steps = t.getList("Steps", 10);
            for (int i = 0; i < steps.size(); i++) g.getSteps().add(read(steps.getCompound(i)));
            g.setStepIndex(Math.min(t.getInt("StepIndex"), g.getSteps().size()));
        }
        if (t.contains("Invitees")) {
            ListNBT ids = t.getList("Invitees", 11); // 11 = int array (UUID)
            for (int i = 0; i < ids.size(); i++) g.getInvitees().add(NBTUtil.loadUUID(ids.get(i)));
            g.setInviteIndex(t.getInt("InviteIndex"));
        }
        if (t.contains("Plan")) g.setInvitePlan(read(t.getCompound("Plan")));
        if (t.contains("Message")) g.setInviteMessage(t.getString("Message"));
        return g;
    }

    /** Deep copy of a goal's intent (fresh runtime state). */
    public static AgentGoal copy(AgentGoal g) {
        return read(write(g));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException | NullPointerException e) { return fallback; }
    }
}
