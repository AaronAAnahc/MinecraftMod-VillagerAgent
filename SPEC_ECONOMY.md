# SPEC — LLM-free 村庄经济与通用消息总线

> 范围：在 VillagerAgent 中新增一套**不依赖 LLM** 的村庄经济系统 + 把 `VillageSignalBoard` 升级为通用村庄消息总线。
> 目标：让村民在**没有 API key** 时也能出现「缺料→发需求→他人响应→双方主动前往交易」的涌现行为；同时把玩家发起的议价从 LLM 评估改为**价值表确定性评估**。
> 关联：`SPEC.md`（睡眠/战斗整改，已落地）。

---

## 1. Overview

当前系统在无 LLM 时，战斗集结（集体防御）是纯 Java 的涌现，但**经济/社交/贸易全靠 LLM**（见 `VillagerTradeSystem` "fully LLM-driven"、`TradeRequestPacket` 直接裸调 `LLMService.queryLLM`）。本 SPEC 补齐「经济涌现」这一块，使其 LLM-free：

- **玩家议价**：维护每张物品的价值表；玩家交易时按表比较总价值决定 ACCEPT/REJECT；不在表内的物品价值记为 0.01（不计估值，但村民仍实体接收多余物品）。
- **村庄消息总线**：把 `VillageSignalBoard` 从纯战斗升级为带类型的通用消息板（THREAT 向后兼容，新增 TRADE_WANT / TRADE_HAVE 等）。
- **供求经济**：每个村民按职业必需品清单 + 库存，自动发 `TRADE_HAVE`（卖多余）/ `TRADE_WANT`（缺料）；系统配对后让双方**主动导航到彼此**并在 proximity 内完成物品转移。

设计铁律（沿用 SPEC.md）：
- **不引入任何 LLM 调用**到经济/议价路径；LLM 仅作为可选增强（本 SPEC 不涉及）。
- 消息总线是全村庄共享单例（假设单一主世界），与 `VillageSignalBoard` 现有假设一致。
- 数据放 JSON（`item_values.json` / `profession_essentials.json` / `item_sources.json`），行为开关放 toml。

---

## 2. Data Models

### 2.1 物品价值表 `item_values.json`
```json
{
  "minecraft:wheat": 2.0,
  "minecraft:wheat_seeds": 0.5,
  "minecraft:emerald": 10.0,
  "minecraft:wooden_hoe": 8.0,
  "minecraft:iron_ingot": 6.0
}
```
- `value(item) = table.getOrDefault(regName, 0.01)`。
- 0.01 语义：不在表内 → 不计入议价估值，但村民仍会**物理接收**（玩家倒废料不被整体拒收）。

### 2.2 职业必需品 `profession_essentials.json`
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
- `surplus = count - minQty`；`surplus > 0` → 可卖（发 TRADE_HAVE）。
- `count < minQty` → 缺口（发 TRADE_WANT，缺口量 = minQty - count）。
- 「保留 minQty 基础上卖多余」——即 keep 量 = minQty（用户原话「保留一个」是 minQty=1 的特例）。

### 2.3 物品来源注册表 `item_sources.json`
```json
{
  "minecraft:iron_ingot": "SMELT",
  "minecraft:wheat": "FARM",
  "minecraft:emerald": "TRADE",
  "minecraft:diamond": "MINED|TRADE"
}
```
来源枚举：`CRAFT | SMELT | FARM | MINED | FISH | GATHER | TRADE`。多来源用 `|` 分隔。
- 缺口 item 的来源 decision（见 §3.3）。

### 2.4 消息 `VillageMessage`（新增于 VillageSignalBoard）
```java
public static final class VillageMessage {
    public final UUID id;
    public final MsgType type;        // THREAT / TRADE_WANT / TRADE_HAVE / FOOD_SHORTAGE / BUILD_NEEDED
    public final UUID author;
    public final BlockPos origin;
    public final ResourceLocation item;   // TRADE_* 填物品；THREAT 填 mob
    public final int quantity;
    public final long postedTick, expireTick;
    public final Set<UUID> responders = new HashSet<>();
    public UUID matchedWith = null;       // TRADE 配对的对端消息 id
    public boolean resolved = false;
}
```

---

## 3. User / System Flows

### 3.1 玩家议价（价值表，无 LLM）
1. 玩家在交易界面提交 `TradeRequestPacket`（offer 给村民 / request 从村民取）。
2. 服务端 `evaluateTradeWithValueTable`：
   - `offered = Σ value(offer_i)`，价值 0.01 物品仍计入「物理接收」但**不拉高估值**。
   - `requested = Σ value(request_i)`。
   - 成交：`offered >= requested * BARTER_FAIRNESS_TOLERANCE`（默认 0.95）。
3. 成交 → `executeTrade`（复用现有）；拒绝 → `returnItemsToPlayer`（复用现有）。
4. 全程无 `LLMService` 调用。

### 3.2 消息总线（升级 VillageSignalBoard）
- 保留 `RallyCall` / `respond` / `shouldCharge` / `nearestRally` 的 combat 逻辑（向后兼容）。
- 新增：`post(author, origin, type, item, qty)`、`hear(villager, pos, type)`（半径 `TRADE_MSG_TTL`/HEAR_RADIUS 内）、`ack`、`matchTrade(wantId, haveId)`、`clearExpired(now)`。
- 单例 `INSTANCE` 不变，新增 `Map<MsgType, List<VillageMessage>> board`。

