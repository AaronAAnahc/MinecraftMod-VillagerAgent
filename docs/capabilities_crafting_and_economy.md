# Capabilities, crafting, and economy

Reviewed: 2026-10-23. Behavior execution follows the [main loop](execution_pipeline_and_resource_orders.md); see [failure recovery](failure_recovery.md) for shortages.

## Capabilities and professions

Each agent's `CapabilityPool` contains FARMING, FISHING, SMITHING, BREWING, ENCHANTING, CRAFTING, GATHERING, SOCIAL, and COMBAT. It stores XP and the last practice day, derives levels through `CapabilityLevelTable`, and is saved in agent NBT.

Profession, capability, and resource role represent work identity, skill level, and resource-order eligibility. `ResourceProfession` lumberjack/miner roles are saved separately; vanilla professions use POIs and villager data. Initialization and profession-assignment capability updates enter through `VillagerAgentData` and `VillagerEventHandler`. Recipe eligibility follows actual capabilities and catalog queries.

Practice and exchanges update XP and dates through `addXp`. Daily slow updates apply decay, synchronize vanilla trade levels, and refresh unlocks. Decay has a two-day grace period, then multiplies current XP by `max(0, 1-(effectiveDays/rustDays)^2)`; `rustDays` defaults to 14. Entries without practice dates skip decay. Vanilla conversion uses `max(0,min(5,(level-1)/2))`; inspect `syncVanillaTradeLevel` for final entity writes.

`VillagerProficiency` / `ProficiencyCapability` are a separate Forge Capability XP interface. Start with `CapabilityPool` and actual callers when changing capability gates; inspect consumers of both models separately.

## Behavior eligibility and recipes

`BehaviorCatalog` supplies gating metadata, `BehaviorExecutor.TYPE_TO_SKILL` maps executable goals, and each skill's `canHandle` determines eligibility. Farming maintenance/reclamation are internal `FarmSkill` phases, with capability and tool checks in skill methods.

`DeterministicPlanGenerator` provides profession behavior-pool queries and crafting-goal selection. `RoutineScheduler` calls these to maintain candidates. Generation and Utility scoring are separate stages; runtime scoring is documented in the main-loop topic.

`SkillCraftCatalog` combines profession recipes, general recipes, and capability unlocks; `UnlockedRecipes` stores each villager's available entries. `craft_unlocks.json` configures unlocks. Craftability requires both unlocking and recipe resolution.

For ordinary vanilla shaped/shapeless crafting-table recipes, `CraftSkill` uses `CraftingPlan`. It expands ingredients backward from a real inventory copy, searches recipes and tag alternatives, and plans dependencies by batch yield. Inputs are reserved slot by slot to prevent double-counting. Planning includes cycle detection, depth 24, 256 crafting steps, and a 4096 search budget. The recipe index is cached for at most 100 ticks, so datapack changes are reread within about five seconds.

With an unlocked wooden hoe and one log: one log → four planks; two planks → four sticks; the remaining two planks plus two sticks → one hoe, leaving two sticks. Here `craft` quantity means additional output, rounded up to whole batches; surplus remains in inventory. Steps execute separately using vanilla `matches`, `assemble`, and `getRemainingItems` to validate the grid, output, and containers such as buckets. All inputs and capacity are checked on an inventory copy before one commit; failure consumes nothing. Inventory changes abandon unexecuted steps and trigger replanning next time; completed intermediates remain.

Craftable ingredient recipes for unlocked targets are recursively added to the unlocked set, including tag alternatives but excluding raw ores without crafting-table recipes. Expansion runs during slow updates and crafting start, and is recomputed after capability refresh. Dependencies do not become independent random daily crafting goals; the current plan schedules them. `AcquireItemSkill` and craftable `RestockSkill` needs reuse craft child tasks. Missing-hoe recovery prefers an unlocked iron/stone/wooden hoe craftable from current inventory; it neither unlocks wooden hoes automatically nor substitutes wood for iron.

