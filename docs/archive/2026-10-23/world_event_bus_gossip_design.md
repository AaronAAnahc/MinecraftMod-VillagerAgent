# WorldEventBus 与 GossipChannel 技术设计

> 状态：**设计文档，未实现**。本文按《villager_agent_intelligence_pipeline.md》§三（Tier 0 感知深化）的既有方向细化，落地于 Phase C。
> 核心结论先行：**过期就没意义的是事件，归 EventBus；以后仍然成立的是知识，归 Gossip。**

---

## 一、设计目标与动机

### 1.1 当前缺口

Tier 1（确定性基线）目前的"环境变化 → 行为反应"链路靠**例行窗口 + 轮询**：

- 作物是否成熟：farm goal 执行时现场扫描；
- 掉落物：`ItemAttractionSystem` 周期扫描；
- 远处资源：只有村民**亲自去过**的区块才在 `ChunkMemory` 里。

后果：**没有反应力**（作物成熟了要等例行窗口轮到才知道），**没有信息差**（全村知识 = 每个村民视野的并集，"传播"这个概念不存在）。

### 1.2 两条管线为什么互补

| | WorldEventBus | GossipChannel |
|---|---|---|
| 消息语义 | **事件**：「tick T、位置 P 发生了 X」 | **知识**：「存在事实：X 类资源在 P」 |
| 时间敏感性 | 高（过期即无意义） | 低（下周仍成立） |
| 存储位置 | 全局总线缓冲，村民只持游标 | 村民自己的 `ChunkMemory` |
| 传播拓扑 | 扇出 + 感知门过滤（一次发布一次消费） | 接触传染式（逐跳扩散，延迟容忍） |
| 距离语义 | 半径内即时可知；半径外**永远不知** | 无距离上限，但**必须物理接触**才传一跳 |
| 失效机制 | 环形缓冲覆盖（= TTL） | 衰减 + 证伪（见 §四） |
| 解决的问题 | **反应力**：无 LLM 也对环境变化响应 | **信息差**：知识、协作与交易的动机 |

一句话：**EventBus 空间上宽、时间上窄；Gossip 时间上宽、空间上窄。** 只有前者，半径外的信息永远传不进来；只有后者，环境突变得等村民碰面才知道。

### 1.3 与 VillageSignalBoard 的三方分工

| 信道 | 职责 | 现状 |
|------|------|------|
| `VillageSignalBoard` | **即时呼喊**：战斗集结、TRADE_WANT——"现在需要你" | **已实现** |
| `WorldEventBus` | **环境事件流**："世界发生了什么" | 本文设计 |
| `GossipChannel` | **知识传播**："我知道什么，讲给你听" | 本文设计 |

判定准则：消息需要**对方立即行动** → SignalBoard；消息**过期就没意义** → EventBus；消息**以后仍然成立** → Gossip。

---

## 二、消息分类边界

### 2.1 分类总表

| 消息 | 判定 | 去处 | 理由 |
|------|------|------|------|
| 作物成熟 | 事件 | EventBus `CROP_MATURED` | 成熟窗口有限，过期没意义 |
| 方块变化（破坏/放置） | 事件 | EventBus `BLOCK_CHANGED` | 结构层面变化由 `WorldStructureIndex` 负责失效，事件只做行为触发 |
| 物品掉落 | 事件 | EventBus `ITEM_DROPPED` | 掉落物秒级被捡/消失 |
| 新村民到来 | 事件 | EventBus `NEW_VILLAGER` | 触发问候/社交目标 |
| 工作站空闲 | 事件（v1.5） | EventBus `WORKSTATION_FREE` | 触发对应职业 craft 目标；需新增检测 |
| 威胁出现 | 事件 | EventBus `THREAT_APPEARED` + SignalBoard 集结 | 30 秒后位置失效；即时响应走已有 SignalBoard |
| 资源点（矿/树/水/农田） | 知识 | Gossip | 长期成立，值得传播 |
| 威胁出没区域 | **不做**（v2 再议） | — | 时效介于事件与知识之间，边界模糊；v1 不引入灰区 |
| 贸易需求 | **不走 Gossip** | SignalBoard `TRADE_WANT`（已有） | 已有信道覆盖 |
| 债务/八卦 | **不走 Gossip v1** | LLM 社交层 | 属"更像人"的部分，不是 Tier 1 生存刚需 |

### 2.2 Gossip 内部的知识分类（失效机制分流）

