# Task methods and execution policy

## Interface and responsibilities

`TaskMethod<C>` is the common method interface: stable ID, remaining-cost estimate, start with an attempt UUID, tick with explicit progress/safe-switch evidence, and idempotent close. Estimates must not start work or spend inventory. Unavailable estimates are positive infinity; invalid/negative estimates are excluded. Methods may wait for external help without claiming that success is guaranteed.

`TaskMethodExecution<C>` owns selection, effective attempt time, progress, failure history, cooldown and cancellation order. It selects the lowest finite score, keeps one method active, and closes the old attempt before starting a replacement. A method's DONE returns control to its parent; actual task completion is checked against world/inventory state. Every new attempt gets a different UUID. The executor has no Minecraft dependency and is tested through the same interface used by the live adapters.

`ItemTaskCatalog` registers parameterized providers with output matching, a bound `TaskContract`, and a `TaskMethod` factory. `TaskContract` records remaining item inventory requirements (consumed ingredients versus retained tools), minimum capability levels, expected target output quantities, and practice effects. Output declarations discover candidates; only real inventory completes acquisition. Practice metadata describes the skill trained, not a promised final level or an exact batch XP total. `ResourceExtraction` and `CraftSkill` award XP directly; the planner does not execute `practice` metadata or discover training paths from it.

| Method | Inputs/gates | Output |
|---|---|---|
| `craft` | Unlocked recipe; existing inputs or a paid ingredient basket; required workstation | Recipe target |
| `craft_inputs` | Unlocked ordinary recipe; recursively acquire missing ingredients, then craft | Recipe target |
| `woodcutting` | WOODCUTTING ≥ 1; usable axe, acquired first if absent; reachable natural tree | Requested log species |
| `mining` | MINING ≥ 1; correct pickaxe tier; exposed reachable source | Requested 1.16.5 ore drop |
| `quarrying` | QUARRYING ≥ 1; pickaxe; exposed reachable stone | Cobblestone |
| `farming` | FARMING ≥ 1; reachable mature wheat/carrot/potato/beetroot crop | Requested crop |
| `buy_product` | Wildcard item target; actual offer, explicit price, inventory capacity and sufficient emeralds | Purchased target |
| `cooperative_supply` | Registered raw output and a nearby loaded, skilled worker with the right tool | Delivered raw target |

Generic GATHERING is separate. `GatherSkill` checks target stock and moves to an optional target location; it relies on passive pickup and does not find/harvest berries, extract resources, or publish supply requests. It is not registered as an item-producing provider. Explicit `pickup_item` owns deliberate ground-item collection. Planting/tilling are farm prerequisites, not immediate crop outputs. Farming acquisition harvests existing mature crops; it does not predict future growth or automatically replant. Cooperative supply is an order to another worker, not a workstation producing free daily items. Non-grid processing remains supported by `craft` with existing unlocked inputs; its missing inputs are recorded but not recursively acquired by `craft_inputs`.

The executor asks the catalog for all applicable providers and compares their costs. Recipe ingredient purchases still use the bounded basket search, including finished ingredients, planks and logs. `craft_inputs` additionally connects raw resource producers and their tool prerequisites. `CraftingPlan.materialRequirements` resolves successive blockages on virtual inventories (up to 64 passes) so the initial cost is not just the first missing ingredient. Tag alternatives prefer branches supported by observed reachable raw sources while retaining priority for actual stock; this can prefer an available log species, but remains a bounded heuristic and does not guarantee that every feasible species or recipe wins. Source feasibility probes are cached for 40 ticks and invalidated after movement beyond two blocks. Recursive method estimates have depth 6 and 64 visits; execution has depth 8 and rejects an ancestor item before touching its session. Cycles such as log → axe → log are unavailable unless inventory or another method breaks the cycle. The sum of estimated prerequisite expenditures must fit the current emerald balance. Estimates are not a shared reservation of cash, supplier stock or tools across all branches, so later revalidation can still reject a route. Recipe/tag selection and future tool sharing remain heuristic, so this is not exhaustive/global cost optimization.

Extraction and resource orders share `ResourceExtraction`: capability/tool checks, bounded loaded-source sensing, navigation, protected real block breaking, real loot, contact pickup, and practice XP on successful extraction. Higher-tier existing tools are accepted; Silk Touch is rejected when it would change the requested output. The baseline missing-tool path requests the catalog's minimum tool. Each mining request supplies its desired item; mining is not hard-coded separately for each villager or each order.

`AcquisitionTasks` holds root-owned sessions keyed by target item. They survive WAITING, scheduler changes and `TaskExecution` frame reconstruction, including acquisition children of restock. A changed desired quantity or parent attempt starts a fresh session. Closing a parent method recursively cancels its child acquisition sessions and their owned requests, including off-scheduler waits. Root removal/expiry/completion, satisfied inventory, dimension change, entity removal and world unload also close sessions. Root references and method attempts are runtime-only, not NBT data. After a restart, persisted objectives replan from real inventory.

## Contract consumption and execution boundaries

Contracts are bound to the current request/inventory. Item quantities are absolute inventory targets; output quantities describe the remaining demand. Already usable tools are omitted from missing-input requirements. `consumed` distinguishes ingredient/tool intent but is not a generic debit instruction: recipes consume materials, and block actions handle tool durability. Recipe unlocks, usable tool predicates, workstation reachability, world-interaction switches, quotes and cash checks live in providers/executors rather than a complete declarative precondition list. Buy/supply contracts declare their target output but do not encode payment, supplier eligibility or recipient inventory transfers.

