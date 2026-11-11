# Village Ecology Loop Refactoring Plan

Date: 2026-10-28.

Status: design discussion and implementation plan, not yet implemented. This document records the user's proposal, confirmed choices, engineering recommendations, and open questions; it is not evidence that the described functionality is complete. The current implementation facts remain governed by the source code and the topic documents under `docs/`. This round only adds this document; it does not change runtime logic.

## 1. Goals and confirmed directions

The goal is to let villagers form a sustainable living loop through real production, consumption, and paid exchange, while keeping actions observable, tasks interruptible, and resources and currency auditable.

The user proposed and requested inclusion in the plan:

- Cut down trees outside the village/building range, support chained logs, and replant saplings when available. Prefer the vanilla convenient-village check or convenient natural-tree recognition, and avoid building a complex territory system first.
- Remove free resource-collaboration orders and replace them with paid production orders, where a producer produces and delivers after seeing demand.
- Prevent multiple workers from producing duplicate work for the same demand, so that only one person ends up with the order.
- Actions such as breaking blocks, tilling, and crafting should have duration and cost, avoiding instant interactions.
- Eating as a task; when food is missing, recover through the existing acquisition system, then continue eating.
- Discuss whether Method should be extended to all Skills, rather than directly deciding to wrap everything in a layer.

The user has explicitly chosen: ore and stone are produced continuously through a mine workbench, consuming tools, time, and food. This is abstract in-game extraction, not refreshing natural ores back into the world.

Not yet decided: the public-fund/external-trade system for emeralds; mine output and rates; specific action durations. The recommended parameters in this document are not confirmed requirements.

## 2. Current implementation baseline

Entry points:

- [Project entry](docs/README.md), [code index](docs/CODE_INDEX.md), [verification scope](docs/DEBUG_VERIFICATION.md).
- [Task methods](docs/task_methods.md), [economy and crafting](docs/capabilities_crafting_and_economy.md).
- `VillageMarkets` maintains the market affiliation of loaded villagers: bell memory first, then anchors such as beds and job sites, with nearby centers grouped within 64 blocks; this is market affiliation, not a village plot boundary.
- `WorldStructureIndex.getAt(BlockPos)` can query recognized buildings; current recognition depends on scanning and bed seeds, so a failed query does not prove something is not a building.
- `ResourceSensor` currently filters logs through nearby non-persistent leaves, and has not implemented whole-tree recognition, replanting, or explicit building exclusion.
- `WorkOrderBook`/`ResourceWorkSystem`/`ResourceWorkSkill` form the free collection-and-delivery chain; market trading separately has real emerald settlement and mutual appointments.
- `VillagerNeedsSystem` eats food directly from the backpack; eating is not yet a scheduled task.
- `BlockInteractionAction.tickBreak` already has tool-related break duration, cracks, and periodic swinging; it cannot be described as fully instant.
- `FarmActionSkill` calls the real farming operation after arriving; workbench grid crafting already waits 20 ticks per step, and old workstation production uses 200 ticks. The presentation and rhythm need unifying, not another set of timers.

## 3. Village range and natural-tree detection

### 3.1 Verified vanilla interfaces

Verified against the project-local Forge 1.16.5 / 36.2.42 official mapped jar, using JDK 11 `javap` to inspect method bodies:

```java
world.isVillage(pos);
world.isCloseToVillage(pos, sectionDistance);
world.sectionsToVillage(SectionPos.of(pos));
```

`isVillage(BlockPos)` calls `isCloseToVillage(pos, 1)`, which compares the section distance in the POI distance map. The parameter is not a block radius; in this version a distance parameter above 6 returns false directly, so 64 should not be filled in casually.

This is a dynamic POI village check, not the exact outline of a generated village, and it cannot recognize all player buildings outside a village. The existing 64-block grouping of market centers cannot substitute for this check. There is no need to implement a complex village polygon for the first version of logging.

### 3.2 Recommended first-version detection

A candidate tree satisfies: not inside village range per the vanilla check, not inside the protected area of a recognized building, and the tree shape matches natural-tree features.

