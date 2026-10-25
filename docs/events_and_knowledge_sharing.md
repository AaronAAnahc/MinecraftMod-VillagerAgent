# Events, immediate signals, and knowledge sharing

Reviewed: 2026-10-23.

| Component | Data meaning | Current behavior |
|---|---|---|
| `WorldEventBus` | Environmental events at a time and place | Per-dimension ring buffer consumed by cursor and distance |
| `VillageSignalBoard` | Immediate rally and trade requests | Expiring signals and their business consumers |
| `GossipChannel` | Shareable resource locations | Probabilistically transfers at most one feature anchor in one direction per call |

## WorldEventBus

The in-memory buffer holds 256 events and overwrites the oldest. Each villager has a cursor and reads at most eight events per slow update by default, filtered by chunk Manhattan distance with a default radius of four chunks. Out-of-range events still advance the cursor. Long absences can miss overwritten events; overwriting depends on event count, not a fixed TTL.

The enum contains only `BLOCK_CHANGED`, `ITEM_DROPPED`, and `NEW_VILLAGER`. Bridges publish from block placement/breaking, entity joins, and related paths. `VillagerAgentManager.onWorldEvent` deduplicates `NEW_VILLAGER` into one-shot socialize goals. The other two types inject no goals in that consumer; separate systems maintain the block index and handle pickup. Determine whether entity joins represent load restoration from producer paths.

Perception combines periodic updates with limited events. Local scans discover crops; combat scans and rally signals handle combat.

## GossipChannel

`tryGossip` attempts sharing with `gossip_chance` (default 0.30). It scans the speaker's chunk memories and copies one representative resource anchor and visit time that the listener lacks. Resources include ores, FARMLAND, CROP, and WATER; furniture and building anchors are excluded. Copying ore sets `oreExpiryTick`, default span 72000 ticks.

`SocializeSkill.chat` calls `tryGossip` when starting a conversation, connecting resource sharing to social behavior. Ore expiry is saved and exported for debugging, but sharing selection does not filter by it, and no complete arrival-based disproof consumer exists. Expiry-based sharing suppression and gathering-based disproof remain incomplete. Relationship-opinion sharing lives in `VillagerSocialSystem`, separately from anchor copying.

The model supports differing villager knowledge. Actual gathering site selection still uses `ResourceSensor`'s local loaded region, exposure checks, standing positions, and paths.

## Calls for help, rallying, and retreat

The help board uses shared storage; records carry dimensions, and queries and expiry cleanup filter by dimension. `Combat.combat_rally_radius` controls hearing range, default 64 blocks around the enemy, allowed range 8–512. Only loaded managed villagers in the same dimension and range participate; no extra chunks load. There is no per-village membership/boundary pipeline, so neighboring villages inside the radius may respond.

On detecting an enemy, villagers call for help and flee instead of immediately dueling. Healthy armed responders enter RALLY and suspend ordinary work; ineligible villagers retreat. Charging requires at least two eligible responders within eight blocks with line of sight, adequate weapons/health, and total power reaching `combat_charge_total_power`. Responders who have only acknowledged, not arrived, died, unloaded, have NoAI, are demo-occupied, or have not updated for over 100 ticks contribute no power. Combat refreshes responses and checks the team; insufficient numbers/power trigger retreat.

Attack eligibility is checked every two ticks. Falling below `combat_charge_min_health` (default 10 HP, five hearts) or losing a weapon stops pursuit/attacks and enters FLEE. Rejoining requires restored eligibility. Flee navigation uses vanilla panic speed 0.75 (normal 0.5 × 1.5), samples farther reachable land through vanilla `getLandPosAvoid`, and preserves valid escape paths. Seven blocks is the separation target. Nearby enemies prevent returning to work merely because a rally timer expires. Enclosed terrain may have no escape route.

Support eligibility reuses sleep thresholds: at night (`SleepSkill` ticks 12541–23458), fatigue reaching `REST_MIN_FATIGUE=40` prevents distant support; at any time, fatigue reaching `REST_INTERRUPT_FATIGUE=80` prevents support. Equality follows sleep entry conditions. Checks cover reception, RALLY travel, continued attacks, and power accounting; exhausted responders contribute no power. If fatigue crosses the threshold during travel and the villager is safe, support ends and normal rest scheduling resumes. Nearby enemies require fleeing first. Sleeping villagers subject to these fatigue limits are not woken solely by distant calls; direct threats can still trigger escape.

Initial arrival credit requires eight-block proximity and line of sight. An arrived villager attacking the same enemy remains credited during pursuit within 16 blocks; brief occlusion does not immediately erase power. Excess distance, stopping attacks, or failing health/fatigue checks still removes credit, so distant responders cannot count before arrival.
