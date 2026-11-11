# Failure-driven replanning

`FailureRecoveryPlanner.handle` runs after a root returns BLOCKED/FAIL or has no handler. Missing tools and materials both use `MISSING_ITEM`.

## Missing items and method selection

Recovery reuses or creates a one-shot `acquire_item` root ("Acquire X (recovery)", priority 6) with an absolute inventory target (current stock plus shortage). An existing acquisition for the same item has its target raised as needed and its cooldown cleared, so one blocked task never holds more than one recovery acquisition per item. While that acquisition exists, the blocked task scores zero (`GoalScheduler.waitingOnAcquisition`) and resumes once the acquisition ends. Recovery does not commit to a method. An item the village cannot produce at all (`VillageProducible.producible` is false: glass, paper, string…) gets no acquisition; the planner logs `recovery-unproducible` instead of creating a request nobody can answer.

Skills only report what they lack. `CraftSkill` reports the first leaf its `CraftingPlan` cannot cover; an ingredient that can only be made from the recipe's own output (a hay bale for wheat, a coal block for coal) is never reported, so the shortage is the real input (wheat, coal). Work skills that report a missing tool by category (`axe` from logging, `pickaxe` from mining) get one category acquisition (`requestCategory`, any tier) targeting the profession's configured tool, else the stone tool; an existing acquisition for that category is reused. A missing `food` is left to the eat goal's own `#food` acquisition. Farming's missing hoe is any hoe: recovery picks the first unlocked iron/stone/wooden hoe that current inventory can craft, else keeps the iron hoe as the target.

`AcquireItemSkill` checks the actual target inventory through `AcquisitionTasks`, then delegates to `TaskMethodExecution` and the output-matched methods from `ItemTaskCatalog`: `craft`, `craft_inputs`, extraction (logging, mining, quarrying, farming — run as the villager's own `lumberjack_work`/`miner_work`/`farm` session when it has that routine), `smelt` and `request_supply`. Sessions survive WAITING and restock-child frame reconstruction. Methods are scored by estimated travel/work, an own-production penalty on requests, failure history and time without progress, with minimum run, hysteresis, timeout and cooldown. A method that ends on its own exit condition without meeting the objective cools down so another method is tried. See [Task methods](task_methods.md) for the interface, defaults and ownership lifecycle.

`AcquisitionPlanner` checks crafting feasibility from the villager's own simulated inventory with `CraftingPlan`, which handles ingredient reuse, recipe batches and remainders. A missing crafting table does not rule crafting out; `CraftSkill` obtains and places one. Root recipes must be unlocked. Missing ingredients are acquired recursively under `craft_inputs`; inputs nobody can produce make that route unavailable. Requests are free village requests on the notice board (see [cooperative supply](capabilities_crafting_and_economy.md#requests)); explicit `/va craft` remains craft-only.

Village requests are villager-owned (`OwnRequests`): closing or replacing the posting method does not withdraw them. A request whose need was met another way is withdrawn by walking to the board (`withdraw_request`); delivered and expired requests are retired as bookkeeping. Real world changes may invalidate an estimate.

## Other failures

- `NO_RECIPE` with an item creates/reuses acquisition; without an item it records a memory.
- `NO_JOB_SITE` reuses or creates `find_job_site`.
- Capability gaps and other failures (`NO_BED`, `BED_OCCUPIED`, `NOT_NIGHT`, `UNREACHABLE`, `UNKNOWN`) record a memory; exit policy governs retries.

## Output metadata

`AgentGoal.outcomeItems` remains a lightweight goal/debug hint. Method discovery uses `ItemTaskCatalog` and bound `TaskContract` requirements/effects, with item quantities, retained tools, minimum levels and practice effects. `ResourceCatalog` provides parameterized resource producers; `ResourceExtraction` executes local extraction and `FarmSkill` runs crop sessions for villagers that remember a field. Outputs stay in the producer's inventory until used or delivered. See [Task methods](task_methods.md) for recursive prerequisites, cycle bounds and ownership.

## Sources

- `ai/FailureRecoveryPlanner.java`: failure dispatch and acquisition objectives.
- `ai/VillageProducible.java`: village-wide producibility and circular-input checks.
- `ai/AcquisitionPlanner.java`, `ai/CraftingPlan.java`: crafting feasibility and inventory simulation.
- `ai/behavior/AcquireItemSkill.java`, `RestockSkill.java`: execution and retry boundaries.
- `ai/AcquisitionMethods.java`, `ai/AcquisitionTasks.java`: method adapters and sessions.
