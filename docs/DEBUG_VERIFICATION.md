# Debugging and verification

## Swimming diagnostic capture

Obstacle capture: `[DEBUG-movement]` samples horizontal collisions at most once per villager per 20 game ticks, including on land. It records navigation state, next-node rise, ground/water flags, upward clearance, action, and nonempty collision shapes in the loaded 3x3x3 neighborhood (shape boxes are block-local). Block states include trapdoor open/half/facing properties. `jump-request source=stuck-recovery` records only the custom recovery jump request, not vanilla MoveControl jumps or successful jump execution. Capture a full-block step and open/closed trapdoor cases separately; absence of this sample means no sampled horizontal collision, not proof of a valid route. No traversal behavior was changed.

Validation: JDK 11 `gradlew.bat compileJava --offline --console=plain` passed; targeted `git diff --check` passed. No in-game capture has been performed and no swimming behavior fix is claimed.

With `brain_log_enabled = true` in `config/villageragent-common.toml`, managed adult villagers emit `VillagerBrain | DIAG | [DEBUG-swim]` samples every 20 game ticks while in water and once on leaving water. Sampling runs after the retained vanilla SwimTask and before movement physics; the velocity is not the result of the current tick's jump. Samples include UUID, dimension, position, elapsed sample ticks, displacement, velocity, water depth, task status, ground/collision flags, one-block upward collision clearance, feet block/fluid and local flow, next path node/rise, target/reachability, and current action. Local feet flow does not represent the full bounding-box fluid force. A stopped SwimTask or a single velocity sample alone does not prove a missing jump.

Reproduce a still-water bank exit and an upstream walk for 15–30 seconds each, then retain the corresponding `logs/latest.log`, including navigation-repath/navigation-stuck messages and the installed mod JAR name. Multiplayer captures must come from the server; single-player captures come from the client instance's integrated server log. Compare displacement with flow and node rise over multiple samples. Logging uses the existing brain log switch and does not alter swimming physics. In-game reproduction remains pending.

## 2026-11-10 (midnight) `ask`, titles, optional end time

`ask` = an invitation whose plan is not shared: only those who agree do it, the asker gets no copy and nobody follows the asker. `cook` maps to `butcher_cook`. A `title` on plans is shown per viewer in the goals panel and the reports. The prompt no longer pushes `until`: plans end when their steps are done unless the point is to stay together. `javac` over the tree with Minecraft/Forge unresolved: no unresolved members on the mod's own types. Pending live check: `/va ask <villager> ask <butcher name> to cook in an hour` → `[invite] Ask <butcher>: Cook meat`; after acceptance only the butcher holds `Cook meat (asked by …)`.

## 2026-11-10 (late night) Group activities: gather, hold, linger, end time; self-started invitations

Evidence (game log 23:28–23:30): Rowena's chat goal `invite Bram … move 588,64,-408 → socialize @13:09` was accepted and both copies started on time (`selected sequence score=7.0`). Bram's `move` failed once (`UNREACHABLE … x1`, 15 blocks short), which ended his sequence. Rowena arrived, but her `socialize` step chose another villager, returned WAITING, and that ended the sequence, which then restarted every ~10 s. Her invite walk restarted its `move` child 62 times in 3 s (`task-child-exit … SUCCESS`), because arrival (block distance) and the talk check (entity distance) disagreed. The LLM wrote "dinner" as `socialize`.

Changes: `until`/`for_minutes` end time (`AgentGoal.endTick`, persisted); `SequenceSkill` activity mode (gather ≤1200 ticks at the meeting place, retry WAITING/FAIL steps instead of ending, linger until the end with periodic chats, finish at the end); steps inherit group/leader/end; `socialize` prefers participants; new `stroll` step (`StrollSkill`, leader walks, guests follow); `GroupActivities`; invite arrival fix; `socialLimit` in the intake; social-planning hint in thought and dawn prompts; friendship in the known-villager list; goal panel and `/va ask` show end time and host. `javac` over the tree with Minecraft/Forge unresolved: no unresolved members on the mod's own types. Pending live check: `/va ask <villager> invite <name> to dinner at <x y z> from 18:00 until 19:00` → both walk there, the first to arrive waits (`group-gathered`), both eat, then stay and chat until 19:00; a villager near a player occasionally invites a friend on its own.

## 2026-11-10 (evening) Plain-text LLM prompts sent in JSON mode; `/va ask|preview`

Symptom (game log 18:23–18:39): villager dialogue and greetings showed raw JSON, e.g. `[Bramwell] {"Bramwell": ...}`, `{ "lines": [...] }`, `{"spoken": ...}`. Cause, confirmed from the request bodies in `debug.log`: `LLMGuard.query` always called `LLMService.queryLLMJson`, so the social-conversation and greeting prompts (which ask for `Name: message` lines / one sentence) carried `response_format: json_object`; only memory summaries went out as text. Trade proposals/decisions (GIVE/WANT line protocol) used the same call. Fix: `LLMGuard.queryText` / `LLMGuard.queryJson` replace `query`; text: `VillagerSocialSystem`, greetings in `VillagerAgentManager`, both `VillagerTradeSystem` stages; JSON: player chat (`VillagerAgentData.generateChatResponse`), `HarnessDecisionPlanner`, `Invitations`. The same session sent no player chat or thought prompt, so it carries no evidence about sequence/invite generation.