Recommended natural-tree features: the trunk base relation to growable ground, restricted connected logs, adjacent natural leaves, species match, and reasonable height/volume. Keep the existing leaf check as a quick prefilter, but do not treat "there is a leaf beside it" as a complete proof.

- Both the tree root point and the planned log set are checked against exclusion conditions; re-check once more before each actual break.
- Building detection should cover the building boundary/shell, not only the standing indoor space. Reuse the building bounding range plus a small buffer; the exact range is verified during implementation.
- Do not move the protection boundary because a villager walked outside; do not use the lumberjack's current position as the territory center.
- Unloaded regions are not force-loaded; skip the candidate or wait when data is insufficient.
- A naturally grown player tree farm may match natural-tree features, and structure recognition may also miss. We cannot promise 100% separation of "wild" from "player-planted". An explicit no-cut zone can be added later, but it is not a first-version hard dependency.

The preferred scheme is the three-check combination. If actual testing shows the vanilla village range is too wide and renders surrounding tree farms unusable, then discuss switching to "natural-tree recognition + building exclusion" without silently changing the user's requirement.

### 3.3 Chained logging and replanting

Chaining means one task recognizes and processes one tree, not instantly deleting all connected logs.

1. Search for a legal tree, forming a tree plan with node count, height, and horizontal-extension limits.
2. Atomically reserve the tree root/tree resource identifier to prevent workers from different orders processing the same tree simultaneously.
3. Break blocks one by one using the existing break action, preserving real drops, tool wear, event cancellation, and action presentation.
4. Do not expand infinitely across touching canopies to the whole forest; adjacent trees sharing leaves still need separation. Stop expanding when connecting to a building or crossing the protection boundary.
5. Chained logs must still satisfy explicit distance/standing rules; remote breaking of the tree top is not allowed by default. When a tall tree cannot be completed, exit in segments without faking reachability.
6. Collect logs and watch for saplings dropped by leaves afterwards. When saplings, legal soil, and space are available, perform a timed replant action and deduct real saplings.
7. When saplings do not drop immediately, record a time-limited replant task without blocking order delivery indefinitely; double-width trees follow the corresponding planting layout and quantity.
8. Release the reservation after interruption; already-cut logs and consumed durability are not rolled back or regenerated.

Replanting does not guarantee success for every tree, so monitor available saplings and tree count; regeneration failure should surface as supply shortage, not implicitly gift saplings.

## 4. Paid production orders replacing free collaboration

### 4.1 Take the order first, then produce

Do not adopt "everyone cuts first, whoever gathers first takes the order". Recommended state machine:

```text
publish and lock budget → atomic claim → prepare/produce → stock ready
    → reserve delivery → atomic settlement → complete
```

The order records the buyer, village/dimension, item and matching conditions, undelivered quantity, fixed price, budget, producer, lease, progress, deadline, and status. Order demand deducts existing inventory, in-transit procurement, and committed production from the real gap.

- A worker first checks capability, basic tool/material path, delivery distance, and capacity, then atomically claims on the server thread.
- First version: one order allows only one producer; when a large order needs splitting, explicitly assign non-overlapping quantities rather than letting multiple people claim full quantities.
- Only after a successful claim does order-driven logging/mining begin. Proactive stockpiling may exist but must be accounted separately from claimed orders.
- While a worker holds an order, use tree/workbench resource reservation to avoid different orders fighting over the same production point.
- Production claims must not occupy `TradeAppointments` meeting slots, otherwise the buyer and worker cannot conduct other trades during the long production period.
- Only create a meeting appointment when goods are ready and both sides have a reachable delivery opportunity.
- The safe action boundary allows attending appointments; farming must not continuously occupy until a trade inevitably times out.

### 4.2 Payment, cancellation, and persistence

Lock the real budget when publishing an order; the same emerald cannot be promised to multiple orders at once. Use a persistent escrow ledger and ensure refund/payment after save happens exactly once; clarify the conservation relationship between physical items and escrow records before implementation.

