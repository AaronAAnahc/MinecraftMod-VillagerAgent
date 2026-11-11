# Goal utility and chance factors

Reviewed: 2026-11-06. This document describes how the scheduler scores candidate goals and how each skill contributes its own opportunity weight.

## Utility formula

`GoalScheduler.utility()` computes the score for each candidate goal:

```
utility = basePriority * weight * chanceFactor * recentPenalty
```

| Component | Source | Range |
|---|---|---|
| `basePriority` | `max(1, goal.effectivePriority(now))` — includes timed boosts from scheduled goals | 1-10 |
| `weight` | `max(0.1, goal.getWeight())` — default 1.0, set at goal creation | 0.1+ |
| `chanceFactor` | `VillagerSkill.chanceFactor(agent, goal, now)` — each skill overrides this | 0 = suppress, 1 = neutral, >1 = boost |
| `recencyFactor` | Rest after a session: `0.3 + 0.7 × (elapsed / T)²` after the same type last ended, recovering to 1.0 after `T` = 600–3600 ticks, scaled by the last session's length (measured from selection; 600+ ticks gives the full 3600) | 0.3–1.0 |

Goals with utility 0 are excluded from selection. A goal that last stopped on `MISSING_ITEM` scores 0 while an `acquire_item` for that item exists (`waitingOnAcquisition`). Priority >= 9 bypasses weighted sampling and wins outright; otherwise a runnable [appointment](#appointments) is selected before any ordinary goal.

Between tasks the routine wander pauses 40 ticks (it used to be 100: in `latest.log` 190 villager-pauses of ~5 s each were the largest idle source). While idle (no running task, or standing between two wander walks, not moving, not in an action or conversation) the villager keeps looking around: every 1.5–4.5 s `IdleGaze` picks a nearby villager, player or animal it can see, or a spot 60–180° to either side, and re-applies that look target every tick so the head actually turns; the body follows once the head is more than 35° off-axis, so villagers visibly turn around.

Sampling is direct weighted random (not softmax): a goal with score 6 is three times as likely to be picked as one with score 2. Idle activities — wandering and the routine notice-board check (`GoalScheduler.isIdle`) — only participate when no other positive-score goal exists; then they compete with each other (both score 2 by default: wander 2 × 1.0, board check 4 × 0.5).

## Appointments

`GoalScheduler.isAppointment` covers a booked `meet_trade` and a timed LLM goal whose start tick has come (`AgentGoal.isScheduledDue`). While a timed goal is still before its `scheduledTick` (`isHeldUntilScheduled`) its utility is 0. Once due, it is picked ahead of ordinary goals the next time the villager is free or at a yield point, runs at `max(priority, scheduledPriority)` (at least 7), and is dropped if it has not started by its `deadlineTick`; `AgentGoal.markStarted` (called when the scheduler hands it the running slot) clears that start-by deadline. An appointment with an `endTick` is an activity: it stays committed until that tick (see `SequenceSkill`). Timing is set from `"at"`/`"in_seconds"`, see [LLM-authored goals](llm_and_world_interaction.md#llm-authored-goals).

## Commitment and yield points

`GoalCommitment` keeps a selected task running: walking to the workstation, field, forest, mine or board never ends in a switch to another ordinary task, and a booked `meet_trade` waits. Only needed sleep (curfew or fatigue ≥ 80), priority-9+ emergencies and reactive combat interrupt it. Wandering is never committed and yields to anything with a purpose. A `sequence` goal stays committed through the yield windows its steps open.

Multi-unit routines open a 30-tick yield window after each finished unit — a crop tended (`FarmSkill`), a tree felled and its drops collected (`LumberjackWorkSkill`), a block mined (`MinerWorkSkill`). Only inside that window may the scheduler pick another task (log `goal-yield … why=yield_point`). A block being broken is never interrupted.

## chanceFactor architecture

Each `VillagerSkill` implementation defines:

```java
default double chanceFactor(VillagerAgentData agent, AgentGoal goal, long now) {
    return 1.0; // override to adjust
}
```

`GoalScheduler` resolves the skill via `BehaviorExecutor.skillForGoalType(goalType)` and calls `chanceFactor` once per utility evaluation. The scheduler contains no per-type switch; all opportunity logic lives in the skill that owns the behavior.

To add a new skill with custom weighting, implement `chanceFactor` in the skill class. No changes to GoalScheduler are needed.

## Per-skill chance factors

### Needs-driven (survival)

| Skill | Factor | Logic |
|---|---|---|
| **SleepSkill** | 0 to ~32 | Returns 0 when fatigue < 40 (not tired enough). Otherwise `nightBoost * fatigueScale` where nightBoost is 4 at night, 1 at fatigue >= 80, 0.005 during daytime; fatigueScale is `(fatigue/35)^2`. A villager at fatigue 70 at night scores ~16x base. With `night_curfew` inside the sleep window the factor is 1000 regardless of fatigue (see [night curfew](events_and_knowledge_sharing.md#night-curfew-and-night-alarms)). |
| **EatSkill** | 0 to ~2.8 | Returns 0 when hunger >= 85 (already full), except 1.0 for an explicit (LLM/player) eat, which eats at least one item. Otherwise `((100-hunger)/60)^2`. The `eat` goal is seeded below hunger 60: ~0.44 there, ~1.0 at hunger 40. At hunger=15 (starvation): ~2.0. At hunger=0: ~2.8. The priority itself also jumps to 9 at hunger < 15, making it near-guaranteed. |

### Work-driven (profession routines)

| Skill | Factor | Logic |
|---|---|---|
| **FarmSkill** | 0 to 4 | `min(4, (farmOpportunities + rememberedFarm ? 3 : 0) / 3)`. More mature crops and known farmland = higher score. A farmer who has never seen farmland scores ~0. |
| **LumberjackWorkSkill** | 0.2 to 4 | `min(4, max(0.2, rememberedWoodLogs / 20))`. Counts total WOOD_LOG in all chunk memories. A lumberjack who has seen 80+ logs in forest chunks is at maximum. |
| **MinerWorkSkill** | 0.5 to 4 | `max(0.5, 1.0 + (miningLevel-1) * 0.3)`. Linear scaling from mining capability: Lv1=1.0, Lv5=2.2, Lv11=4.0. Higher skill = more productive = more motivated. |
| **FishSkill** | 0.5 to 3 | `max(0.5, min(3, waterChunks * 0.5 + 0.5))`. Counts chunks tagged WATER in memory. A fisherman near a river/ocean is boosted. |
| **ButcherSkill** (`butcher_work`) | 0.3 to 3 | `max(0.3, min(3, rememberedLivestock / 5))`. More animals in memory = more motivation. |
| **CookSkill** (`butcher_cook`) | 0, or 1 to 3 | 0 when the butcher carries no raw meat and no smoker/campfire load is due for collection; otherwise `min(3, 1 + rawMeat / 8 + (collectionDue ? 1 : 0))`. |
| **ShepherdSkill** | 0.3 to 3 | `max(0.3, min(3, rememberedLivestock / 4))`. Same principle as butcher, slightly more sensitive. |
| **FletcherSkill** (`fletcher_work`) | 0.2 to 3 | 0.2 when 64 arrows and 8 each of flint and feathers are carried; otherwise `min(3, 1 + missingArrows / 32)`. |
| **BreedSkill** (`make_family`) | 1.5 | Always selectable; without a live partner reservation the goal deletes itself on its first tick. |
| **CraftSkill** | 0 or 1 | Returns 0 for the repeatable craft routine once the target's stock count from `craft_targets.json` is reached (`DeterministicPlanGenerator.craftShortfall`). Otherwise 1.0. |
| **SmeltSkill** | 1.0 | Default (no override). Smelting runs when the routine is seeded. |

### Social and trade

| Skill | Factor | Logic |
|---|---|---|
| **SocializeSkill** | 0 to ~7.5 | Returns 0 if no social partner, cooldown active, or already socializing. Otherwise `timeSinceLastChat/600 * personalityBonus` (friendly/cheerful get 1.5x). Longer isolation = stronger urge to chat. |
| **TradeMeetingSkill** | 2.5, 1, 0 | 2.5 normally, 1 after one failure, 0 after two. A runnable `meet_trade` is also picked ahead of ordinary goals (see above). |
| **RestockSkill** | 0 or 1 | Returns 0 if all outcome items are in stock. Returns 1 if any daily-supply outcome item has zero count (separate from profession essentials). |

### Utility and fallback

| Skill | Factor | Logic |
|---|---|---|
| **WanderSkill** | 0.25 or 1 | Tired villagers (fatigue > 60) wander less. Wander is an idle activity — it only wins when no other positive-score goal exists. |
| **TradeSkill** (routine board check) | 1.0 | Idle activity alongside wander; 4800-tick cooldown. |
| All others | 1.0 | Skills that don't override `chanceFactor` get neutral weight. |

## Interaction with priority floors

`GoalScheduler.applyWorkPriority()` sets minimum priority floors (find_job_site=8, farm/craft/work=7, restock/acquire/gather/collect_smelt=6). The routine board check has no floor. These ensure work goals compete fairly but do not override chanceFactor — the two multiply together.

Example: a `farm` goal with priority floor 7, weight 1.0, and chanceFactor 2.0 (good farmland) has utility 14. A `rest` goal with priority 5, weight 1.0, and chanceFactor 8.0 (very tired at night) has utility 40 and dominates.

## Session timing and cooldowns

chanceFactor is evaluated every time the scheduler considers a goal, which happens at reconsideration intervals (~20 ticks minimum). The recency factor implements "rest after a long session": a session is recorded when the goal succeeds or is switched away from (`session-recorded type=… ticks=…`), timed from `goalSelectedTick`. (`goalStartedTick` is only the stuck-task watchdog; skills reset it while working.) `GoalExitPolicy` cooldowns (100-1200 ticks depending on result) also suppress goals between attempts.

## Source entry points

| Responsibility | File |
|---|---|
| chanceFactor interface | `ai/behavior/VillagerSkill.java` |
| Utility computation | `ai/GoalScheduler.java` |
| Skill lookup for goalType | `ai/behavior/BehaviorExecutor.skillForGoalType()` |
| Priority floors | `GoalScheduler.applyWorkPriority()` |
| Needs system (hunger/fatigue decay, eat/rest seeding) | `ai/VillagerNeedsSystem.java` |
| Routine seeding (repeatable work goals) | `ai/RoutineScheduler.java` |
