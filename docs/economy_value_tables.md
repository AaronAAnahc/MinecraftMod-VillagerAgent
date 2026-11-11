# Economy value tables

Reviewed: 2026-10-31. This topic documents the populated content in
`src/main/resources/data/villageragent/economy/`. Loading order, personal-table construction and
bundle settlement remain in [Capabilities, crafting, and economy](capabilities_crafting_and_economy.md);
this page only covers **what the numbers are and why they are those numbers**.

## Files and layers

| File | Role | Entries now |
|---|---|---|
| `item_values.json` | profession → item → subjective value. Key `default` is the shared baseline; other keys override it per profession | 161 items in `default`, 131 profession overrides across 15 professions |
| `junk_values.json` | item → universal floor value, lowest precedence, no profession owns it | 14 items |
| `payment_accepts.json` | profession (or `default`) → item → `{priority, retain}`. Payment preference and sellability | 17 professions incl. `default` |
| `active_offers.json` | profession → list of items advertised as standing offers (sell priority) | 15 professions |
| `price_modifiers.json` | `playerMargin` 1.1892, `selfProduceDiscount` 1.0 | 2 keys |
| `profession_essentials.json`, `item_sources.json` | tool-only minimum reserves and source reference data | — |

Merge order when a personal table is built is `junk → default → profession`, so a profession entry
always wins over the baseline and the baseline always wins over junk. Every item that appears in two
layers would silently take the higher layer; the layers are kept disjoint on purpose and
`scripts/check_value_tables.py` reports any overlap.

## Unit and anchor

Value is a dimensionless point scale anchored at **one emerald = 10 points**. A price is only ever a
comparison between two entries, so the absolute scale matters only for readability: an item priced
`0.70` is worth 0.07 emerald to that villager.

**General equivalent.** `minecraft:emerald` appears in `default` only. Because profession tables override
`default`, keeping it out of every profession table is what makes the currency identical for everyone
("identical value in everyone's eyes"). Adding an emerald entry to any profession table would silently break that.

The currency is the only item no profession discounts. Smelting is not implemented, and no profession
mints ingots, so there is no second general equivalent.

## Price formation

| Side | Formula | Source |
|---|---|---|
| Villager ↔ villager subjective value | the table value itself | `villagerValue` |
| Villager sells to the player | value × `playerMargin` | `sellPriceForPlayer` |
| Villager buys from the player | value ÷ `playerMargin` | `buyPriceForPlayer` |

`selfProduceDiscount` is set to **1.0**: profession discounts for own output are hand-written into
`item_values.json`, so the runtime must not discount a second time. See *Maintenance* before changing it.

`playerMargin = 1.1892 = 2^(1/4)`. Because both legs of a player trade carry the margin, an
item↔emerald round trip costs exactly **2×**: buy in and sell back out and the player loses half. The
per-line spread is `M² = 1.4142`. With the old `1.8` the round trip was `1.8⁴ ≈ 10.5`, which made every
round trip punitive. Setting `playerMargin` to `1.4142` instead would give a per-line spread of exactly 2
and a round trip of 4 — pick one deliberately, both are one edit in `price_modifiers.json`.

## Profession → own output (0.8× rule)

A profession prices what it can produce at roughly **0.8× the baseline**, on both the buy and the sell
side, because the personal table holds one value per item. Lower value means cheaper sale price and a
worse rate when someone sells the item to that villager. The numbers are written out per item by hand;
nothing recomputes 0.8 at runtime.

| Profession | Discounted output | Workstation note |
|---|---|---|
| `farmer` | crops, seeds, bread, cookies, cake, pumpkin pie, golden carrot | composter |
| `fisherman` | raw and cooked fish, fishing rod, string | barrel |
| `shepherd` | all 16 wool colours, white carpet, mutton | loom |
| `fletcher` | arrow, bow, crossbow | fletching table |
| `librarian` | book, bookshelf | lectern |
| `cartographer` | paper, empty map, filled map | cartography table |
| `cleric` | glass bottle, ender pearl | brewing stand |
| **`armorer`** | iron/chainmail/diamond armour **and iron/gold ingots + nuggets** | **blast furnace** |
| `weaponsmith` | iron sword, iron axe, diamond sword | grindstone |
| `toolsmith` | iron and diamond tools | smithing table |
| `mason` | stone, stone bricks, chiseled bricks, polished andesite, bricks | stonecutter |
| `leatherworker` | leather armour, saddle | cauldron |
| `butcher` | cooked meats, rabbit stew | smoker |
| `villageragent:lumberjack` | logs, planks, sticks, wooden axe | custom POI |
| `villageragent:miner` | coal, all ores, cobblestone, gravel, flint | custom POI |

The armorer owns the vanilla **blast furnace**, so it also discounts ingots and nuggets ahead of smelting
being implemented: once smelting lands, the miner's ore (`iron_ore` 2.40 for the miner) flows to the
armorer, who turns 3.20-valued ingots into armour priced at 16.00–32.80. Note that **no profession is
currently able to smelt**, so raw ore is priced below the ingot it would become (`iron_ore` 3.00 vs
`iron_ingot` 4.00) purely to leave the future margin visible.

Nitwit and unemployed carry no overrides: they use the baseline for everything.

## Worked numbers