| 类别 | 例子 | 失效机制 |
|------|------|----------|
| **可再生资源** | 树林、水源、农田、建筑 | **被动衰减**：新鲜度随 `lastVisitedTick` 下降，靠 `saliency` 淘汰；不会"被用光" |
| **可耗尽资源** | 铁矿、钻石矿 | **主动证伪 + 有效期**：见 §四。4，仅时间衰减不够——衰减到一半的"矿在 X"仍会骗村民跑一趟 |

---

## 三、WorldEventBus 设计

### 3.1 数据结构

```
WorldEventBus（全局单例，每维度一个：WeakHashMap<World, WorldEventBus>，同 WorldStructureIndex）
  ├ Event[] ring          // 环形缓冲，容量 EVENT_CAPACITY（建议 256）
  │   Event { EventType type; BlockPos pos; long tick; int payload }  // ~24 B，无对象池需求
  ├ long writeIndex       // 全局单调递增写指针
  └ （读侧游标不在总线上，在村民身上）
```

```java
public enum EventType { CROP_MATURED, BLOCK_CHANGED, ITEM_DROPPED, NEW_VILLAGER, THREAT_APPEARED }
```

### 3.2 游标（每村民消费位点）

总线是**一条全村共享的 append-only 时间线**，事件只存一份；每个村民在 `VillagerAgentData`（NBT 可序列化）上存一个 `long eventCursor`——"我已经消费到第几条了"。慢 tick 时（挂在 `updateAgent`，受 `AGENT_THINK_INTERVAL` 节流）：

```
1. head = min(writeIndex, eventCursor + EVENTS_PER_THINK)   // 预算：每次最多读 8 条
2. for i in (eventCursor, head]:
     e = ring[i mod CAPACITY]
     if e 已被覆盖（i < writeIndex - CAPACITY）→ 跳过（太老，等价于 TTL 过期）
     else if 感知门通过 → 转成知识/目标（§3.4）
3. eventCursor = head
```

三个由此获得的性质：

- **离线不漏读**：村民在战斗独占 / chunk 卸载期间事件不丢，恢复后续读；
- **事件只存一份**：N 个村民共享一条日志，而非每人一个队列；
- **预算控制最简单**：每人每慢 tick 读 ≤ `EVENTS_PER_THINK` 条，摊平模式与 `FLOODS_PER_TICK` 一致。

### 3.3 感知门（反心灵感应）

事件消费时按**区块曼哈顿距离**过滤：

```
dist = |事件chunkX - 村民chunkX| + |事件chunkZ - 村民chunkZ|
可见 iff dist <= PERCEPTION_RADIUS_CHUNKS (= 4，即 64 格)
```

- 生产者与感知门解耦：所有事件无差别 append，**过滤在消费端**——写路径零开销；
- 半径外的事件被跳过但游标照常推进（读过 ≠ 看见，只是不响应）；
- 半径是 `ModConfig` 项（行为开关类，符合 toml/JSON 分离约定）。

### 3.4 消费端：事件 → 响应式目标

事件**不直接驱动行为**，只注入目标（遵守统一目标漏斗，EventBus 是又一个 `GoalSource`）：

| 事件 | 注入 | priority | 说明 |
|------|------|----------|------|
| `CROP_MATURED` | 农民 `farm` 目标提权 + `targetLocation` | boost +3 | 已有 farm goal 则提权，不重复建 |
| `BLOCK_CHANGED` | （v1 仅记忆失效辅助）刷新 `ChunkMemory` 对应区块 saliency | — | 不产目标 |
| `ITEM_DROPPED` | `pickup` 目标（收编现有 `ENABLE_AUTO_PICKUP`） | 6 | 距离门内才生成 |
| `NEW_VILLAGER` | `socialize` 目标 | 3 | 需求系统社交值低时才生成 |
| `THREAT_APPEARED` | 调用现有 `VillageSignalBoard.respond()`（不新建路径） | 9 | 战斗系统独占，目标层让路 |

**去重**：沿用 `FailureRecoveryPlanner` 的结构化去重模式——注入前扫描现有目标（`produces` / `targetLocation` 匹配），已有则提权而非新建。

### 3.5 生产者接线点

