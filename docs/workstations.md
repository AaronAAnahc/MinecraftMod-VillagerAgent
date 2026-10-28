# Resource workstations

`content/Workstations.java` registers plain blocks, block items, POI types and villager professions through Forge deferred registries. Forge's POI registration callback installs the state mapping. No block overrides player interaction and neither block opens a screen, inventory or crafting interface.

| Block | Profession | Initial capability baseline | Appearance |
|---|---|---|---|
| `villageragent:carpenter_table` | `villageragent:lumberjack` | WOODCUTTING 3 | Crafting-table top; oak-log bark on the other five faces |
| `villageragent:miner_workbench` | `villageragent:miner` | MINING 3, QUARRYING 3 | Smooth-stone top, iron-ore sides, cobblestone bottom |

Unemployed adults claim the stations through `FindJobSiteSkill` and vanilla POI profession assignment. Each station has one ticket. Resource orders only recruit existing eligible workers; they no longer convert unemployed villagers into workers. Losing a station allows the normal job-site search to run again. `/va occupation` is an administrative override that updates the entity profession and clears the previous station reservation. Old separately saved resource roles reconcile with the actual profession on the next profession update.

The first resource profession claim gives a wooden axe or stone pickaxe through the existing persistent resource-starter flag. Switching occupations does not reset that flag. Subsequent `restock` goals acquire missing tools using normal methods; stations do not generate daily free materials. New professions reserve their basic tools in the default essentials table. Tool default prices follow material values: wooden axe/pickaxe sell 4, stone 5.5, iron 16; buy prices are half, with profession overrides and saved personal tables retaining their normal precedence.

Both block items appear in Decorations and have self-drop loot tables. Recipes:

```text
Carpenter table:       Miner workbench:
 C                    SIS
LLL                   SSS
L L                   S S

C = crafting table    S = cobblestone
L = any log           I = iron ingot
```

For testing:

```mcfunction
/give @p villageragent:carpenter_table
/give @p villageragent:miner_workbench
```

All block textures are local files under `src/main/resources/assets/villageragent/textures/block/`: `carpenter_table_{top,side,bottom}.png` and `miner_workbench_{top,side,bottom}.png`. They are independent copies of the bundled Minecraft 1.16.5 textures, so later replacements do not require model changes. Blockstates, block/item models, Chinese/English names, loot and recipes are included. Profession clothing initially uses local copies of fletcher/mason textures under `textures/entity/villager/profession/`, with matching zombie-villager textures.

The carpenter table is a profession workstation, not a usable vanilla crafting table. Recipes requiring a crafting table still need a real `minecraft:crafting_table`. Initial capability levels follow the repository's profession baseline convention; ordinary villagers still start with zero skills plus one deterministic level-one hobby, and nitwits are cleared. New extraction skills never inherit old GATHERING XP.

Compilation and static asset validation do not verify in-game POI claiming or rendering. Acceptance cases are tracked in `DEBUG_VERIFICATION.md`.
