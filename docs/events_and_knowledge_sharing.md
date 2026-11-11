# Events, immediate signals, and knowledge sharing

Reviewed: 2026-11-09.

| Component | Data meaning | Current behavior |
|---|---|---|
| `WorldEventBus` | Environmental events at a time and place | Per-dimension ring buffer consumed by cursor and distance |
| `VillageSignalBoard` | Immediate rally and trade requests | Combat and trade use separate persistent-village boards; combat also filters radius |
| `GossipChannel` | Shareable resource locations | Probabilistically transfers at most one feature anchor in one direction per call |

## WorldEventBus

The in-memory buffer holds 256 events and overwrites the oldest. Each villager has a cursor and reads at most eight events per slow update by default, filtered by chunk Manhattan distance with a default radius of four chunks. Out-of-range events still advance the cursor. Long absences can miss overwritten events; overwriting depends on event count, not a fixed TTL.

The enum contains only `BLOCK_CHANGED`, `ITEM_DROPPED`, and `NEW_VILLAGER`. Bridges publish from block placement/breaking, entity joins, and related paths. `VillagerAgentManager.onWorldEvent` deduplicates `NEW_VILLAGER` into one-shot socialize goals. The other two types inject no goals in that consumer; separate systems maintain the block index and handle pickup. Determine whether entity joins represent load restoration from producer paths.

Perception combines periodic updates with limited events. Local scans discover crops; combat scans and rally signals handle combat.

## GossipChannel

`tryGossip` attempts sharing with `gossip_chance` (default 0.30). It scans the speaker's chunk memories and copies one representative resource anchor and visit time that the listener lacks. Resources include ores, FARMLAND, CROP, and WATER; furniture and building anchors are excluded. Copying ore sets `oreExpiryTick`, default span 72000 ticks.

`SocializeSkill.chat` calls `tryGossip` when starting a conversation, connecting resource sharing to social behavior. Ore expiry is saved and exported for debugging, but sharing selection does not filter by it, and no complete arrival-based disproof consumer exists. Expiry-based sharing suppression and gathering-based disproof remain incomplete. Relationship-opinion sharing lives in `VillagerSocialSystem`, separately from anchor copying.

The model supports differing villager knowledge. Logging revisits remembered WOOD_LOG chunks before scanning connected trees; other extraction uses local loaded exposure and path checks.

## Calls for help, rallying, and retreat

Combat alarms and trade messages use the resident's own `VillageMarkets` board. Neighboring villages cannot hear one another's alarms even inside the configured radius. Unaffiliated residents cannot rally other unaffiliated residents. Combat additionally checks same dimension and `combat_rally_radius` (default 64, allowed 8–512); only loaded managed villagers respond. Ordinary threat avoidance still works without a board. Combat alarms are separate from item requests on the same board. See [village identity](capabilities_crafting_and_economy.md#runtime-village-detection).

Direct threat detection uses the six-block expanded bounding box, hostile classification, and `villager.canSee`. A villager's own pursuer (a mob whose `getTarget()` is the villager, or its last attacker) is also noticed when heard within 10 blocks or seen within 16. A sleeping villager notices only its own pursuer. No heightmap or path probe is used. This is custom perception; vanilla Brain/PANIC ticking remains suppressed.

On detecting an enemy, villagers call for help and flee instead of immediately dueling. Healthy armed responders enter RALLY and suspend ordinary work; ineligible villagers retreat. Charging requires at least two eligible responders within eight blocks with line of sight, adequate weapons/health, and total power reaching `combat_charge_total_power`. Responders who have only acknowledged, not arrived, died, unloaded, have NoAI, are demo-occupied, or have not updated for over 100 ticks contribute no power. Combat refreshes responses and checks the team; insufficient numbers/power trigger retreat.

Personal threat memory lasts 40 game ticks after the last nearby visible hostile. Alarm membership responses alone cannot create or renew an alarm: only a nearby direct sighting refreshes its position and 40-tick lifetime. ATTACK, FLEE, and RALLY stop at the next combat update when both personal sighting memory and the target's in-range village alarm have expired; navigation stops and suspended goals resume. A responder may continue supporting a live alarm without personally seeing the target. Another visible hostile replaces an obscured/invalid target. Melee damage also requires current line of sight, including during the memory grace period.

Attack eligibility is checked every two ticks. Falling below `combat_charge_min_health` (default 10 HP, five hearts) or losing a weapon stops pursuit/attacks and enters FLEE. Rejoining requires restored eligibility. Flee navigation uses vanilla panic speed 0.75 (normal 0.5 × 1.5), samples farther reachable land through vanilla `getLandPosAvoid`, and preserves valid escape paths. Seven blocks is the separation target. Nearby visible enemies prevent returning to work merely because the flee attempt timer expires; hidden enemies do not bypass the sighting/alarm expiry above. Enclosed terrain may have no escape route.

Support eligibility reuses sleep thresholds: at night (`SleepSkill` ticks 12541–23458), fatigue reaching `REST_MIN_FATIGUE=40` prevents distant support; at any time, fatigue reaching `REST_INTERRUPT_FATIGUE=80` prevents support. Equality follows sleep entry conditions. Checks cover reception, RALLY travel, continued attacks, and power accounting; exhausted responders contribute no power. If fatigue crosses the threshold during travel and the villager is safe, support ends and normal rest scheduling resumes. Nearby enemies require fleeing first. Sleeping villagers subject to these fatigue limits are not woken solely by distant calls; direct threats can still trigger escape.

### Night curfew and night alarms

With `night_curfew` (default true) during the sleep window: `GoalScheduler.pickNextGoal` returns the rest goal before utility sampling (unless a priority ≥ 9 goal exists), `shouldPreempt` lets it interrupt any non-emergency task, `SleepSkill.chanceFactor` is 1000, and sleepers do not get up when fatigue reaches 0. They get up at dawn once fatigue is below `REST_MIN_FATIGUE`. A villager that finds every bed taken keeps its bedtime goal, walks once to an outdoor waiting spot beside the village notice board (else the village centre's surface), stands there and looks for a bed again every 100 ticks (`sleep-no-bed`). An idle villager starts combat only for its own pursuer; a monster merely nearby is ignored. Support eligibility at night is then only `fatigue < REST_INTERRUPT_FATIGUE`, so sleepers answer the chased villager's alarm (RALLY wakes them). When combat ends, the suspended rest goal resumes and they walk back to bed. With the curfew off, the fatigue rules below apply unchanged.

Bed choice (`SleepSkill`): a villager uses its own bed first (vanilla `HOME` memory, else its saved bed), comparing beds by their head half; otherwise it borrows a bed that belongs to no other loaded managed villager. An owner arriving at its bed while someone else sleeps in it wakes the intruder and takes the bed back. A villager heading for a bed claims it for 200 ticks (renewed while walking), and other villagers skip claimed beds. If the target bed (other than its own) becomes occupied or claimed by someone else on the way, the villager stops walking at once and chooses again instead of entering the room.

Initial arrival credit requires eight-block proximity and line of sight. An arrived villager attacking the same enemy remains credited during pursuit within 16 blocks; brief occlusion does not immediately erase power. Excess distance, stopping attacks, or failing health/fatigue checks still removes credit, so distant responders cannot count before arrival.
