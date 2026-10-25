# Beds, rooms, and the building index

Reviewed: 2026-10-23. Algorithm entry: `ai/vision/BuildingLocator.locateBed`; index entry: `ai/world/WorldStructureIndex`.

## Data flow and responsibilities

`ChunkEvent.Load` reads beds from chunk block-entity tables; placement/break events maintain beds and invalidate structures. Beds enter `pending`; `processPending` calls `locateBed` within budget. Results become `BuildingRecord` entries queried by perception, sleep, and building navigation.

Bed-driven detection determines the building bounds around a bed. `SleepSkill` consumes the index alongside HOME memory and the agent's home bed for selection/navigation; it must still check actual bed state and reachability.

## Geometry parameters

| Constant | Value | Units or purpose |
|---|---|---|
| `REACH_CAP` | 16 | Upward block layers for roof prescreening |
| `MAX_SCAN_BLOCKS` | 2000 | World-block budget for the derived scan box |
| `SUBDIV` | 2 | Subcells per axis: eight subvoxels per block |
| `AIR_RUN` | 16 | Subcells; continuous air in both directions on one axis must strictly exceed this |
| `BIG_AIR_FRACTION` | 0.85 | Atmospheric-air fraction threshold |
| `MIN_ROOM` | 8 | Minimum room blocks |
| `ATMOSPHERE_HALO` | 2 | Subcell expansion budget after flood fill reaches open-sky air |

`deriveBoxDims` sets Y near the square root of the budget and splits remaining volume across X/Z, producing an approximately 6×44×6 scan box. It is horizontally centered on the bed and rises from one block below it, with Y clipped to 0–255. The volume budget limits coverage of wide houses.

## locateBed steps

1. Collect both bed halves. A roof within 16 layers above either half passes prescreening. Roofs use the same material test as walls.
2. Build a solid-block table and expand it to a half-block grid. Bed subcells are non-solid flood seeds and are excluded from wall-distance sources.
3. Flood six-connected air from the bed. `openSkyUp` means no solid obstruction upward inside the scan box; entering this air is limited by `ATMOSPHERE_HALO`. The signal is bounded by the scan box.
4. Run multisource BFS from every solid subcell to compute six-neighbor wall distance `D`.
5. Atmospheric air is `inC && (skyOpen || longRun)`. `longRun` requires both directions of an axis to strictly exceed `AIR_RUN`; `growBigAir` expands where at least four of six neighbors are atmospheric.
6. Find regional-maximum plateaus in the connected-air distance field. Non-atmospheric plateaus become indoor seeds. Outdoor seeds prefer atmospheric cells with `D == maxInteriorD`; if absent, search downward for the largest available distance, then upward.
7. All seeds start at distance zero in a simultaneous FIFO multisource BFS. Indoor plateaus have separate labels; outdoor seeds share one. Contact between sources marks boundaries using `label[n] != cl && dist[n] >= cd`.
8. Exclude boundaries and calculate atmospheric fractions for indoor/outdoor sets. If one is below the threshold and the other meets it, choose the former; otherwise choose the lower fraction, with ties going indoors. Deduplicate to blocks, compute an AABB, and return empty below `MIN_ROOM`.
9. Expand the AABB by one block in all six directions to include walls, constraining only Y to world height. Classify roughly as `house/cave_house` using the embedded-rock fraction of perimeter solid blocks.

`BuildingRecord` stores id, seedBed, beds, bounds, sealRadius, roomBlocks, and coarseType. It provides coarse geometry; there is no separate parser for relationships among rooms, doors/windows, and furniture.

Classification uses scan-local bounds to index `blockSolid` and `roomBlock`. World-space coordinates are reserved for the returned building bounds; mixing them would skip cells under the classifier's bounds checks and bias the result toward `house`.

## Blocking rules

Air, fluids, `Material.PLANT`, `REPLACEABLE_PLANT`, and `BlockTags.CLIMBABLE` are traversable. Glass, leaves, stone/wood, chests, and decorations outside the allowlist count as walls. Material/tag rules are not equivalent to collision shapes or visual transparency.

## Indexing and persistence

- `byId` stores buildings and `byChunk` provides spatial reverse lookup. `claimedBeds`, `rejectedBeds`, and `pending/pendingSet` manage assigned beds, negative caching, and pending scans.
- The current flood budget is one per tick. Neighbor loading uses `getChunkNow`; an unloaded area causes that bed to leave the queue. A later load/sample must offer it again.
- Beds discovered inside an existing AABB bind to that record. Block changes intersecting a building remove it and requeue its seed; nearby rejected beds can be reconsidered.
- Removing a building clears `claimedBeds` for every recorded bed, allowing all of its anchors to be considered again.
- Bed removal matches recorded beds and their other halves. Explicit removal schedules a rescan if beds remain, or removes the record when the last bed disappears.
- `pruneStaleBeds` checks beds on a 100-tick throttle and retains unloaded beds; no remaining beds removes the record. Inspect explicit removal and periodic checking separately for their exact updates.
- Piston, explosion, fluid, and `/setblock /fill /clone` handlers call `markDirty`. Invalidation and new-bed discovery are separate operations; discovery of command-created beds needs separate acceptance testing.
- `StructureIndexSavedData` persists a per-dimension `WorldSavedData` index. `BuildingRecord` NBT supports a beds list, falling back to seedBed when absent.

## Geometry and verification boundaries

Wide buildings can be clipped by the budget box. Narrow openings into roofed dark spaces can merge rooms. Geodesic boundaries depend on seed distances and need not align with door planes. AABB expansion can overlap adjacent houses; test multiple beds near shared walls. Buildings without beds are outside this discovery path.

In-game cases: outdoor beds, glass roofs, plants/ladders, removing either half of either of two beds, removing the last bed, piston/explosion shell changes, cross-chunk loading, and adjacent houses. Python simulations in `AI scripts/building_detection_verify/` must be aligned with the Java algorithm before serving as evidence for the current algorithm. Their results are not in-game acceptance evidence.
