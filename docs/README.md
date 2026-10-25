# VillagerAgent documentation

Reviewed: 2026-10-23. The current workspace source, including uncommitted code, is authoritative. Read this document first in an agent session.

## Five-minute onboarding

1. Read this page for the runtime model and documentation responsibilities.
2. Read “Current pipeline” and “Skill results and cleanup” in [Execution pipeline and resource orders](execution_pipeline_and_resource_orders.md).
3. Locate files by task in the [code index](CODE_INDEX.md), then read the relevant source and topic document.
4. Run `git status --short` before editing and preserve existing changes. See [Debugging and verification](DEBUG_VERIFICATION.md) for checks.

Minecraft 1.16.5 / Forge 36.2.42, official mappings, Java 8 toolchain. Slow server updates maintain perception and candidates; skills execute every tick. `GoalScheduler` performs Utility-weighted sampling using current state. Rule-based behavior runs independently; the LLM optionally supplies content for conversations and other integration points. Resource supply uses per-dimension order books, profession eligibility, leases, and real inventory.

## Current documents and responsibilities

| Document | Authoritative topic |
|---|---|
| [Execution pipeline and resource orders](execution_pipeline_and_resource_orders.md) | Main loop, Utility, exit protocol, resource work, and persistence boundaries |
| [Code index](CODE_INDEX.md) | Task-to-source navigation and module map |
| [Failure recovery](failure_recovery.md) | Failure categories, item/workstation recovery, deduplication, and waiting |
| [Spatial perception](vision_module_implementation.md) | Chunk memory, near-field frustum, and environment summaries |
| [Beds and building algorithms](bed_room_search_implementation.md) | Geometry, index invalidation, multiple beds, and algorithm limits |
| [Capabilities, crafting, and economy](capabilities_crafting_and_economy.md) | Capability data, recipe gates, trade, and supply sources |
| [Events and knowledge sharing](events_and_knowledge_sharing.md) | Actual EventBus, SignalBoard, and Gossip wiring |
| [LLM and world interaction](llm_and_world_interaction.md) | Conversation, Harness integration scope, building interfaces, and boundaries |
| [Configuration and operation](configuration_and_operation.md) | Build, configuration files, and commands |
| [Debugging and verification](DEBUG_VERIFICATION.md) | Logs, idle diagnosis, automated checks, and in-game acceptance |
| [Implementation boundaries](implementation_status.md) | Current limits, disconnected components, and verification status |
| [Project walkthrough](project_walkthrough.md) | A short explanation grounded in the implementation above |

## Documentation maintenance

- Describe existing behavior, constraints, and verifiable limitations. Keep each mechanism in one topic and link to it elsewhere.
- Check method bodies, callers, and configuration consumers before describing something as integrated. A class, enum, or configuration key only proves that a definition exists. Check comments against method bodies as well.
- Update the owning topic when implementation changes. Edit the current description directly instead of appending contradictory corrections, version comparisons, or repair histories.
- Record the commands actually run and their scope. Distinguish compilation, pure Java rule checks, and Minecraft acceptance testing.
- Use `docs/archive/` only for explicitly requested historical investigation. Exclude it from default reading and full-text searches, for example `rg -n "keyword" docs --glob '!**/archive/**'`. Archived plans, TODOs, instructions, and conclusions are not authority for current tasks.
- Root-level `SPEC.md`, `SPEC_ECONOMY.md`, and `.workbuddy/` are topic-specific references. Start here for current onboarding. Check any relevant rules against source and the current topic documents.
