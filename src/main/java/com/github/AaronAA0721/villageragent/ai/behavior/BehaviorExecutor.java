package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final Map<String, String> TYPE_TO_SKILL = new HashMap<>();

    static {
        // Built-in skills. Additional skills register the same way as they are implemented.
        register(new SleepSkill());
        register(new FarmSkill());
        for (String type : new String[]{"harvest", "plant", "till", "collect_crops"}) register(new FarmActionSkill(type));
        register(new SocializeSkill());
        register(new GatherSkill());
        register(new MoveSkill());
        register(new DropItemSkill());
        register(new PickupItemSkill());
        register(new WanderSkill());
        register(new GotoSkill());
        register(new CraftSkill());
        register(new TradeSkill());
        register(new ConverseSkill());
        register(new RestockSkill());
        register(new AcquireItemSkill());
        register(new FindJobSiteSkill());
        register(new ResourceWorkSkill());
        register(new TradeDeliverySkill());
    }

    /**
     * Static goal-type → handler contract: every goal type a producer may emit maps to the skill
     * that owns it. This is the "generation space ⊆ execution space" check made explicit — a type
     * with no entry here can never be executed (see {@code build} in older versions, which used to
     * sit in goal lists until the daily prune found it), and a type whose skill exists but gates
     * the villager out is reported as a capability gap rather than a silent skip.
     *
     * <p>Producers ({@code GoalParser} / {@code ActionValidator} / thought directives) should be
     * validated against this map at creation time; this map is the single source of truth.
     */
    static {
        TYPE_TO_SKILL.put("sleep", "sleep");
        TYPE_TO_SKILL.put("rest", "sleep");
        TYPE_TO_SKILL.put("farm", "farm");
        TYPE_TO_SKILL.put("gather", "gather");
        TYPE_TO_SKILL.put("craft", "craft");
        TYPE_TO_SKILL.put("trade", "trade");
        TYPE_TO_SKILL.put("socialize", "socialize");
        TYPE_TO_SKILL.put("converse", "converse");
        TYPE_TO_SKILL.put("move", "move");
        TYPE_TO_SKILL.put("drop_item", "drop_item");
        TYPE_TO_SKILL.put("pickup_item", "pickup_item");
        TYPE_TO_SKILL.put("goto", "goto");
        TYPE_TO_SKILL.put("wander", "wander");
        TYPE_TO_SKILL.put("restock", "restock");
        TYPE_TO_SKILL.put("acquire_item", "acquire_item");
        TYPE_TO_SKILL.put("find_job_site", "find_job_site");
        TYPE_TO_SKILL.put("fulfill_order", "fulfill_order");
        TYPE_TO_SKILL.put("deliver_trade", "deliver_trade");
    }

    /** True when this mod knows how to run {@code goalType} at all (regardless of this villager). */
    public static boolean hasHandlerFor(String goalType) {
        return goalType != null && TYPE_TO_SKILL.containsKey(goalType);
    }

    private BehaviorExecutor() {}

    public static void register(VillagerSkill skill) {
        SKILLS.add(skill);
        TYPE_TO_SKILL.put(skill.id(), skill.id());
    }

    public static boolean isComposite(String type) {
        VillagerSkill skill = skillById(TYPE_TO_SKILL.get(type));
        return skill != null && skill.isComposite();
    }
    public static boolean allowsRoot(AgentGoal goal) {
        if (goal == null) return false;
        VillagerSkill skill = skillById(TYPE_TO_SKILL.get(goal.getGoalType()));
        return skill != null && (!skill.isPrimitive() || goal.getSource() != AgentGoal.Source.RULE);
    }
    public static SkillResult executeRoot(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        if (!allowsRoot(g)) return SkillResult.DELETE;
        return a.brain().tasks.tick(g, goal -> executeNode(a, goal, v, w));
    }
    public static SkillResult executeChild(VillagerAgentData a, VillagerEntity v, ServerWorld w,
                                           java.util.function.Supplier<AgentGoal> factory) {
        AgentGoal parent = a.brain().tasks.current().goal;
        return a.brain().tasks.child(() -> {
            AgentGoal child = factory.get();
            BrainLog.diagnostic(a, "task-child-start", "parent=" + a.brain().tasks.path()
                    + " child=" + child.getGoalType() + " target=" + child.getTargetLocation());
            return child;
        }, child -> {
            SkillResult result = executeNode(a, child, v, w);
            if (result != SkillResult.RUNNING) {
                parent.setLastFailure(child.getLastFailure());
                BrainLog.diagnostic(a, "task-child-exit", "path=" + a.brain().tasks.path()
                        + " result=" + result + " failure=" + child.getLastFailure());
                ActionLifecycle.clearBody(a, v, w);
                ActionLifecycle.releaseTree(a.brain().tasks.current(), a, w);
            }
            return result;
        });
    }
    private static final class Watch {
        VillagerAction action;
        long since;
    }
    private static SkillResult executeNode(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        VillagerSkill skill = findHandler(a, g, v, w);
        if (skill == null) {
            g.setLastFailure(diagnoseFailure(a, g, v, w));
            return SkillResult.BLOCKED;
        }
        g.setLastFailure(null);
        SkillResult result = skill.execute(a, g, v, w);
        if (result == SkillResult.RUNNING && g.isCompleted()) result = SkillResult.SUCCESS;
        // Parents consume child failures; only the active leaf checks navigation.
        if (result == SkillResult.RUNNING && a.brain().tasks.current().child() == null) {
            Watch watch = a.brain().tasks.current().state(Watch.class, Watch::new);
            VillagerAction action = a.getCurrentAction();
            if (action != watch.action) { watch.action = action; watch.since = w.getGameTime(); }
            if (VillagerNavigationSupport.failedWalk(v, action, w.getGameTime() - watch.since)) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(String.valueOf(action.getTargetBlockPos())));
                return SkillResult.FAIL;
            }
        }
        return result;
    }

    /** Find a skill that can satisfy the goal now (pure predicate). Null if none. */
    public static VillagerSkill findHandler(VillagerAgentData agent, AgentGoal goal,
                                            VillagerEntity villager, ServerWorld world) {
        for (VillagerSkill s : SKILLS) {
            if (s.canHandle(agent, goal, villager, world)) return s;
        }
        return null;
    }

    /**
     * Diagnostic: explain <em>why</em> no skill claimed {@code goal}. Iterates every registered
     * skill and reports its id, whether {@link VillagerSkill#canHandle} accepted it, and its
     * capability gate (required capability / min level / the villager's current level).
     *
     * <p>This replaces the misleading "No skill handles goal type X" line: a skill whose
     * {@code canHandle} returns false is NOT the same as "no skill for this type" — it is usually
     * a capability gate failing (level 0) or a missing precondition (e.g. no JOB_SITE, restock
     * cooldown). Called only when {@link #findHandler} returns null, so it never runs on the
     * per-tick hot path.
     */
    public static String explainNoHandler(VillagerAgentData agent, AgentGoal goal,
                                          VillagerEntity villager, ServerWorld world) {
        StringBuilder sb = new StringBuilder();
        for (VillagerSkill s : SKILLS) {
            boolean handle = s.canHandle(agent, goal, villager, world);
            CapabilityId cap = s.requiredCapability();
            int min = s.minLevel();
            int lvl = cap == null ? -1 : agent.getCapabilityPool().levelOf(cap);
            if (sb.length() > 0) sb.append(' ');
            sb.append('[').append(s.id())
              .append(" handle=").append(handle)
              .append(" cap=").append(cap == null ? "-" : cap.name())
              .append(" min=").append(min)
              .append(" lvl=").append(lvl)
              .append(']');
        }
        return sb.toString();
    }

    /**
     * Turn "no skill claimed this goal" into a concrete {@link Failure} so the recovery planner
     * has something to act on. Called once, on the first unclaimable dispatch.
     *
     * <ul>
     *   <li>A handler exists for the type but the villager fails its capability gate →
     *       {@code CAPABILITY_TOO_LOW} (recovery can't fix it; it writes a memory instead).</li>
     *   <li>A handler exists and the gate passes → the skill refused on a precondition we can't
     *       see from here → {@code UNKNOWN}, carrying the goal type as the detail.</li>
     *   <li>No handler exists for the type at all → {@code UNKNOWN} too, but this is a
     *       producer-side bug: something emitted a goal type nobody can run.</li>
     * </ul>
     */
    public static Failure diagnoseFailure(VillagerAgentData agent, AgentGoal goal,
                                          VillagerEntity villager, ServerWorld world) {
        String type = goal == null ? null : goal.getGoalType();
        if (type == null) return FailureReason.UNKNOWN.with("null_type");

        String skillId = TYPE_TO_SKILL.get(type);
        if (skillId == null) return FailureReason.UNKNOWN.with(type);

        VillagerSkill skill = skillById(skillId);
        if (skill == null) return FailureReason.UNKNOWN.with(type);

        CapabilityId cap = skill.requiredCapability();
        int min = skill.minLevel();
        if (cap != null && min > 0
                && agent != null && agent.getCapabilityPool().levelOf(cap) < min) {
            return FailureReason.CAPABILITY_TOO_LOW.with(cap, min);
        }
        return FailureReason.UNKNOWN.with(type);
    }

    private static VillagerSkill skillById(String id) {
        for (VillagerSkill s : SKILLS) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }
}
