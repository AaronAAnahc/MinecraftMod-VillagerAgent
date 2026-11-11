# Task methods and execution policy

## Interface and responsibilities

`TaskMethod<C>` is the common method interface: stable ID, remaining-cost estimate, start with an attempt UUID, tick with explicit progress/safe-switch evidence, and idempotent close. Estimates must not start work or spend inventory. Unavailable estimates are positive infinity; invalid/negative estimates are excluded. Methods may wait for external help without claiming that success is guaranteed.

`TaskMethodExecution<C>` owns selection, effective attempt time, progress, failure history, cooldown and cancellation order. It selects the lowest finite score, keeps one method active, and closes the old attempt before starting a replacement. A method's DONE returns control to its parent; actual task completion is checked against world/inventory state. Every new attempt gets a different UUID. The executor has no Minecraft dependency and is tested through the same interface used by the live adapters.

`ItemTaskCatalog` registers parameterized providers with output matching, a bound `TaskContract`, and a `TaskMethod` factory. `TaskContract` records remaining item inventory requirements (consumed ingredients versus retained tools), minimum capability levels, expected target output quantities, and practice effects. Output declarations discover candidates; only real inventory completes acquisition. Practice metadata describes the skill trained, not a promised final level or an exact batch XP total. `ResourceExtraction` and `CraftSkill` award XP directly; the planner does not execute `practice` metadata or discover training paths from it.

| Method | Inputs/gates | Output |
|---|---|---|
| `craft` | Unlocked recipe; existing inputs; required workstation | Recipe target |
| `smelt` | SMITHING >= 1; reachable loaded furnace/blast furnace, or none known (then a furnace is crafted and placed first); acquire missing ore/fuel, reuse matching batch/output; real processing and capacity-limited collection | Iron/gold ingots, one batch at a time |
| `craft_inputs` | Unlocked ordinary recipe; recursively acquire missing ingredients, then craft | Recipe target |
| `woodcutting` | WOODCUTTING ≥ 1; usable axe, acquired first if absent; reachable natural tree. Runs a `lumberjack_work` session when the villager can run that routine, else local `extract_item` | Requested log species |
| `mining` | MINING ≥ 1; correct pickaxe tier; exposed reachable source. Runs a `miner_work` session for a miner, else local `extract_item` | Requested 1.16.5 ore drop |
| `quarrying` | QUARRYING ≥ 1; pickaxe; exposed reachable stone. Runs a `miner_work` session for a miner, else local `extract_item` | Cobblestone |
| `farming` | FARMING ≥ 1; a remembered field (any chunk-memory farmland anchor), run as a `farm` session; without one, a reachable mature crop within the local survey | Requested crop |
| `request_supply` | Wildcard target; reachable village notice board; not a request's own item or alternative while serving that request; adopts the villager's existing request for the same need, or posts a free villager-owned request after a 60-tick board read (exact item with "or" alternatives, food points, or tool category for a missing work tool); deliveries arrive through `meet_trade` goals | Received target units |

Generic GATHERING is separate. `GatherSkill` checks target stock and moves to an optional target location; it relies on passive pickup and does not find/harvest berries, extract resources, or publish supply requests. It is not registered as an item-producing provider. Explicit `pickup_item` owns deliberate ground-item collection. Planting/tilling are not crop outputs by themselves. Resource acquisition is redirected to the villager's own work routine (`Produce.workRoutineFor`, log `acquire-redirect`): crops with a remembered field run a `farm` session, logs a `lumberjack_work` session, ores and stone a `miner_work` session, each as the method's child. When the session ends — or returns WAITING because nothing is left to do — the method is DONE (`acquire-redirect-end`) and the parent re-checks stock; a still-unmet objective exhausts the method and another is tried. A routine session does not target the requested item specifically (a mining session follows its own district plan). Growth is not predicted. Non-grid processing remains supported by `craft` with existing unlocked inputs; its missing inputs are recorded but not recursively acquired by `craft_inputs`.

