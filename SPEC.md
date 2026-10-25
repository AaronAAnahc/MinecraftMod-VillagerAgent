# SPEC: Routine scheduling through a unified goal funnel and stricter combat gates

> Topic-specific design reference, not a statement of current implementation. Current behavior is documented in `docs/README.md` and its topics.
> Related original design: the archived architecture reflection and simplification proposal (single goal funnel, modes reduced to routine generators).
> Proposed workflow: specification → approval → implementation. The original proposal required approval before implementation.

## Overview

Represent sleep as a routine `AgentGoal` injected/removed by time windows, with the highest non-emergency priority (8) and highest weight at night. Tighten proactive attack eligibility so unarmed villagers cannot duel or gang up on zombies.

Original explicit design constraints:

- Sleep wins through the scheduler as a high-priority time-window goal, without a hard-coded sleep override.
- Do not add nighttime returns to `tickFarming`; the night window excludes farm goals.
- Normal sleep follows `rest`/`sleep` → `SleepSkill.execute`; modal `tickRest` is only a no-goal fallback.
- Preserve two-stage soft sampling for non-emergencies. Priority 8 is most likely, not guaranteed, to beat other non-emergency goals; this tradeoff was confirmed.

## Data models

### 1. Event windows, proposed for `ai/RoutineScheduler.java`

```java
/** Routine goal templates injected during a daily window; dayTime is world.getDayTime()%24000. */
public class RoutineWindow {
    long startTick, endTick;          // Inclusive start/end within 0..23999
    String id;                        // "day_farm" / "night_rest" etc.
    List<RoutineSpec> specs;
}
public class RoutineSpec {
    String goalType;                  // "farm" | "rest" | "sleep"
    int priority;                     // 1..10
    double weight;                    // GoalScheduler weighting within one priority band
    // Optional parameters, such as  farmMode="maintain"|"reclaim"
    String param;
}
```

Default windows form a constant table that could later become configuration:

- `night_rest`: 12541–23458, the vanilla sleep window; inject `rest`, priority 8 (highest non-emergency), weight 4.0.
- `day_farm`: 0–12000; inject `farm`, priority 5, weight 2.0, matching the then-current `ensureFarmRoutineGoal`.
- Other periods inject no routine; villagers use agenda/ambition goals or idle.

### 2. AgentGoal extension

Add optional `routineWindowId` (String, default null) with accessors to identify the injecting window and precisely remove expired routines. Check whether `VillagerAgentData` persists goals in NBT; if not, no NBT work is required because routines regenerate every tick.

### 3. Combat configuration in ModConfig

| Key | Type | Default | Meaning |
|---|---|---|---|
| `combat_require_weapon` | bool | true | Proactive attacks require a real weapon (`findBestWeaponDamage > BASE_DAMAGE`); unarmed villagers never duel or contribute charge power |
| `combat_solo_win_factor` | double | 0.7 | Armed solo condition: `myTtk <= tTtk * factor` |
| `combat_charge_total_power` | double | 5.0 | Replace `RALLY_MIN_MEMBERS` and DPS comparison with summed responder power meeting this threshold |
| `combat_charge_min_health` | double | 10.0 | Attacks require this health; lower-health villagers only approach the rally point to distract |

Remove `combat_rally_min_members` (previously `RALLY_MIN_MEMBERS=2`) and `combat_rally_safety_margin`. Use total power rather than head count or threat-DPS comparison. Unarmed villagers still `respond` and approach to distract, but have approximately zero power and cannot attack; distraction creates opportunities for armed allies.

### 3b. CombatAction power formula

Power is weapon damage plus profession-level bonus, or zero when unarmed:

```
isArmed(agent)     = findBestWeaponDamage(agent, null) > BASE_DAMAGE + ε
combatPower(agent) = isArmed ? (weaponDamage + levelBonus) : 0.0
weaponDamage(agent)= findBestWeaponDamage(agent, null)   // Unarmed=1.0, iron sword≈6–7, diamond sword≈8
levelBonus(agent)  = agent.getLevel() * LEVEL_POWER_BONUS // Default 0.3/level; verify getLevel during implementation
totalPower = Σ combatPower(responder)           // Responders only; unarmed contributes zero
```

## User flows and API contracts

### A. Routine generation, once per villager per main-loop tick

```
ensureRoutineGoals(villager, agent, world):
  window = activeWindow(world.getDayTime()%24000)   // Time-driven; ignores scheduledActivity
  wantTypes = window.specs.map(s->s.goalType)
  // 1) Remove routine goals left from other windows
  for g in agent.getGoals():
      if g.getLifecycle()==ROUTINE && !wantTypes.contains(g.goalType): remove g
  // 2) Inject missing goals for this window
  for s in window.specs:
      if !hasGoalOfType(agent, s.goalType):
          g = new AgentGoal(s.goalType, desc, s.priority)
          g.setLifecycle(ROUTINE); g.setWeight(s.weight); g.setRoutineWindowId(window.id)
          if "farm".equals(s.goalType): g.setFarmMode(resolveFarmMode(world, villager))  // maintain/reclaim
          agent.getGoals().add(g); BrainLog.goalAdded(...)
```

