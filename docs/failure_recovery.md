# Failure-driven replanning

`FailureRecoveryPlanner.handle` runs after a root returns BLOCKED/FAIL or has no handler. Missing tools and materials both use `MISSING_ITEM`.

## Missing items and method selection

Recovery reuses or creates a one-shot `acquire_item` root with an absolute inventory target (current stock plus shortage). It does not immediately commit to crafting. Existing acquisition targets are raised as needed and their cooldown is cleared. Farming's missing-hoe substitution still checks unlocked iron/stone/wooden hoes producible from current inventory before choosing the target item; costs compare ways to acquire that exact item, not tool durability or alternative tool tiers.

`AcquireItemSkill` checks the actual target inventory through `AcquisitionTasks`, then delegates to `TaskMethodExecution` and the output-matched methods from `ItemTaskCatalog`. Sessions survive WAITING and restock-child frame reconstruction. Methods are scored by remaining expenditure, failure history and time without progress, with minimum run, hysteresis, timeout and cooldown. See [Task methods](task_methods.md) for the interface, defaults and ownership lifecycle.

`AcquisitionPlanner` estimates each ordinary craft or finished-product purchase method using nearby priced surplus. It excludes unknown-item fallback prices, seller essential reserves, unavailable stock, unaffordable baskets and plans lacking a reachable remembered table. Purchase simulation shares buyer and seller inventories; `CraftingPlan` handles ingredient reuse, recipe batches and remainders. Root recipes must be unlocked. Crafting purchases can include direct ingredients or upstream inputs such as logs. The buy-product method cannot silently become crafting, and craft cannot buy the finished target.

Costs are additional integer emerald expenditure, rounded up per seller/item batch. Existing materials, walking and time have no monetary charge; the method executor adds time only to the selection score. Bounded search considers up to 256 dependency item IDs, eight deterministic live offers, 32 units per offer, single-offer probes and 128 basket expansions. It is not guaranteed globally optimal. Ingredient purchases execute one at a time; after receipt the craft method updates its remaining plan using actual inventory. Non-grid processing only uses already available inputs and its existing profession-station execution.

Cooperative supply is a separate zero-money method for supported raw targets with a nearby loaded, skilled and equipped worker. Material shortages are acquired recursively under their parent production method. A supplier does not guarantee reachable resources. Stall penalties and timeout allow switching to paid routes. No daily free workstation refill exists. Restock and economy essential deficits use acquisition; explicit `/va craft` and production craft goals remain craft-only.

Method-owned requests are canceled by attempt UUID only. Completion removes the owning method's outstanding requests without deleting unrelated tasks' wants. No money is reserved across unrelated transactions; delivery and settlement revalidate funds. Real world changes may invalidate an estimate.

## Other failures

- `NO_RECIPE` with an item creates/reuses acquisition; without an item it records a memory.
- `NO_JOB_SITE` reuses or creates `find_job_site`.
- Capability gaps and other failures record a memory; exit policy governs retries.

## Output metadata

`AgentGoal.outcomeItems` remains a lightweight goal/debug hint. Method discovery uses `ItemTaskCatalog` and bound `TaskContract` requirements/effects, with item quantities, retained tools, minimum levels and practice effects. `ResourceCatalog` provides parameterized resource producers, and `ResourceExtraction` executes both personal extraction and supplier work. Supplier `fulfill_order` outputs belong to the requester; the separate personal extraction method keeps its outputs. See [Task methods](task_methods.md) for recursive prerequisites, cycle bounds and ownership.

## Sources

- `ai/FailureRecoveryPlanner.java`: failure dispatch and acquisition objectives.
- `ai/AcquisitionPlanner.java`, `AcquisitionSearch.java`: quotes, simulated baskets and bounded cost search.
- `ai/behavior/AcquireItemSkill.java`, `RestockSkill.java`: execution and retry boundaries.
- `ai/CraftingPlan.java`: ordinary recipe dependencies and inventory simulation.
- `ai/VillagerEconomySystem.java`: surplus quotes and paid settlement.