- On delivery, validate item, quantity, capacity, and payment on an inventory snapshot, then commit; failure deducts neither payment nor goods.
- Allow explicit partial delivery; payment is computed from the cumulative delivered quantity to avoid overpayment from rounding each small batch up.
- Delivered parts are settled, undelivered parts are refunded; do not pay the full reward just because production occurred.
- A buyer canceling freely after claim causes production loss. First version recommends freezing claimed quantities and releasing on expiry/fault; do not treat simple hunger decay as an automatic withdrawal reason.
- A worker dying, unloading, being stalled for a long time, or losing capability has an explicit release flow. After expiry and reassignment, the old producer cannot deliver with an old token or keep producing on behalf of the order.
- Lease renewal must be tied to progress, not indefinite occupation by being alive every tick. Sleep/eat may enter a bounded pause state, distinct from permanent disconnection.
- Avoid duplicate production in normal cases; after fault/timeout reassignment we can only limit the duplicate amount, not promise zero physical-world waste. Surplus items are kept as inventory and offset later production.
- Restore orders, escrow funds, and delivered quantities on restart; runtime meeting appointments are rebuilt, and invalid entity references are not persisted.

### 4.3 Scope of removing the old mechanism

Remove the `cooperative_supply` method registration, the free request/assignment/delivery calls, the bypass that generates free collection orders in the economy scan, and related descriptions. All rule-driven external supply goes through paid orders.

The claim/lease idea of `WorkOrderBook` can be reused, but the old free-order semantics must exit, not merely be renamed. `ResourceExtraction` and other actual collection capabilities continue to be reused.

Free orders in old saves are canceled, releasing old `fulfill_order` root tasks; demand regenerates paid orders based on inventory. Old orders have no escrow funds, so when migrating, do not issue refunds or fabricate rewards. Migration must be versioned and re-runnable.

## 5. Where emeralds come from and how the loop sustains

Paid orders let workers earn emeralds by selling output. Example money flow: farmer buys tools → craftsman buys ore/wood → miner and lumberjack buy food → farmer regains currency.

This is only a feasible path, not an automatic guarantee. A closed-village market can keep total currency constant without issuing new money per labor; but it needs sustained purchasing demand, enough initial liquidity, and reasonable distribution.

Main risks:

- When everyone lacks money, no one can publish a budgeted order, nor earn the first income by taking orders.
- Food consumption is continuous while tool purchases are intermittent; funds may concentrate on farmers; others with unsold inventory still lack food.
- Villagers without production income need explicit support or external help, otherwise we cannot promise sustained living for everyone.
- Workers need to buy food/tools first to produce, creating a startup-capital constraint. Order payment itself does not solve startup capital.
- If whole-tree time/durability far exceeds the quoted reward, workers keep losing; quotes must account for consumables, food, and delivery cost.
- Small transactions are limited by integer emeralds; use reasonable batch quoting to avoid independently rounding up every log.

Institutional schemes awaiting the user's decision:

| Scheme | Fund source and reflux | Limitations |
| --- | --- | --- |
| Pure villager market | Initial currency conserved, reflux via residents buying from each other | Cannot rely on mechanisms alone to guarantee no one is poor; needs explicit employment and demand balance |
| Village public fund | Limited public budget buys public goods/pays public labor, recovered via tax | Must account the budget; subsidies cannot default to unlimited minting |
| External caravan | Sell surplus products externally for currency, imports create currency outflow | Must limit external demand and price to avoid unlimited purchasing becoming a money printer |

Recommend comparing "limited public budget + limited external trade" against the pure internal market over the long term. No choice has been received yet; do not write public funds or caravans as approved implementation items. Initial currency or external input is a boundary condition; we cannot claim a fully closed system creates purchasing power from nothing.

## 6. Mine workbench: the confirmed sustainable supply method

Register a controlled production method at the mine workbench, consuming work time, tool durability, and food per recipe batch, producing stone/ore. It can accept paid production orders; output is driven by orders and inventory targets.

