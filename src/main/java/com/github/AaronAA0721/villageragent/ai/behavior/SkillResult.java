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
    /** Waiting for external state; release movement and retry after a cooldown. */
    WAITING,
    /** Skill cannot proceed right now (e.g. no bed in range) — keep the goal, retry later. */
    BLOCKED,
    /** Skill can never satisfy this goal — the executor marks it completed (drops it). */
    FAIL,
    /**
     * Skill decides the goal is impossible / invalid and should be removed entirely (e.g. a
     * craft goal for an item that has no recipe, or a malformed goal with no target item). The
     * executor deletes it from the active list immediately — no success marking, no
     * failure-recovery injection. This is the "delete a todo" signal: returning DELETE makes the
     * goal simply stop existing so it never competes for the running slot again.
     */
    DELETE
}
