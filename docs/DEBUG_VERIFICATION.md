# Debugging and verification

Reviewed: 2026-10-23. This document describes executable checks and observation methods; documentation maintenance does not imply Minecraft acceptance testing was performed.

## Automated checks

2026-10-27 per-village paired trading: `gradlew.bat compileJava check --offline --console=plain` passed with JDK 11, including 25 new `TradeCoordinationChecks` assertions and all existing checks. New coverage: shared participant exclusivity across roles/requests, two-party readiness, stale cleanup, per-board enumeration, cross-market rejection, fixed matched quantity, resident removal/expiry cleanup, ordinary-task non-preemption and emergency preemption. These are Java rule checks, not an executed Minecraft simulation.

Pending live acceptance for this change:

1. Give A funds and B/C sellable stock. Post two A requests: exactly one appointment may own A; the other remains unpaired until cancellation/completion. Repeat with A as seller in one request and buyer in the other.
2. Keep both parties crafting/farming. Accept an order: their running roots and movement must continue; after natural completion/WAITING each selects `meet_trade`. The first ready participant waits and only the second ready flag starts both approaches. Check `trade-meeting-start/end` logs and single inventory commit.
3. Interrupt a ready participant with danger, remove a meeting goal, unload one participant or consume booked stock/funds. Verify no partial exchange and release of both slots. Test full receiving inventories and unreachable meeting points/600-tick timeout.
4. Create two separated bell markets A/B and run `/va markets`. Publish goods only in B: an A resident must not enumerate or match them. Repeat across dimensions and unloading/reloading B. Check 100-tick membership refresh and cleanup, plus fallback grouping without meeting memories.
5. `/va trade` follows the same booking/readiness rules and preserves total inventory quantities. `/va demo_trade` remains a generated-gift demonstration and rejects already booked participants. Saving/restarting must not leave usable orphan meeting roots or persisted appointment locks.


2026-10-27 documentation/source audit: validated 325 local Markdown links across active top-level documents and complete code-index coverage for 169 Java source/test files. `git diff --check` passed. This prose-only update did not rerun compilation or claim new game acceptance. See [Documentation audit](documentation_audit.md) for corrected claims and remaining implementation boundaries.

2026-10-27 resource workstations and output-based task discovery: `gradlew.bat compileJava check --offline --console=plain` passed with JDK 11. The suite passed 20 `TaskContractChecks` assertions (independent skills, NBT round trips, minimum-level/output contracts, retained tool metadata, cycle/depth/node limits and failed-branch cleanup), 27 method-executor assertions (including child progress and monetary cost excluding failure penalties), 12 request-ownership assertions, 28 order assertions, 86 goal-exit assertions, 225 scheduler assertions, 44 task-frame assertions and eight acquisition-search cases. The standalone capability check uses the built-in level table when no Forge configuration context exists. Compilation does not exercise actual recipes, POI ownership, world breaking or rendering.

Static validation parsed all 20 packaged JSON resources and verified both workstation blockstate → model → local texture chains, item models, recipe outputs, self-drop loot, and six 16×16 PNG block textures. `git diff --check` passed. No Minecraft client/server acceptance session was run. Pending game checks:

1. Give/place both stations; confirm the specified textures, names, creative entries and no right-click UI. Check recipes, breaking drops, villager and zombie profession clothing.
2. Two unemployed adults compete for one POI; only one claims it. Destroy/replace a claimed station and verify reacquisition. Orders alone must not assign an unemployed villager a role.
3. Claim lumberjack/miner and inspect WOODCUTTING 3 versus MINING 3 / QUARRYING 3. Load an older high-GATHERING villager: new skills remain zero unless its real profession grants them. Switching resource jobs must not duplicate starter kits.
4. Request oak logs with an axe and WOODCUTTING 1, then with WOODCUTTING 0. Compare logging, a real paid offer and cooperative delivery; no-money paid purchases must not start.
5. Remove the axe and provide a purchasable/craftable replacement. Verify nested tool acquisition, then logging; log → axe → log with no external route must not recurse or cancel its ancestor unexpectedly.
6. Request coal, iron ore and cobblestone with separate skill levels/tool tiers. Reject wrong tiers and Silk Touch when it changes the target drop; actual world drops determine completion. Breaking practices the appropriate skill; buying/dropping/picking up the item must not grant that production skill.
7. Request a crop: mature crops can be harvested, immature ones cannot. Source removal, protected blocks, full inventories, preemption and dropped-item competition must not create items or falsely complete a target.
8. Craft a hoe through missing raw materials, including nearby oak versus unavailable alternative log species. Verify all missing-material costs, shared wallet affordability, retained surplus, stall-based replacement, child progress propagation, and cancellation of only the abandoned method's nested wants/orders.