- Recommend fixed recipes and configurable rates; the first version does not use hard-to-audit random windfall mechanics.
- Maintain 1.16.5 item semantics, e.g. iron ore; whether smelting needs fuel, a smelting station, and independent time must be defined together with the processing chain, not assuming ore equals ingot.
- The workbench has limited concurrent slots with atomic reservation; progress accumulates only when someone is present, able to work, and inputs are satisfied.
- Validate food, available tools, and receiving capacity before use; re-validate and commit inputs/outputs when a batch completes, forbidding consuming inputs first and then losing output.
- Tool durability is deducted per actual batch; tool breakage triggers the existing acquisition task. "Tool exists" cannot substitute for permanent no-durability-loss.
- Food is a user-specified production input. Its accounting with personal eating must be clarified: the recommended mine-recipe food cost represents extra work supply, with personal hunger existing as usual, recorded separately; if merging is desired, let the work raise hunger consumption to avoid deducting the same food twice. Specific rules are pending.
- First version: no offline output, no unlimited time compensation for unloaded chunks; interruption does not grant a complete batch, and committed batches are not re-executed.
- Do not auto-restore world ore blocks or overwrite player buildings; output belongs to explicit workbench rules.
- First batch of output is recommended to converge to cobblestone, coal, and iron ore. Whether to include gold, diamond, or emerald is decided separately by the user; emerald output in particular directly changes the currency system and must not be opened by default.
- The alternative route for wooden/stone basic tools must be reachable, preventing "no pickaxe → no ore → cannot make a pickaxe". Facilities such as workbenches still need an explicit initialization or construction path.

The acceptance requirement is input-limited renewable production, not unlimited free refresh. Workbench capacity, food rate, durability, and output rate jointly determine the village scale ceiling.

## 7. Connecting the eating task to the existing system

Adopt the user's scheme: the needs system only updates hunger and maintains an eating goal, stopping direct background food deduction.

```text
eat → choose an edible from the backpack → eating action → deduct food and restore hunger
  └─ no food → acquire_food → choose a specific food → acquire_item → return to eat
```

- `EatSkill` uses the existing composite task tree to hold the acquisition sub-task; do not generate an extra identical root task on every failure.
- Food is a category need; choose a specific item by nutrition, price, existing inventory, and reachable supply, then reuse `acquire_item`. Do not interpret "no bread" as "all food unavailable".
- `acquire_food` may have a food-selection strategy; `acquire_item` still handles buying/crafting methods. When hunger is urgent, prioritize food actually reachable, not a cheap but distant route.
- Keep personal food rations and do not list items already reserved for eating; carrots that are also seeds need a sowing share reserved.
- One eating decides whether to continue by expected recovery, with start/end thresholds to prevent oscillation; the hunger-urgent priority and interruption points must be explicit.
- No goods, no money, no recipe return an observable waiting reason; event wake-up and backoff retry; cannot keep generating more same-kind tasks by infinite recursion of acquiring food.
- This connection can close the behavior chain, but cannot by itself solve payment ability, food production shortage, or transport failure; it must be accepted together with the economic system.
- Container return, edibility, food taken by others, trade reservation, and post-cancellation release are all re-validated at the commit point.

## 8. Action duration and presentation

Unify the operation phases: approach → prepare/sustain → commit effect → finish. Animation and duration belong to the action execution layer, not the Method selection layer.

- Breaking keeps the existing tool speed, swinging, and cracks; do not turn chained logging into batch instant deletion.
- Tilling/sowing/harvesting add small sustained-action states; on completion, re-validate and change the block, consuming seeds or durability.
- Crafting keeps real work waiting and unifies the rhythm and hand/facing/sound feedback across paths. The current 20-tick vs 200-tick difference needs redesign per recipe/batch, avoiding blindly stacking waits.
- Eating adds a held item, eating duration, and feedback; deduct food only once on completion.
- The server decides progress and commit; the client only presents; execute across multiple ticks, forbidding blocking sleep.
- Work interruption does not commit unfinished effects; on recovery, clarify whether to keep or redo the current action, and never complete twice.
- Multi-step crafting/farming keeps completed parts and allows emergency needs and trade handoff at safe step boundaries.
- Configure durations and separately accept readability and throughput; do not make basic production lower than consumption just to look slow.

## 9. Engineering boundary between Skill and Method

Recommend keeping three responsibilities: Goal expresses intent; Method expresses alternative schemes to complete the same intent; Skill/Action performs concrete work and reports progress.

