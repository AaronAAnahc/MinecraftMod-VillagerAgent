package com.github.AaronAA0721.villageragent.ai.behavior;

/**
 * Outcome of one {@link VillagerSkill#execute} tick, used by the {@link BehaviorExecutor}
 * to decide what to do with the goal next frame.
 */
public enum SkillResult {
    /** Goal satisfied — the executor marks it completed. */
    SUCCESS,
    /** Skill is mid-progress (e.g. walking to a target) — call again next tick. */
    RUNNING,
    /** Skill cannot proceed right now (e.g. no bed in range) — keep the goal, retry later. */
    BLOCKED,
    /** Skill can never satisfy this goal — the executor marks it completed (drops it). */
    FAIL
}
