# Cooperative Village Supply Implementation Plan

Date: 2026-11-08

Status: Proposed implementation. This document describes intended changes, not current runtime behavior. No implementation is included in this planning change.

## Objective

Replace paid villager-to-villager trade with free, demand-driven cooperation through the village notice board. Villagers retain personal inventories, respond according to their stock and production capabilities, and physically deliver items to other villagers.

Production is voluntary and non-exclusive. Multiple villagers may independently work toward the same request. A production task references a request without claiming it. Only an imminent delivery reserves part of the remaining demand.

## Confirmed requirements

- A villager with an unmet need visits the notice board and publishes a request.
- Villagers periodically visit and inspect their village's board.
- A villager with suitable shareable stock may initiate a free delivery.
- A villager able to produce a requested item may create a task even when ingredients are missing.
- Manufactured items such as tools use crafting tasks. Logs and ores use the corresponding resource work/acquisition tasks. Missing prerequisites use acquisition subtasks.
- The responding villager maintains a one-way link from its task to the request. Production does not exclusively assign the request to that villager.
- Several villagers may produce for the same request concurrently.
- When a villager discovers that a request has disappeared or is no longer active, it cancels that request's production task and its associated subtasks.
- Requests may describe a category, such as `food`, rather than one exact item. Farmers, butchers, or any other villagers with matching food may supply it.
- Each profession protects one best available tool for each tool category it actually requires. Other tools, including lower-tier duplicates, may be shared with other professions.
- Goods are free: no price, payment, change, or affordability check controls cooperation between villagers.

## Scope and proposed defaults

The first version covers demand publication, observation, production, prerequisite acquisition, free delivery, and cancellation. Public warehouses, construction approval, village-wide production quotas, and a new economic simulation are outside this change.

The following are implementation proposals rather than additional confirmed requirements:

| Decision | Proposed first-version behavior |
|---|---|
| Player trading | Preserve the existing paid player-trade path |
| Food quantity | Express generic `food` demand in hunger-restoration points; exact food requests remain item counts |
| Generic tools | Support `hoe`, `pickaxe`, and other configured tool categories alongside exact item requests |
| Tool ranking | Compare an explicit material/tier rank, then remaining durability, with a stable tie-breaker; enchantment valuation is deferred |
| Request memory | Keep structured request references, not natural-language or LLM memory |
| Restart | Rebuild transient requests and responses from live needs and inventory, consistent with the current runtime-only market model |
| Inventory protection | Introduce no additional permanent food/material reserve in this change; protect required tools and quantities committed to active tasks or deliveries |

Food units and tool ranking should be centralized policies so they can be revised without changing task execution. Generic food matching should use an explicit edible-item policy, including whether harmful foods are excluded.

## Current implementation and reusable components

The current workspace, including uncommitted changes, is the implementation baseline. Existing user changes must be preserved.

| Component | Current behavior and planned use |
|---|---|
| `VillagerEconomySystem` | Queues physical board visits, publishes demands/offers, and creates paid production opportunities; replace paid eligibility and matching for villager cooperation |
| `VillageSignalBoard` / `VillageMarkets` | Hold runtime messages scoped to a village; extend request matching and delivery accounting |
| `VillageNoticeBoards` | Handles physical access and travel to the board; reuse |
| `PaidProductionSkill` | Links a `produce_order` goal to a request, acquires output, and returns to advertise it; replace this behavior with linked production followed by delivery |
| `ItemTaskCatalog` / `AcquisitionMethods` | Discover crafting, extraction, and smelting methods and their prerequisites; adapt selection and waiting to unpaid supply |
| `TaskExecution` / `ActionLifecycle` | Execute nested tasks and clean up actions; use for request-owned cancellation |
| `TradeCoordinator` / `TradeInventory` | Coordinate meetings and physical item handoff; adapt to one-way transfers while preserving inventory validation |
| Notice-board view and packets | Display market messages; update for category requests, remaining quantities, and free deliveries |

Current production eligibility checks prices and simulated affordability, and tracks a single `productionWorker`. Current ordinary matching is initiated by a requester reading the board. Production completion returns to the board to advertise stock. These behaviors must change to permit independent responders and remembered-request delivery.

The existing `WorkOrderBook` is compatibility data, not the target implementation for the new live flow. Do not revive its old executor as a second supply system.

## Request and response model

### Shared request

A request needs a stable ID, village identity, requester ID, matching specification, quantity unit, requested amount, delivered amount, active delivery reservations, expiry, and lifecycle state.

The matching specification distinguishes exact items from configured categories. An exact `minecraft:iron_pickaxe` request must not silently accept a wooden pickaxe. A generic `pickaxe` request may accept any eligible pickaxe.