| 事件 | 生产者 | 现状 |
|------|--------|------|
| `BLOCK_CHANGED` | `WorldStructureIndex.onBlockChanged`（已有钩子，加一行 publish） | 可直接接 |
| `CROP_MATURED` | 在 block-change 钩子里派生：farmland 上方作物方块 age 达到最大值的生长变化 | 新增判定（读 BlockState，零额外扫描） |
| `ITEM_DROPPED` | `EntityJoinWorldEvent`（Forge 事件，仅 ItemEntity） | 新增 listener |
| `NEW_VILLAGER` | `EntityJoinWorldEvent`（仅 VillagerEntity，且非加载恢复——区分 `!world.isClientSide && entity.loadedFromTag==false`） | 新增 listener |
| `THREAT_APPEARED` | 现有战斗检测路径（`tickCombat` 发现敌对目标时 publish） | 新增一行 |

### 3.6 持久化与生命周期

- **事件不落 NBT**：时效短，重启后旧事件无意义；重启时 `eventCursor` 与 `writeIndex` 同时清零（或游标持久化但钳制到 `max(0, writeIndex-CAPACITY)`，避免重启后村民"补读"一堆已无意义的事件）。
- **TTL 的实现就是环形覆盖**：容量 256 × 每村民每次读 8 条，无需任何定时清理逻辑。

### 3.7 与 SignalBoard 的边界

SignalBoard 消息带**接收者语义**（TRADE_WANT 期望有人供货、集结期望有人来），是请求-响应；EventBus 事件是**陈述**（世界变了），无人有义务响应。需要"有人回应"走 SignalBoard，只需要"有人知道"走 EventBus。

---

## 四、GossipChannel 设计

### 4.1 知识表示：复用 ChunkMemory，不新建存储

Gossip 的"知识"**不是新数据结构**，就是村民已有的 `ChunkMemory` 特征锚点（`ChunkFeature` + packed 位置 + `lastVisitedTick` 新鲜度）。GossipChannel 只负责**搬运**：把 A 的记忆条目复制进 B 的记忆。

置信度标记（一手 / 二手）暂不引入——v1 的知识都是客观资源点，不存在观点分歧；置信度留给 v2 的八卦类知识。

### 4.2 传播：挂在社交系统相遇（已确认）

复用 `VillagerSocialSystem` 已有的相遇/问候近邻逻辑：

```
当两村民距离 < GOSSIP_DISTANCE (= 8 格) 且触发一次社交相遇：
  以概率 GOSSIP_CHANCE (= 30%) 发生一次情报交换：
    1. 从 A 的记忆里选一条「B 不知道 且 新鲜度最高」的特征锚点
       （B 不知道 = B 的对应 ChunkMemory 无此 feature，或 B 的 lastVisitedTick 更旧）
    2. 复制进 B 的 ChunkMemory（带 A 的 lastVisitedTick 作为二手新鲜度）
    3. 交换是单向的且每次相遇最多 1 条（对称交换会指数扩散，难调试）
  概率与距离/新鲜度无关（v1 保持简单）；GOSSIP_CHANCE 为 ModConfig 项
```

- **不产生新目标**：传播只改变"B 知道什么"，B 是否去用这条知识由既有决策层决定（铁匠行为池"记忆里有铁矿 → 高权 craft"的现有设计直接受益）；
- **相遇判定零新增成本**：社交系统已经在做近邻查询；
- **摊平**：交换本身 O(1)，无预算问题；唯一注意是相遇事件的触发频率已有社交系统自身节流。

### 4.3 信息差与调试度量

信息差的**存在**就是目的（协作与交易的动机来源）。调试用两个 BrainLog 指标：

- `KNOWLEDGE_COVERAGE`：某特征（如某铁矿）全村知晓人数 / 村民总数——观察一条情报的扩散曲线；
- `GOSSIP_TRANSFER`：每次交换一行 trace（谁 → 谁、什么特征、哪个区块）。

### 4.4 失效：衰减与证伪双机制（核心）

| 机制 | 适用 | 行为 |
|------|------|------|
| **被动衰减** | 可再生资源（树/水/农田/建筑） | 新鲜度随 `lastVisitedTick` 缓慢下降 → `saliency` 淘汰；无需额外代码，ChunkMemory 已有 |
| **主动证伪** | 可耗尽资源（矿） | 村民**实际到达**该位置执行采集/挖掘类目标，发现目标不存在 → 立即删除该条目 + 记一条 agent 记忆（"X 处矿已耗尽"），防止重复跑空 |
| **有效期** | 可耗尽资源 | 二手矿类情报附加 `EXPIRE_TICKS`（建议 3 个游戏日 = 72000 tick），到期从「可传播集」移除——不再传给别人，但自己保留到证伪为止 |

