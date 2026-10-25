# Configuration and operation

Reviewed: 2026-10-23. Versions come from `gradle.properties`, commands from `VillagerAgentCommand`, and defaults from `ModConfig`.

## Build and launch

Minecraft 1.16.5, Forge 36.2.42, official 1.16.5 mappings; `build.gradle` uses a Java 8 toolchain. Run the Windows wrapper from the repository root:

```powershell
.\gradlew.bat compileJava --console=plain
.\gradlew.bat check --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runClient
```

Artifacts are in `build/libs/`; install them in the matching Forge instance's `mods/` directory. Add `--offline` when dependencies are cached. `check` includes order and goal-exit rule checks; see [Debugging and verification](DEBUG_VERIFICATION.md) for scope.

## Configuration files

Behavior switches, ranges, and timing live in the game directory's `config/villageragent-common.toml`. `ai/config/VillagerAgentConfigDir` manages external data in `config/villageragent/`, initializing missing files from bundled defaults. Check each reader for loading/reloading support.

| Data | Bundled source |
|---|---|
| Custom recipes | `src/main/resources/villageragent/custom_recipes.json` |
| XP and levels | `src/main/resources/villageragent/proficiency.json` |
| Skill recipe unlocks | `src/main/resources/villageragent/craft_unlocks.json` |
| Local dialogue | `src/main/resources/villageragent/default_chat_lines.json` |
| Economic values, sources, profession essentials | Three JSON files in `src/main/resources/data/villageragent/economy/` |

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

## Optional LLM

`llm_api_type` supports openai, anthropic, ollama, and gemini. Defaults are type openai, URL `https://api.deepseek.com/v1/chat/completions`, model `deepseek-chat`, and an empty key. Configure the provider's actual URL, model, and credentials through `/va llm apitype/model/apiurl/apikey`, then check status.

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

- `/va trade <first villager> <second villager> <first item> <quantity 1–64> <return item> <quantity 1–64>`: one meeting, with return delivery only after the first transfer is fully received. Each AgentInventory supplies its own items with exact NBT matching. Swap participants and corresponding items to reverse order. Both need stock and receiving capacity beforehand. The demo_trade adult, range, AI, timeout, and occupancy restrictions apply.
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

The radius does not change charging conditions: at least two eligible responders must arrive within eight blocks with line of sight and sufficient power; low-health villagers retreat. Demo-occupied villagers remain excluded. Distant responders navigate toward the enemy but may fail to arrive on unreachable terrain.
