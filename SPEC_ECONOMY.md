# SPEC: LLM-free village economy and general message bus

> Topic-specific design reference, not a current implementation description. See `docs/README.md` for current behavior.
> Scope: an LLM-independent economy and a general village message bus extending `VillageSignalBoard`.
> Goal: shortages → requests → responses → both villagers actively approaching to trade, without an API key. Replace player barter evaluation by an LLM with deterministic value-table evaluation.
> Related: `SPEC.md`, described as implemented by this original economy proposal.

## 1. Overview

The proposal's starting point was pure-Java emergent combat rallying while economy/social/trade depended on LLMs, including `VillagerTradeSystem`'s “fully LLM-driven” description and direct `LLMService.queryLLM` in `TradeRequestPacket`. Proposed additions:

- Player barter: compare total offered/requested values to ACCEPT/REJECT. Unknown items have value 0.01, do not increase valuation, but are physically accepted as extras.
- General typed village board: retain THREAT and add TRADE_WANT / TRADE_HAVE and other types.
- Supply/demand: profession essentials and inventory generate surplus offers and shortage requests. Matched participants actively navigate toward each other and transfer on proximity.

Design constraints:

- No LLM calls in economy/barter paths; optional LLM enhancements are outside this specification.
- A shared village singleton assumes one overworld, matching the original signal-board assumption.
- JSON stores `item_values.json`, `profession_essentials.json`, and `item_sources.json`; TOML stores behavior switches.

## 2. Data models

### 2.1 Item values: item_values.json

```json
{
  "minecraft:wheat": 2.0,
  "minecraft:wheat_seeds": 0.5,
  "minecraft:emerald": 10.0,
  "minecraft:wooden_hoe": 8.0,
  "minecraft:iron_ingot": 6.0
}
```

`value(item) = table.getOrDefault(regName, 0.01)`. The 0.01 sentinel means excluded from barter valuation but still physically accepted, so extra junk does not cause rejection of the entire offer.

### 2.2 Profession essentials: profession_essentials.json

```json
{
  "minecraft:farmer": [
    {"item":"minecraft:wooden_hoe","minQty":1},
    {"item":"minecraft:wheat_seeds","minQty":12},
    {"item":"minecraft:wheat","minQty":24}
  ],
  "minecraft:toolsmith": [
    {"item":"minecraft:iron_ingot","minQty":4},
    {"item":"minecraft:coal","minQty":8}
  ]
}
```

`surplus = count - minQty`; positive surplus publishes TRADE_HAVE. Stock below minQty publishes TRADE_WANT for `minQty - count`. Keep minQty and sell the excess; “keep one” is the minQty=1 special case.

### 2.3 Item sources: item_sources.json

```json
{
  "minecraft:iron_ingot": "SMELT",
  "minecraft:wheat": "FARM",
  "minecraft:emerald": "TRADE",
  "minecraft:diamond": "MINED|TRADE"
}
```

Sources are `CRAFT | SMELT | FARM | MINED | FISH | GATHER | TRADE`, with multiple sources separated by `|`. Section 3.3 describes shortage routing.

### 2.4 VillageMessage inside VillageSignalBoard

```java
public static final class VillageMessage {
    public final UUID id;
    public final MsgType type;        // THREAT / TRADE_WANT / TRADE_HAVE / FOOD_SHORTAGE / BUILD_NEEDED
    public final UUID author;
    public final BlockPos origin;
    public final ResourceLocation item;   // TRADE_* uses an item; THREAT uses a mob
    public final int quantity;
    public final long postedTick, expireTick;
    public final Set<UUID> responders = new HashSet<>();
    public UUID matchedWith = null;       // Counterpart message ID for a matched trade
    public boolean resolved = false;
}
```

## 3. User and system flows

### 3.1 Player barter using values, without LLM

1. The UI submits `TradeRequestPacket`, offering to the villager and requesting from it.
2. Server `evaluateTradeWithValueTable` calculates `offered = Σ value(offer_i)` and `requested = Σ value(request_i)`. Sentinel-value 0.01 items are physically received but do not increase valuation. Accept when `offered >= requested * BARTER_FAIRNESS_TOLERANCE`, default 0.95.
3. Reuse `executeTrade` on acceptance and `returnItemsToPlayer` on rejection.
4. Make no `LLMService` calls.

### 3.2 Message-bus extension