The acquisition path checks contracts and scores methods; it does not convert every `VillagerSkill` into a provider. In particular, generic gathering, planting/tilling and skill training are not automatic prerequisite producers. All resource outputs use actual Minecraft 1.16.5 loot; iron/gold outputs are ore blocks, not later-version raw-metal items.

Equal finite scores retain provider iteration order for a fresh selection: `craft`, `craft_inputs`, extraction, `buy_product`, then `cooperative_supply`. Only one method per acquisition session runs at a time; different queued roots/restock item sessions can retain independent external requests. This is not a guarantee of one outstanding request per villager.

`hasSupplier` checks a loaded living worker within 64 blocks, profession, skill and tool. It does not reserve that worker, check its current lease, or prove resource availability. `offerWork` separately allocates an order using role/skill eligibility; a missing tool can then trigger recovery. Cooperative supply is free under the current model, while stock trades require payment. There is no automatic conversion between these two models.

During an active production child, the production method estimates no new purchase cost; while acquiring a prerequisite, it uses that child's last recorded remaining monetary estimate and estimates the other missing inputs. These values are refreshed at method reevaluation, not a continuous exact simulation of the entire future route.

## Score and clocks

```text
score = remaining monetary cost
      + failure count (capped at 3) * failure penalty
      + max(0, effective time since progress - grace) * time weight
```

The time term applies to the active method. Alternative methods receive their estimated remaining cost and retained failure penalty. Task creation time is never used. Time before a method starts is excluded. External WAITING time continues while the root is off-scheduler; a RUNNING body action accrues at most one tick per execution call so preemption does not become fake stall time. Progress resets the stall clock, not the method's total elapsed time or failure history.

Default policy (game ticks, 20 TPS conversion only):

| Rule | Default |
|---|---|
| Reconsideration interval | 100 ticks / 5 seconds minimum |
| Minimum method run before voluntary switch | 100 effective ticks |
| Stall grace period | 200 ticks / 10 seconds |
| Time weight | 0.0025 score per tick after grace; 1 point per 20 seconds |
| Required switch advantage | alternative score + 0.5 must be strictly below current score |
| Stall timeout | 1200 ticks / 60 seconds without observed progress |
| Failed/abandoned method cooldown | 400 ticks / 20 seconds |
| Failure penalty | 1 point per failure, capped at 3 |

Root WAITING still uses the existing 200-tick scheduler cooldown, so observations/switches can occur later than these thresholds. Timing is game time, not wall time. Policy is centralized in `TaskMethodExecution.Policy.defaults()`; no configuration-file keys were added.

A failed method or a lost precondition can bypass minimum run. A hard stall releases the method even if no alternative is available; its cooldown prevents immediate restart. Replacement and timeout require the method's safe-switch flag. Current adapters report safety after each server-thread tick, outside atomic inventory transfers. Completion clears that method's failure history. Monetary feasibility remains separate from scoring: elapsed time never becomes an emerald debit or permits borrowing.

## Progress evidence

- Crafting: committed recipe steps increment the child goal's runtime progress version; movement counts only when distance to a concrete destination improves.
- Purchase: the supplier measurably approaches the requester; a resolved delivery is retained as a receipt even if the signal board prunes it during a WAITING cooldown.
- Extraction: a real block is broken, target stock increases, or distance to a concrete target improves. Child progress propagates to its parent acquisition.
- Supply: a worker takes responsibility, its relevant gathered stock increases, it approaches a concrete work target, or requester stock increases. Lease renewal or RUNNING alone does not count.

Recipe progress, received items and money already spent remain in the world after cancellation. A new estimate uses the resulting inventory; no cancellation refunds consumed inputs or reverses a completed trade.

## Request ownership and cleanup

Trade wants created by a method have `ownerAttempt`. They are isolated from ordinary author/item deduplication, broad author cleanup, automatic rematching and demand-triggered production. Only the selected method matches its request to a quoted offer. Closing the attempt removes its requests and releases their matching offers. Existing stock/funds/capacity checks still guard matching and settlement; no money reservation system is introduced.

Resource requests carry the same attempt UUID. They deduplicate within that owner only; different owners and legacy demand are intentionally separate rather than shared. Cancellation never deletes another owner's demand. Owned orders have a renewable 600-tick demand lifetime and are not saved, preventing orphan demand after restart. Legacy shared orders retain their existing persistence. A worker may keep already gathered items if its order is canceled; the executor cannot undo harvesting.

`AcquisitionTasks.maintain` cleans removed, expired or satisfied objectives before root scheduling. Entity removal and world unload explicitly close sessions. Supplier delivery tasks reject canceled requests through their existing checks. Unloaded demand without further renewal expires. Logs use `task-method` with transition, active ID, score and stall ticks.

## Scope and verification

The executor is reusable; item acquisition now discovers producers through a Java provider registry. Providers are scanned for matching outputs; this is not a precomputed reverse index or a JSON-configurable universal task graph. Other handwritten composite skills are not automatically converted to method catalogs, and no automatic skill-training planner is implemented. A level-zero producer is unavailable; profession claim supplies its baseline, and existing learning systems can raise capabilities. Ordinary purchase-basket search remains bounded (see failure recovery), and estimates do not claim global optimality. The adapters do not currently predict travel duration, durability value or future sellers. Free cooperative supply is considered only with a nearby eligible supplier, then can be abandoned as stall penalties rise. Skill/resource feasibility is rechecked during execution; presence of a supplier does not guarantee an exposed resource or a successful route.

Automated coverage is listed in `DEBUG_VERIFICATION.md`. Live navigation, asynchronous deliveries, disappearance of villagers and inventory changes require in-game acceptance.