**与未来挖矿功能的衔接**：挖完矿的村民持有一手证伪；v2 扩展为**负面情报传播**——下次相遇把"X 处已挖空"传给仍持有旧知识的村民，使全村知识收敛（每个人都不用再白跑一趟）。v1 只做持有者本人证伪删除，负面情报不进 v1（避免"删除他人记忆"的写冲突语义过早引入）。

### 4.5 为什么按这条边界切（回答设计评审的疑问）

- 威胁位置时效介于事件与知识之间（比作物长、比矿短），引入会造成"半衰期"调参泥潭 → v2 用"威胁频率统计"（哪一片**经常**有僵尸）这类真正长期成立的聚合知识再进 Gossip；
- 贸易需求是**请求**（期望回应）→ SignalBoard 已覆盖；
- 债务八卦是**观点与关系**（带主观性，需置信度建模）→ 属 LLM 社交层，不进确定性 Tier 1。

---

## 五、实施阶段

### Phase C1 — WorldEventBus 最小闭环

| 项 | 内容 |
|----|------|
| 新建 | `ai/world/WorldEventBus.java`（环形缓冲 + publish + 维度单例） |
| 改 | `VillagerAgentData`：`eventCursor`（NBT）；`updateAgent`：消费循环 |
| 接 | `BLOCK_CHANGED`（挂 `WorldStructureIndex.onBlockChanged`）+ `ITEM_DROPPED`（`EntityJoinWorldEvent`） |
| 验证 | 放一块方块 → 附近村民（≤4 区块）日志出现消费 trace；远处村民**无** trace；重启后游标清零无补读风暴 |

### Phase C2 — 事件响应目标

- `CROP_MATURED`（block-change 派生）+ `NEW_VILLAGER`；
- 注入逻辑走结构化去重 + 提权；
- 验证：夜间种下催熟作物 → 清晨农民 farm 目标被提权并带 `targetLocation`，无需等例行扫描。

### Phase C3 — GossipChannel

| 项 | 内容 |
|----|------|
| 改 | `VillagerSocialSystem` 相遇处加概率交换钩子；`ChunkMemory`：可耗尽特征加 `expireTick` 字段 |
| 新建 | `BrainLog.gossip` trace |
| 验证 | 村民 A 扫过含矿区块 → 与远处村民 B 多次相遇后 B 的 ChunkMemory 出现该锚点 → B 铁匠出现 craft 倾向变化；`KNOWLEDGE_COVERAGE` 曲线呈 S 型扩散 |

### v2 候选（明确不做进 v1）

负面情报传播（证伪收敛）、威胁频率统计、置信度与二手衰减系数、WORKSTATION_FREE、社交网络权重影响传播概率（朋友间更快）。

---

## 六、现有类接线点清单

| 现有类 | 改动 |
|--------|------|
| `VillagerAgentData` | + `eventCursor`（NBT 序列化） |
| `VillagerAgentManager.updateAgent` | + 事件消费循环（预算 ≤ 8 条/次） |
| `WorldStructureIndex.onBlockChanged` | + `WorldEventBus.publish(BLOCK_CHANGED)`（一行） |
| `VillagerEventHandler` | + `EntityJoinWorldEvent` listener（ITEM_DROPPED / NEW_VILLAGER） |
| `VillagerAgentManager.tickCombat` | + `publish(THREAT_APPEARED)`（一行） |
| `VillagerSocialSystem` | + 相遇处 gossip 交换钩子 |
| `ChunkMemory` | + 可耗尽特征 `expireTick` |
| `ModConfig` | + `PERCEPTION_RADIUS_CHUNKS` / `GOSSIP_CHANCE` / `EVENTS_PER_THINK`（行为开关，toml） |
| `BrainLog` | + gossip / knowledge-coverage trace |

## 七、已知局限与开放问题

1. `ITEM_DROPPED` 在刷怪塔/农场场景可能高频刷屏 → 生产端加**同类合并**（同 chunk 同类型事件合并为计数）是 v1 就要留的口子；
2. 相遇交换是单向单条，扩散速度依赖社交频率——若村里村民作息高度重合（现状是昼夜相位、非 per-villager chronotype），扩散会比预期快，属可接受偏差；
3. 二手知识无置信度：B 从 A 处得到的过期矿点会被证伪机制兜底（跑一趟删一条），代价可控；
4. 感知门用区块曼哈顿距离（矩形），比欧氏稍宽——刻意从简，与 `WorldStructureIndex` 的 `queryNear` 语义一致。
