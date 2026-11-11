package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who takes part in a group activity. A villager takes part while it holds an unfinished goal with
 * the activity's {@link AgentGoal#getGroupId() group id} — so membership needs no extra state: it
 * follows acceptance (goal added), completion, expiry and drop automatically. Server thread only.
 */
public final class GroupActivities {
    private GroupActivities() {}

    /** Loaded, living villagers other than {@code self} that take part in {@code groupId}. */
    public static List<VillagerEntity> others(UUID groupId, UUID self, ServerWorld world) {
        List<VillagerEntity> out = new ArrayList<>();
        if (groupId == null || world == null) return out;
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (other.getVillagerId().equals(self) || !LlmGoalIntake.hasGroupGoal(other, groupId)) continue;
            Entity e = world.getEntity(other.getVillagerId());
            if (e instanceof VillagerEntity && e.isAlive()) out.add((VillagerEntity) e);
        }
        return out;
    }

    /** Names of everyone other than {@code self} taking part in {@code groupId}, loaded or not. */
    public static List<String> participantNames(UUID groupId, UUID self) {
        List<String> out = new ArrayList<>();
        if (groupId == null) return out;
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (!other.getVillagerId().equals(self) && LlmGoalIntake.hasGroupGoal(other, groupId)) out.add(other.getName());
        }
        return out;
    }

    /** The nearest of {@code members} to {@code villager}, or null. */
    public static VillagerEntity nearest(VillagerEntity villager, List<VillagerEntity> members) {
        VillagerEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (VillagerEntity m : members) {
            double d = villager.distanceToSqr(m);
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }
}
