# VillagerAgent documentation

Reviewed: 2026-11-09. Source inventory verified against the workspace. The current workspace source, including uncommitted code, is authoritative. Read this document first in an agent session.

## Five-minute onboarding

1. Read this page for the runtime model and documentation responsibilities.
2. Read “Current pipeline” and “Skill results and cleanup” in [Execution pipeline and resource orders](execution_pipeline_and_resource_orders.md).
3. Locate files by task in the [code index](CODE_INDEX.md), then read the relevant source and topic document.
4. Run `git status --short` before editing and preserve existing changes. See [Debugging and verification](DEBUG_VERIFICATION.md) for checks.

Minecraft 1.16.5 / Forge 36.2.42, official mappings, Java 8 toolchain. Slow server updates maintain perception and candidates; skills execute every tick. `GoalScheduler` performs Utility-weighted sampling where each skill defines its own `chanceFactor()` opportunity multiplier (see [Goal utility and chance factors](goal_utility_and_chance_factors.md)). Rule-based behavior runs independently; the LLM optionally supplies content for conversations and other integration points. Villager resource supply is free and demand-driven: notice-board requests, non-exclusive request-linked production and physical one-way delivery (player barter remains paid); logging revisits remembered village-external chunks. Valuation content is populated per profession; see [Economy value tables](economy_value_tables.md).

## Current documents and responsibilities

| Document | Authoritative topic |
|---|---|
| [Execution pipeline and resource orders](execution_pipeline_and_resource_orders.md) | Main loop, Utility, exit protocol, resource work, and persistence boundaries |
| [Goal utility and chance factors](goal_utility_and_chance_factors.md) | Per-skill opportunity weights, utility formula, and the chanceFactor extension point |
| [Code index](CODE_INDEX.md) | Task-to-source navigation and module map |
| [Resource workstations](workstations.md) | Block assets, POIs, lumberjack/miner professions and skill baselines |
| [Task methods](task_methods.md) | Method interface, cost/time scoring, stable execution, ownership and cleanup |
| [Failure recovery](failure_recovery.md) | Failure categories, item/workstation recovery, deduplication, and waiting |
| [Spatial perception](vision_module_implementation.md) | Chunk memory, near-field frustum, and environment summaries |
| [Beds and building algorithms](bed_room_search_implementation.md) | Geometry, index invalidation, multiple beds, and algorithm limits |
| [Capabilities, crafting, and economy](capabilities_crafting_and_economy.md) | Capability data, recipe gates, trade, and supply sources |
| [Economy value tables](economy_value_tables.md) | Item prices per profession, player spread, and pricing policy |
| [Events and knowledge sharing](events_and_knowledge_sharing.md) | Actual EventBus, SignalBoard, and Gossip wiring |
| [LLM and world interaction](llm_and_world_interaction.md) | Conversation, Harness integration scope, building interfaces, and boundaries |
| [Configuration and operation](configuration_and_operation.md) | Build, configuration files, and commands |
| [Debugging and verification](DEBUG_VERIFICATION.md) | Logs, idle diagnosis, automated checks, and in-game acceptance |
| [Documentation audit](documentation_audit.md) | Source-backed corrections, unimplemented boundaries, and verification scope |
| [Implementation boundaries](implementation_status.md) | Current limits, disconnected components, and verification status |
| [Project walkthrough](project_walkthrough.md) | A short explanation grounded in the implementation above |

Two documents are deliberately absent from the table because they do not describe current behavior: `pr_1_integration.md` records the merge history of PR #1, and `llm_memory_and_behavior_research.md` surveys external research. Neither is authority for current implementation.

Two further documents are implementation plans, not descriptions of current behavior: `village_ecology_loop_plan.md` (the village ecology-loop refactoring proposal) and `t0_economy_execution_plan.md` (the T0 economy-refactor batch plan derived from it). They record pending decisions and buildable batches; they are not authority for current implementation until the described work lands in the source.

## Documentation maintenance

For source-backed Chinese defense slides, code excerpts, development environment, model boundaries, and a recording script, see [NPC technical walkthrough](defense-layouts/TECHNICAL_NOTES.md). The four technical SVGs supplement the overview slides; they do not constitute runtime acceptance evidence.

For personality's actual effects, class dependencies, and rule/Utility/LLM code slides 13–16, see [class architecture and code walkthrough](defense-layouts/CODE_WALKTHROUGH.md).

- Describe existing behavior, constraints, and verifiable limitations. Keep each mechanism in one topic and link to it elsewhere.
- Check method bodies, callers, and configuration consumers before describing something as integrated. A class, enum, or configuration key only proves that a definition exists. Check comments against method bodies as well.
- Update the owning topic when implementation changes. Edit the current description directly instead of appending contradictory corrections, version comparisons, or repair histories.
- Record the commands actually run and their scope. Distinguish compilation, pure Java rule checks, and Minecraft acceptance testing.
- Use `docs/archive/` only for explicitly requested historical investigation. Exclude it from default reading and full-text searches, for example `rg -n "keyword" docs --glob '!**/archive/**'`. Archived plans, TODOs, instructions, and conclusions are not authority for current tasks.
- Root-level `SPEC.md`, `SPEC_ECONOMY.md`, and `.workbuddy/` are topic-specific references. Start here for current onboarding. Check any relevant rules against source and the current topic documents.