Added `/va ask|preview [villager] [message]` (`LlmPlanCommands`): same prompt as player chat, reply applied (or dry-run) by `ChatReplyProcessor`, report from `LlmGoalIntake.process`. `javac` over the tree with Minecraft/Forge/Log4j unresolved: no unresolved members on the mod's own types. Pending live check: `/va preview` near two villagers should print an `invite` whose activity is a timed `sequence` (`move` → `eat`); villager dialogue and greetings should be plain lines again.

## 2026-11-10 (night) LLM-authored goals, appointments, sequences, invitations and the chat-panel goal list

Scope: `LlmGoalCatalog` (prompt vocabulary + JSON reader; `rest`/`wander` dropped), `GoalDraft`/`LlmGoalIntake` (bind → `GoalValidator` → enqueue, shared by chat, thoughts, dawn reflection and invitations), `KnownVillagers` in the chat and thought prompts, `"at"`/`"in_seconds"` appointments on `scheduledTick` (`GoalScheduler.isAppointment`, `AgentGoal.markStarted`), `SequenceSkill`, `InviteSkill` + `Invitations`, explicit `eat`, LLM/player goal persistence (`GoalNbt`), `ChatMessagePacket` reply handling moved to the server thread, and the chat-screen Goals button (`VillagerGoalsRequestPacket`/`VillagerGoalsPacket`, network protocol 4).

Verification: `javac` over the source tree with Minecraft/Forge/Log4j/Gson unresolved reported no syntax errors and no unresolved methods on the mod's own classes. `GameClock.format`/`ticksUntil`/`duration` were checked with a standalone pure-Java run (noon, just-passed grace, tomorrow rollover, invalid input). No Gradle build or Minecraft session was run. Pending live acceptance:

1. Chat panel → Goals lists every goal with priority, score and source; the running goal is marked ▶; the list refreshes every 2 s and scrolls.
2. Ask a villager "invite Bob to lunch by the well at noon". The goal list shows `invite` with the invitee and an activity `sequence` (`move` → `eat`) `@12:00`. The villager walks to Bob; players nearby see both lines; Bob's journal entry has kind `INVITE`. If Bob accepts, both villagers get the plan as `appointment: starts in …`, it starts at noon ahead of ordinary work (after any yield point), both walk to the spot and eat at least one item even when full.
3. Decline path (set a negative relationship or a reply that refuses): only memories change; no plan is added. With the LLM unconfigured, the relationship rule decides.
4. Ask for `rest` or `wander`: no goal is added. Ask to talk to an unknown name: `goal-rejected … unknown-villager`.
5. Interrupt a running sequence with an emergency (priority 9) and confirm it resumes at the same step; a timed goal that has not started 2400 ticks after its time disappears.
6. Save and reload with a pending appointment and a half-finished sequence: both reappear with the same time and step; routine goals are re-seeded as before.

## 2026-11-10 (late) production-only offers and claimed offers

Scope: `VillagerEconomySystem.advertiseSurplus` posts offers only for items in the profession's `active_offers.json` list (rewritten to produced goods: farmer crops/seeds, fisherman raw fish, miner coal and iron/gold ore, lumberjack logs, butcher meat/leather/feathers, shepherd wool, fletcher arrows; crafting professions unchanged), subtracts output promised to own `supply_request` tasks and removes the villager's stale offers; `CooperativeSupply.findOffer/offerUnits/claimOffer/claimOffersForOwnRequests` and the shared `book` (formerly the body of `startDelivery`); `request_supply` claims a matching offer when posting; `TradeCoordinator` pickup meetings (`Meeting.pickup`, requester walks, provider invited).

Verification: `javac` with Minecraft/Forge unresolved showed no syntax errors in the changed files. No Gradle build or Minecraft session was run. Pending live acceptance:

1. The board shows offers only from producing professions and only for produced items; no nitwit/unemployed offers; a villager's offer disappears once that stock is gone or promised.
2. A miner offers iron ore; a villager needing ore logs `offer-claim … provider=<miner>`, gets `meet_trade`, walks to the miner; the miner gets `trade-invite` within 16 blocks; the ore is thrown and picked up.
3. Before that handoff, the miner's offer quantity drops by the claimed amount, and an ore request from a third villager is skipped by the miner (`supply-claim-skip … stock_short`) unless it holds more ore.
4. A provider who claimed a request no longer offers the reserved items.
5. A request posted before a matching offer appeared is claimed against it on the requester's next board visit.

## 2026-11-10 (evening) idle looking, livestock, cooking, fletcher, fishing bobber and baby villagers

