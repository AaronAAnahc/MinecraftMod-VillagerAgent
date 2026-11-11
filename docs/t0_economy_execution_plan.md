# T0 Execution Plan: Economy System Refactor and Decision Convergence

> This document is the convergence result of step 1 of [village_ecology_loop_plan.md](village_ecology_loop_plan.md) §10 — "clarify undecided economic rules, unify inventory transactions and input/output units" — and gives directly buildable batch divisions.
> This document only describes "what will be built", not the discussion process. After implementation completes, behavior is governed by the topic documents under `docs/`.
> Status markers: **Decided** = the user has signed off; **Recommended** = has a default value and can be built directly, changeable at any time; **Pending** = blocks construction and must be decided first.

## 0. Scope

This plan covers six things: the price model, orders and bundle settlement, village affiliation, removal of free supply, initial endowment, and the mine workbench. Action timing, logging exclusion, ration reservation, and the smelting chain are only listed as dependencies, not built in the first T0 batch.

Four cross-cutting constraints:

1. Currency is no longer the only payment method; emeralds are downgraded to a unit of account and change.
2. All prices can be adjusted at runtime by multiple sources, so invariants must be guarded by code, not by data-file self-discipline.
3. Village identity uses the vanilla Structure, not the POI distance field, not the `/locate` primitive.
4. Removing free supply must be delivered in the same batch as the paid chain, otherwise the intermediate version cuts off all external supply.

---

## 1. T0 decision table

| # | Decision item | Status | Conclusion |
|---|---|---|---|
| 1 | Remove free supply | Decided | Remove three channels, keep no toggle |
| 2 | Currency system | Decided | Physical bundle payment primary, emerald as unit of account and change; not fully dependent on emeralds |
| 3 | Initial endowment | Decided | Grant 8 emeralds once when a villager joins a village; remove wooden-axe/stone-pickaxe grants |
| 4 | Village affiliation criterion | Decided | Vanilla Structure bounding box + own persistent registry; placing a bell does not create a village, dismantling a village does not delete records |
| 5 | Price model | Decided | Each villager has only one **valuation** per item, buy price == sell price; buy/sell prices for outsiders derive from "valuation × player margin" |
| 5b | Bundle selection rule | Decided | Read both sides' two valuation tables; after excluding forbidden items, **minimize the buyer's outlay**, with the constraint of **reaching the seller's asking price**; pick payment items in descending valuation ratio, change items in ascending |
| 6 | Off-table items | Decided | Four-layer lookup chain, a miss means valuation absent (no listing, no payment use); a player forcing it in follows the existing "throw-in" semantics |
| 7 | Mine output scope | Recommended | First batch `cobblestone / coal / iron_ingot`, no `iron_ore`, no smelting chain |
| 8 | Mine food accounting | Pending | Extra recipe input vs raising personal hunger |
| 9 | Action duration | Pending | Break duration exists, other actions have none |
| 10 | Logging "outside village" criterion | Pending | Whether to exclude in-village tree farms |
| 11 | Ration/seed reserve ratio | Pending | Affects `profession_essentials.json` |
| 12 | Player margin and max price delta | Recommended | Both bound by the §2.5 formula; margin 1.8 supports ±50% |
| 13 | Self-produce discount granularity by capability level | Recommended | Uniform 0.7 first, level granularity after T0-E |
| 14 | Expressing "do not sell" for profession tools | Decided | Use the `retain` flag in the data table, **do not set the sell price to infinity** (see §2.8) |

---

## 2. Price model

### 2.0 What each quantity actually is (name them first)

Every variable in this document first gets a natural-language meaning, then a code identifier.

| Quantity | Meaning | Colloquial | Code identifier |
|---|---|---|---|
| **Villager valuation** | A villager's subjective value of an item. Buying pays by it, selling charges by it, **the two prices are exactly equal** | "How much this thing is worth to me" | `villagerValue(agent, item)` |
| **Player margin** | The markup multiplier when a villager trades with a player. Selling to player = valuation × margin; buying from player = valuation ÷ margin | "Charge outsiders an extra cut" | `playerMargin` |
| **Player sell price** | What a player pays to buy this thing | — | `sellPriceForPlayer` |
| **Player buy price** | What a player gets for selling this thing to a villager | — | `buyPriceForPlayer` |
| **Max price delta** | How much LLM and other factors can float above/below the base valuation | "How biased one person can be" | `maxPriceDelta` |
| **Self-produce discount** | The discount a villager applies to the valuation of things they can make themselves | "Don't treasure what you can make yourself" | `selfProduceDiscount` |
| **Retain flag** | The item cannot be used for payment or listed for sale (**but can still be bought**) | "This is my livelihood, not for sale even for money" | `retain` |
| **Asking price** | The minimum total value a seller requires to part with an item, **converted at the seller's own valuation** | "I must at least trade back this much" | `askingPrice` |
| **Outlay (paid)** | The buyer's total valuation of the bundle they hand over (**before change**), | "How much it hurts to hand these over" | `paidByBuyer` |
| **Net outlay** | Outlay minus the value of the returned change in the buyer's eyes | "How much I actually lose after change" | `netPaidByBuyer` |
| **Gained value** | The buyer's total valuation of the acquired target items | "How much I get back is worth to me" | `gainedByBuyer` |
| **Outlay ratio** | Outlay ÷ gained value | "Is this deal worth it" | `costGainRatio` |
| **Valuation spread** | For the same item, "seller valuation ÷ buyer valuation", the sole sort key for picking payment and change items | "Who values this more" | `valuationSpread` |
| **Overshoot** | The part of the last payment item that exceeds the asking price. Items can only be paid whole, so this necessarily exists | "The extra paid slice" | `overshoot` |
| **Change** | The seller returns items from their own inventory to offset the overshoot | — | `change` |
| **Villager-to-villager spread** | **Deprecated**. An early version gave each villager separate buy/sell prices per item; now "buy = sell" makes it identically 1, no longer existing | — | none |