2026-10-27 unified task methods: `gradlew.bat compileJava check --offline --console=plain` passed with 25 `TaskMethodExecutionChecks` assertions, 28 work-order assertions and the existing acquisition/goal/task checks. After fixing expiry cleanup, `gradlew.bat compileJava methodRequestChecks --offline --console=plain` passed all 12 request assertions. Coverage includes cost ordering, minimum run, stall grace/timeout, failure cooldown, hysteresis, meaningful-progress reset, off-scheduler body time, safe-switch boundaries, cleanup order, owned versus ordinary requests, matched-offer release, pruned delivery receipts and exclusion of transient owned orders from saves. `git diff --check` passed. No Minecraft session was run.

Pending live method cases: waiting supply loses to a feasible paid method after its stall score rises; active harvesting/delivery resets stall time; a cheap but unavailable table does not trap acquisition; preemption and WAITING retain method identity/cooldowns; partial ingredient deliveries replan without duplicating purchases; goal clear/expiry, entity removal, dimension transfer and world unload clean only their own requests; simultaneous same-item roots do not erase each other's demand; money already spent and intermediates already made are preserved after switches. Observe `task-method` transition/score/stall logs and real inventories. The 100-tick reconsideration interval is a minimum; the ordinary 200-tick WAITING scheduling cooldown can delay observations.


2026-10-27 acquisition costs: `gradlew.bat compileJava check --offline --console=plain` passed, including eight new `AcquisitionSearchChecks` cases and the existing 376 rule assertions. The new cases cover logs versus finished hoes, direct versus mixed ingredients, insufficient funds, zero-cost existing stock, per-transaction rounding, and bounded-search fallback. These pure basket tests do not validate live Minecraft recipe/world integration. Pending game cases: cheap finished hoe versus costly ingredients; cheap log converted to a hoe with two spare sticks; multiple suppliers and changing balances; missing/unreachable table; locked recipe; full buyer/seller inventories; unpriced item; no available quote; raw cooperative supply; retry after delivery; explicit craft remains craft-only. Current method logs use `task-method`. Search caps and excluded non-grid processing are documented in failure recovery.


2026-10-27 automatic trade funds: `gradlew.bat compileJava --offline --console=plain` and `python scripts/check_trade_ui.py --jdk "C:/Program Files/Java/jdk-11.0.15.1"` passed. The existing harness covers the shared inventory transaction's insufficient-stock and capacity rejection, not live signal matching/navigation. Pending game checks: zero/insufficient funds must create no matched delivery; exact funds settle completely; spending funds during approach cancels delivery without transfer; full inventories and missing seller stock transfer nothing; two pending purchases cannot spend the same emeralds twice. Resource orders are separate from paid trades.

2026-10-27 value-table reset: `gradlew.bat compileJava --offline --console=plain` passed using JDK 11; packaged price JSON parsed successfully and `git diff --check` passed. No game session was run. In game, verify `/va reset_values` with one UUID, a nearby multi-villager selector and a mixed-entity selector; confirm reset counts, removal of personal overrides, profession prices plus cobblestone buy 0.75 / sell 1.5, independent tables between villagers, and persistence after save/reload. Unselected villagers' prices and all inventories must remain unchanged.

Run from the project root:

```powershell
.\gradlew.bat compileJava --console=plain
.\gradlew.bat workOrderChecks --console=plain
.\gradlew.bat goalExitChecks --console=plain
.\gradlew.bat check --console=plain
```

`WorkOrderBookChecks` covers pure Java request merging, exclusive claims, lease renewal/expiry, stock satisfaction, and partial deliveries. `GoalExitPolicyChecks` covers retention, failure exits, and cooldowns. `GoalSchedulerChecks` covers wander fallback, preemption, weighted selection, and farmland-anchor NBT round trips; `check` also includes task-frame, acquisition-search, method-executor, request-ownership and task-contract checks, as recorded above. Compilation checks interfaces/mappings; rule checks cover specified logic. In-game actions, navigation, entity loading, and presentation need separate acceptance tests.

