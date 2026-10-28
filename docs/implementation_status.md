# Current implementation boundaries and verification status

Reviewed: 2026-10-27. This document lists source-confirmable scope and limits. The user specifies actual development tasks; topic documents explain current mechanisms.

| Topic | Current scope | Limits or checks still needed |
|---|---|---|
| Main loop | Slow perception, Utility sampling, per-tick skills, unified exits | Continuous execution and exceptional cases need in-game tests |
| Resource work | Workstation professions; independent woodcutting/mining/quarrying; shared personal/order extraction, leases and inventory checks | Local exposed resources; no complete methods for tunneling, whole trees, high scaffolding, or regional exploration |
| Item method discovery | Contracts, output providers, recursive prerequisites, cost/stall scoring and scoped cancellation | Bounded recipe/tag/cost search; no global optimum or automatic skill-training planner |
| Goal lifecycle | Cooldowns, waits, exit after at most three failures, delayed routine recreation | Check source eligibility against handlers |
| Spatial perception | Chunk summaries, bed-anchored buildings, near-field frustum | Budget clipping, narrow-opening merges, thin decorations, wall occlusion |
| Building index | Bed/chunk seeding, event invalidation, per-dimension persistence | Unloaded beds await another offer; command-created bed discovery needs testing |
| Interior semantics | Coarse AABB, type, and bed set | No complete `StructureParser` relationship model |
| Capabilities and crafting | XP, decay, recipe unlocks, custom recipes | Check Forge Proficiency and agent capability consumers separately |
| Events | Three buffered types, NEW_VILLAGER goal injection | BLOCK_CHANGED/ITEM_DROPPED are consumed without goal injection |
| Resource sharing | `SocializeSkill.chat` calls `GossipChannel` to copy anchors | Expiry filtering and gathering disproof remain incomplete |
| LLM Harness | Guard, schema, validator, journal, planner method | No external `decideDailyGoal` caller or complete replay/evaluation platform |
| Long-term memory/commitments | Natural-language memories/compression and agenda data | No complete scored retrieval or generic proactive agenda decomposition |
| Building | Block interaction, structure parsing/validation, ordering, BuildJob | Complex navigation, recovery, and third-party compatibility require in-game tests |

## Verification status

The latest recorded workstation/method implementation passed offline compilation, 442 assertions across seven check programs, and eight acquisition-search cases. Packaged JSON and workstation asset references were validated. These checks do not exercise live Forge POI claiming, recipe integration, rendering, harvesting or navigation. No Minecraft acceptance session was run for these changes. Earlier PR/trade-harness results remain scoped to their recorded revisions. See [Debugging and verification](DEBUG_VERIFICATION.md) for exact scope and manual acceptance procedures. Repository logs and simulations require confirmation of their generating version and conditions before they can verify current behavior.

## Interface constraints for extensions

New goals need a producer, executable type mapping, eligibility checks, a skill method, completion evidence, and exit cleanup. Store multi-step per-villager state on agent/action objects; check actual NBT fields before claiming persistence. Item transfers must use actual capacity. Recheck asynchronous world writes on the server thread. Resource-catalog additions must define outputs, source blocks, tools, and eligibility.