The request must not contain an exclusive production owner. Multiple local response tasks can reference its ID without reducing its available quantity.

### Local response task

A response stores the request ID, selected concrete output, planned quantity, and ownership identity for its descendants. Reuse existing goal/task fields where their semantics fit rather than adding a parallel scheduler.

Keep at most one active response per villager per request. Repeated board inspection must not duplicate that villager's work. Different villagers remain free to respond independently.

For a category request, choose a concrete output supported by actual stock, recipes, or registered work capabilities. A profession label alone is not evidence that a particular production action exists.

### Delivery reservation

Immediately before arranging a meeting, atomically reserve a bounded quantity against both the remaining request and the provider's shareable stock. Production creates no such reservation.

The reservation records concrete stacks, quantities, and request-unit credit. Partial fulfillment is supported. Category quantities must convert deterministically between whole items and request units; permit only the unavoidable final-item excess when a food item exceeds the last remaining hunger point requirement.

Revalidate stock, protected tools, participant availability, and receiving capacity before throwing. Reservations prevent competing providers from all committing to an already-covered request.

## Execution flow

1. A need produces a board visit and a deduplicated request based on the actual remaining shortage.
2. A board visitor reads local requests and first checks shareable inventory.
3. If suitable stock exists, it attempts to reserve a delivery and arrange a meeting.
4. Otherwise, it checks production capability separately from current ingredient availability.
5. It creates a request-linked crafting or resource acquisition/work task. The task may create prerequisite acquisition children and publish missing-input requests.
6. While prerequisites are unavailable, retain the work in a waiting state with bounded retries. Do not treat missing market stock as proof that the villager cannot craft the output.
7. On obtaining output, consult the remembered request reference, verify its current status, and attempt delivery without requiring another visit solely to advertise the finished item.
8. Credit only items actually received. Close the request when its requirement is satisfied; otherwise retain the remaining shortage.
9. Other responders discover completion on their next validity check and cancel related unfinished work.

The task association can be retained by a thin response context around existing execution. It must not restore an exclusive `produce_order` claim or require an unrelated daily crafting task to infer who wanted its output.

## Observation and cancellation

Discover new requests only through physical board access. After reading a request, maintain its ID as structured local memory. Proposed validity checks occur when resuming linked work, before starting another production step, and before arranging delivery. These checks validate a known request; they must not enumerate unseen requests remotely.

When the known request is resolved, canceled, expired, or definitively removed:

- Cancel the linked root and its owned descendant tasks.
- Release navigation, work leases, uncommitted delivery reservations, and owned prerequisite requests.
- Preserve unrelated roots and their requests. Cancel by ownership identity, never by item type alone.
- Keep completed output and intermediate products in real inventories. Do not refund consumed ingredients or roll back completed world operations.
- If a shared dependency is introduced later, release only this response's reference; cancel it only when no owners remain. Prefer separately owned dependencies in the first version.

Temporary inability to reach the board is not proof that a known request was canceled. Distinguish unavailable observation from confirmed request invalidation.

For deliveries already thrown, preserve physical handoff cleanup and conservation rules. Do not refund, replay, or cancel away real item entities. Record actual receipts and release or retire the remaining reservation exactly once. A partially completed delivery leaves a remaining need rather than falsely completing the request.

## Tool retention and shareable stock

Replace fixed-item reserve rules for profession tools with required tool categories. A farmer protects its best hoe; a miner protects its best pickaxe. Other professions use an explicit mapping derived from their supported work requirements.

Protect exactly one actual tool stack per required category. Preserve item NBT and damage when identifying it. Recompute the selection when inventory changes and before delivery commits.

Examples:

| Inventory and profession | Protected | Shareable |
|---|---|---|
| Farmer: diamond hoe, iron hoe, wooden hoe | Diamond hoe | Iron and wooden hoes |
| Miner: two iron pickaxes, one stone pickaxe | Best remaining-durability iron pickaxe | Other iron pickaxe and stone pickaxe |
| Farmer: hoe and pickaxe | Best hoe | Pickaxe |
| Miner: one wooden pickaxe | Wooden pickaxe | No pickaxe until another suitable tool is available |

A retained lower-tier tool satisfies a generic tool requirement. Receiving a better tool changes which stack is protected and makes the old one shareable. Do not introduce automatic upgrade demands in this change.

Keep permanent profession protection separate from temporary task-input and delivery reservations. A second task must not give away ingredients or goods already committed to an active operation.

## Acquisition and scheduling changes

Remove barter cost and affordability from cooperative acquisition. Free transfer still has travel, waiting, and production time, so it must not receive an unconditional zero-cost preference that starves feasible local work.