### Player trade regression checks

For the PR #1 integration with local main (`788922d`), `gradlew.bat compileJava check --offline --console=plain` passed all four rule-check programs (376 assertions). The trade harness below also passed. No Minecraft session was run for this integration; prior game-scenario verification notes remain bounded to their stated scope.

Run `python scripts/check_trade_ui.py --jdk "C:/Program Files/Java/jdk-11.0.15.1"` (or provide your own JDK directory). The harness extracts the actual selection, clearing, and closing methods from `VillagerTradeScreen` and runs `TradeInventory` with minimal ItemStack/Minecraft API doubles. It reproduced the old repeated-click overflow and passes after the fix. Cases cover combined slot quantities, stack limits, repeated armor selection, proposal-only offers, repeated cancellation/close, insufficient stock, NBT matching, capacity failures, and exchanges using newly freed slots. These checks do not exercise real networking or rendering.

In Minecraft, verify left/right/Shift selections followed by Clear, Back, Escape, or screen replacement preserve item counts and NBT. Rejection must neither debit nor create items; acceptance must conserve both inventories and synchronize the client. Closing after submission does not cancel an already-sent request. Test repeated submissions, two slots requesting the same item, distinct NBT, inventory plus equipped armor, full inventories, stale villager snapshots, and a villager that unloads or moves beyond eight blocks.

## Logs and idle diagnosis

Enable `brain_log_enabled` and filter `VillagerBrain` in the actual instance's `logs/latest.log` by villager UUID; trace identically named villagers separately. Start with `SNAPSHOT` activity, running goal, action, position, path, fatigue, farming opportunities, and candidate scores, then inspect:

| Tag | What to check |
|---|---|
| `no-candidate` | Goal count, handler eligibility, and positive-score but non-executable reasons |
| `SWITCH`, `skill-exit` | Goal changes, results, and failure reasons |
| `order-offer-empty` | Eligibility, ownership, distance, requester loading |
| `order-claim/wait/blocked` | Lease, inventory, resources, and tool shortages |
| `resource-scan` | Matching, natural trees, exposure, standing positions, and paths |
| `order-delivery`, `pickup-collected` | Real transfer and contact pickup; inspect `resource-scan`, task-child logs and inventory for extraction |
| `move-start/no-path/stuck` | Navigation requests versus displacement |
| `social-empty/no-path/chat` | Neighbors, paths, and actual conversation |
| `job-site-empty/approach/select/reject` | POIs, path completeness, temporary exclusions, and station approach |
| `wander-fallback` | Each goal's score, cooldown, handler, farm anchors, and vanilla/resource profession |
| `farm-session-start/end`, `farm-step/done`, `farm-skip` | Continuous sessions, children, and skipped locations; completion tag is `farm-step-done` |
| `farm-memory-add/defer/forget`, `farm-travel/arrive` | Farm records, deferral, invalidation, travel, and on-site checks |

Slash abbreviations expand to full tags with the same prefix, such as `order-wait` and `move-stuck`. `SNAPSHOT` appears at most about once per 200 ticks per villager; observe several slow updates. An activity label alone does not prove stoppage while a running goal exists. Verify arrival/gathering/delivery against coordinates, blocks, both inventories, and order state.

Wandering uses loaded reachable locations, vanilla `GroundPathNavigator` advances movement, and `VillagerNavigationSupport` provides assistance/failure detection. For stalls, inspect target height, loaded chunks, doors, standing positions, and actual displacement.

## Visualization

Enable with `/va config set debugoverlay true` and confirm using `/va config list`. `DebugSync` sends snapshots; `DebugOverlay` renders HUD, buildings, seeds, and distance fields under `debug_show_*` controls. HUD shows perception; server logs remain authoritative for behavior flow.

Missing visuals: check switches, nearby managed villagers, snapshot transmission, and client rendering. Missing building boxes: check bed load/placement seeds, pending queues, surrounding chunk loading, roofs, and geometry budgets. Chunk-load bed events are a discovery source.

## In-game acceptance scenarios