The executor asks the catalog for all applicable providers and compares their costs. `craft_inputs` recursively acquires missing ingredients through the same catalog, connecting raw resource producers and their tool prerequisites. An ingredient that can only be made from the recipe's own output (`VillageProducible.onlyMadeFrom`, a hay bale for wheat) is used only from stock; `craft_inputs` is unavailable when such an ingredient is missing. `CraftingPlan.materialRequirements` resolves successive blockages on virtual inventories (up to 64 passes) so the initial cost is not just the first missing ingredient. Tag alternatives prefer branches supported by observed reachable raw sources while retaining priority for actual stock; this can prefer an available log species, but remains a bounded heuristic and does not guarantee that every feasible species or recipe wins. Source feasibility probes are cached for 40 ticks and invalidated after movement beyond two blocks. Recursive method estimates have depth 6 and 64 visits; execution has depth 8 and rejects an ancestor item before touching its session. Cycles such as log → axe → log are unavailable unless inventory or another method breaks the cycle. Estimates are not a shared reservation of supplier stock or tools across branches, so later revalidation can still reject a route. Recipe/tag selection and future tool sharing remain heuristic, so this is not exhaustive/global cost optimization.

Extraction and resource orders share `ResourceExtraction`: capability/tool checks, bounded loaded-source sensing, navigation, protected real block breaking, real loot, contact pickup, and practice XP on successful extraction. Higher-tier existing tools are accepted; Silk Touch is rejected when it would change the requested output. The baseline missing-tool path requests the catalog's minimum tool. Each mining request supplies its desired item; mining is not hard-coded separately for each villager or each order.

`AcquisitionTasks` holds root-owned sessions keyed by target item. They survive WAITING, scheduler changes and `TaskExecution` frame reconstruction, including acquisition children of restock. A changed desired quantity or parent attempt starts a fresh session. Closing a parent method recursively cancels its child acquisition sessions, including off-scheduler waits; village requests are villager-owned and survive (see Request ownership). Root removal/expiry/completion, satisfied inventory, dimension change, entity removal and world unload also close sessions. Root references and method attempts are runtime-only, not NBT data. After a restart, persisted objectives replan from real inventory.

## Contract consumption and execution boundaries

Contracts are bound to the current request/inventory. Item quantities are absolute inventory targets; output quantities describe the remaining demand. Already usable tools are omitted from missing-input requirements. `consumed` distinguishes ingredient/tool intent but is not a generic debit instruction: recipes consume materials, and block actions handle tool durability. Recipe unlocks, usable tool predicates, workstation reachability and world-interaction switches live in providers/executors rather than a complete declarative precondition list. Request contracts declare their target output but do not encode supplier eligibility or recipient inventory transfers.

The acquisition path checks contracts and scores methods; it does not convert every `VillagerSkill` into a provider. In particular, generic gathering, planting/tilling and skill training are not automatic prerequisite producers. All resource outputs use actual Minecraft 1.16.5 loot; iron/gold outputs are ore blocks, not later-version raw-metal items.

Equal finite scores retain provider iteration order for a fresh selection: `craft`, `craft_inputs`, extraction, smelting, `request_supply`. Only one method per acquisition session runs at a time. A villager has at most one live request per spec; further needs for the same spec join it.