Steps fitting 2×2 can be crafted by hand. Steps requiring 3×3 travel to a remembered real crafting table instead of treating profession blocks such as composters as tables. During slow updates, `WorkbenchMemory` observes visible tables within eight horizontal blocks and three vertically. It saves up to 64 exact, dimension-qualified positions in villager entity persistent NBT. Recall checks loading, block presence, 128-block range, and vanilla path reachability. Without a usable table, execution yields and waits; it neither finds a profession workstation nor builds a table automatically. Existing `findWorkbenches()` coarse anchors remain part of environment memory; this path uses exact records.

Smelting, other processing, and custom recipes retain the `NativeRecipeResolver` / `ResolvedRecipe` profession-station path. Furnace fuel and processing-time simulation are not implemented here; special dynamic recipes are outside recursive planning support.

Custom recipes cover items without vanilla recipes and similar cases. Profession catalogs contain attributes such as master enchanting eligibility; execution reads them for equipment output. When adding items, check unlocks, vanilla/custom recipes, ingredient supply, and inventory changes on completion.

## Trade and supplies

- `TradeRequestPacket` handles player requests; `VillagerTradeScreen` and `TradeResultPacket` provide UI and results. Prices use item values and personal valuations; completion depends on server checks and real inventory transfer.
- Cursor and trade slots contain proposal copies; they never debit the player's actual inventory. Clearing, canceling, pressing Escape, or replacing the screen releases selections. Rejection leaves real items untouched and does not create refund copies. Closing after submission does not revoke the server request.
- Requested quantities are capped by the combined inventory/armor snapshot minus selections in both request slots, with per-slot stack limits and exact item/NBT matching. Player offers likewise reserve only available stock. Server checks remain authoritative if either inventory changes after the snapshot.
- `TradeInventory.plan` first debits both parties on copies, then inserts receipts. It rejects the entire transaction if stock, NBT, or either inventory's capacity fails. Successful plans are applied on the server thread and synchronized to the player. The trade does not immediately reselect equipment, avoiding overflow when displaced armor cannot fit. The villager must be alive in the player's dimension within eight blocks.
- `VillagerEconomySystem` scans profession essentials and supply/demand, maintaining stock trades and production needs. Stock deliveries enter `deliver_trade`, executed by `TradeDeliverySkill` with emerald settlement.
- `VillageSignalBoard` publishes immediate `TRADE_WANT/TRADE_HAVE` signals. `WorkOrderBook` maintains resource demand and leases. Cooperative raw-material supply and stock-trade settlement have separate entry points.
- `InventoryTransfer` transfers only what fits; full inventories and partial deliveries retain untransferred items.
- `JobStarterItems` defines first-assignment supplies and restock needs. Restocking submits requests; first-time resource roles can receive basic tools.

`RecipeRegistry`, `CraftingAction`, and `ActionExecutor` remain in the tree. Trace active goal crafting through `CraftSkill`, `SkillCraftCatalog`, and `NativeRecipeResolver`. Packet registration, class existence, and actual client sends are distinct evidence.

See [Configuration and operation](configuration_and_operation.md) for data and settings.

## Acquiring profession workstations

`FindJobSiteSkill` directly drives vanilla `GatherPOITask` and `AssignProfessionTask`. Vanilla code handles POI candidates within 48 blocks, reachable paths, ticket reservations, candidate memories, and assignment. A visible free station already within vanilla assignment distance (`BlockPos.closerThan(v.position(), 2.0)`) is first atomically reserved through vanilla `take` and handed to the assignment task, without requiring a path inside an enclosed cell. Distant navigation uses the POI's valid approach distance and vanilla path, recording its endpoint as the standing position instead of repathing into a solid block. Interruptions release candidate tickets through vanilla `releasePoi`, retaining claimed JOB_SITE memories.

Employed villagers whose stations disappear regain find-station candidates. Loaded stations are checked against actual blocks and profession POI predicates; invalid memories are cleared. Unloaded stations are retained without force-loading or treating them as destroyed. Vanilla assignment and Brain refresh remain in use; the mod still owns the full Brain tick and does not enable a second activity scheduler. Resource professions do not claim vanilla profession POIs; nitwits and children are excluded. Failures use common backoff.