Scope: `IdleGaze` replaces the one-tick `idleLook`; livestock rule `AnimalCare`/`AnimalJob` (pairs fed, extra adults culled, animals outside the village hunted) used by `ButcherSkill`, `ShepherdSkill` (feeding) and `FletcherSkill` (chickens); butcher starter items carrots/wheat/potatoes and the second butcher routine `butcher_cook` (`CookSkill`, smoker or campfire); `fletcher_work` (arrows, gravel digging for flint with `tickMine(..., dropChance)`, chicken hunting), bow tool category and fletcher essentials (bow, iron shovel), flint/feather/leather/string treated as village-producible; ranged combat `ArcherCombat` wired into `VillagerAgentManager` combat; `VillagerBobberEntity` + `VillagerBobberRenderer` (registered through `ModEntities`/`ClientSetup`) and the rewritten `FishSkill` (vanilla timings, 3–5 catches per session, loot thrown to the villager); `VillagerBreeding`/`BreedSkill` (`make_family`).

Verification: every Minecraft/Forge member used by the new code was checked by name and descriptor against `build/createMcpToSrg/output.tsrg` (Forge-only APIs `NetworkHooks`, `RenderingRegistry`, `DeferredRegister`, `ForgeRegistries.ENTITIES` were not checkable there). A `javac` pass with Minecraft/Forge unresolved showed no syntax errors. No Gradle build, rule checks or Minecraft session was run: run `gradlew.bat compileJava --offline` first. Pending live acceptance:

1. Idle: a villager with nothing to do turns its head and body to new directions every few seconds, sometimes to a nearby villager/player/animal, sometimes behind itself; it stops as soon as it walks or works.
2. Butcher: with 2 adult cows in the village and no calf it walks to each and feeds wheat (hearts); with 3+ adult cows it kills one in ≤ 2 strikes and picks up beef/leather; a pig outside the village bounds is hunted; named or leashed animals are untouched.
3. Butcher cooking: with raw meat it loads a smoker (output taken first, coal added) or a lit campfire, waits beside it and collects cooked meat; `butcher_cook` is never selected with nothing to cook.
4. Shepherd: with exactly 2 adult sheep and no lamb it feeds them wheat before shearing.
5. Fletcher: with flint/sticks/feathers it fletches 4 arrows per 40 ticks at a fletching table; without flint it digs exposed gravel (block stays, flint about 1 in 4, cracking animation); without feathers it hunts a surplus or outside chicken; without a bow it gets "Acquire profession tool bow".
6. Archer: a fletcher with arrows shoots a zombie from 4–16 blocks, backs off to > 7 blocks when it closes in, holds fire while a villager or golem is in the line, side-steps, and melees under the normal rules once out of arrows.
7. Fishing: the hook flies in an arc onto the water and a line connects it to the villager's rod; after 5–30 s fish particles approach, the float dips with a splash, the catch flies to the villager and is picked up; a session lasts 3–5 catches. Check the rod-tip offset (`VillagerBobberEntity.ROD_TIP_FORWARD/HEIGHT`) against the held rod.
8. Babies: two villagers each carrying 3+ bread, with more beds than villagers nearby, meet, show hearts and a baby villager appears; each loses about one day of food; neither breeds again for 5 minutes; no baby when beds ≤ villagers.

## 2026-11-10 (afternoon) wood categories, responder tools, meeting walks and rally recruiting

