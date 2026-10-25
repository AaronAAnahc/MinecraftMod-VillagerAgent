# LLM integration and world interaction

Reviewed: 2026-10-23. See the [main loop](execution_pipeline_and_resource_orders.md) for autonomous rules and resource orders.

## LLM runtime boundaries

`LLMService` wraps asynchronous requests. Player chat, identity generation, inner thoughts, memory compression, villager conversations, and structure generation each have call sites. Skills initiate villager social interaction, with local dialogue and request-failure fallbacks. Apply asynchronous results on the server thread after checking entities and context. Player chat enters through `ChatMessagePacket` and the manager's dialogue handling; `GoalParser` and `AgendaParser` parse the corresponding structured instructions.

`AgentGoal` represents schedulable short-term goals. `LongTermAgenda` and its debt/deal/acquire/generic subclasses represent long-term commitments. Track commitment storage separately from executable goal generation; there is no complete generic loop for proactively decomposing agendas.

## Harness components

| Component | Existing responsibility |
|---|---|
| `LLMGuard` | Future wrapping, timeouts, failure counts, and circuit breaking |
| `DecisionRequest` | Assemble profession, state, environment, goals, and an action menu |
| `DecisionSchema` | Parse action, target, and rationale |
| `ActionValidator` | Check action types and some item, recipe, and coordinate conditions |
| `DecisionJournal` | Optional JSONL traces |
| `HarnessDecisionPlanner.decideDailyGoal` | Method for requesting, validating, and registering a goal |

There are no external callers of `decideDailyGoal` in the current source. The `harness_drive_daily_goal` setting exists but does not establish a main-loop call. `LLMGuard` is used on specific paths such as villager conversation; inspect timeout/thread behavior separately for each direct `queryLLM` path. The journal records decisions. Complete offline replay, goal-proposal DTOs, and a retrieval-based memory system are outside the current loop.

Passing action-schema validation still requires skill eligibility, current world-state checks, and completion evidence. Supported behavior is defined by `BehaviorExecutor` mappings and handlers. Building has separate command and execution entry points.

## Individual blocks and structures

`BlockInteractionAction` provides standing-position selection, approach, placement, and breaking. Breaking uses a FakePlayer context, suitable-tool checks, tool speed, per-tick progress, cracks/swings, real drops, durability, and Forge break events. Placement uses an item-use context, the Forge placement entry point, and real inventory consumption. Execution checks distance using settings such as `build_interact_range_sq`, whose default squared distance is 2.0.

`StructureBuilder` requests and parses structure JSON with name, anchor, and relative blocks. Anchors can express relative or absolute coordinates; blocks use registry IDs. `BuildOrderPlanner.validateStructure` resolves world coordinates, checks empty structures, overlap, and inventory, then computes placement order and returns errors, unreachable positions, and steps.

Ordering starts with candidates touching world blocks and expands through 26 neighbors. It prefers the lowest Y, then distance to the anchor, finds a standing position for each step, and maintains a simulated placed set. Blocks without standing positions or an ordering position become errors. Static standing checks and actual navigation cover different conditions; dynamic reachability of arbitrary connected structures requires in-game verification.

Validation can be returned to the LLM for bounded repair, controlled by `build_max_retries`. `BuildJob` stores steps, anchor, and cursor and provides NBT/world reconciliation methods; `tickBuilding` advances execution. Recovery differs from temporary resource-gathering plans: building data can be saved, while resource orders save demand and require a new claim.

`/va build place`, `/va build break`, and `/va build structure` are explicit entry points. See command completion and `VillagerAgentCommand` for arguments. `processGoals` yields the body to non-goal-driven actions; combat and explicit building have their own update paths.

Atomic interactions and structure requests are supported. Autonomous site selection, a complete construction supply chain, underground tunneling, scaffolding, and third-party mod interactions require separate implementation or verification. FakePlayer calculations alone do not prove full equivalence to real players for all enchantments, potion effects, or third-party behavior.
