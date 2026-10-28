package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/** The target item selects the registered resource, capability and valid tool tier. */
public final class ExtractItemSkill implements VillagerSkill {
    public String id() { return "extract_item"; }
    public boolean isPrimitive() { return true; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "extract_item".equals(g.getGoalType()) && ResourceCatalog.get(g.getTargetItem()) != null;
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return a.brain().tasks.current().state(ResourceExtraction.class, ResourceExtraction::new)
                .tick(a, g, v, w, ResourceCatalog.get(g.getTargetItem()), Math.max(1, g.getTargetQuantity()));
    }
}