1. Without LLM, use two adults, natural trees, and reachable exposed ore. Request supplies and observe claims, gathering, pickup, delivery, and quantity conservation.
2. Two workers compete for one order; interrupt/unload the claimant and verify exclusivity and reacquisition after release.
3. Missing tools, removed target blocks, blocked paths, full inventory, and partial supply: inspect WAITING/BLOCKED/FAIL, cooldowns, and the real remaining shortage.
4. Save/reload: professions/demand persist, leases are reclaimed. Test BuildJob/world reconciliation separately.
5. Move a farmer 40–80 blocks from an observed field and confirm `farm-travel → farm-arrive → farm work`. Also test reload, removed anchors, blocked routes, and immature crops. Check opportunities/needs, skill results, and actual farming, daytime fatigue sleep, and neighbor conversation.
6. Bed geometry: use boundary cases in [Building algorithms](bed_room_search_implementation.md), checking index and wireframes.
7. LLM enabled/failed: check conversation, fallback, thread writeback, and request logs; record actual service, settings, and responses.

Reports should record jar/source version, test-world conditions, commands, log locations, and observed outcomes. This document provides no measured scale/TPS, behavior-distribution, or LLM-latency conclusions.

### Active dropping and pickup

Create `drop_item` / `pickup_item` through TODO instructions (see execution topic), then inspect `pickup-target` and `pickup-collected`:

- Place target items 8–12 blocks away with autopickup disabled. Confirm deliberate approach and the requested quantity in inventory; arrival alone cannot succeed.
- Move drops with water, merge them, or let another player take them. Type targets may search again; a missing explicit UUID should fail.
- Test full/partial inventory capacity and compare item NBT, ground remainder, and goal remainder for loss/duplication.
- Drop multiple same-type stacks with different NBT and verify preservation. Canceled entity spawning must not debit that stock.
- Block paths, unload entities, and resume after preemption; verify exits or repathing. Re-enabled passive pickup may collect leftovers independently.

`goalSchedulerChecks` also covers TODO quantity, UUID, and invalid-argument parsing. Real entities, navigation, and inventory transfer need the scenarios above.

Farming continuity: put over 12 mature crops, empty farmland, and water-qualified tillable soil in one 25×25 area; disable passive pickup. The same farm session should repeat HARVEST→COLLECT→PLANT/TILL until no work remains before `farm-session-end`. Also test missing seeds/hoes, full inventory, unreachable targets, urgent preemption, and two farmers. Automated scheduling covers child-action continuity and urgent preemption, not actual blocks/drops.

### Nested-task regression

`gradlew taskExecutionChecks` (included in `check`) covers execution trees, three-level nesting, child-result isolation, local-state rebuild after cancellation, RULE versus LLM/PLAYER restrictions, arbitrary composite-skill ownership, and urgent preemption. `task-child-start` / `task-child-exit` expose full paths. In game, verify continuous farm harvesting/collection/planting/tilling, skipping unreachable targets, and rechecking after urgent preemption. Automated checks do not cover world navigation.

### Command-driven trade demo

See configuration documentation for `/va demo_trade` syntax and display switches. Tag A/B on open ground 8–12 blocks apart, request three diamonds, and observe movement, facing, throwing, and B gaining three while A's inventory stays unchanged. Repeat with autopickup disabled. Reject duplicate participants, the same entity, non-villagers, children, NoAI, different dimensions, distance over 128, and full inventory. A wall should cause exit within 600 ticks. Test unload/death/AI disable after throwing: remaining drops regain normal pickup, and normal completion restores daily scheduling. Compilation is not in-game acceptance.

### Workstations and recursive table crafting: 2026-10-23 static scope

At the user's request, that change was not compiled, automatically tested, or run in Minecraft. Calls were checked against declarations/bytecode in the local mapped 1.16.5 jar using `javap`; review covered control flow, inventory-copy commits, candidate POI cleanup, and `git diff --check`. This is not runtime acceptance.

Pending game cases: unemployed villagers claim different POIs; employed villagers find replacements after station removal; two villagers compete; blocked paths/preemption/unload do not leak tickets. Observe and later recall tables, isolate dimensions, remove broken tables, and wait for unloaded tables. An unlocked wooden hoe plus one log should produce one hoe and two spare sticks. Test wood species, mixed planks, multiple outputs, full inventory, and ingredients removed mid-plan for loss/duplication. Check container returns for cake-like recipes. Cycles and shortages must terminate within bounds. Locked targets reject; unlocking a target enables its ingredient chain.

### Diagnosis of villagers enclosed by composters

