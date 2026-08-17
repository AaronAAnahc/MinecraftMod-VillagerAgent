package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * The autonomous goal runner — the half of the agent that runs WITHOUT the LLM.
 *
 * <p>For every tick it is asked, it looks at the agent's goals (which the LLM planner
 * only ever authors/edits), finds the highest-priority one, and dispatches it to the first
 * {@link VillagerSkill} that claims it via {@link VillagerSkill#canHandle}. This is what
 * turns "a villager should sleep" (a plan) into "the villager walks to its bed and sleeps"
 * (an action) on a fixed cadence, independent of any language-model round-trip.
 *
 * <p>Skills register themselves here. The executor owns no behaviour of its own — adding a
 * new capability is just implementing {@link VillagerSkill} and calling {@link #register}.
 */
public final class BehaviorExecutor {

    private static final List<VillagerSkill> SKILLS = new ArrayList<>();

    static {
        // Built-in skills. Additional skills (CraftSkill, MoveSkill, TradeSkill, SocializeSkill,
        // ...) register the same way as they are implemented.
        register(new SleepSkill());
        register(new FarmMaintainSkill());
        register(new FarmReclaimSkill());
    }

    private BehaviorExecutor() {}

    public static void register(VillagerSkill skill) {
        SKILLS.add(skill);
    }

    /** Find a skill that can satisfy the goal now (pure predicate). Null if none. */
    public static VillagerSkill findHandler(VillagerAgentData agent, AgentGoal goal,
                                            VillagerEntity villager, ServerWorld world) {
        for (VillagerSkill s : SKILLS) {
            if (s.canHandle(agent, goal, villager, world)) return s;
        }
        return null;
    }
}