`internal` is the villager-to-villager rate in points per emerald (emerald = 10); `give N` is how many the
player must hand over for one emerald; `get M` is what one emerald buys back. Figures from
`scripts/check_value_tables.py`.

| item | holder | value | items per emerald | give N for 1 emerald | get M per emerald | emeralds per unit |
|---|---|---|---|---|---|---|
| coal | baseline | 0.70 | 14.29 | 21 | 10 | 1 |
| coal | miner | 0.56 | 17.86 | 26 | 12 | 1 |
| wheat | baseline | 0.50 | 20.00 | 29 | 14 | 1 |
| wheat | farmer | 0.40 | 25.00 | 36 | 17 | 1 |
| bread | baseline | 1.80 | 5.56 | 8 | 3 | 1 |
| bread | farmer | 1.44 | 6.94 | 10 | 4 | 1 |
| iron ingot | baseline | 4.00 | 2.50 | 4 | 1 | 1 |
| iron ingot | armorer | 3.20 | 3.12 | 5 | 2 | 1 |
| iron pickaxe | baseline | 16.00 | 0.62 | — | — | 3 |
| iron pickaxe | toolsmith | 12.80 | 0.78 | — | — | 2 |
| iron chestplate | baseline | 41.00 | 0.24 | — | — | 6 |
| iron chestplate | armorer | 32.80 | 0.30 | — | — | 5 |
| diamond | baseline | 30.00 | 0.33 | — | — | 5 |
| diamond | miner | 24.00 | 0.42 | — | — | 4 |
| wool | baseline | 1.00 | 10.00 | 15 | 7 | 1 |
| wool | shepherd | 0.80 | 12.50 | 18 | 8 | 1 |
| cod | baseline | 2.00 | 5.00 | 8 | 3 | 1 |
| cod | fisherman | 1.60 | 6.25 | 9 | 4 | 1 |

Coal is the calibration case: the baseline coal value 0.70 puts the internal rate at 14.3 coal per emerald,
the player pays ~21 coal for one emerald and gets ~10 back — the 20/10 round trip the table was tuned for.

## Sellability and payment preference

`payment_accepts.json` does two things that the value tables cannot:

- **`retain` gates selling.** With no rule at all, any profession-essential with a positive minimum is
  retained outright and the villager cannot sell or pay with it. Every essential therefore has an explicit
  `retain: false` rule, which still keeps the reserved minimum (`available = stock - reserve`). This is why
  the farmer can actually part with surplus wheat above its reserve of 24, and why `active_offers` listing
  food has any effect.
- **`priority` breaks ties** when two candidate payment items have the same seller/buyer valuation ratio.
  Own output is 9 (happiest to hand it over), neutral goods 3–7, tools and working stock 1–2 (reluctant).
  `default` supplies the fallback for any item a profession table does not mention.

`active_offers.json` lists each profession's product lines so surplus is re-published even when ordinary
surplus advertising is filtered by stock or reserve changes.

## Anti-minting policy

Every profession-specific product is priced **above its own recipe cost at that profession's own prices**,
so producing and selling is profitable; margins run about 20–30% for tier-1 goods. Conversely every lever
that could print value from unpackaged material is priced at or below cost: logs→planks is exactly
break-even, planks→sticks and coal+stick→torch are slightly loss-making, wheat→seeds loses value, and
9 nuggets never exceed the ingot they came from. Verified by `scripts/check_value_tables.py`, which
recomputes recipe costs from the vanilla client jar and `custom_recipes.json`; the only reported losses are
the intentional `stick` and `torch` ones.

## Verification performed

Read-only checks only — no compilation and no in-game run:

- All five economy files parse; every key resolves to a real 1.16.5 item id (checked against
  `assets/minecraft/models/item/` in the client jar).
- `junk_values` and `default` are disjoint.
- Every profession-essential is priced and has a `retain: false` rule, so nothing is silently unsellable.
- Every `active_offers` and `payment_accepts` entry is priced for its profession.
- Per-profession craft profitability as described above.
- Item ids and recipe shapes (map needs a compass, cookie yields 8, glass bottle is 1:1, chiseled bricks go
  through slabs) were taken from `data/minecraft/recipes/` rather than from memory.

Pre-existing problems noticed while populating, **not** fixed here: `SkillCraftCatalog` gives the master
librarian a `minecraft:quill` recipe for an item that does not exist in 1.16.5, `item_sources.json` still
lists `minecraft:cotton` and a bare `minecraft:planks`, and `VillagerEconomySystem.emeraldCost` has no caller.

## Maintenance

1. To add or change a price, edit the layer that owns the item — junk, `default`, or the profession. Do not
   duplicate an entry across layers.
2. Keep `minecraft:emerald` out of every profession table.
3. When you add a profession product, add its discounts to that profession table, list it in
   `active_offers`, and give it a `retain: false` rule if it is an essential.
4. Re-run `python scripts/check_value_tables.py` and read the loss list; anything new there is either a bug
   or a deliberate brake that belongs in the policy section above.
5. Existing villagers keep their personal table for the session. Use `/va reset_values <selector>` (or
   reload the world) to rebuild them from the new templates. The conversion happens automatically whenever
   the profession changes.

Not implemented and deliberately out of scope: separate buy and sell columns per profession (the personal
table still stores one value, buy and sell both derive from it), currency-level price drift or inflation,
and durable smelting-dependent price gaps until smelting itself exists.
