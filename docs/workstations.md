# Resource workstations

`content/Workstations.java` registers plain blocks, block items, POI types and villager professions through Forge deferred registries. Forge's POI registration callback installs the state mapping. The two profession workstations do not open a screen, inventory or crafting interface; the notice board has a read-only order screen.

| Block | Profession | Initial capability baseline | Appearance |
|---|---|---|---|
| `villageragent:carpenter_table` | `villageragent:lumberjack` | WOODCUTTING 3 | Crafting-table top; oak-log bark on the other five faces |
| `villageragent:miner_workbench` | `villageragent:miner` | MINING 3, QUARRYING 3 | Smooth-stone top, iron-ore sides, cobblestone bottom |

Unemployed adults claim the stations through `FindJobSiteSkill` and vanilla POI profession assignment. Each station has one ticket. Resource orders only recruit existing eligible workers; they never convert unemployed villagers into workers. Losing a station allows the normal job-site search to run again. `/va occupation` is an administrative override that updates the entity profession and clears the previous station reservation. Old separately saved resource roles reconcile with the actual profession on the next profession update.

Taking the profession grants starter items once (`JobStarterItems`: lumberjack iron axe, miner iron pickaxe), and the workstation grants daily supplies (lumberjack 8 oak logs, miner 16 cobblestone). Missing tools use ordinary acquisition; lumberjacks can craft wooden/stone axes and miners wooden/stone pickaxes themselves. See the [economy topic](capabilities_crafting_and_economy.md#profession-essentials).

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

The carpenter table is a profession workstation, not a usable vanilla crafting table. Recipes requiring a crafting table need a real `minecraft:crafting_table`. Initial capability levels follow the repository's profession baseline convention; ordinary villagers start with zero skills plus one deterministic level-one hobby, and nitwits are cleared. New extraction skills never inherit old GATHERING XP.

Compilation and static asset validation do not verify in-game POI claiming or rendering. Acceptance cases are tracked in `DEBUG_VERIFICATION.md`.

### Lumberjack tree session

The routine selects loaded remembered forest chunks using complete paths to sampled surface standing cells (nine columns per chunk). It retains the selected route until success/failure instead of retargeting every tick. In the chunk it compares complete routes to standable cells beside up to 16 trunk bases. Tree approach uses that cell with a strict arrival radius and rechecks block reach before chopping, not a walk into the trunk. Null/partial paths are unavailable; no straight-line fallback is used. Failed trees/chunks are excluded for the execution, body/child state is cleared, and another target is selected. Three distinct target failures end the execution with UNREACHABLE/FAIL and a minimum 1200-tick root cooldown; no remaining reachable forest also fails. After at least one tree has been felled, the same conditions end the session with SUCCESS instead. The outer goal failure policy remains separate and bounded.

Any log in the connected component may touch leaves; the selected trunk itself need not touch leaves. The goal-owned action holds one root for the summed break time of the validated 26-neighbor log component (maximum 512 logs), then removes the component. Village-protected components are rejected. Chopping plays the block hit sound every 4 ticks and a wood-break thunk on each swing. After a tree, `DropCollector` walks the villager to each dropped item within 12 blocks, nearest first (160 ticks per item, 1200 overall), until none is left; passive contact pickup takes the items. A session fells several trees: a quota of `min(6, 3 + level/2)` plus 0–1 at random (3–6 trees). After each tree's drops are collected it picks the next tree in the same chunk, then moves to the next remembered forest chunk when that chunk is empty; it ends at the quota, when the inventory has no free slot, or when the axe is gone. Between two trees the scheduler may switch task (a yield point; see [Goal utility](goal_utility_and_chance_factors.md#commitment-and-yield-points)). Real navigation requires live acceptance.

## Miner expeditions

`MinerWorkSkill` selects known ore districts by the available source order diamond, gold, redstone, lapis, iron, coal. Skill and suitable-tool gates apply before selection. Emerald is not a registered extraction source. Within a rarity tier, complete paths to standing cells beside remembered ore anchors determine proximity; the miner does not walk to a chunk's surface center. Unloaded anchors defer for 200 ticks and unreachable anchors for 1200 ticks. Each selection tick probes at most one anchor's neighboring standing cells.

An expedition keeps a fixed 3×3 district centered on the selected chunk. It first mines the selected source, then clears other exposed, reachable ores by local path cost, without quarrying stone or expanding the district. A surveyed ore block seeds a vein: the 26-connected blocks of the same eligible sources within 6 blocks (at most 24; stone at most 8) are queued and every exposed one is mined nearest-first without a new survey. After the vein, `DropCollector` walks the miner to each dropped item within 10 blocks until none is left. Mining takes `mining_time_multiplier` (default 3) times the player break time for the same tool.

Dark spots need a torch. A block, or a district destination, whose brightness (block or sky light ignoring time of day, brightest of the spot and its six neighbours) is below 10 (`MinerWorkSkill.MIN_LIGHT`) is skipped for 1200 ticks when the miner carries no torch (`mining-dark`). With a torch, the miner places one on the floor at or beside its standing cell on arrival (`mining-torch`). Miners keep 16 torches through their routine craft stock (see [routine crafting](capabilities_crafting_and_economy.md#routine-crafting)). The existing chunk memory supplies one representative position per ore type; visiting these positions at their recorded heights and surveying nearby blocks discovers additional ore. A survey inspects horizontal ±10, vertical -4/+5 around the villager, clipped to the district; this is a local visibility/reachability search, not an exhaustive underground scan. It never digs access tunnels. Surveys inspect at most 512 cells and probe at most 12 paths per tick, retain their cursors across ticks, and do not equate budget exhaustion with no resources. Already-mined personal targets are filtered before path selection. Failed paths, occupancy and unloaded terrain remain retryable.

After clearing a district, global rarity selection resumes. If no known eligible visit remains, the routine quarries nearby stone; it rechecks ore visits between blocks when remembered ore anchors change, or at a 100-tick cadence for deferred routes. It does not explore unknown terrain or force-load chunks. The existing random per-execution block quota and ordinary work/rest scheduler remain; each mined block opens a yield point where the scheduler may switch task. Finishing a quota or being interrupted preserves the district and clearing phase. Resumption revalidates destinations and routes. New days and changes to eligible ore types clear survey deferrals without abandoning an active district.

`MiningWorldData` stores personal mined positions and `MiningProgress` in per-dimension world saved data (`villageragent_mining`). Each villager UUID may yield once from each block per `gameTime / 24000` day, independently of other villagers. Both routine mining and acquisition extraction use the same ledger; `tickMine` checks and records eligibility immediately before producing a yield. Previous-day positions are pruned when the data is accessed. Missing data in older saves initializes empty. District phase, center, source, destination and anchor retry times survive saves; transient paths, cracks, quotas and reservations do not.

Reservations are per block, shared only while occupied: renewed for 100 ticks during walking/mining and released on body cleanup, completion, death or chunk unload; absent owners are pruned on access. Another miner can then receive their own daily yield. Stone bonus uses one mutually exclusive roll: coal probability `min(0.03, 0.01 + level*0.002)`, iron probability `min(0.01, max(0, level-2)*0.001)`, otherwise no bonus. Base cobblestone is unchanged. Diagnostics are `mining-visit`, `mining-vein`, `mining-phase`, `mining-yield`, `mining-dark` and `mining-torch`.

## Livestock, cooking, fletching and fishing

Vanilla professions with their own routines (seeded by `RoutineScheduler`, run by the skills named below).

**Livestock rule** (`AnimalCare`, shared by butcher, shepherd and fletcher). Cows, mooshrooms, pigs, sheep, chickens and rabbits within 32 blocks are split into village animals (inside the `VillageRegistry` village bounds grown by 12 blocks) and animals outside the village. Per species in the village: exactly two adults and no young → walk over and feed one of them with something it eats (`AnimalEntity.isFood`), so the pair breeds through vanilla animal AI; more than two adults → slaughter one adult. Outside the village any adult may be hunted. Named, leashed, ridden or carrying animals are never killed. With no known village nearby every animal counts as a village animal, so nothing is hunted. Slaughter takes at most two strikes with the best carried weapon (vanilla damage, so the animal's own loot table drops), then `DropCollector` gathers the drops. There is no pen detection: the village bounds stand in for the "animal enclosure".

**Butcher.** Starter items carrots, wheat and potatoes ×16 each plus coal ×4; daily supplies coal, wheat, carrots and potatoes ×4. Two routines:
- `butcher_work` (`ButcherSkill`): up to three livestock jobs per session under the livestock rule (feed, cull, hunt).
- `butcher_cook` (`CookSkill`): only selectable while raw meat is carried or an earlier load is due. Goes to the nearest smoker that has fuel or output (or that he can fuel), else a lit campfire with a free slot, within 24 blocks. Smoker: takes finished food from the output slot, loads one kind of raw meat, adds coal/charcoal (one per 8 items) when needed. Campfire: places raw meat on free slots via the vanilla campfire recipe. Waits by the station up to 640 ticks and collects what is done; food still cooking is collected on a later run.

**Shepherd** (`shepherd_work`). Starter items shears, 16 white wool and 16 wheat; daily supply wheat ×4. Feeds the village sheep pair first (livestock rule, feeding only, no culling), otherwise shears the nearest woolly adult sheep.

**Fletcher** (`fletcher_work`, `FletcherSkill`). Starter items iron shovel and bow; profession essentials bow and iron shovel (a fletcher without one gets the shared tool acquisition goal: crafting a bow needs string, which can be requested from the village — the fisherman's daily supply). Each session does up to four units, in order of need:
- fletch: with flint, a stick and a feather, make 4 arrows at the nearest fletching table (or in place), up to 64 arrows;
- dig gravel: with a shovel, dig exposed dry gravel within 24 blocks like the miner mines ore — the block stays, a dig yields one flint with 25 % chance and otherwise nothing (`BlockInteractionAction.tickMine` with a drop chance); each gravel block once per day per fletcher (`MiningWorldData`);
- hunt chickens for feathers under the livestock rule.
No sticks → an acquisition for 8 sticks is posted.

**Archers** (`ArcherCombat`). Any villager carrying a bow and arrows fights at range: it targets the usual threats or any visible monster within 16 blocks (creepers included, endermen ignored) and does not wait for a group charge. Closer than 4 blocks to a monster that can path to it → it runs until more than 7 blocks away (shoots point-blank when cornered); beyond 4 blocks and in sight within 16 → it stands, draws for 20 ticks and fires one arrow (skeleton aim, steadier), but only when no villager or iron golem is in the line of fire — otherwise it side-steps. Each shot uses an arrow and one bow durability; Power and Flame apply. A bow with arrows counts as combat power 6. Without arrows it falls back to the normal melee rules (including the group-charge requirement).

**Fisherman** (`fish`, `FishSkill`). Walks to the water's edge, then fishes a session of 3–5 catches. Each cast throws a `VillagerBobberEntity` (custom entity: vanilla `FishingBobberEntity` needs a player owner and is deleted at once otherwise) onto a visible water block 3–8 blocks away; the client renders the vanilla hook and a sagging line to the villager's rod tip (`VillagerBobberRenderer`). Vanilla timings: 100–600 ticks until a fish notices, 20–80 ticks of fish wake particles approaching, then the float is pulled under with a splash and the villager reels in after 6–14 ticks. The vanilla fishing loot table is rolled at the hook and each item is launched toward the villager with the vanilla retrieve velocity (owned by him while in flight), then collected. A hook that lands on dry ground is reeled in and recast (up to four failed casts).

**Baby villagers** (`VillagerBreeding`, `BreedSkill`). Managed villagers never run the vanilla brain, so vanilla breeding never happens. Instead an adult that is off its breeding cooldown, awake, fed (hunger ≥ 60), rested (fatigue < 60) and carries more than two days of food (`hunger_decay_per_minute` × 20 ÷ 8 food points per day; about two loaves of bread at the default) looks for another such villager within 24 blocks, provided there are more beds than villagers within 48 blocks. The pair is reserved, the initiator gets a one-shot `make_family` goal, walks to the partner (who stops and waits), both show hearts for 3 s, and a baby is spawned with `VillagerEntity.getBreedOffspring`. Each parent eats one day of food and gets the vanilla 6000-tick cooldown.

## Village notice board

`villageragent:notice_board` is a horizontal, two-sided wooden panel with its own block item and non-profession POI. Its generated structure is an oak fence with the panel immediately above it. Blockstates support all four horizontal orientations; models reuse vanilla wood textures. Chinese/English names, self-drop loot and a recipe (planks / paper / planks, three of each per row) are included. `/give @p villageragent:notice_board` supplies a panel for manual replacement; right-click opens its village order screen.

Minecraft 1.16.5 has bells. Economy maintenance lazily adds one structure to each discovered vanilla village without a live board, including existing saves. It prefers a loaded meeting/bell POI inside that village, falling back to the registered village center. It searches loaded surface columns 2–12 blocks from the anchor, inside the village bounds, requiring natural ground (grass, dirt, path, sand, gravel, snow, stone, terracotta, sandstone and similar; never a roof such as the well's), two empty blocks and an adjacent standing cell on natural ground, without replacing existing blocks. If none qualify, it retries later. The position is saved in `VillageRegistry`.

A notice board a player places (`NoticeBoardEvents`) becomes the board of the village whose bounds contain it, else of the nearest registered village within 32 blocks; the previous board block stays as decoration. Breaking the registered board clears it; the village then adopts the next board placed, or gets a new generated one. This is first-discovery placement, not a jigsaw house-pool lottery. No board is generated for unaffiliated villagers outside registered vanilla villages.

Economic reading/posting requires a loaded panel within three blocks and an unobstructed ray from the villager's eyes. Navigation targets reachable adjacent ground; failure, removal or unloading cannot count as a successful visit. See the economy and task-method topics for trading behavior.

### Notice-board player interface

Right-click sends the interacting player a server-generated page of local requests. The request tab lists item icon/name (exact item, category, or an "or" request shown as `Stick (or 2 Oak Planks / 1 Oak Log)`), quantity, author and either “Unclaimed” or the provider whose claimed delivery is in progress; the stock tab lists advertised shareable stock. Prices and economic terms are absent. Non-exclusive production has no unique producer and is not shown.

Pages contain four orders, sorted newest first with stable message-ID tie breaking. Previous/next, Refresh and Close controls are provided; the non-pausing screen refreshes every 40 client ticks. Long text is clipped in rows and available in a hover tooltip. Resolved, expired, consumed, zero-quantity and itemless entries are filtered without modifying the board. Empty villages and boards outside any registered village have separate empty states.

The server resolves the village from the clicked board's saved position or containing vanilla village bounds, not the player's nearest market. It validates a loaded notice-board block and a living player within eight blocks for every page request. The client closes the screen when the board disappears, the player leaves range, or the dimension changes; late refresh replies do not reopen a closed screen. Packets have explicit directions and bounded page/string fields. Network protocol version is 3, requiring matching client/server builds. This interface is inspection-only and cannot accept/cancel orders or move inventory.