Reuse existing scheduling and method-selection infrastructure with comparable effort/time estimates. Preserve bounded reconsideration, cooldowns, progress tracking, and urgent-need preemption.

Ensure missing ingredients can create request-backed waiting dependencies even when no current supplier exists. Retain dependency cycle/depth limits: a missing pickaxe needed to obtain ore for that same pickaxe must wait or find an external route, not recursively generate infinite tasks.

Recipe batches may produce surplus. Count output using real inventories, retain surplus, and revalidate the request before further batches. Concurrent production may also create legitimate surplus; this is an accepted consequence of non-exclusive participation.

## Implementation sequence

### Phase 1: Request policies and accounting

- Define exact/category matching and quantity-unit conversion.
- Implement required-tool selection and shareable-stock calculation independent of prices.
- Add partial fulfillment and delivery-only reservation accounting.
- Remove exclusive production ownership from the new path.

### Phase 2: Board observation and linked work

- Adapt request publication and periodic board visits.
- Allow providers to initiate fulfillment from stock.
- Generate deduplicated request-linked crafting/resource tasks without an exclusive claim.
- Add known-request validation and ownership-scoped cancellation.

### Phase 3: Missing prerequisites

- Adapt acquisition methods to request free inputs and wait for future supply.
- Connect crafting, extraction, and smelting to those dependencies.
- Preserve waiting tasks through ordinary scheduling interruptions while cleaning up invalidated owners.

### Phase 4: Free physical delivery

- Introduce one-way transfer terms without constructing a zero-price barter quote.
- Reuse participant booking, meeting navigation, exact-stack validation, spawning, and contact pickup.
- Account for partial receipts, interruption, timeout, unload, and released reservations.
- Remove paid gates from all villager cooperation entry points while keeping the player-trade path isolated.

### Phase 5: UI, integration, and documentation

- Display exact/category demand, units, remaining quantity, and delivery state.
- Do not display a unique assigned producer for non-exclusive production.
- Update diagnostics to expose request IDs, linked tasks, cancellation reasons, and actual delivered quantities.
- Update the owning current-behavior documents after implementation: capabilities/economy, execution pipeline, task methods, failure recovery, configuration, code index, and verification scope.

## Verification plan

Follow `DEBUG_VERIFICATION.md` and use the repository's existing checks and toolchain. Compilation and pure Java checks are necessary but do not establish Minecraft navigation or pickup correctness.

### Focused automated checks

- Exact versus category matching, incompatible items, and food-unit conversion.
- One protected best tool per required category, durability ties, NBT preservation, and protection changes after an upgrade.
- Multiple producers for one request, but no duplicate response by the same villager.
- Atomic delivery reservations, partial quantities, release, and receipt accounting without double credit.
- Cancellation of the correct descendants and owned requests while unrelated work survives.
- Missing-input waiting, bounded cyclic dependencies, expired requests, and transient-state rebuild after restart.
- Inventory and ground-item conservation for free transfer, canceled spawns, partial pickup, and interrupted meetings.
- Existing paid player trades remain covered if preserved.

### Minecraft acceptance scenarios

1. A farmer requests an iron hoe. A smith lacking ingots reads the request, creates crafting work, obtains or requests inputs, crafts the hoe, and delivers it without payment.
2. Two smiths respond to that same request. One delivers first; the other cancels remaining linked work when it discovers completion. Already produced stock remains intact.
3. Publish `food`. A farmer and butcher offer different matching foods; actual deliveries satisfy the shared quantity without duplicate credit.
4. A miner receives a better pickaxe and shares its old one. Its only protected pickaxe must never be given away, including when inventory changes during an approach.
5. Cancel a parent tool request while its material request is pending. Only its related task tree and owned material requests disappear.
6. Test missing boards, unreachable paths, full inventories, death, combat interruption, chunk unload, request expiry, and restart. Verify bounded waiting and no stale participant locks.
7. Test a complete food -> mining -> ore -> smelting -> tool supply loop with no payments. Disable or control smith daily iron/coal grants in the test setup so they do not hide missing supply links.

## Estimate and completion criteria

Planning estimate: approximately 6-9 developer working days for implementation and validation by one developer familiar with this codebase. This is not a delivery commitment. The main uncertainty is integration between waiting acquisition dependencies, concurrent responses, and interrupted physical delivery. Persistent exact task recovery across restarts would expand the scope.

The change is complete when villagers can publish and fulfill exact/category requests without payment; independently produce for the same demand; cancel only obsolete request-owned work; protect their one best required tool; and complete physical handoffs without duplicated items, duplicate request credit, or abandoned reservations. Automated checks and the Minecraft scenarios must be reported separately, with any unexecuted acceptance cases explicitly listed.
