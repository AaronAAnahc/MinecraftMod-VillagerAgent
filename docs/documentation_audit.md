# System documentation audit

Reviewed: 2026-10-27 against the current working tree, including uncommitted implementation changes. This audit covers the active system overview, method selection, resource work, capabilities, workstation setup, operating instructions, code index and verification guidance. Archived documents and presentation materials are outside this audit. It is a documentation/source review, not a new runtime acceptance test.

## Claims corrected against implementation

| Documentation issue | Current implementation and evidence | Owning document |
|---|---|---|
| Method selection described as entirely handwritten with no search | `ItemTaskCatalog.discover`, `AcquisitionMethods.Produce` and `TaskMethodExecution.tick` implement producer discovery, recursive prerequisites and scored selection; supply delivery steps remain handwritten | [Task methods](task_methods.md), [overview](execution_pipeline_and_resource_orders.md) |
| Order cancellation described as an unused enum | `AcquisitionMethods.Owned.close` calls `WorkOrderBook.cancelOwned`; active owned orders become CANCELLED and release workers | [Orders](execution_pipeline_and_resource_orders.md) |
| All orders described as merged by requester/item, lasting 24000 ticks and surviving reload | `requestOwned` includes owner identity in deduplication and uses a 600-tick demand expiry; `WorkOrderSavedData.save` excludes owned orders | [Orders](execution_pipeline_and_resource_orders.md) |
| All supplier leases described as acquired only when scheduled | `ResourceWorkSystem.offerWork` claims before adding the goal; execution renews or attempts to claim again | [Walkthrough](project_walkthrough.md) |
| Resource professions described as not using profession POIs | `Workstations` registers custom POIs/professions and `FindJobSiteSkill` drives the normal vanilla assignment adapter | [Capabilities](capabilities_crafting_and_economy.md), [workstations](workstations.md) |
| Mod described as a server-only system | Decisions execute on the server, but the mod also registers blocks/items and contains client presentation/assets | [Walkthrough](project_walkthrough.md), [operation](configuration_and_operation.md) |
| Removed extraction log tags listed as current diagnostics | The shared extractor uses `resource-scan`, task/child progress and `pickup-collected`; supplier transfer retains `order-delivery`. Current selection uses `task-method` | [Verification](DEBUG_VERIFICATION.md) |
| Source/test inventory omitted new modules | The directory inventory and test list now enumerate current Java files, including contracts, discovery, extraction and workstation registration | [Code index](CODE_INDEX.md) |

## Implemented behavior that needed explanation

- Contract input quantities are inventory targets, not automatic consumption instructions. Existing usable tools satisfy retained requirements. Recipe and block execution perform the actual consumption/durability changes.
- `practice` is descriptive metadata. XP is awarded directly by executing code; there is no generic effect interpreter or planner that searches for a required skill level.
- Registry matching scans Java provider definitions. It does not provide a precomputed reverse index or automatically turn all skills into producers.
- Equal-cost initial choices follow provider insertion order. Selection is exclusive per acquisition session; a villager can retain requests from multiple waiting roots or restock items.
- Recursive cost estimates check the current wallet but do not jointly reserve money, supplier stock or shared tools across branches. An active child contributes its last monetary estimate; failure/time penalties remain selection scores, not payment.
- Supplier discovery checks nearby loaded, skilled, equipped workers but does not reserve availability or establish that a resource is reachable. Order allocation and runtime extraction recheck their own conditions.
- Profession capability baselines are repaired during updates/loading, rather than granted only once. Ordinary GATHERING XP is not migrated into the new extraction skills.
- Personal crop acquisition harvests existing mature crops. Those crop entries cannot create resource orders, and this method does not plan planting, waiting for growth or replanting.

These details are maintained in the corresponding topic documents rather than treated as additional implemented features.

## Requirements that remain outside the implementation

| Desired/generalized behavior | Actual boundary |
|---|---|
| Every task fully described by requirements and effects | Contracts cover acquisition providers; tool compatibility, recipe permission, quotes, capacity, workstation checks and mutations still live in executable code |
| Any desired skill level can generate a training path | Minimum levels filter methods; no training-path discovery is connected to practice metadata |
| Ordinary gathering automatically produces berries or arbitrary resources | `GatherSkill` moves toward an optional location and waits for target stock; it has no berry harvesting or catalog provider |
| Exhaustive lowest-cost path across all resources, recipes and sellers | Searches have explicit bounds and heuristics; shared prerequisites and alternative tool tiers are not globally optimized |
| Daily free production at the new workstation blocks | Blocks provide profession POIs and no interaction UI; later tool shortages use acquisition rather than free replenishment |
| Arbitrary mineral extraction or future-version raw metals | Target items select explicitly registered 1.16.5 sources and their real drops; buried-ore tunneling is not implemented |
| Purely declarative task execution | Contracts aid matching and prerequisite creation; executors remain responsible for world actions and completion evidence |

## Verification scope

This documentation update changes no Java code or assets. Validation passed for 325 local Markdown links and complete index coverage of 169 Java source/test files, plus `git diff --check`. Compilation and automated rule results recorded in [Debugging and verification](DEBUG_VERIFICATION.md) belong to the preceding implementation run; they were not rerun for prose-only changes. In-game rendering, POI claiming, recipe/world integration, pathfinding and asynchronous cancellation still require the listed acceptance scenarios.
