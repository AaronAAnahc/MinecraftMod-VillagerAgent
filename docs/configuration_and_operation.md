# Configuration and operation

Reviewed: 2026-10-27. Versions come from `gradle.properties`, commands from `VillagerAgentCommand`, and defaults from `ModConfig`.

## Build and launch

Minecraft 1.16.5, Forge 36.2.42, official 1.16.5 mappings; `build.gradle` uses a Java 8 toolchain. Run the Windows wrapper from the repository root:

```powershell
.\gradlew.bat compileJava --console=plain
.\gradlew.bat check --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runClient
```

Artifacts are in `build/libs/`; install them in the matching Forge instance's `mods/` directory on the server and participating clients. Custom blocks/items and client presentation are part of the mod. Add `--offline` when dependencies are cached. `check` includes order, goal-exit, scheduler, task-frame, acquisition-search, method-executor, request-ownership and task-contract checks; see [Debugging and verification](DEBUG_VERIFICATION.md) for scope.

## Configuration files

Behavior switches, ranges, and timing live in the game directory's `config/villageragent-common.toml`. `ai/config/VillagerAgentConfigDir` manages external data in `config/villageragent/`, initializing missing files from bundled defaults. Check each reader for loading/reloading support.

| Data | Bundled source |
|---|---|
| Custom recipes | `src/main/resources/villageragent/custom_recipes.json` |
| XP and levels | `src/main/resources/villageragent/proficiency.json` |
| Skill recipe unlocks | `src/main/resources/villageragent/craft_unlocks.json` |
| Routine craft stock targets | `src/main/resources/villageragent/craft_targets.json` (see [Crafting, requests and supply](#crafting-requests-and-supply)) |
| Local dialogue | `src/main/resources/villageragent/default_chat_lines.json` |
| Economic values, sources, profession essentials | JSON files in `src/main/resources/data/villageragent/economy/`; four content tables intentionally empty, schemas in the economy topic |

## Common switches

| Key | Default | Current purpose |
|---|---|---|
| `enable_ai_agents` | true | Master agent switch |
| `agent_think_interval` | 100 | Slow perception/candidate interval in ticks |
| `enable_world_interaction` | true | World interaction |
| `economy_enabled` | true | Economy and supply operations |
| `enable_villager_social` | true | Villager social interaction |
| `enable_villager_thoughts` | false | Optional inner thoughts |
| `enable_auto_pickup` | true | Dropped-item pickup |
| `enable_building` | true | Building path |
| `brain_log_enabled` | true | Decision/execution logs |
| `enable_debug_overlay` | false | Debug visualization |
| `debug_render_range` | 64 | Visualization range |
| `debug_show_hud/buildings/seeds/field` | true | HUD, buildings, seeds, and distance field respectively; full keys start with debug_show_ |

`enable_daily_schedule`, `behavior_executor_interval_ticks`, and `harness_drive_daily_goal` exist as definitions. The current loop is driven by needs/opportunities and executes every tick; the Harness daily entry has no caller. Trace consumers before inferring runtime effects from a configuration UI.

## Commands

Both `/va` and `/villageragent` are supported. Use in-game completion for arguments:

```text
/va config list
/va config set debugoverlay true
/va llm status
/va info
/va orders
/va occupation lumberjack
/va occupation miner
/va occupation none
/va request minecraft:oak_log 8
```

occupation, request, and orders address resource work for nearby managed villagers; see the main-loop topic for semantics. `/va goals clear` removes goals, so use it deliberately when debugging. `/va reload` coverage follows its implementation and the readers.

## Overhead recording labels

Head-top labels show the running goal (or activity fallback) and candidate utility bars. Enable them in a single-player recording world with:

```mcfunction
/va config set debugoverlay true
/va config set villagerlabels true
/va config set labelfixedsize true
/va config set debughud false
```

`Agent Behavior.debug_labels_fixed_screen_size` defaults to false in `config/villageragent-common.toml`. When enabled, each label drawing unit occupies one GUI pixel (the bar is 80 GUI pixels wide), compensating for camera depth and projection/FOV while remaining anchored above the interpolated villager head. GUI scale controls the resulting physical pixel size. Set `labelfixedsize false` to restore world-size labels. `config get labelfixedsize` and `config list` report the switch.

For higher views, increase `debug_render_range` in the same section on both server and client; labels use this range instead of a hard-coded 64-block cutoff. Only tracked, loaded managed villagers with fresh server data can be labeled. Common config is not automatically synchronized to remote clients: on multiplayer, enable data transmission on the server and configure the recording client's render switches locally. Labels can overlap in a dense overhead view; there is no collision avoidance. These labels describe the current goal, not every nested physical action.

## Promotional showcase

`ShowcaseSync` (server) → `ShowcasePacket` → `client/ShowcaseOverlay` draws a recording overlay independent of the debug overlay. Section `Showcase` in `config/villageragent-common.toml`:

| Key | Default | Effect |
|---|---|---|
| `showcase_labels` | false | Fixed-GUI-pixel card above every managed adult within range: `name · job`, `Task:` (running root goal, or Fighting/Fleeing/Answering the alarm/Sleeping), `Step:` (nested task chain plus current action description) and a goal-weight bar (utility per goal type, two heaviest labelled with their share). |
| `showcase_label_range` | 160 | Radius from the player for cards and tags (vertical search at least 128, for flying). |
| `showcase_label_scale` | 0.75 | Size multiplier for cards, request tags and the board panel (1.0 = one font pixel per GUI pixel; range 0.2–3.0). |
| `showcase_board` | false | Top-right panel: notice board of the village containing the player (bounds ±16, else nearest centre within 160): open/claimed/delivered counts and up to 10 requests (`item xN — author`, `OPEN` / `CLAIMED → provider` / `DELIVERED by provider`). Adds a request tag above villagers: Requested, Making … for, Delivering … →, Receiving … ←, Meeting to hand over goods, Reading the notice board, or Being chased. |
| `night_curfew` | true | See [events](events_and_knowledge_sharing.md#night-curfew-and-night-alarms). |
| `ensure_core_jobs` | true | `VillageJobGuarantee`: every loaded village keeps a farmer, lumberjack and miner. |
| `async_pathfinding` | true | Off-thread recompute/recovery/continuation searches (see below). |
| `path_repath_interval` | 80 | Ticks without 0.5-block progress before a goal walk re-plans. A villager pressing against a block (horizontal collision) recovers after at most 30 ticks: it jumps onto an ascending path step, otherwise alternates a side-step into a free neighbouring cell (`navigation-sidestep`) with a fresh route; six recoveries without progress fail the walk. |
| `mining_time_multiplier` | 3.0 | Ore/stone mining (`BlockInteractionAction.tickMine`) takes this many times the player break time for the same tool (1.0–10.0). |

Commands (`/va showcase …`, permission 2):

```mcfunction
/va showcase labels true      # cards on (same as /va config set showcaselabels true)
/va showcase board true       # board panel + request tags
/va showcase jobs             # fill missing farmer/lumberjack/miner in the nearest village now
/va showcase field             # plant a 9x9 irrigated wheat/carrot/potato field near the village for the farmer
/va showcase monster day      # helmeted zombie (no daylight burn) 10-14 blocks from the nearest villager, targeting it
/va showcase monster night @e[type=villager,limit=1,sort=nearest]
/va request minecraft:oak_log 8   # make the looked-at villager post a request
```

`/va config set|get` also accepts `showcaselabels`, `showcaseboard`, `nightcurfew`, `corejobs`, `asyncpath`, and the building debug layers `debugbuildings` (building AABBs and air volumes), `debugseeds` and `debugfield` (map to `debug_show_buildings/seeds/field`), so they can be toggled during play.

`/va showcase breaktools [villager]` removes every tool (per `SupplyPolicy.toolCategory`) from the given villager, or the nearest managed adult, with the vanilla break sound and particles, to stage a tool request on camera.

Work flow shown on camera: the miner flood-fills the 26-connected blocks of the surveyed ore (same eligible sources, within 6 blocks, max 24; stone max 8) and mines every exposed one nearest-first without a new survey between blocks. After the vein, and after a lumberjack fells a tree, `DropCollector` walks the villager to each dropped item within 10 (miner) / 12 (lumberjack) blocks, nearest first, until none is left (8 s per item, 60 s overall); the per-tick passive pickup takes the item on contact. Breaking, mining and chopping play the block hit sound every 4 ticks; chopping also plays a wood-break thunk on each swing.

Movement and field work: wander picks a village spot 80% of the time (near the notice board, market centre or meeting point, else a random reachable point in the village bounds, 6–64 blocks away); exploration targets must stay within 40 blocks of the village, and a villager further out always heads back. Till, plant and harvest count as arrived once `FarmingAction.canWork` holds (block in an adjacent column, top face between one block below the feet and 1.6 above, clear line of sight), so raised beds are worked from beside them. `PathNavigatorMixin` blocks vanilla corner cutting for managed villagers when the skipped node is a step up or the straight line crosses a solid cell at body height (`VillagerPathfinding.unsafeCornerCut`); this keeps paths over pumpkin/melon rows on their step-up nodes.

Economy pacing: hunger falls `hunger_decay_per_minute` (section `Economy`, default 1.5, range 0–20) points per in-game minute (1200 ticks) and the `eat` goal is seeded below 60; eating refills to 85 and one bread restores 25, so a villager needs about one bread per 17 minutes. A villager without food posts a `#food` request; requests live `request_ttl_ticks` = 48000 (two game days). Field bootstrap: while a farmer remembers fewer than 16 farmland blocks (`FarmMemory.knownFarmland`, distinct farmland within 8 blocks of each remembered field anchor, recounted every 200 ticks), the farm goal may till any dirt/grass with air above that is within vanilla water range (4 blocks, same level or one below), with no neighbouring-farmland or level-2 requirement (`FarmSkill.Step.TILL_NEW`), up to the remaining count. With no field remembered, the farmer walks to `bootstrapSite`: tillable ground beside water nearest its job site (else village centre), searched within 24 blocks every 60 s. At 16 known blocks the normal rules apply again. A hoe is required; without one the farm goal reports the missing hoe and the acquisition can post a request. `/va showcase field` plants a ready-made 9x9 field on demand; fields are never built automatically.

Core-job guarantee: every 600 ticks per village. A missing role goes to an unemployed resident, else a level-1 holder of a duplicated non-core profession, else any level-1 non-core resident, else a level-1 duplicate of another core role; nitwits and levelled villagers are never retrained. A free matching POI inside the village bounds is reused, otherwise the workstation (composter, carpenter table, miner workbench) is placed on open ground 2–14 blocks from the notice board (village centre fallback) with at least two standable neighbours. The POI is registered and its ticket taken immediately, `JOB_SITE` is set, the profession is set with villager XP ≥ 1, and the agent profession/role is updated.

### Navigation smoothness

Low-ceiling steps: a villager is 1.95 blocks tall, so with only two air blocks above a step its feet must be between 2.00 and 2.05 above the start while it moves over the edge. Vanilla's 0.42 jump peaks at about 1.25, the head meets the ceiling over the step and the villager slides back down the face. `VillagerPathfinding.stepAssist` keeps the villager's own vanilla jump and step height (0.6), but when the next path node is one block up and the vanilla arc would hit a ceiling over the step or the villager, it lowers the upward speed on the first airborne tick so the arc peaks about 0.03 above the step top. On the tick whose move ends inside the head-room window, it gives a small push (0.15) toward the step node. Vertical movement resolves before horizontal, so the villager slides over the edge at the apex and lands. Ordinary steps are untouched.

Route-cost probes (`ReachablePaths`, `ResourceSensor`, `FarmSkill`, `WanderSkill`) use `VillagerPathfinding.compute`, an isolated `PathFinder` over a captured `Region`, never `navigator.createPath`, which would overwrite the navigator's `targetPos` and make the next real repath install a brand-new path. `PathNavigatorMixin` skips the start node(s) in the villager's current block column whenever a new path is installed (`moveTo(Path, double)`) or vanilla recomputes, removing the step back to the block centre that vanilla 1.16 performs. With `async_pathfinding`, block-update recomputes (`recomputePath()`), stuck recovery, and continuation of partial paths (requested when ≤ 6 nodes remain) are searched on two daemon worker threads; `VillagerPathfinding.poll` installs a result on the server thread only if the navigator still follows the path it was requested for and the agent still runs the same action, within 40 ticks. Worker exceptions leave the current path in place. Probe cache: a probe from within 3 blocks of the same origin is reused for 100 ticks.

## Optional LLM

`llm_api_type` supports openai, anthropic, ollama, and gemini. Defaults are type openai, URL `https://api.deepseek.com/v1/chat/completions`, model `deepseek-chat`, and an empty key. Configure the provider's actual URL, model, and credentials through `/va llm apitype/model/apiurl/apikey`, then check status.

To check what the LLM makes of a request, use the chat prompt from a command; it prints the reply, the raw text (and whether it was JSON), and each goal with its steps or rejection reason:

```text
/va preview                              # nearest villager (16 blocks), built-in test: invite the nearest known villager to a timed lunch (move -> eat); nothing is added
/va ask                                  # same test, and the goals are added
/va ask @e[type=minecraft:villager,sort=nearest,limit=1] At 12:00 walk to 10 64 20 and then eat
/va preview <villager> <any message>
```

`preview` adds no goals, agendas or memories, but the exchange is still kept in the villager's recent conversation like any chat.

Rules run without an LLM. `harness_timeout_ms` defaults to 15000, `harness_circuit_failures` to 3, and `harness_circuit_cooldown_ticks` to 1200. These affect paths using `LLMGuard`.

## Two-villager trade demonstration

Administrators (permission level 2) can run `/va demo_trade <A selector or UUID> <B selector or UUID> <item ID with optional NBT> <quantity 1–64>`. Stand beside each villager and tag them with `/tag @e[type=minecraft:villager,sort=nearest,limit=1] add tradeA` and the equivalent `tradeB` command, then run:

```mcfunction
/va demo_trade @e[type=minecraft:villager,tag=tradeA,limit=1] @e[type=minecraft:villager,tag=tradeB,limit=1] minecraft:diamond 3
```

This demonstration spawns items: both villagers approach and face each other, A throws items, and B receives them in AgentInventory. It does not debit A or charge payment and is independent of LLM, economy, and passive-pickup switches. AI must be enabled; participants must be distinct adult villagers in the same dimension within 128 blocks, without NoAI, and B must have capacity. Each villager can join only one session. The demo temporarily suspends daily scheduling, combat, and building ticks, restoring them afterward. Use flat ground without hostiles. It aborts after 30 seconds (600 ticks), on entity departure, or on world unload; remaining spawned items stay on the ground. Session state is not saved.

Display switches are persisted:

```mcfunction
/va config set debugoverlay false
/va config set debughud false
/va config set thoughts false
```

These disable all debug visualization, only the status/mental HUD, and automatic inner thoughts respectively. Disabling only the HUD leaves building visuals controlled by their own switches. Multiplayer clients use local HUD configuration: set `debug_show_hud = false` in the client's `config/villageragent-common.toml`; server commands do not synchronize client settings.

## Directed two-way trade and crafting demonstrations

Administrator commands also accept `/villageragent`:

- `/va reset_values <villagers>`: resets selected living villagers' personal prices to shared defaults plus their current profession template, removing personal overrides. Accepts one UUID or a multi-entity selector; skips non-villagers and dead entities and returns the reset count. Requires permission level 2. Example: `/va reset_values @e[type=minecraft:villager,distance=..16]`; add `sort=nearest,limit=1` to reset only the nearest villager. Uses packaged price data; it does not reload files or change inventory.

- `/va trade <first villager> <second villager> <first item> <quantity 1–64> <return item> <quantity 1–64>`: reserves both residents in the same runtime village market. They finish or yield current roots, independently select `meet_trade`, then start one shared meeting once both are ready. Inventory exchange commits atomically; no generated items or sequential dropped-item payment. Both must be loaded adults with AI, in the command's dimension, within 128 blocks and free of other trade/demo/social sessions. Stock and receiving space are checked before booking and again before settlement. Pending appointments expire after 2400 ticks; the first-ready wait/approach limit is 600 ticks.
- `/va markets`: lists runtime markets in the current dimension, including center coordinates, meeting-point/fallback origin, cached loaded residents and offer/demand counts. Membership cache refreshes every 100 ticks; this does not discover unloaded villages.
- `/va give <villager> <item with optional NBT> <quantity 1–64>`: spawns demo supplies directly into AgentInventory. Vanilla `/give` cannot stock this custom inventory.
- `/va craft <villager> <item ID> <quantity 1–64>`: creates a one-shot crafting goal for additional output; batch yields may exceed it. Requires an unlocked standard shaped/shapeless recipe and reuses recursive planning, real consumption, and workbench memory. It neither supplies missing ingredients nor bypasses unlocks. NBT restrictions only inspect explicitly entered `{...}`; automatically generated default durability NBT does not invalidate a plain item-ID command. A reachable table is required for 3×3 steps; 2×2 steps are handheld. Missing ingredients request supply; missing tables cause waiting. AI and worldinteraction must be enabled.
- `/va wear_hoe <villager> <remaining durability>`: adjusts the first inventory hoe for filming actual breakage. Unbreaking may delay it; use a farmer with one unenchanted hoe.
- `/va till <villager> <x y z>`: approaches and tills the specified soil block, respecting capability, hoe, adjacent farmland, and soil checks. Coordinates refer to soil, not air above. Repair requires FARMING≥1 and at least three farmland blocks among eight neighbors; reclamation needs FARMING≥2 and nearby water. Both require a same-height cardinal farmland neighbor and air above.

Trade is independent of automatic economic matching, LLM, and autopickup. Both participants remain occupied until the second transfer completes. Items follow normal thrown-item physics; recipients approach to collect. Death, unload, inventory changes, or timeout abort the session: received goods are not rolled back, unthrown goods remain with the sender, and remaining drops regain ordinary pickup. Sessions are unsaved and are not atomic exchanges.

Preparation: run the two tag commands beside different villagers, ensuring one villager per tag. demoA should be a farmer with the iron-hoe recipe unlocked. Place a visible reachable crafting table within initial observation range (about eight horizontal blocks and three vertically), keep daytime, and remove hostiles.

```mcfunction
/tag @e[type=minecraft:villager,sort=nearest,limit=1] add demoA
/tag @e[type=minecraft:villager,sort=nearest,limit=1,tag=!demoA] add demoB
/va give @e[type=minecraft:villager,tag=demoA,limit=1] minecraft:oak_log 1
/va give @e[type=minecraft:villager,tag=demoA,limit=1] minecraft:emerald 1
/va give @e[type=minecraft:villager,tag=demoB,limit=1] minecraft:iron_ingot 2
/va wear_hoe @e[type=minecraft:villager,tag=demoA,limit=1] 1
```

Scene one: prepare soil with air above and farmland to its north, east, and west at the same height, meeting FARMING 1 repair requirements. Replace `X Y Z` below with its coordinates; the final actual till consumes hoe durability.

```mcfunction
/va till @e[type=minecraft:villager,tag=demoA,limit=1] X Y Z
```

Scene two: B supplies two iron ingots first; A returns one emerald after receiving them. One command suffices:

```mcfunction
/va trade @e[type=minecraft:villager,tag=demoB,limit=1] @e[type=minecraft:villager,tag=demoA,limit=1] minecraft:iron_ingot 2 minecraft:emerald 1
```

Scene three: wait for trade completion, then run the command. The farmer consumes real materials in order: log → planks → sticks → iron hoe, traveling to the table for the final step. If missing-hoe recovery has already crafted it, do not repeat: this command requests another hoe.

```mcfunction
/va craft @e[type=minecraft:villager,tag=demoA,limit=1] minecraft:iron_hoe 1
```

Disable mental-state and other debug displays:

```mcfunction
/va config set thoughts false
/va config set debughud false
/va config set debugoverlay false
```

## Help-call broadcast radius

Set this in the existing `[Combat]` section of the game directory's `config/villageragent-common.toml`; do not duplicate the section:

```toml
[Combat]
combat_rally_radius = 64.0
```

Default 64 blocks, allowed range 8–512, measured in three dimensions around the enemy. Only loaded managed villagers in the same dimension respond; distant chunks are not force-loaded. Use 256.0 for a larger settlement. Stop the game/server, edit, and restart. `/va config set` boolean options do not change this numeric setting. A large broadcast radius does not provide per-village membership isolation.

The radius does not change charging conditions: at least two eligible responders must arrive within eight blocks with line of sight and sufficient power; low-health villagers retreat. Recruiting stops once enough strength is on its way: a villager hearing the alarm (not one facing the threat itself) ignores it when at least two live responders able to fight together bring `combat_charge_total_power × 1.25` (`VillageSignalBoard.needsHelp`, log `rally-not-needed`). Recruiting resumes if responders fall away. Demo-occupied villagers remain excluded. Distant responders navigate toward the enemy but may fail to arrive on unreachable terrain.


## Resource workstations

`/give @p villageragent:carpenter_table` and `/give @p villageragent:miner_workbench` provide the new profession blocks. Both are noninteractive blocks with ordinary crafting recipes and creative entries. Placement lets eligible villagers claim the corresponding lumberjack/miner profession through POIs. See [Workstations](workstations.md) for textures, skills, starter tools and administrative occupation behavior; [Task methods](task_methods.md) describes automatic item-method discovery.

Default `craft_unlocks.json` keys include `woodcutting`, `mining` and `quarrying`. Existing external overrides remain in control and are not overwritten. Saved personal prices are likewise retained; use the existing `/va reset_values <targets>` command to adopt the default axe/pickaxe values.

## Craft stock table

`config/villageragent/craft_targets.json` (extracted from `src/main/resources/villageragent/craft_targets.json` on first start, read once at startup) lists, per profession, the items its routine "craft goods" goal keeps in stock and how many:

```json
{
  "professions": {
    "villageragent:miner": { "minecraft:torch": 16, "minecraft:bread": 4 }
  }
}
```

Profession keys may carry any namespace; item order is preference order. A profession that is not listed has no craft routine; the farmer is deliberately absent and bakes bread only to answer a village food request. Because the on-disk copy wins, an existing `config/villageragent/craft_targets.json` that still lists `minecraft:farmer` keeps the old routine until that entry is removed or the file is deleted. See [routine crafting](capabilities_crafting_and_economy.md#routine-crafting) for selection rules, and [cooperative supply](capabilities_crafting_and_economy.md#requests) for how requested items and tools are made.