### 2.1 One villager has only one valuation per item: buy price equals sell price

Each villager has only **one valuation** per item. Buying pays by it, selling charges by it, **the two are equal**.

The difference is not within one villager, but **between villagers**. The same item's valuation varies by profession and person:

| Villager | Bread valuation | Iron-pickaxe valuation |
|---|---|---|
| Farmer | Low (produces grain) | High |
| Smith | High | Low (produces tools) |

Profession differences like "farmer sells grain cheap, smith sells tools cheap" all fall into the initial template table, generated by the §2.6 discount rule — **no need to hand-write every combination**.

### 2.2 This constraint automatically eliminates circular arbitrage

Let A value an item lower than B values the same item:

| Direction | Result |
|---|---|
| B pays A to buy: A asks by their own (low) valuation, B is willing to pay at most their own (high) valuation | Room to trade, item flows from low-valuation to high-valuation side ✓ |
| A pays B to buy: A can pay at most their own (low) valuation, while B asks their own (high) valuation | **Never trades** ✓ |

The reverse leg never profits ⇒ **the A→B→C→A carry loop does not exist in this model**. The §3.3 minimization objective and §3.4 change are both accounted only within a single transaction and do not change this property. This is the biggest benefit of "buy = sell": `HOLDING` no longer needs to suppress cyclic carrying; it degrades to solving only the narrower problem of "will a villager sell all their food".

### 2.3 The selection rule itself is the behavior rule

Payment items are not queued by "whose price is higher", but by solving a minimum-cost problem: reaching the seller's asking price while making the buyer hurt least (§3.3). "A villager prefers to sell what they can produce" is the computed result of this rule, not a separate write.

Farmer buying a pickaxe from a smith:

| Step | Result |
|---|---|
| Smith's asking price = smith's pickaxe valuation × quantity | Smith self-produces pickaxes ⇒ low valuation ⇒ **low asking price** |
| Picking payment items looks at valuation spread = smith valuation ÷ farmer valuation | Wheat is discounted only by the farmer ⇒ high spread ⇒ **wheat picked first** |
| When wheat reaches the asking price, farmer's outlay | Farmer self-produces wheat ⇒ low valuation ⇒ **low outlay** |
| Farmer's gained value = farmer's pickaxe valuation × quantity | Farmer cannot make pickaxes ⇒ no discount ⇒ **high gained value** |

Note the discounts on both sides multiply: the seller discounts their own output once and the buyer discounts their own output once, so at self-produce discount 0.7 the outlay ratio is about 0.49. **Supply and demand each at 0.7 stack into 0.49**, a direct consequence of the self-produce discount value — keep it in mind when tuning.

Thus "a villager prefers to sell what they can produce" only needs to be **reflected in the valuation**, not an extra listing probability or behavior rule — the selection rule computes this preference automatically.

### 2.4 Player buy/sell prices derive from villager valuation

```
Player buy price = villager valuation ÷ player margin   // what a player gets selling to a villager
Player sell price = villager valuation × player margin  // what a player pays buying from a villager
```