Scope: `log`/`planks` material categories and category "or" alternatives (`SupplyPolicy`, `RequestAlternatives`, recovery); responders may not request work tools (`Context.noToolRequests`); meeting walk falls back to the partner and the handoff no longer requires the meeting point; meeting booking ignores the 10-goal soft cap (the cap blocked the lumberjack's deliveries in `latest.log`); claim/booking failure reasons and a 600-tick provider back-off; rallies stop recruiting once committed strength reaches `combat_charge_total_power × 1.25`.

Meetings were then changed so only the provider sets off after a claim; the requester is invited (`trade-invite`) once the provider is within 16 blocks and can reach it, joins at its next yield point or task end, and both walk towards each other (no fixed board meeting point; 4800-tick booking window).

Verification: `javac` over the main sources with Minecraft/Forge unresolved showed no errors in the changed code. No Gradle build, rule checks or Minecraft session was run. Pending live acceptance: a provider walks to a mining/farming requester, `trade-invite` appears within 16 blocks, the requester joins after its current block/crop/tree, and the handoff completes; a miner short of torch sticks posts `#log` (or `2 sticks | 2 #planks | 1 #log`) and a lumberjack with birch logs answers it; an unemployed villager never posts an axe request; a delivery whose board point is unreachable for the requester completes by walking to the partner (`trade-meeting-no-path` absent or recovering); one zombie draws only enough responders (`rally-not-needed` for the rest).

## 2026-11-10 task commitment, meetings, villager-owned requests and work routines

Scope: `GoalCommitment` (no switch while walking to or doing a task; yield points between work units for farming, logging and mining); lumberjack sessions of 3–6 trees; deliveries as paired `meet_trade` goals (the root-commitment mechanism `committedRoot`/`commitment` is removed); villager-owned requests with adoption, de-duplication and physical withdrawal (`OwnRequests`, `withdraw_request`); acquisition of logs/ores/crops redirected to the villager's own `lumberjack_work`/`miner_work`/`farm` session; blocked crafts waiting on their acquisition; idle activities (wander and the routine board check) chosen only when nothing else scores; session-length measurement for the recency factor; no farmer bread routine; `collect_smelt` "nothing to collect" is WAITING, not FAIL; removal of the unreferenced `VillagerTradeSystem` and `ActionExecutor`.

Verification: a `javac` pass over the main sources with Minecraft/Forge classes unresolved showed no errors involving the changed or new symbols. No Gradle build, rule checks or Minecraft session was run. The check programs `GoalSchedulerChecks`, `TradeCoordinationChecks` and `MethodRequestChecks` were not re-run and may reference the removed `GoalScheduler.canYieldBetweenActions`/`mayInterruptCommitment`, the old `TradeCoordinator.enqueueDelivery`/`CooperativeSupply.startDelivery` signatures, or attempt-owned request isolation; run `gradlew.bat compileJava check --offline` and update them. Pending live acceptance:

1. Commitment: a villager walking to its workstation, field, forest, mine or the board never switches to another ordinary task on the way (no `SWITCH` away from a non-wander goal without a `goal-yield … why=yield_point` or an emergency/sleep).
2. Yield points: during farming, `goal-yield … why=yield_point` appears only right after a crop step; during logging only after a tree's drops are collected; during mining only after `mining-yield`.
3. Logging: a lumberjack session fells 3–6 trees (`felled tree #n`), moving to the next remembered forest chunk when one is cleared.
4. Meetings: a provider claim logs `trade-booked` for both villagers and adds two `meet_trade` goals; a busy partner arrives after finishing its task; cancellations log `trade-meeting-cancel reason=…`.
5. Requests: one villager never shows two live requests for the same spec (`supply-request-merged`/`supply-request-adopt`); a method switch (`cheaper:request_supply -> start:farming`) leaves the request posted; when farming covers the need, a `withdraw_request` goal walks to the board and logs `request-withdrawn`.
6. Acquisition: wheat/logs/ores are produced through `acquire-redirect … routine=farm|lumberjack_work|miner_work`, ending in `acquire-redirect-end`; a waiting acquisition re-checks at most every 1200 ticks (`acquire-waiting onRequest=true`).
7. Craft waiting: a craft blocked on `MISSING_ITEM(x)` is not re-selected while `acquire_item x` exists.
8. Idle: the routine board check is chosen only when nothing else scores, alternating with wander.
9. Recency: `session-recorded type=… ticks=…` reports real session lengths (minutes for farming), not 0.
10. Farmer: no "Craft goods" routine for farmers (after removing the farmer entry from an existing `config/villageragent/craft_targets.json`).

## 2026-11-09 crafting, village supply, farming, mining and navigation

Scope: showcase labels and board panel, miner veins and drop collection, building debug toggles, wander/farming/corner-cut fixes, bed ownership, request lifetime and field bootstrap, `/va showcase breaktools`, notice-board placement and adoption, village producibility, self-made crafting tables, stock-table routine crafting, "or" requests with non-circular responses, torch-gated dark mining, self-built furnaces, stock-only unpacking recipes, farming from remembered fields, exhausted-method fallback and the low-ceiling step jump.

Verification: the edited sources passed a `javac` syntax pass only (Minecraft classes unresolved). No Gradle build, rule checks or Minecraft session was run. Pending live acceptance:

1. Requests: a farmer short of wheat for bread posts wheat, never a hay bale; a miner short of coal for torches posts coal, never a coal block. A carried hay bale is unpacked.
2. "Or" requests: a villager needing 2 sticks that can craft sticks and planks posts `Stick (or 2 Oak Planks / 1 Oak Log)`; a responder holding planks delivers planks and the requester crafts the sticks (`supply-alternative-received`). A smith without logs logs `supply-skip … reason=only_by_same_request`; a lumberjack answers by logging.
3. A toolsmith answers a pickaxe request and posts its own stick/iron requests when short.
4. Farming: a farmer remembering a field chooses the farm session for wheat over a request; when the session ends with nothing to do and wheat is still short, the log shows `exhausted:` and a request follows; harvesting enough withdraws the request.
5. Routine crafting: only miners (torches, bread) and farmers (bread) run "Craft goods"; other professions have no craft routine. A crafting table seen nearby completes any "acquire crafting table" goal.
6. Mining: with no torch the miner skips blocks with light below 10 (`mining-dark`); with torches it places one on arrival (`mining-torch`).
7. Smelting with no furnace known: the smith crafts and places a furnace, then smelts.
8. Navigation: a villager steps onto a block with exactly two air blocks above it (natural jump, step height 0.6), from full blocks and from path blocks/farmland.
9. Notice boards: generated boards stand on natural ground (never a well roof); a player-placed board within 32 blocks becomes the village board.
10. Smelting: an armorer with 8 iron ore loads all 8 and one coal at once; opening the blast furnace shows ore, fuel and a growing ingot stack; ingots may be collected while the batch continues.
14. Meetings: a delivery booked by a provider at the board makes the requester walk to the board; neither switches to other work on the way; no bookings at night.
15. Requests: a farmer whose farm session ran out posts a wheat request that stays on the board until delivered or covered by its own harvest.
16. Names: every villager shows a first and last name; no two residents of one village share one (`name-assigned`).
11. Beds: with fewer beds than villagers, bedless villagers walk to the notice board and wait there (`sleep-no-bed`); no two villagers walk to the same free bed; a villager whose target bed gets taken turns back before entering.
12. Deliveries: a villager making an item for a request (for example a wooden axe) that requests an input (oak logs) receives the delivery; `trade-meeting-end success=true` and no per-tick `supply_request` reselection.
13. A lumberjack without an axe gets an "Acquire profession tool axe" goal (never `recovery-unproducible item=axe`).

## 2026-11-08 persistent miner districts and personal daily yields

JDK 11 offline `compileJava` and full `check` passed. After the final unloaded-target cleanup, survey filtering, new-memory detection and profession revalidation changes, `miningChecks --offline --console=plain` recompiled the final source and passed again. `MiningChecks` passed 104 assertions against the production ordering, progress, world saved data and resumable survey code. It covers rarity versus distance, nearest same-tier paths, district-local proximity, negative-coordinate/diagonal 3×3 bounds, non-expanding boundaries, primary → clear → select → stone transitions, progress/destination/deferral NBT round trips, two miners' independent yields, shared temporary occupancy, reload/interruption/expiry release, day rollover, dimension isolation, old-save defaults, low capped mutually exclusive bonus probabilities, and the original candidate-truncation failure (a reachable target beyond the first eight, a mined nearest target with other ore remaining, and occupied targets retried). The survey test uses a small world-query double; each tick is checked against the 12-path budget and an incomplete scan must remain pending.

The full workspace whitespace check also reports pre-existing trailing whitespace in `JobStarterItems.java`; the files changed for mining pass targeted checks. No Minecraft session or deployment was performed. Pending live acceptance: iron-first district choice, then nearby coal without stone; different-height remembered anchors; completing the fixed district before following a newly discovered rare ore; quota/meal/combat interruptions and save/restart resumption; shared ore with independent personal yields and temporary reservations; switching between routine and acquisition mining; unreachable/unloaded/changed anchors and chunk-unload cleanup; new day/tool unlocks; a nearby ore beyond the old scan cutoff; and stone fallback with immediate between-block reconsideration of newly remembered ore. Observe `mining-visit`, `mining-phase`, `mining-yield` and verify path costs, server-tick load, sounds, physical drops and pickup in Minecraft.

## 2026-11-08 visible-threat panic

Threat scans now require nearby hostile entities to be visible. Direct sightings retain a 40-game-tick grace period; rally responses do not renew sighting evidence. All three combat actions leave when personal evidence and the target alarm expire, stopping navigation and resuming goals. Melee damage requires current visibility.

Per the user's restriction, no compilation, automated tests, or Minecraft session was run. Verification is limited to source/caller review and targeted `git diff --check`. Pending live acceptance: zombies below a solid floor and behind walls must not interrupt work/sleep or raise alarms; exposed nearby zombies must trigger escape; brief occlusion under 40 ticks must retain the response; sustained occlusion must end combat and resume work after about two seconds (within scan cadence), even if responders keep answering. Repeat for ATTACK/FLEE/RALLY, unaffiliated villagers, a second visible enemy, dead/unloaded targets, and restored visibility. A distant responder may continue while another villager actually sees the threat, but must not sustain the alarm alone. Confirm no melee damage through a wall during the grace period.

## 2026-11-08 physical trade handoff

Source/caller review found that `TradeCoordinator` applied both final inventories and spawned the outgoing differences, duplicating stock on pickup and dropping NBT. Both directed and market trades now spawn the exact agreed stacks, debit outgoing stock only, and use explicit contact pickup for incoming stock. Spawn cancellation removes staged drops without debiting inventories. Once thrown, interruption retires the attempt and releases remaining drops without refunds or replay. The unused direct-inventory `completeMatchedExchange` entry point was removed.

Per user instruction, no compilation, automated checks, or Minecraft tests were run. This change received source/caller review and targeted `git diff --check` only. Pending live acceptance: command and market exchanges; both sides' inventory plus ground-item conservation; exact NBT; payment/change bundles and same-item trades; passive pickup disabled; delayed/out-of-contact drops; partial/full capacity; canceled first or later spawn; repeated market scans; death, combat, disappearance, timeout, participant/chunk/world unload. Both participants must remain in place after throwing and must not navigate toward out-of-contact drops. Verify each tracked drop is collected only by its owner UUID. Incoming inventory must change only at contact pickup. Drops must not merge or be taken by other entities while reserved. On interruption, surviving drops must become ordinarily collectible and the original attempt must not be replayed.

## 2026-10-31 paid village orders and whole-tree logging

Validation: JDK 11 offline `gradlew.bat compileJava check --offline --console=plain`; `python scripts/check_trade_ui.py --jdk "C:/Program Files/Java/jdk-11.0.15.1"`; `git diff --check`. New `BarterMathChecks` (47 assertions) covers subjective ratio ordering, priority ties, zero/missing/nonfinite values, tiny positive prices, full seller coverage, stock bounds, change and purchase probability. `TreeGraphChecks` (10 assertions) covers connected leaves, disconnected leaves, leaf-separated trees, village-crossing components, unloaded borders, missing roots and exact/over-limit components. Existing scheduling, method, request, trade coordination and inventory checks remain relevant. Legacy `WorkOrderBookChecks` only tests the retained compatibility class, not current paid supply.

No Minecraft client/server session was run. Pending live acceptance:

1. Populate test values in a separate test setup (packaged four content tables remain empty). Post seller stock before buyer demand, and buyer demand before producer output. Verify same-village polling, production prerequisites, no free transfer, one production intent claimant, and barter conservation.
2. Exercise buyer-paid bundles, seller change, retained tools, no price, insufficient stock, full inventories, expired orders and interrupted meetings. Buying with no emeralds but sufficient valued goods must work. Empty default tables must create no paid matches or production intents.
3. Two nearby vanilla villages, an arbitrary player bell, village destruction, save/reload, dimension travel and a resident away logging: verify stable origin IDs, retained affiliation and no cross-village trade/rally messages. A dimension with no discovered original village leaves residents pending, without an invented market.
4. Observe a WOOD_LOG chunk outside village bounds, request logs, and follow travel → chunk scan → connected tree → single-root crack animation → all log drops. Compare wooden/stone/iron/diamond axe durations with vanilla. Verify quantity conservation, physical pickup, axe wear, urgent preemption and two workers on one tree.
5. Village logs, trees linked into village structures, bare log constructions, disconnected nearby leaves, unloaded tree borders, a modified tree during chopping, a protected Forge break event and a component above 512 logs must not cause partial planned felling or fabricated drops. Live event/mod behavior remains unverified.



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
| `goal-yield` | A running goal was replaced: from/to, `why=preempt|yield_point|idle_or_boundary`, work units done |
| `session-recorded` | Goal type and session length feeding the recency factor |
| `trade-booked`, `trade-meeting-start/end/cancel` | Meeting booking per participant, start, outcome, and the cancellation reason |
| `supply-request`, `supply-request-merged/adopt/filled/retry/lapsed` | Request posted, joined, adopted by a new method, delivered, re-posted, or expired |
| `request-withdrawn`, `request-withdraw-visit`, `request-retired`, `request-expired` | Physical withdrawal at the board and bookkeeping retirement |
| `acquire-redirect`, `acquire-redirect-end`, `acquire-waiting` | Acquisition run as a work routine session, its end, and waiting re-check interval |
| `collect-smelt-idle` | No furnace output to collect (not a failure) |
| `hobby-tool` | One-time wooden axe/pickaxe/hoe for a woodcutting, mining/quarrying or farming hobby |
| `navigation-sidestep` | A villager wedged against a block stepped into a free neighbouring cell before re-planning |
| `request-reopened` | A delivery meeting ended before any handoff; the request is OPEN again |
| `trade-invite` | A delivery's requester received its `meet_trade` goal because the provider came within 16 blocks |
| `supply-claim-skip`, `trade-book-failed`, `trade-meeting-no-path` | Why a provider could not claim/book a delivery, and meeting walks that found no path |
| `rally-not-needed` | An alarm listener stayed away because responders already bring enough strength |

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

### Lumberjack session correction (2026-11-08)

`compileJava treeGraphChecks --offline --console=plain` passed (10 graph assertions). Logs showed repeated unreachable tree moves. Source review found leaf-adjacent root selection and a break action missing goal ownership. The routine now uses chunk-center estimates with travel retargeting, component-level leaf validation, a goal-owned timed whole-tree action, and a pickup phase before exiting. Live checks remain: unequal-distance remembered chunks, memory updates during travel, tall and branching trees, single-root animation over summed duration, all connected logs removed, gather movement with passive collection enabled, unreachable drops, full inventory and urgent interruption. Existing unrelated JobStarterItems whitespace makes repository-wide `git diff --check` fail.

The subsequent collection adjustment reuses GatherSkill for movement and leaves item transfer to passive contact pickup. No compilation or tests were run for this adjustment, as requested. Verify moving/disappearing drops, pickup delays and full inventory in game.


### Fixed screen-size head labels (2026-11-08)

`compileJava --offline --console=plain` passed with JDK 11. Targeted `git diff --check` passed for the renderer, config, commands and operation documentation. A standalone projection calculation checked 27 depth/FOV/GUI-height combinations for an 80-GUI-pixel bar; this does not exercise Minecraft rendering. No game session was run.

Pending live checks: enable `debugoverlay`, `villagerlabels`, and `labelfixedsize`; move the camera between near and overhead views, pan toward screen edges, change FOV and GUI scale, and confirm constant GUI dimensions with interpolated head tracking. Disable fixed sizing to restore perspective shrinkage. Increase `debug_render_range` on server/client and verify tracked villagers beyond 64 blocks, absence behind the camera, stale-data expiry and multiplayer client-local configuration. Dense labels may overlap; the display reports goals rather than nested physical actions.

### Physical village notice boards (2026-11-08)

`gradlew.bat compileJava check --offline --console=plain` passed during implementation. After the final matching/scoring adjustments, `gradlew.bat compileJava taskMethodChecks --offline --console=plain` passed; the method suite now has 30 assertions, including inspection completion followed by a fresh comparison without minimum-run lock. These pure executor checks do not exercise the Minecraft adapters. Seven notice-board asset/language JSON files parsed successfully, and targeted `git diff --check` plus new-Java whitespace checks passed. No Minecraft client/server session was run. Compilation reports standard deprecated block override warnings.

Pending live acceptance:

- Discover new and existing vanilla villages in each biome. Confirm one fence-plus-panel structure near a loaded bell, or near the registered center without a bell; occupied/unloaded columns must remain untouched. Save/reload must preserve the position without duplicates. With no valid site, placement must defer.
- Inspect all four panel orientations, held item, recipe, Chinese/English name and self-drop loot. Break the panel: no automatic replacement or economic access; restore it at the recorded position to resume. Check board/chunk unloading and two dimensions.
- Put buyer/seller far from the board: no remote posting or live quote reads. Observe seller travel before listing, buyer inspection, then `done:buy_product` followed by a newly selected method. Change offers during travel; an expensive/unavailable offer must permit crafting/extraction or waiting without committing payment.
- Compare otherwise identical acquisitions with near/far boards and near/far remembered resources. Board distance belongs to Buy; resource travel/work belongs to Produce. Verify missing boards and unknown resource destinations remain unavailable; unpriced/unpayable offers are rejected after board inspection.
- Block the route or eye-to-panel ray; no posting through walls. Remove the panel while approaching. Interrupt travel and resume; no stale movement or duplicated demand. Visit completion alone must not satisfy the inventory target.
- No viable local quote: demand is posted only after arrival. A producer must visit to discover it and return after production to advertise. Existing appointments may complete away from the board; ordinary background scans must not commit an acquisition buyer before its reevaluation.
- Verify paid exchanges still conserve items under stale stock, full inventories, competing buyers, cancellation and unload. Combat broadcasts and player-villager trade remain independent of notice-board access.

### Distance-only board inspection and walking trace diagnosis (2026-11-08)

Board inspection estimates `0.05 * complete path length` to an adjacent standing cell. It does not include the desired item's value/quantity or reject unpriced items before inspection. Missing, partial or unloaded routes are unavailable. At-board quote evaluation and purchase validation remain unchanged.

Read-only analysis of the supplied instance's `logs/latest.log` and `logs/debug.log` found the same 4382 move-start / 3616 move-no-path events in each (do not add the two files together). During 09:25:30–09:25:40, Rosalind `19080e5f-98e9-4223-b7eb-60b9e582508a` logged 100 move starts and 100 no-path failures with zero root SWITCH events; Kenneth `3b5288f8-0af1-401a-ab62-033f91be3dca` logged 103 move starts and 97 successful lumberjack move-child exits, again with zero root SWITCH events. The repeated origin/target pairs establish movement-child churn inside `lumberjack_work`, not rapid root goal switching in that window. Three server-behind warnings occurred earlier (09:14:57, 09:15:27, 09:16:22); those cannot by themselves explain the later repeated child loops.

The installed `mods/villageragent-1.0-SNAPSHOT.jar` has timestamp 2026-11-07 20:46:32. `javap -p -c` confirms its failed tree approach clears the root without retaining a rejected-tree set. Current workspace LumberjackWorkSkill already excludes failed roots, so the trace must not be treated as validation of current source. The successful-child restart symptom also warrants live inspection of arrival versus block-reach predicates: move-to-tree uses squared radius 4, while block interaction requires all three axis differences to be at most 1. Navigation, collision and position between log samples were not replayed in Minecraft. The subsequent navigation changes below address target rejection and arrival; the installed instance has not been updated or replayed.

### Bounded target reselection and complete-path costs (2026-11-08)

`compileJava check --offline --console=plain` passed, including 10 new TargetAttemptsChecks assertions. After final forest-child cleanup and stable extraction destination changes, `compileJava targetAttemptsChecks --offline --console=plain` also passed. Targeted diff/whitespace checks passed. The pure checks exercise the live selection/exclusion helper with an unreachable nearby target, a long detour, a shorter reachable route, failed-target exclusion, duplicate rejection and termination after three distinct failures. PathLength is checked on a three-segment detour whose route is 15 blocks although its endpoints are only 5 apart; board score is 0.75. Empty/invalid paths are unavailable. These checks do not simulate Minecraft pathfinding, terrain or entity motion.

LumberjackWorkSkill and ResourceExtraction retain failed targets within an execution and reselect rather than immediately replaying them. Three failures exit; no remaining reachable candidate exits earlier. Routine failures use the existing goal policy with a 1200-tick minimum cooldown after exhaustion. Method-based extraction returns failure to acquisition so alternatives can be considered. Complete paths to nearby standing cells replace movement into tree trunks; strict goal moves reject partial paths and handle already-arrived entities before starting navigation. The forest-to-tree transition disposes of its old child frame. TreeFelling abandons immediately when its standing-cell path is unavailable. Selection is bounded and loaded-only, not an exhaustive search of unloaded terrain.

Pending live acceptance: a nearby tree behind a wall versus a farther tree with a shorter actual route; a two-block vertical reach mismatch like Kenneth's trace; three different blocked targets then an exit; removal/obstruction during travel; no forest chunks loaded; interruption and root cooldown; a board requiring a long detour. In logs inspect `logging-target-rejected` / `extraction-target-rejected`, and confirm no immediate identical failed-target restart inside one execution. No game run or deployment was performed.

### Real furnace acquisition and inventory boundaries (2026-11-08)

`python scripts/check_furnace_inventory.py --jdk "C:/Program Files/Java/jdk-11.0.15.1"` passes 26 assertions against the production FurnaceInventory/InventoryTransfer code with small Minecraft API doubles. Cases cover mixed fuel preservation, matching fuel top-up, coal preference independent of inventory order, empty/full/incompatible input and output slots, gold-before-iron selection for empty input, matching iron despite available gold, tool preservation, fuel shortage, remaining burn time, partial/full collection and NBT distinctions. These checks do not run Minecraft recipe ticks or the full purchase-to-smelt chain. The first compile exposed the private `isLit` API; the implementation now reads the block LIT property. `compileJava check --offline --console=plain` passed before the final fuel/ore selection refinement; `compileJava --offline --console=plain` also passed after the final refinement. Targeted diff and new-file whitespace checks passed.

Pending live acceptance: smith with an ingot deficit but no ore buys ore through a real notice-board quote, acquires coal if needed, reaches a furnace-side standing cell, waits for vanilla processing and collects before its inventory target completes. Compare buying ingots against producing them; test no supplier/funds/furnace and SMITHING below 1. Give both ores and an empty input (gold first), then existing iron (iron only); mixed coal/charcoal, tagged fuel, full slots and a fuel container must never be overwritten. Put an uncollectible output nearer than a collectible output; only the latter should be selected. With one unit of backpack capacity and twenty output items, nineteen remain in the furnace. Test concurrent actors, interruption/reload, destruction/unloading, blocked paths, datapack recipe changes and exhausted fuel. Shared furnaces have no per-agent batch reservations. No live game or deployment was performed.

### Tool-only essentials, physical restock and stationary movement (2026-11-08)

Essentials now retain only existing tools at quantity one; mason uses a stone pickaxe, material-only professions have empty lists, and nitwit/unemployed have no entries. Restock validates the current dimension, loaded profession workstation, proximity and line of sight immediately before granting, and delegates travel to a child move goal. Failed/unfinished movement never grants. Only a positive actual inventory insertion updates lastRestockTime and plays one workstation sound. The routine no longer exists solely because starter items are defined.

`python scripts/check_restock.py --jdk "C:/Program Files/Java/jdk-11.0.15.1"` passed 510 assertions using the production execute/arrival methods and Minecraft API doubles: 500 successive walking calls, failed movement, false child success without arrival, blocked sight, invalid/unloaded/changed/cross-dimension stations, zero insertion and successful audible grant. This is not live pathfinding or audio verification. `MovementProgressChecks` exercises the production stationary-clock helper: a 3000-tick moving route, 200/201 stationary boundaries, resumed/vertical/detour movement, tiny jitter and clock rewind.

Pending live acceptance: travel longer than 400 and 2400 ticks without premature failure or remote grants; block movement for over 200 ticks; resume before timeout; climb and detour; destroy/unload/switch the job site mid-route; wall between villager and nearby station; full/partial inventory; successful supply sound exactly once; interruption and retry without consuming daily cooldown on failure. Check that removed material essentials no longer generate new reserve goals (already saved acquisition goals are not deleted by this content change). No Minecraft run or deployment was performed.

### Notice-board player order screen (2026-11-08)

`compileJava --offline --console=plain` and then `noticeBoardViewChecks --offline --console=plain` passed with JDK 11 (18 assertions). The latter recompiles the final networking version and tests the real VillageSignalBoard projection and PacketBuffer serialization: buy/sell and cross-village isolation, publisher names, active versus stale production assignments, matched buyer/seller precedence, exact expiry and resolution filtering, read-only projection, newest-first pagination and clamping, round-trip names/quantities, and rejecting oversized wire pages. Both language JSON files parsed with all 12 screen labels; targeted diff and new-file whitespace checks passed. The block use override produces the expected deprecation warning. No Minecraft rendering, right-click interaction, dedicated server or live networking test was performed.

Pending live checks: right-click the generated panel and a manually placed panel inside/outside registered village bounds; show different orders for neighboring villages; inspect buy/sell tabs, item icons, quantities, publisher and accepted names with no prices anywhere. Create and cancel a production goal, match a trade, complete/expire orders, and observe automatic/manual refresh. Test more than four orders, removal of the last page, long Chinese/English names and tooltips at different GUI scales. Close before a refresh reply, break the board, move/teleport beyond eight blocks, and change dimensions. Verify the screen does not pause the server or change inventories/orders. Client/server builds must both use network protocol 2. No JAR was deployed to the game instance.