`gamelog/latest.log` at 2026-10-23 16:47:00 records Edmund (UUID `311eaa36-23b7-4020-804d-b5129d9fb108`) spawning at `(12515,71,1042)`. The old `job-site-reject` classified four adjacent stations as `no useful path`, followed by a 2000-tick `find_job_site` cooldown and failed wander paths. These stations had entered POI candidates; failure was in path filtering, so the old `no free POI` wording did not prove unrecognized blocks.

`job-site-start mode=vanilla-poi-adjacent-v2` identifies the new implementation. Successful adjacent reservation should log `job-site-adjacent navigation=unnecessary`, then `job-site-claim`. Empty searches log matching/free counts and `no_matching_poi/all_occupied/no_reachable_candidate`, separating index/profession, occupancy, and reachability. Pending tests: adults enclosed on four sides by one/two layers of composters should claim nearby stations using vanilla's two-block assignment distance, including visible upper composters. Occupied stations must not be stolen, walls must block claims, and distant POIs still use vanilla paths. This change was only statically checked.

Tilling connectivity: near-water soil without cardinal farmland must not be reclaimed; diagonals/other heights do not connect. Same-height shared edges permit expansion if other conditions hold. Three diagonal maintenance neighbors alone do not establish connectivity. Removing the sole adjacent farmland after candidate generation must make actual tilling reject. Only source/caller/diff review was performed, without compilation or game execution.

### Swimming, contact pickup, and two-way demonstrations

That change received static diff/bracket review and `git diff --check`, plus local mapped-jar `javap` interface checks. Per user request, no compilation or game run was performed.

- Specify movement between a one-block-deep pool and reachable land; verify floating and exiting. Also test currents, deep water, and task switches.
- Drops outside the contact box must not experience per-tick attraction; entering it should play vanilla collection animation. Check partial/full capacity, NBT, delay, and owner. Explicit pickup must work with autopickup disabled.
- Run the three scenes in configuration documentation. Verify A/B iron and emerald conservation, iron before emeralds, and no ordinary actions between transfers. Reverse parameters to reverse order. Reject missing stock, full inventory, and duplicate occupancy. Check death, timeout, and unload in either phase, including remaining drops and undelivered stock.
- Iron-hoe crafting must debit log→planks→sticks→hoe ingredients and retain leftovers. Removing the table causes waiting/research. Locked recipes reject; repeated commands request additional outputs.

Craft-command parsing: plain `minecraft:iron_hoe` passes NBT checks; explicit `minecraft:iron_hoe{Damage:0}` and `minecraft:iron_hoe{}` reject. Constructor-generated Damage is not user input. Constructor behavior was checked in local jar bytecode, without compilation or game testing.

### Rally and fatigue regression

Help-call static review covered VillageSignalBoard, CombatAction, VillagerAgentManager, VillagerAction, VillagerActivitySystem, and ModConfig, without compilation/game execution. Game cases: A keeps fleeing while B is distant or behind a wall; two healthy armed villagers within eight visible blocks permit charging; either falling below 10 HP stops attacks; one remaining villager retreats; busy responders pause work; unreachable escape routes never permit wall traversal or teleportation. `Combat.combat_rally_radius` defaults to 64: test loaded responders beyond 20 but within the radius, and exclusion outside it/across dimensions. Distant responders do not count before arrival. Allowed range is 8–512; configuration wiring and callers were only statically checked.

Fatigue boundaries: nighttime 39.9 can support and 40 cannot; daytime 79.9 can support and 80 cannot, subject to health and other eligibility. Distant calls do not interrupt sleeping villagers. Crossing fatigue thresholds or entering night during travel/combat removes support/power; flee nearby enemies before returning to scheduling. This received source/caller/diff review only, without compilation/game launch.

Log review: `gamelog/debug.log` shows Penelope/Edmund RALLY at 21:42:58, three arrived / 16.5 power and all engaging at 21:43:01, then all FLEE at 21:43:02. This confirms signaling/arrival ran; old logs lacked detailed retreat reasons, so the disqualified member cannot be identified. Added `rally-broadcast`, `rally-response`, `combat-retreat reason`, and `Rally charge stopped` logs accompany a 16-block pursuit retention range. Recheck pursuit beyond eight blocks, brief occlusion, injury, and fatigue in game. Default radius changed to 64, but existing config values win; update the runtime TOML too. This change was not compiled or run in game.