Replace the old `ensureFarmRoutineGoal` (`VillagerAgentManager:1055`), which depended on `scheduledActivity=="farming"`.

### B. Goal-driven nighttime sleep

1. The night window injects priority-8 `rest`.
2. `processGoals` → `GoalScheduler.pickNextGoal`: emergency ≥9 wins directly; otherwise retain two-stage soft sampling. Rest has the highest ordinary priority/weight, and the window removes lower-priority farm routines.
3. `BehaviorExecutor.findHandler` → `SleepSkill.canHandle` for rest/sleep → goal-path `SleepSkill.execute` → approach bed → `trySleep`.
4. For non-farm running goals, `setScheduledActivity(null)` (then line 1450) prevents duplicate modal `tickRest` execution.

### C. Stricter proactive attacks, reactive and LLM-free

Keep `performCombatActions` (then line 252), tightening internal gates:

- `CombatAction.wouldWinSolo` returns false when weapons are required and absent. Armed villagers duel only when `myTtk <= tTtk * solo_win_factor`; otherwise use `rallyOrFlee`.
- `VillageSignalBoard.shouldCharge` uses `Σ combatPower(responder) >= combat_charge_total_power`, removing head-count/DPS comparison and `RALLY_MIN_MEMBERS`.
- Response branches (then lines 284–300) and rally charging gate `startCombatAction` through `isArmed(agent) && villager.getHealth() >= combat_charge_min_health`. Others `moveTo(rally.lastPos)` to distract and keep responding.
- Unarmed responders navigate but add zero power and never initiate attacks.

### D. Combat/siege interaction

- A close zombie reactively triggers flee/charge, suspending the running goal, including rest. `resumeSuspendedGoals` restores sleep after danger passes; survival takes precedence.
- A nighttime transition during farmMove allows `rest`(8) to preempt farm(5) through `shouldPreempt` (difference ≥3). The window removes that farm routine until daytime.

## File structure

| File | Change | Purpose |
|---|---|---|
| `ai/RoutineScheduler.java` | Create | Window/spec definitions, `ensureRoutineGoals`, default windows |
| `ai/AgentGoal.java` | Modify | `routineWindowId` and accessors |
| `ai/VillagerAgentManager.java` | Modify | Call new generator, remove `ensureFarmRoutineGoal`, generalize the farm-only scheduledActivity exception at then line 1450 |
| `ai/CombatAction.java` | Modify | `isArmed`/`combatPower`, stricter solo gate; retain villagerDps only as reference |
| `ai/VillageSignalBoard.java` | Modify | Remove minimum members; charge using total power; groupDps may remain unused for charging |
| `config/ModConfig.java` | Modify | Four new combat keys above; remove old member/safety keys; explicit brain-log default |
| `ai/BrainLog.java` | Modify | Null-safe `isEnabled`: `ModConfig.BRAIN_LOG_ENABLED!=null && .get()` |
| `ai/behavior/SleepSkill.java` | Unchanged | Goal execution already available; modal tickRest remains fallback |

## Edge cases and error handling

- No bed at night: BLOCKED reaches `BLOCKED_MAX_STREAK`, completes the goal, then ROUTINE regenerates rest next tick while the villager stays awake/idle.
- Daytime removes rest and injects farm.
- Each tick belongs to one window; remove old routines before inserting new ones.
- Unload/restart requires no routine persistence. If goals persist in NBT, discard unknown `routineWindowId` on read.
- Sleeping during siege retains the existing behavior: currentAction skips combat scans. Waking to bedside threats is a later enhancement.
- Configuration drift (P2): eliminate ambiguity around absent/null `brain_log_enabled` with an explicit default and null-safe BrainLog.

## Out of scope

- Suppressing LLM agenda/ambition goals at night. Highest ordinary priority/weight remains probabilistic, with no deterministic non-emergency preemption.
- Other routines such as eat, socialize, or trade; only farm/rest are proposed.
- Proactive waking for combat.
- Cross-restart goal persistence, which routines do not require.

## Open questions

1. Is power 5.0 appropriate? Iron swords around 6–7 allow one-person charging, while stone around 5 and wood around 4 were expected to need two.
2. Is minimum health 10.0 appropriate out of 20? Too low risks death; too high excludes wounded villagers entirely.
3. Should `professionBonus` augment weapon damage plus level bonus?
4. Should unarmed distraction keep a minimum safe distance? Direct `moveTo(lastPos)` risks contact; consider approaching only outside `FLEE_HOLD_DIST` and fleeing inside it.
5. High-weight non-routine LLM ambitions can dilute nighttime sleep under soft sampling. This is the confirmed tradeoff of avoiding deterministic preemption.