### 3.3 村民供求经济（每 tick 经济循环 `tickEconomy`）
对 `getAllAgents()` 中每个村民（alive）：
1. **发 TRADE_HAVE**：按 `profession_essentials` 算 `surplus`；`surplus > 0` → 确保 board 上有 `TRADE_HAVE(item, surplus)`（同 author+item 去重/更新数量）。
2. **发 TRADE_WANT**：
   - `count < minQty` → 缺口 `deficit = minQty - count` → 确保 `TRADE_WANT(item, deficit)`。
   - 不可合成/不可采集的缺口若无人回应，留在 board 上挂起（等待玩家或其他村民产生 surplus）。
3. **配对 `matchTrade`**：遍历未配对 `TRADE_WANT(item,q)` 与 `TRADE_HAVE(item,q2)`（`q2 >= q` 且双方 alive）→ 设 `matchedWith`，记录交易对状态。
4. **导航+成交**：配对双方各自 `getNavigation().moveTo` 到对端位置；当**双方都在彼此 TRADE_MEET_RADIUS 内**→ 执行转移：
   - 从 haver 扣 `q` 个 item 给 wanter（`AgentInventory.addItem/removeItem`）。
   - wanter 付款：优先付绿宝石（按 `value(emerald)` 覆盖 requested 价值），否则按价值表用一件可比 surplus 抵，再不行就**村社无偿共享**（v1 简化）。
   - 价值表在此主要管付款公平性 + 玩家议价；村民间以「需求满足」为主。
5. **清理**：`clearExpired` 删过期消息；成交后移除对应 TRADE_HAVE/TRADE_WANT。

来源决策（缺口 item）：
- `CRAFT`/`SMELT` → 走现有 `CraftingAction`（若村民有输入料）；无输入料则它本身就是 TRADE_WANT。
- `FARM` → 现有 `FarmingAction`。
- `MINED`/`FISH`/`GATHER` → **v1 不支持**（无对应原语），缺口只能靠 TRADE/玩家供给。
- `TRADE` → 留 board 挂起，等玩家/他人 surplus。

---

## 4. File Structure

| 文件 | 变更 |
|---|---|
| `src/main/resources/data/villageragent/economy/item_values.json` | 新建：物品价值表 |
| `src/main/resources/data/villageragent/economy/profession_essentials.json` | 新建：职业必需品清单 |
| `src/main/resources/data/villageragent/economy/item_sources.json` | 新建：物品来源注册表 |
| `ai/VillageSignalBoard.java` | 升级：新增 `MsgType`/`VillageMessage`/`post`/`hear`/`ack`/`matchTrade`/`clearExpired`；combat API 不变 |
| `ai/VillagerEconomySystem.java` | 新建：JSON 加载（Gson）+ `tickEconomy` + 配对/导航/转移 |
| `ai/VillagerAgentManager.java` | 新增 `tickEconomy(world)` 转发到 `VillagerEconomySystem` |
| `network/TradeRequestPacket.java` | `evaluateTradeWithLLM` → `evaluateTradeWithValueTable`（复用 executeTrade 等） |
| `config/ModConfig.java` | Economy 段：ENABLE_ECONOMY / ECONOMY_TICK_INTERVAL / BARTER_FAIRNESS_TOLERANCE / TRADE_MEET_RADIUS / TRADE_MSG_TTL / ECONOMY_USE_VALUE_TABLE |
| `events/VillagerEventHandler.java` | `onWorldTick` END 阶段加 `tickEconomy`，用 ENABLE_ECONOMY 门控 |

---

## 5. Edge Cases

- **消息过期/重复**：`clearExpired` 按 `expireTick` 删除；同 author+item 的 TRADE_HAVE/WANT 更新数量而非叠加，避免刷屏。
- **配对死锁**：双方都只想要对方没有的东西 → 配对时要求 `have.qty >= want.qty` 且 item 一致，否则不配对；成交后清消息，避免永久占用。
- **surplus 抖动**：村民刚卖出 surplus 又低于 minQty → 下一 tick 自动转 TRADE_WANT；同一 tick 内先 POST 再 MATCH，避免自匹配（author 不同才配对）。
- **导航失败**：某村民被卡住/死亡 → 配对超时（用消息 TTL）后释放，对方回归 routine。
- **未知物品议价**：value=0.01，不拉高估值；但玩家若只给未知物品换高价值 request → 估值不足 → REJECT（符合「不滥用价值表」）。
- **并发**：`getAllAgents()` 返回 `ConcurrentHashMap` 视图；经济 tick 只读库存 + 写 board（board 自身用线程安全容器）。

---

## 6. Out of Scope (v1)

- `MINED`/`FISH`/`GATHER` 采集原语（需新增 `MiningAction` 等，单独排期）。
- 村民↔村民对话式议价（仍走 LLM，本 SPEC 不碰）。
- 长程意图/债务驱动的跨村民履约（与 `DebtAgenda` 的集成留待后续）。
- 多维度村庄（按 `ServerWorld` 分片 board）。

---

## 7. Open Questions

1. **村民间贸易是否强制付款**？v1 默认：有绿宝石则付，否则无偿共享。是否要严格价值对等？
2. **profession_essentials 初始清单**覆盖哪些职业？先 farmer/toolsmith 两个样例，其余留给内容扩充。
3. **ECONOMY_TICK_INTERVAL** 默认多少？建议每 20 tick（1s）跑一次经济扫描，避免每 tick 开销。
4. **MINED 原语**是否 v1 就做？建议否（见 Out of Scope）。