Player margin > 1 is a **numeric value in a JSON table** (`price_modifiers.json`'s `playerMargin`, see §2.10), not a code constant.

The three invariants `player buy price ≤ villager valuation ≤ player sell price` **hold automatically from the above**, needing no runtime check and no sync between two tables.

`item_values.json` therefore simplifies from the `{buy, sell}` pair to a **single villager valuation**, with the player dimension computed at read time.

> **Deviation from "store two tables"**: this downgrades the "villager↔player table" from **storage** to **derivation**. To give different margins per profession or item, add overrides in `price_modifiers.json`, defaulting to the global value. Do not return to writing one valuation in two places — the same number written twice inevitably desyncs.

### 2.5 Allowing ±50% personal price adjustment requires a large enough player margin

Cross-villager player intermediation arbitrage happens like this: the player buys an item from A at A's **sell price**, then sells it to B at B's **buy price**; as long as "B's buy price > A's sell price", it is free profit.

The no-arbitrage condition is:

```
max price delta  ≤  (margin×margin − 1) / (margin×margin + 1)
```

Where "margin×margin" is the multiple between the player's buy and sell prices, see the second column below.

| Player margin | Player buy/sell price multiple | Allowed max delta |
|---|---|---|
| √2 ≈ 1.414 | **2× (current item_values.json)** | **33.3%** |
| 1.6 | 2.56× | 43.9% |
| √3 ≈ 1.732 | 3× | 50.0% (exactly at the boundary) |
| **1.8** | **3.24×** | **52.8%** ← recommended |
| 2.0 | 4× | 60.0% |

**This is the hidden premise of "LLM can do ±50%"**: the current price table has only a 2× spread for outsiders, supporting only ±33%. To open up to ±50%, the player margin must be raised to ≥ 1.8, at the cost of players buying more expensive and selling cheaper. This is the only place in this change that needs an external trade-off.

### 2.6 Self-produce discount: villagers value what they can make less

A villager discounts the valuation of things they can produce, so they sell cheap and also cannot afford to buy high:

```
own valuation = base valuation × self-produce discount
```

**The self-produce discount is written in a JSON table** (`price_modifiers.json`, default 0.7, overridable per profession), **not in code**. This lets balance tuning skip recompilation, and lets profession differences like "smith especially scorns tools" be configured separately.

**Who can produce what — the determination sources all already exist, no new table needed**:

| Source | Gating | Coverage |
|---|---|---|
| `SkillCraftCatalog.isUnlockedFor(capability pool, profession, item)` | profession **and** capability | Crafted products (tools, equipment, general goods) |
| `ResourceCatalog.get(item)` + that villager's matching capability level ≥ 1 | capability | Farming / logging / mining / quarrying products |

Note `ResourceCatalog` registers crops under `ResourceProfession.NONE` + `CapabilityId.FARMING`, so on the food side what actually takes effect is **capability, not profession**: a librarian who trained farming will also undervalue wheat. This matches the "can produce" semantics and is more accurate than "is a farmer".

Whether to further refine by capability level (higher level → lower valuation) is pending, see §9.2 item 9.

### 2.7 Profession template + offsets from multiple sources

Only the **valuation** itself needs per-source offsets (recorded as source offsets); the buy/sell prices for outsiders derive from the valuation and follow automatically, never adjusted separately.

```
final valuation = base valuation × product of discount factors × (1 + sum of source offsets)
```

| Source | Trigger | Decay (written in the table, see §2.10) |
|---|---|---|
| `TEMPLATE` | The **base valuation** itself from the profession template | No decay |
| `LLM` | Preferences formed by personality, context, and conversation | −10% per game day |
| `SCARCITY` | Ratio of whole-village stock to in-transit demand | −20% per game day |
| `HOLDING` | Holdings deviating from the comfort zone (prevent selling all food) | Recalculated in real time, not accumulated |
| `TRADE_HISTORY` | Recent transaction prices | −20% per game day |

Reason to store offsets rather than final values: multiple sources don't overwrite each other; decay per source independently; old saves automatically benefit from template updates.

**Clamping happens on write**: the write-offset interface computes the final valuation once internally, and if it exceeds the bounds, trims that offset and logs, without persisting. The read path stays pure computation.

`TRADE_HISTORY` is **positive feedback**; one accidental high price amplifies into a trend. Recommend not using it for valuation adjustment, but as the acceptance-probability correction in §3.5.

### 2.8 Retain flag: tools of the trade are not for sale even for money

First clarify how "a miner pays their only stone pickaxe as change" happens — this is an **existing code gap**, not introduced by new rules.

In a bundle trade, the one handing items out is the **buyer**, and the seller chooses what to take from the buyer's inventory. The existing `offerDemandProduction` only deducts the **seller's own profession** reserves when computing "how much can be sold":

```java
java.util.List<Essential> reserves = getEssentials(producer.getProfession());  // seller only
```

**The buyer's profession essentials were never checked.** So when a miner buys bread as a buyer, the seller (farmer)'s essentials list has no stone pickaxe, and the pickaxe is taken as ordinary change.

**The fix cannot be "set the pickaxe sell price to infinity".** Because buy price == sell price is the same valuation; once made unsellable, the miner can **never buy a replacement pickaxe** — a broken pickaxe means permanent unemployment.

The correct approach separates "hard to sell" from "forbidden to sell":

| What to express | Where | Effect |
|---|---|---|
| How much this thing is worth | The valuation in `item_values.json` | Buy and sell use the same number |
| This thing **must not be parted with** | The `retain` flag in `payment_accepts.json` | Cannot be used for payment, cannot be listed for sale; **but can still be bought** |

`retain` is a flag independent of valuation, not a sentinel price. It is data, not code:

- The default value **auto-derives** from `profession_essentials.json` — every item with `minQty > 0` automatically gets `retain`, no second hand-write. Current table already has: miner `stone_pickaxe`, lumberjack `wooden_axe`, farmer 4 items.
- Can still be manually appended in `payment_accepts.json` (e.g. "do not hand over weapons when under attack", a non-profession rule).

Runtime effect: `retain` items are filtered in four places — payment-bundle candidates (check the **buyer**, items are taken from them), change-bundle candidates (check the **seller**, change is returned from them, see §3.4), `advertiseSurplus` listings, and `active_offers` proactive sell listings.

### 2.9 Lookup chain and off-table items

Four layers, short-circuiting per layer:

| Layer | Source | On miss |
|---|---|---|
| 1 | The villager's personal table (template reference + per-source offsets) | Next layer |
| 2 | Profession template | Next layer |
| 3 | **Global junk table** (hand-maintained common junk: `rotten_flesh / stick / gravel / flint`) | Next layer |
| 4 | No price to look up | Stop |

**The earlier "buy 0.01, sell infinity" pair no longer holds after "buy = sell"** — the same valuation cannot be both low enough to take freely and high enough to be unsellable. Off-table state has only one representation:

| Layer | Result |
|---|---|
| Hit layer 1–3 | Has a valuation, can buy and sell, can enter payment bundles |
| All four miss | **Valuation absent**: no sale listing, no payment bundle; a player forcing it in is not valued but physically accepted, consistent with `SPEC_ECONOMY.md`'s existing "unknown item value 0.01, does not raise valuation, but accepted as a throw-in" |

That is, the layer-3 global junk table upgrades from "fallback" to **the only place deciding whether a piece of junk can circulate**. To make rotten flesh circulable, write it into layer 3; anything not written is pure placeholder.

Since valuation can be absent, the `Double.POSITIVE_INFINITY` sentinel is unneeded — JSON cannot write Infinity, and NBT, though supporting it, cannot be expressed in data files either. **Absence as `null` is far safer than a magic number.**

The NaN risk is downgraded but not gone: the player trade path still has a chance of receiving a non-finite value (see §3.7). Payment-bundle candidates still need an explicit `isFinite` check before summing — "infinity − infinity" and "0 × infinity" both produce NaN, and NaN in any comparison is always false, so failures manifest as "mysteriously refuses to trade" rather than errors.

### 2.10 Data files

| # | File | Status | Content |
|---|---|---|---|
| 1 | `item_values.json` | Rework | `profession → item → single valuation` (replaces `{buy,sell}`); add `villageragent:lumberjack` / `villageragent:miner` |
| 2 | **`price_modifiers.json`** | **New** | All balance coefficients, see table below |
| 3 | `junk_values.json` | New | Global junk table, `item → valuation` |
| 4 | `profession_essentials.json` | Unchanged | Reserve inventory, the default source of `retain` |
| 5 | `payment_accepts.json` | New | `profession → item → {payment priority, retain}`; profession tools auto-derive `retain` from essentials |
| 6 | `active_offers.json` | New | `profession → proactively listed items for sale` |
| 7 | `item_sources.json` | Unchanged | self-obtainable determination |

**`price_modifiers.json` is the only landing point for all numeric values in this change**, no price or time constant in code:

| Key | Default | Meaning |
|---|---|---|
| `playerMargin` | **1.8** | Player margin, overridable per profession/item |
| `maxPriceDelta` | **0.5** | Max price delta (±50%) |
| `selfProduceDiscount` | **0.7** | Self-produce discount, overridable per profession |
| `llmDecayPerDay` | 0.1 | LLM adjustment daily decay |
| `scarcityDecayPerDay` | 0.2 | Scarcity daily decay |
| `historyDecayPerDay` | 0.2 | Transaction-price memory daily decay |

> This is consistent with the existing convention: `.toml` only holds behavior toggles (enable or not), JSON holds content data and balance values.

Table files no longer need "villager / player" duplicates, because the player side is derived (§2.4); the same valuation written twice inevitably desyncs.

Migration: each item's base valuation takes `sqrt(old buy price × old sell price)` (their geometric mean), generated by a one-time script. The old 1.414× internal spread is taken over by the player margin after migration.

`active_offers` cannot derive from `item_sources.json`: the latter has only 32 items, not divided by profession, and `oak_log` is marked `MINED`, while `cobblestone / iron_ore / stone_pickaxe` are all missing.

**"Willing to sell" is not made into a table**: computed at runtime from "backpack − reserve", a static table would make villagers peddle things they do not have.

### 2.11 NBT and migration points

The personal table structure changes to `Map<Item, Map<Source, Offset>>` (**stores only valuation offsets**, not final values, nor buy/sell prices for outsiders). Add `PriceVersion`; on version mismatch set `personalValueTable = null` for the next access to rebuild (`setProfession` already does this, at zero cost).

Read-side locations needing changes:

| File | Location | Change |
|---|---|---|
| `VillagerEconomySystem` | `buyValue` / `sellValue` | Split into `villagerValue(agent, item)` and `buyPriceForPlayer` / `sellPriceForPlayer`; the latter two computed from valuation and player margin |
| `VillagerEconomySystem` | `emeraldCost` | Conversion switches to `sellPriceForPlayer`, currency anchored to the emerald valuation |
| `VillagerEconomySystem` | `unitPrice` | Villager path uses valuation, player path uses `sellPriceForPlayer` |
| `TradeRequestPacket` | 227 / 234 | Switch to `buyPriceForPlayer` / `sellPriceForPlayer` |
| `AgentDebugExporter` | 135-137 | Export valuation, the two external prices, and offsets split by source |
| `VillagerAgentData` | 1357-1360 / 1518-1524 | NBT read/write structure change |
| `VillagerEconomySystem.setPrice` | 445 | Split into write-offset / clear-offset (by source) two interfaces |

---

## 3. Orders and bundle settlement

### 3.1 Order structure

The buyer publishes "what to buy + price" and "what to sell + price" to the village message pipeline simultaneously. Sell orders carry their own quote, no longer zero-price shared lists.

Orders need new fields: `unitPrice`, `escrowReserved`, `delivered`, `paid`, `villageChunk`. **Escrow uses ledger reservation, not physical transfer** — entity loss equals vanishing into thin air, and the inventory transaction layer must do reservation anyway.

### 3.2 Filter candidates first: exclude unsellable items

The candidate set = the buyer's backpack items satisfying all four conditions below. Each table reads its own copy:

| Filter condition | Check whom |
|---|---|
| Not in the buyer's `retain` reserve | Buyer retain flag (§2.8) |
| Not in the buyer's profession-essential reserve share | Buyer essentials |
| Exists in the **seller**'s valuation table, not absent (§2.9) | Seller valuation table |
| Buyer's available quantity > 0 | Buyer backpack |

The one being emptied is the **buyer**, so the first two only check the buyer; the third checks the seller. The existing `offerDemandProduction` only deducted the seller's essentials; the buyer's side was never checked — that is how the miner's pickaxe was paid as change (§2.8).

The third condition need not be a separate rule: a seller-valuation-absent item contributes 0 in the constraint, cannot reach any asking price, and is auto-excluded by the solver. Writing it in the table makes it explicit, independent of solver behavior.

### 3.3 Then solve an optimization problem: meet the seller's cost, hurt the buyer as little as possible

```
Minimize  outlay = Σ buyer valuation(item) × quantity      ← hurt the buyer as little as possible
Subject   asking price ≤ Σ seller valuation(item) × quantity ← let the seller break even
          quantity ≤ the item's available amount in the buyer's hand
```

"Break even or not" reads the seller's table, "hurt or not" reads the buyer's table. The two tables each play a role in the same constraint; neither can be omitted.

Solve rule: **greedy in descending valuation spread**.

```
valuation spread = seller's valuation of the item / buyer's valuation of the item
```

Hand out higher-spread items first ("most useful to the seller, least painful to the buyer first"). Two corollaries grow directly:

- Buyer can produce it ⇒ discounted in the buyer's table by self-produce discount ⇒ spread rises ⇒ **picked first**;
- Seller can also produce it ⇒ likewise discounted in the seller's table ⇒ spread falls ⇒ **not picked**.

Thus "the buyer pays with their own output, and it is something the seller cannot make" happens automatically. `payment_accepts` priority therefore degrades to **a tiebreak among equal-cost solutions** — it cannot decide whom to pick, or it fights the minimization objective.

**No-solution determination**: emptying all candidates still cannot reach the asking price, so this physical trade does not hold, fall back to emerald payment or waiting, **do not degrade to "give whatever you have"**.

### 3.4 Overshoot must be resolved by change, otherwise emeralds can never be spent

The constraint is "≥ asking price", but items can only be paid whole. Buying a 0.8-valuation bread with a 10-valuation emerald makes the last item's overshoot push the outlay ratio above 8 and the acceptance probability to zero — an economically completely reasonable trade blocked by denomination.

Change returns the overshoot:

```
net outlay    = Σ buyer valuation(paid) − Σ buyer valuation(change received)
seller net income = Σ seller valuation(received) − Σ seller valuation(change given) ≥ asking price
```

The change selection rule is **exactly opposite** to payment: make the seller lose less on their books and the buyer recover more, so pick **lowest-spread** items to give as change. Lowest spread = the seller can make it and the buyer cannot ⇒ **change automatically uses the seller's output**.

Together the two are symmetric: **payment uses the buyer's output, change uses the seller's output**, each side clearing its own surplus inventory.

This also explains the emerald's role: everyone values it identically, spread is identically 1, ranking after the high-spread self-produced goods, and never picked as change. It only appears as the last small slice filling the overshoot — exactly matching "emerald downgraded to unit of account and change". **Without the change mechanism, emeralds are almost unusable among villagers.**

When the seller cannot make change, the behavior must be decided, see §9.1 item 7.

### 3.5 Acceptance probability: driven by the gap between buyer outlay and gain

Whether the buyer accepts depends on the gap between "what they hand over is worth in their own eyes" and "what they get back is worth in their own eyes". Written as a formula, this gap must **divide by gained value into a relative gap**, otherwise a bread trade and a diamond trade would be judged by the same absolute value, and the price system distorts on scaling.

```
outlay ratio = net outlay / gained value
relative gap = outlay ratio − 1          ← the normalized form of the gap above
acceptance probability = clamp(2 − outlay ratio, 0, 1) = clamp(1 − relative gap, 0, 1)
```

- Net outlay ≤ gained value (handing over is not more expensive than getting back, including "a lucky steal") → probability 1, must trade
- Net outlay = 2× gained value → probability 0
- Consistent with the long-established rule: equal value must buy, double value goes to zero

With §3.3 minimization + §3.4 change, net outlay is usually clearly below gained value, probability constantly 1 — as it should be, showing real trade gains exist. The probability curve only really acts in three scenarios:

| Scenario | Behavior |
|---|---|
| Seller cannot make change | Overshoot cannot be returned, probability depressed |
| Buyer cannot produce enough candidates (poor, only essentials) | Can only overpay, or the trade directly fails |
| Two same-template villagers | Identical tables, no gain gap, only rounding error acting |

**To get a "villagers are stingier and often hesitate" effect, do not change this curve; change the self-produce discount**: move it from 0.7 toward 1.0, the spread shrinks, the gain thins, and probability naturally drops.

### 3.6 Gain attribution under the single-valuation model (need to know, not to change)

The seller asks by their own valuation, so at equal-value trade the seller's books show **exactly zero gain**, all surplus goes to the buyer. This is not a bug: under the single-valuation model "asking price = own valuation" automatically causes this result, and the seller's motive comes from "clearing surplus inventory", not profiting on the spread.

To let the seller also profit, one would need an independent seller markup — that brings the two prices back and conflicts with §2.1. Not introduced.

### 3.7 Numeric guards

- Payment candidates and change candidates **each** pass the §3.2 four filters: payment items come from the buyer's inventory, check the buyer's `retain` and essentials; change items come from the seller's inventory, check the seller's `retain` and essentials.
- Player path `offered valuation >= asked valuation × tolerance`: the asked value may be non-finite, needing an explicit `isFinite` check, otherwise with tolerance 0, "non-finite × 0 = NaN" would slip through by IEEE754 semantics and the log cannot locate it.
- Assert the outlay ratio is finite before probability computation; net outlay may be negative (the buyer got a big deal), but must not be NaN.

### 3.8 The matching layer must be replaced wholesale

`runMatching`'s `have.unitPrice <= want.unitPrice` spread window, and `acceptTrade`'s `[have.unitPrice, want.unitPrice]` interval constraint, both degenerate to "same float equality" under the single-value model, causing zero trades. This layer is not parameter tuning, it is a rewrite.

---

## 4. Village affiliation

### 4.1 Criterion

| Purpose | Interface |
|---|---|
| Whether a position is inside a village | `world.structureFeatureManager().getStructureAt(pos, false, Structure.VILLAGE)`, returns `StructureStart` |
| Village identity | `StructureStart.getChunkX()` / `getChunkZ()` |
| Village range | `StructureStart.getBoundingBox()` |

The second argument `false` means "inside the village's overall bounding box"; `true` requires being inside a specific piece — **it is not a toggle for skipping unloaded chunks**, internally it only reads loaded chunks and does not trigger generation.

**Disable `findNearestMapFeature`**: it is the `/locate` primitive, which spiral-scans and force-generates chunks, dragging the server down on every tick.

The POI distance field (`isVillage` / `sectionsToVillage`) can only answer "how many sections to a village", not "which village it belongs to", and its source is any OCCUPIED POI — a player dropping a lectern anywhere is a village center. **Do not use it for affiliation determination.**

### 4.2 Village registry

Create `VillageRegistry extends WorldSavedData`, storing `Set<ChunkPos>` (village origin chunks).

Registration timing: a villager is in a loaded chunk and `getStructureAt` hits a VILLAGE with `isValid()`. Once registered it persists forever: a player placing a new bell does not create a new village, dismantling a village does not delete records — this is exactly the requirement.

"Belongs to no village" is therefore a decidable state: the registry has no village matching the chunk where the villager currently is.

### 4.3 Market identity and membership

Three current problems must be fixed together:

| Problem | Location | Fix |
|---|---|---|
| Market identity is `UUID.randomUUID()`, changes on restart, orders and messages cannot associate across restart | `VillageMarkets.Market:14` | Change to village origin `ChunkPos` |
| Creating a Market on the spot when no affiliation is found, so every loaded villager necessarily belongs to some market | `VillageMarkets.index:67` | Delete this fallback; no village means no Market |
| `board()` throws `IllegalStateException` for Market-less villagers | `VillageMarkets.board:85` | Return `null`, and rework 10 call sites for null handling |

"Villagers can belong to no market" is a hard requirement, and this throw is its real cost: `AcquisitionPlanner` 2 places, `TradeCoordinator` 3 places, `VillagerEconomySystem` 5 places.

Market's `center` takes the village bounding-box center, `meetingPoint` changes to "whether a bell exists in the village", only for display, no longer participating in affiliation.

### 4.4 Order partitioning

`WorkOrderSavedData` is currently "one ledger per dimension", cross-village orders mix, conflicting with village_ecology_loop_plan.md §11 "no cross-village order mixing". Orders need a `villageChunk`, filtered by village on read.

---

## 5. Remove free supply

Three channels, all removed:

| Channel | Location | Behavior |
|---|---|---|
| Free collection-order bypass | The `ResourceWorkSystem.request` call inside `VillagerEconomySystem` `offerDemandProduction` | Any unmet demand directly creates a shared free order |
| Direct task injection | `producer.getGoals().add(craft)` in the same function | Skip orders and push a craft goal into someone else's queue |
| The `cooperative_supply` method | `AcquisitionMethods.Supply` → `ResourceWorkSkill.deliverBatch` | After claiming collection, `InventoryTransfer.move` gives it away free |

Root-cause evidence: `WorkOrderBook.Order` has no `price / budget / escrow` field; it is not "a zero-price order" but structurally lacks the concept of money. Deleting these three cuts all free supply, so it **must ship in the same batch as §3.1's paid orders**, otherwise the intermediate version has no external supply source at all.

---

## 6. Initial endowment

| Item | Value |
|---|---|
| Grant | 8 emeralds |
| Timing | The first tick after a villager first joins a village (after successful `VillageRegistry` registration) |
| Persistence | `VillagerAgentData.emeraldGrantGiven`, NBT key `EmeraldGrantGiven` |
| Configurable | `INITIAL_EMERALD_GRANT`, 0 means off |

**Completed changes**: `ResourceWorkSystem.assign` has removed the wooden-axe/stone-pickaxe grants, `VillagerAgentData`'s `resourceStarterGiven` has been renamed to `emeraldGrantGiven`.

**Remaining**: the grant logic itself is not yet connected, it depends on §4 village affiliation — this is why "initial endowment" and "village affiliation" must ship in the same batch.

Note: the NBT key rename causes villagers who already received tools in old saves to receive 8 emeralds again. This is a one-time injection, acceptable; to avoid it, revert to reusing `ResourceStarterGiven`.

The endowment only guarantees a startup, not sustainability. Sustainability comes from the demand structure and prices, within the verification scope of village_ecology_loop_plan.md §11.

---

## 7. Mine

### 7.1 Current state

`MINER_WORKBENCH`, `MINER_POI`, and the `MINER` profession are all registered; `ResourceCatalog` already has seven ore sources: coal / iron_ore / gold_ore / diamond / redstone / lapis / cobblestone. `new PointOfInterestType("miner", ..., 1, 1)` has `maxTickets = 1`, naturally exclusive slots, no extra mechanism needed for concurrent reservation.

### 7.2 Batch production model

From "find an ore vein and dig" to "clock in to work":

```
walk to workstation → atomically reserve slot → validate (food/durability/capacity) → sustained action accumulates progress
→ batch complete re-validate and commit → release slot
```

Output goes directly into the backpack, not changing world blocks: the ore does not deplete, but inputs must be real. Recipe in JSON, fields `{output, count, ticks, foodCost, toolDamage, capability, minLevel}`.

### 7.3 Smelting trade-off (recommended: produce ingots directly)

If the first batch outputs `iron_ore` with no path to `iron_ingot`, the ore is dead stock, and the alternative route "no pickaxe → no ore → cannot make a pickaxe" still cannot close.

Recommend the mine directly outputs `iron_ingot`, `iron_ore` not appearing as output. Reason: the mine is abstract production, output is configurable, no need to carry a smelting chain for physical realism. This trade-off needs confirmation, because it means admitting "the mine directly spits out iron ingots".

### 7.4 Call surface

The mine must be callable by both the **paid-order production phase** and personal `acquire_item`, so it should be independently registered as `mine_batch`, parallel to the existing `extract` (digging natural blocks). After the same item gets two routes, village_ecology_loop_plan.md §9's "allow single-method registration" criterion yields the opposite conclusion, so Method selection must be done.

---

## 8. Execution batches

| Batch | Content | Depends on | Files involved (main) |
|---|---|---|---|
| **T0-A** | Remove three free-supply channels; split out the player-dimension price read (don't change structure yet) | None | `VillagerEconomySystem`, `AcquisitionMethods`, `ResourceWorkSkill`, `TradeRequestPacket` |
| **T0-B** | Village registry; Market identity changed to `ChunkPos`; remove on-the-spot creation; `board()` returns null and 10 call sites | None | `VillageMarkets`, new `VillageRegistry`, `AcquisitionPlanner`, `TradeCoordinator`, `VillagerEconomySystem` |
| **T0-C** | Initial endowment connection (depends on B's registration event) | T0-B | `VillagerRegistry` connection point, `VillagerAgentData` |
| **T0-D** | Price changed to single valuation + external price derived from player margin + offset layer + four-layer lookup chain + junk table + self-produce discount + `retain` flag + NBT migration | T0-B (village affiliation decides who can trade) | `VillagerEconomySystem`, `VillagerAgentData`, `AgentDebugExporter`, `SkillCraftCatalog`, `ResourceCatalog`, `item_values.json`, new `price_modifiers.json` / `junk_values.json` |
| **T0-E** | Orders with price + escrow reservation + matching layer rewrite + bundle settlement + acceptance probability | T0-D, T0-A | `WorkOrderBook`, `WorkOrderSavedData`, `VillageSignalBoard`, `TradeCoordinator`, `VillagerEconomySystem`, `payment_accepts.json`, `active_offers.json` |
| **T0-F** | Mine batch production + `mine_batch` method registration | T0-E | `ResourceCatalog`, `ResourceWorkSkill`, `ItemTaskCatalog`, `AcquisitionMethods`, new mine recipe JSON |
| **Separate** | Action timing skeleton, logging exclusion, ration reservation, smelting chain | T0-E | See village_ecology_loop_plan.md §3 / §8 |

T0-A and T0-B can run in parallel, neither depends on the price structure. T0-D and T0-E are the main workload and cannot be delivered separately — separating them produces a zero-trade version where "valuation is in place but matching still compares by the old price window".

T0-D can be cut once more internally: **do single valuation + derived external price first (including the one-time script rebuilding `item_values.json`)**, then the offset layer. The former lets the current matching logic keep working (`unitPrice` switches to `sellPriceForPlayer`), the latter needs §2.7's write-clamping.

---

## 9. Values that must be decided first

### 9.1 Decided values (all written in `price_modifiers.json`)

The values below all land in `price_modifiers.json` (§2.10), changing them needs no recompilation.

| # | Item | Decided value | Impact |
|---|---|---|---|
| 1 | Player margin | **1.8** | Decides the allowed max delta; ≥1.8 supports ±50% |
| 2 | Max price delta | **±50%** | Derived from the §2.5 formula; at margin 1.8 the theoretical ceiling is 52.8% |
| 3 | Self-produce discount | **0.7** | Decides the valuation dispersion across professions, directly deciding how large trade gains are |
| 4 | Base valuation migration | `sqrt(old buy × old sell)` geometric mean | One-time script; the old 2× spread is taken over by player margin after migration |
| 5 | Whether "recent transaction price" enters valuation | **No** (recommended) | Positive feedback is flammable; recommend only as acceptance-probability correction |
| 6 | LLM adjustment cooldown | Once per item per game day | Prevent adjusting every think cycle, turning prices into a random walk |
| 7 | **What to do when the seller cannot make change** | **Refuse this trade** (recommended); credit-on-account is the alternative | Refusing makes "large item buys small goods" systematically fail; credit needs another set of debt ledger and bad-debt handling. Blocks T0-E |

### 9.2 Recommended, tunable while running (does not block starting)

| # | Item | Recommended value | Impact |
|---|---|---|---|
| 7 | Initial emerald `INITIAL_EMERALD_GRANT` | 8 | One-time, configurable 0 to off |
| 8 | Escrow liquidity guard | Single escrow ≤ 50% of holdings; single-order escrow ≤ 2 game days; stop new orders when whole-village unescrowed emeralds < population × 2 | Missing these three, N people listing simultaneously can deadlock the whole village |
| 9 | Whether "can produce" discount refines by capability level | Fixed `0.7` first | Higher level cheaper is more realistic, but adds a tuning curve |
| 10 | Mine food accounting | Recipe `foodCost` field (recommended) | Accounted separately from personal hunger, must not mix |
| 11 | Mine first-batch output | `cobblestone / coal / iron_ingot` | Decides whether a smelting chain is needed |
| 12 | Off-table item backpack exit | Discard / donate (recommended: discard) | No exit means collection silently fails after the backpack fills |

---

## 10. Acceptance mapping

Corresponding to village_ecology_loop_plan.md §11, the minimum acceptance for each T0 batch:

| Batch | Acceptance |
|---|---|
| T0-A | No `cooperative_supply` call in the whole codebase; villagers with unmet demand no longer push tasks into others' queues |
| T0-B | Placing a bell outside a village by a player does not create a Market; `board()` returns null for village-less villagers and all 10 call sites null-handle; Market identity unchanged after restart |
| T0-C | The same villager does not re-receive after restart, rejoin, or death-respawn; changing profession does not re-receive |
| T0-D | After migration each item has one valuation, and the two external prices converge to it (within float error); producible items are valued lower than non-producible; `retain` items neither list nor enter payment bundles, **but are still buyable**; offsets trimmed to the cap still stay within bounds; old saves load |
| T0-E | The same order with multiple workers applying in the same tick has only one full claim; partial delivery, cancellation, timeout, restart do not duplicate or fabricate currency/items; no cross-village order mixing; **the buyer is not stripped of profession essentials**; payment items in descending spread, change items in ascending spread; failing to reach the asking price errors rather than "give whatever you have"; net outlay after change is clearly below gained value |
| T0-F | Food/durability/time/output conserved; full backpack, slot competition, stoppage and restart; unconfigured ores are not produced |

After all batches complete, run multi-game-day closed-loop and disturbance verification per village_ecology_loop_plan.md §11, and update the corresponding `docs/` topic documents.
