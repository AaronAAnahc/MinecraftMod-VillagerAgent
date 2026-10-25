# Spatial perception and memory

Reviewed: 2026-10-23. The entry point is `VillagerVisionSystem.buildEnvironmentSummary`; see [Beds and building algorithms](bed_room_search_implementation.md) for geometry and indexing.

## Three perception layers

| Layer | Data and entry point | Consumers |
|---|---|---|
| Far | `ChunkContentSampler` → each villager's `ChunkMemory` | Tags, resource anchors, and visit times support environment summaries, sharing, and farm navigation |
| Mid | Per-dimension `WorldStructureIndex` → `BuildingRecord` | `queryNear/getAt` provide building bounds, types, and bed sets |
| Near | `FrustumCuller` + `DetailedViewRecorder` | Filter salient blocks and entities by orientation/distance for near-field descriptions |

Slow updates maintain chunk memory; environment summaries refresh every 1200 ticks. Summaries also include position, time, weather, and biome. Player dialogue can build immediate perception. Utility also directly consumes local farming opportunities and neighbors from `OpportunitySensor`; it does not score every piece of summary information.

## Chunk data

`ChunkMemory` stores `ChunkTag` and `ChunkFeature` bitmasks, one representative coordinate per feature type, coarse block-category counts, entity-category counts, visit time, salience, and known building IDs. Entity counts have observation timestamps. Representative positions pack localX (4 bits), localZ (4 bits), and Y (8 bits), matching this project's 1.16.5 height range.

`ChunkContentSampler` folds samples into these summaries. The shared index owns building records, while villager memories reference IDs. `VillagerAgentData` manages capacity and eviction; inspect its actual sorting and persistence methods when changing capacity handling.

Villagers can share resource intelligence; see [Events and knowledge sharing](events_and_knowledge_sharing.md). `VillagerAgentData` stores natural-language memories and conversation history, with an LLM compression path. There is no complete importance/recency/relevance retrieval pipeline.

## Farm memory and execution

During perception updates for villagers with farming capability, `FarmMemory` scans loaded areas within 12 horizontal blocks and three vertically every 200 ticks. It saves farmland as a `ChunkFeature.FARMLAND` anchor, one per chunk, persisted with ChunkMemory. Farmland under immature crops is recorded too. A chunk marked KNOWN only through a neighboring chunk is still sampled on first entry.

Farming Utility considers local opportunities and revisitable farms. `FarmSkill` prioritizes nearby maintenance. Without local maintenance, it selects loaded remembered farmland within 128 blocks before reclamation and enters `FIELD_TRAVEL`. On arrival it fixes the session center and checks loaded areas within horizontal ±12 and vertical ±3, cycling through crop-drop collection, mature harvesting, planting, repair tilling, and water-qualified reclamation. Tilling requires air above the surface. Harvesting creates real drops collected by a separate action, independently of passive pickup. Ordinary child steps are two ticks apart, with no ordinary preemption between children. Real progress refreshes the inactivity timer, allowing work beyond 2400 ticks, but the entire session, including travel, is capped at 6000 ticks. Genuine stalls still time out, and urgent goals can preempt. See [the execution pipeline](execution_pipeline_and_resource_orders.md) for patch planning, path budgets, and cooldowns.

Unreachable or failed positions enter a session-local exclusion set while other work continues. Missing seeds/tools allow remaining feasible work to finish before a `BLOCKED` exit requests supplies; no executable work yields `SUCCESS`. Drops remain in the world when inventory is full. Exit or preemption clears the center and exclusions so the next session checks again. The system neither force-loads chunks nor treats old anchors as evidence of mature crops.

Checked anchors and navigation failures defer revisits for 1200 ticks. If the anchor block is gone on arrival, the feature is removed; later observation can record it again. Revisit cooldown is runtime-only; anchors persist. Unknown farmland still requires local observation or entering its chunk.

## Near-field perception and limitations

Frustum checks use direction, angle, and distance. Wall occlusion requires separate ray checks: inside the frustum does not imply unobstructed visibility. Far-field representative positions are resource clues; gathering must confirm blocks, tools, loading, and paths on site. Coarse entity counts are not persistent entity locations.

Debugging uses `DebugSync` → `DebugDataPacket` → `DebugOverlay`. Building boxes, seeds, and distance fields are geometry observations. Verify completed behavior using `VillagerBrain` logs and world state; see [Debugging and verification](DEBUG_VERIFICATION.md).