- All Skills unify execution, cancellation, cleanup, completion evidence, and progress conventions.
- Use the Method selector only when alternative routes, common-precondition solving, or output discovery needs exist.
- For acquiring logs, "logging" can be a Method; that Method calls the logging Skill. Even if there is only one acquisition route today, a candidate set of length one is allowed; purchase and other routes can be added later.
- The harvest/plant/till steps of the farming Skill are continuous steps, not mutually exclusive alternative methods.
- Directly execute one till, movement, or eating action; do not artificially wrap a single-element scorer.
- Reuse execution context and lifecycle when unifying observability is needed; do not recursively wrap every Skill just for uniform appearance.
- Do not stack three sets of mutually contradictory stall clocks (root goal, method, action) for the same method; define waiting, working, safe-switch, and progress evidence separately.

Recommended conclusion: allow single-method registration, avoiding forcing every Skill to do Method selection. Use "whether there is an independent strategy change" as the boundary, not "whether the candidate count happens to exceed one".

## 10. Implementation order and code landing points

1. Clarify the undecided economic system, production food accounting, and recipe scope; unify inventory transactions and input/output units.
2. Refactor paid production orders: `VillageSignalBoard`, `VillagerEconomySystem`, `TradeCoordinator`; split production claim and delivery meeting, complete escrow/persistence and old free-order migration.
3. Connect `EatSkill`/food selection, `VillagerNeedsSystem`, `GoalScheduler`, and safe interruption points; reserve personal food ration/seed inventory.
4. Add village and building exclusion, tree planning, reservation, per-block collection, and replanting on `ResourceSensor`/`ResourceExtraction`.
5. Add mine production to the workbench/output-method catalog, sharing skills with paid orders and personal acquisition tasks.
6. Unify `FarmActionSkill`, `CraftSkill`, breaking, and eating action timing; complete player-observable presentation.
7. Run long-term closed-loop verification before expanding professions and output, updating the corresponding `docs/` topic documents as implementation changes.

Removing free collaboration must be delivered in the same batch as a usable paid production chain, to avoid an intermediate version cutting off all external supply. Class names can be adjusted; responsibilities and acceptance constraints take priority.

## 11. Verification and stability standards

Follow the [verification document](docs/DEBUG_VERIFICATION.md), distinguishing pure Java rule tests from actual Minecraft acceptance. This plan has not executed the following acceptance.

- The same order claimed by multiple workers in the same tick: only one full-quantity claim succeeds; different orders can run concurrently but cannot reserve the same tree/slot.
- After restart, unload, death, cancellation, timeout, or partial delivery, currency and items do not duplicate or appear from nowhere; a late old holder cannot settle.
- Inventory, ordered quantity, and claimed production for the same demand stay consistent; no cross-village/dimension order mixing.
- Verify house logs, in-village trees, out-of-village natural trees, trees next to buildings, adjacent canopies, large trees, and double-width trees separately.
- Verify mine food/durability/time/output conservation, full backpack, slot competition, stoppage, and restart; unconfigured ores cannot be produced.
- Verify eating with backpack food, no food but money, no money, out of stock, alternative food, interruption recovery, and that sowing reserves are not eaten.
- Verify action effects only appear at the commit point; swinging/held item/cracks are visible; pause or reconnect does not re-commit.
- Small village with LLM disabled, run for several consecutive game days; record per-person hunger-below-threshold duration, edible inventory, currency distribution, order waiting, net output, failure retries, and work time distribution.
- Disturbances include food shortage, key-worker stoppage, tool breakage, full inventory, and road blockage; observe recovery time rather than only whether they are still moving.
- Expect reasonable production-consumption-rest cycles with allowed daily repetition; must not stay long-term in no-progress retries, hungry but not seeking food, repeated missed appointments, or demandless overproduction.

## 12. Pending confirmation list

1. Do emeralds use a pure villager market, public funds, external trade, or a combination? Asked this round, no choice received yet.
2. Is mine food an extra recipe input, or does work increase personal hunger? The two must not be mixed in accounting.
3. Mine first-batch output scope and whether high-value ores are opened; do not include emeralds by default.
4. Does tall-tree chaining require each block to be actually reachable? Current recommendation requires reachability, keeping unfinished tree segments, and not defaulting to remote breaking.

This round has verified the vanilla village interfaces and related action method bodies. Adding a document does not equal completed implementation, nor does it prove the long-term ecology is stable.
