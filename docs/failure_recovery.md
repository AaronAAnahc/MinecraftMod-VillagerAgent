# Failure-driven replanning

Reviewed: 2026-10-23. `FailureRecoveryPlanner.handle` is called by `VillagerAgentManager.processGoals` when no handler is available or a skill returns `BLOCKED/FAIL`. See the [main loop](execution_pipeline_and_resource_orders.md) for result retention, cooldowns, and exits.

## Failure representation and dispatch

`Failure` stores `reason`, `itemId`, `quantity`, `capability`, and `requiredLevel` on `AgentGoal.lastFailure`. Both missing items and tools use `MISSING_ITEM`.

| Reason | Current handling |
|---|---|
| `MISSING_ITEM` | Find an existing output goal, then an unlocked recipe, then publish supply demand |
| `NO_JOB_SITE` | Reuse or create a one-shot `find_job_site` goal with the target profession |
| `NO_RECIPE` | Publish supply demand when an item ID exists; otherwise record a memory |
| `CAPABILITY_TOO_LOW` | Record the required level and wait for training or other external changes |
| `NO_BED`, `BED_OCCUPIED`, `NOT_NIGHT`, `UNREACHABLE`, `UNKNOWN` | Record a failure memory; let exit policy determine retries |

Enum names describe representable reasons. Inspect each skill's `execute` method for reasons actually emitted. For example, current sleep selection depends on fatigue.

## Missing-item recovery order

1. Search unfinished goals' `outcomeItems`. When a goal can produce the item and has a handler, clear its cooldown. Although named `boost`, this changes `cooldownUntilTick`, leaving priority unchanged.
2. If `NativeRecipeResolver.resolveRecipe` succeeds and either the unlocked set (including ingredient dependencies) or `SkillCraftCatalog.isUnlockedFor` allows it, reuse a craft goal or create a priority-6 one-shot goal with item, quantity, and outcomes.
3. Otherwise call `ResourceWorkSystem.request`, targeting current stock plus the shortage, and publish `TRADE_WANT` on `VillageSignalBoard`. If a recipe exists, continue handling missing ingredients with recursion limited to depth 2.

Known crafting routes take precedence over restocking that only publishes demand. `CraftingPlan` fully expands ordinary crafting-table dependencies independently of the two-level trade fallback limit.

Existing goals, target items, and the signal board provide deduplication. `NO_JOB_SITE` creates priority-7 goals or clears an existing goal's cooldown. Recovery runs on the server thread.

## Restocking and acquiring items

`RestockSkill` traverses profession starter supplies, targeting `min(8, supply.quantity)` for each entry. A shortage with an unlocked ordinary crafting-table route runs a craft child task; otherwise it requests an order, publishes trade demand, and returns `WAITING`. It returns `SUCCESS` when all supplies are satisfied. The starter table defines demand types and quantities; replenishment depends on real supply.

`AcquireItemSkill` uses `targetItem` and `targetQuantity`. Sufficient inventory completes the goal and removes that author's `TRADE_WANT`. An unlocked recipe runs a craft child task with ingredient planning and workbench travel, then checks inventory on the tick after success. The crafting chain reports missing ingredients. Without a crafting route, it requests a resource order and waits. Completion currently removes all `TRADE_WANT` signals by that author; account for this scope with concurrent purchase goals.

For a farmer missing a hoe: `FarmSkill` reports the shortage → recovery reuses an output goal or creates crafting/supply demand → the scheduler selects a feasible goal → farming retries after the tool reaches inventory. Inventory and world state provide completion evidence.

## Source locations

- Failure data: `ai/behavior/Failure.java`, `FailureReason.java`.
- Recovery methods: `ai/FailureRecoveryPlanner.java`.
- Handler eligibility: `findHandler` and `diagnoseFailure` in `ai/behavior/BehaviorExecutor.java`.
- Supply execution: `RestockSkill`, `AcquireItemSkill`, `CraftSkill`, `ResourceWorkSystem`.
- Exit verification: `GoalExitPolicy`, `GoalExitPolicyChecks`.