Request-linked `supply_request` roots run the same executor. Every acquisition context under such a root carries `forbiddenRequests` (the request's item, its "or" alternatives and the chosen output), and `request_supply` is unavailable for those items, so responders produce rather than re-request; prerequisites of other items may still publish free requests. Resource-order bridges do not create or fulfill work orders.

During an active production child, the production method estimates no new cost; while acquiring a prerequisite, it uses that child's last recorded remaining estimate and estimates the other missing inputs. These values are refreshed at method reevaluation, not a continuous exact simulation of the entire future route.

## Score and clocks

```text
score = method-owned remaining cost (estimated travel/work)
      + failure count (capped at 3) * failure penalty
      + max(0, effective time since progress - grace) * time weight
```

`request_supply` estimates `4 + 0.05 × complete path length to a board-side standing cell`, plus 8 when the villager has its own production method for the item (`ItemTaskCatalog.canProduce`), so makers prefer making; it is 2 once its request is posted. Free supply therefore has real travel and waiting cost and does not starve feasible local work. A missing/unloaded board or lack of a complete route makes it unavailable. Its subtasks are post (60-tick board read, one immutable request for the shortage), await, receive (once a provider books the delivery, the method executes the meeting subtask, reporting RUNNING and not switchable; its root is committed in the scheduler) and verify (DONE if met; FAILED after retiring the request when an "or" alternative arrived, so crafting converts it; otherwise retire the consumed request and post again). Whenever the inventory covers the objective, `AcquisitionTasks` closes the session and the request is withdrawn, also while a delivery is pending. An open request counts as a live dependency, not a stall, and is not switchable: once posted it stays until it is delivered, it expires (failing the attempt), or the inventory covers the objective another way (for example the farm routine's harvest), when `AcquisitionTasks` closes the attempt and withdraws it. A cheaper method therefore never withdraws a posted request only to post it again. For food categories, `AcquisitionTasks` converts hunger points into whole target items for production while satisfaction is measured in points. See the HTN tree in [capabilities and economy](capabilities_crafting_and_economy.md#task-structure-htn).

The extraction provider computes its own score: prerequisite costs plus `0.05 × resource travel distance + 0.1 × missing quantity`. For crops with a remembered field, the distance is the straight distance to the nearest field anchor and no crop needs to be in sight. Logging selects loaded remembered forest chunks by complete routes to sampled surface standing cells; other extraction uses complete routes to sensed standing cells. Probes retain the existing 40-tick/two-block invalidation. These are score units based on path length, not money charged or exact route-time predictions. Active production children retain the existing zero-new-cost convention. The scheduler has no resource-specific distance rule.

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

Root WAITING uses the 200-tick scheduler cooldown, so observations/switches can occur later than these thresholds. Timing is game time, not wall time. Policy is centralized in `TaskMethodExecution.Policy.defaults()`; there are no configuration-file keys.

A method that returns DONE while the objective is still unmet has reached its own exit condition (for example a farm session with nothing left to harvest, collect, plant or till): `TaskMethodExecution.exhaustLast` puts it on the normal cooldown without a failure penalty, and the next selection tries another method (`exhausted:<id>` transition). A failed method or a lost precondition can bypass minimum run. A hard stall releases the method even if no alternative is available; its cooldown prevents immediate restart. Replacement and timeout require the method's safe-switch flag. Current adapters report safety after each server-thread tick, outside atomic inventory transfers. Completion clears that method's failure history.

## Progress evidence

- Crafting: committed recipe steps increment the child goal's runtime progress version; movement counts only when distance to a concrete destination improves.
- Request: requester stock of the target increases, or the request is open/booked (an open request counts as a live dependency and is safe to switch away from; the request stays posted).
- Extraction: a real block is broken, target stock increases, or distance to a concrete target improves. Child progress propagates to its parent acquisition.
- Supply: a worker takes responsibility, its relevant gathered stock increases, it approaches a concrete work target, or requester stock increases. Lease renewal or RUNNING alone does not count.

Recipe progress and received items remain in the world after cancellation. A new estimate uses the resulting inventory; no cancellation refunds consumed inputs or reverses a completed delivery.

## Request ownership and cleanup

Requests created by `request_supply` are villager-owned (`ownerAttempt` = the author's id) and tracked by `OwnRequests`: a request is never withdrawn because its method was closed or replaced. `VillageSignalBoard.postOwnedRequest` returns the author's existing OPEN/CLAIMED request for the same spec instead of posting a duplicate (`supply-request-merged`); a method starting while such a request exists adopts it (`supply-request-adopt`). Upkeep retires delivered and expired requests; an OPEN request whose waiting tasks are gone or whose objective was met another way is withdrawn by a `withdraw_request` goal that walks to the board (`request-withdrawn`). Attempt-owned messages (other `ownerAttempt` values) are still removed with their attempt by `removeOwned`. Work-order classes are compatibility data without a live supply executor.

`AcquisitionTasks.maintain` cleans removed, expired or satisfied objectives before root scheduling. Entity removal and world unload explicitly close sessions. Supplier delivery tasks reject canceled requests through their existing checks. Unloaded demand without further renewal expires. Logs use `task-method` with transition, active ID, score and stall ticks.

## Scope and verification

The executor is reusable; item acquisition discovers producers through a Java provider registry. Providers are scanned for matching outputs; this is not a precomputed reverse index or a JSON-configurable universal task graph. Other handwritten composite skills are not automatically converted to method catalogs, and no automatic skill-training planner is implemented. A level-zero producer is unavailable; profession claim supplies its baseline, and existing learning systems can raise capabilities. Estimates do not claim global optimality. Travel scoring uses complete paths within loaded, bounded sensing; unreachable/unloaded candidates have no straight-line fallback. Durability value, exact duration and future sellers are not predicted. Free deliveries revalidate actual stock, protected tools and receiving capacity before throwing. Skill/resource feasibility is rechecked during execution; presence of a supplier does not guarantee an exposed resource or a successful route.

Automated coverage is listed in `DEBUG_VERIFICATION.md`. Live navigation, asynchronous deliveries, disappearance of villagers and inventory changes require in-game acceptance.

Resource extraction retains failed blocks/chunks within its execution. Missing routes immediately reject the current target and return to selection; three distinct rejected targets return FAIL to the method executor, allowing another acquisition method. No remaining reachable remembered target also fails. This is separate from later executor cooldown/retry policy.