Preserve combat `RallyCall`, `respond`, `shouldCharge`, and `nearestRally` APIs. Add `post(author, origin, type, item, qty)`, `hear(villager, pos, type)` with TTL/hearing-range limits, `ack`, `matchTrade(wantId, haveId)`, and `clearExpired(now)`. Keep singleton `INSTANCE`, adding `Map<MsgType, List<VillageMessage>> board`.

### 3.3 Per-tick supply/demand loop: tickEconomy

For each living villager in `getAllAgents()`:

1. Calculate profession surplus and ensure a TRADE_HAVE message, deduplicated by author/item with quantity updated.
2. Ensure TRADE_WANT for `minQty - count`. Uncraftable/ungatherable unanswered shortages remain pending until another villager or player supplies surplus.
3. Match unpaired want `(item,q)` and have `(item,q2)` where `q2 >= q` and both participants are alive; set `matchedWith` and track pair state.
4. Both navigate to the other's position using `getNavigation().moveTo`. When both are within TRADE_MEET_RADIUS, transfer q from haver to wanter using AgentInventory add/remove. Pay emeralds first using emerald value, otherwise one comparably valued surplus item, otherwise free community sharing as the v1 simplification. The value table governs payment fairness and player barter; satisfying needs is primary for villagers.
5. `clearExpired` removes expired messages; completed trades remove associated HAVE/WANT messages.

Shortage source routing:

- CRAFT/SMELT: existing `CraftingAction` when ingredients exist; otherwise publish ingredient demand.
- FARM: existing `FarmingAction`.
- MINED/FISH/GATHER: unsupported in v1 without primitives; rely on trade/player supply.
- TRADE: remain pending for player/other surplus.

## 4. File structure

| File | Change |
|---|---|
| `src/main/resources/data/villageragent/economy/item_values.json` | Create value table |
| `src/main/resources/data/villageragent/economy/profession_essentials.json` | Create essentials list |
| `src/main/resources/data/villageragent/economy/item_sources.json` | Create source registry |
| `ai/VillageSignalBoard.java` | Add MsgType/VillageMessage/post/hear/ack/matchTrade/clearExpired, preserving combat API |
| `ai/VillagerEconomySystem.java` | Create Gson loading, tickEconomy, matching/navigation/transfer |
| `ai/VillagerAgentManager.java` | Forward tickEconomy(world) |
| `network/TradeRequestPacket.java` | Replace evaluateTradeWithLLM with evaluateTradeWithValueTable; reuse transfer logic |
| `config/ModConfig.java` | Economy keys: ENABLE_ECONOMY, ECONOMY_TICK_INTERVAL, BARTER_FAIRNESS_TOLERANCE, TRADE_MEET_RADIUS, TRADE_MSG_TTL, ECONOMY_USE_VALUE_TABLE |
| `events/VillagerEventHandler.java` | Call tickEconomy in onWorldTick END, gated by ENABLE_ECONOMY |

## 5. Edge cases

- Expiry/duplicates: delete by expireTick; same-author/item HAVE/WANT updates quantity instead of accumulating messages.
- Matching deadlock: require matching item and sufficient have quantity; remove messages on completion to avoid permanent occupancy.
- Surplus oscillation: selling below minQty produces WANT next tick. POST before MATCH, and require distinct authors.
- Failed navigation/death: release the pair after message-TTL timeout and return the counterpart to routines.
- Unknown-item barter: value 0.01 never raises valuation; offering only unknown items for valuable goods rejects.
- Concurrency: `getAllAgents()` returns a ConcurrentHashMap view. The proposal describes economy ticks reading inventory and writing a board with thread-safe containers.

## 6. Out of scope for v1

- MINED/FISH/GATHER primitives such as MiningAction, separately scheduled.
- Villager conversational bargaining, which retains LLM behavior.
- Long-term intent/debt-driven fulfillment and DebtAgenda integration.
- Multiple dimensions through ServerWorld-sharded boards.

## 7. Open questions

1. Must villager trades be paid? v1 defaults to emerald payment when available, otherwise sharing; should strict value equivalence be required?
2. Which professions should essentials cover? Begin with farmer/toolsmith examples, expanding content later.
3. Default ECONOMY_TICK_INTERVAL? Suggested 20 ticks (one second) to avoid per-tick scanning cost.
4. Include MINED in v1? Recommendation: no, as listed out of scope.
