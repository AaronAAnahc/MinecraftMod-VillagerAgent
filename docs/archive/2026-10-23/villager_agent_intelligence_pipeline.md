# VillagerAgent 村民智能管线设计（基线自洽 + LLM 叠加）

> 设计目标：**没有 LLM 时村民也有一定程度的智能；LLM 只是让村民拥有"更多"智能（增强，而非前提）。**
> 核心思路：借鉴 Settlements 模组的 `HeuristicAsyncPlanGenerator`（永远兜底）+ `LlmOverlayPlanGenerator`（叠加）双轨模型，
> 把"智能"拆成 **Tier 1 确定性基线规划器（零 LLM、永远在线）** 和 **Tier 2 LLM 叠加规划器（可关闭、只增不改）** 两层，
> 两层都汇入同一条 **统一目标漏斗（`AgentGoal` 列表 + 优先级仲裁）**，执行层（`BehaviorExecutor` + `VillagerSkill`）完全不依赖 LLM。

---

## 一、设计原则

1. **基线必须自洽**：断网 / 关 LLM / API 故障（熔断）时，村民依旧能吃饭、睡觉、务农、互助、防御、按职业作息行动。基线不调用任何 LLM。
2. **LLM 只当"目标作者 / 修饰者"**：它只往目标列表里 *增、改、标重要性*，绝不直接驱动移动或世界操作。执行永远走 `BehaviorExecutor` → `VillagerSkill`。这样 LLM 挂了，执行层毫发无损。
3. **单一目标漏斗**（承接 `docs/架构反思与精简方案.md`）：世界上只有一种"现在要做什么" = `AgentGoal`。`scheduledActivity`（mode）退回成"例程目标生成器 + 派生上下文"，不再作为第二个驱动源。
4. **确定性 + 可复现**：基线规划用 `planSeed = hash(villagerId + day)`，同人同日结果一致，便于调试与回归。
5. **InferenceGate 单一开关**：一个配置项即可把村民切成"纯规则模式"。复用已有 `LLMGuard` 熔断器（连续失败→rule-only 冷却）即可天然实现。

---

## 二、整体管线（分层 + 数据流）

```
                          ┌──────────────────────────────────────────────┐
                          │            Tier 0  感知 / 世界信号            │
                          │  ChunkMemory · WorldStructureIndex · Vision   │
                          │  +【新增】WorldEventBus（事件总线+感知半径）  │
                          │  +【新增】GossipChannel（八卦，唯一远距信道） │
                          └───────────────┬──────────────────────────────┘
                                          │ 事件 / 观察 / 他者知识
                       ┌──────────────────┴───────────────────┐
                       ▼                                      ▼
        ┌──────────────────────────┐            ┌─────────────────────────────┐
        │  Tier 1 确定性基线规划器   │            │  Tier 2 LLM 叠加规划器       │
        │  （零 LLM，永远在线）      │            │  （可关闭，只增/改目标）       │
        │  · Needs→目标注入          │            │  · 长程议程 LongTermAgenda   │
        │  · Chronotype 作息去同步   │            │  · 反思/对话/思考             │
        │  · ProfessionBehaviorPool │            │  · 建造指令 / 债务催收        │
        │  · 世界事件响应式目标      │            │  · 提升某目标 importance      │
        │  · 记忆/八卦驱动目标       │            │  · 细化 targetLocation/Item   │
        │  · 防御集结（SignalBoard） │            └──────────────┬──────────────┘
        └──────────────┬───────────┘                           │ source = LLM
                       │ source = BASELINE / EVENT / GOSSIP    │
                       └───────────────────┬───────────────────┘
                                           ▼
                          ┌──────────────────────────────────────┐
                          │      统一目标漏斗  AgentGoal[]        │
                          │  扩展字段: source / routine窗口 /      │
                          │  goalState；按 priority 仲裁           │
                          │  优先级: needs > defense > LLM > routine│
                          └───────────────┬──────────────────────┘
                                          ▼
                          ┌──────────────────────────────────────┐
                          │  执行层（零 LLM）                     │
                          │  BehaviorExecutor → VillagerSkill     │
                          │  （单例 FSM，状态落 agent / NBT）       │
                          │  Sleep/Farm/Craft/Move/Trade/...     │
                          └──────────────────────────────────────┘
```

**闭环**：行为产生世界变化 → `WorldEventBus` 广播 → 他人在感知半径内收到 → 晋升为知识/目标 → 影响 gossip / 规划 / 对话。无 LLM 也有完整闭环。

---

## 三、Tier 0 —— 感知与世界信号（已有 + 待补）

**已有（直接复用）**
- `ai/memory/ChunkMemory.java`、`ai/vision/*`（Far/Mid/Near 分层感知）—— 已落地。
- `ai/world/WorldStructureIndex.java` —— 建筑/床索引（事件驱动、跨重启持久化）。
- `VillageSignalBoard.java` —— 村庄级"呼喊"频道，目前仅用于战斗集结。

**待补（让基线有"反应力"）**
1. **`WorldEventBus`（仿 Settlements）**
   - 全局事件日志 + 每村民游标 + TTL 驱逐。事件类型：`CROP_MATURED` / `THREAT_APPEARED` / `BLOCK_CHANGED` / `ITEM_DROPPED` / `WORKSTATION_FREE` / `NEW_VILLAGER` 等。
   - **感知门（反心灵感应）**：村民只消费自身 `感知半径`（区块曼哈顿距离 ≤ N，建议 4 区块=64 格）内的事件；远处事件须经 gossip 传播才可知。
   - 这是"无 LLM 也能对环境变化做出反应"的关键。
2. **`GossipChannel`（仿 Settlements `GossipSession`）**
   - 唯一的远距离信息传播渠道：村民 A 亲眼看到铁矿 → 写入自己记忆 → 与邻近村民 B 相遇时按概率把"铁矿@X"传给 B。
   - 让"知识"能跨越距离传播，制造信息差与协作（"我知道哪有矿，你不知道"）。
   - 与 `VillageSignalBoard` 分工：SignalBoard 是**即时呼喊**（战斗），Gossip 是**离线/偶发情报交换**（资源、风险）。

---

## 四、Tier 1 —— 确定性基线规划器（无 LLM 核心）

> 对应 Settlements 的 `HeuristicAsyncPlanGenerator`，但**永远运行、永不调用 LLM**。
> 它替换现有 `VillagerSchedulePlanner.generateDailyPlan` 的"LLM 主路径"，让 LLM 退为可选叠加。

新建 `ai/planning/DeterministicPlanGenerator.java`，在每次黎明（`dayTime < PLAN_WINDOW`）对每位村民生成当日目标集，并支持**事件触发的增量注入**（不必等黎明）。

### 4.1 需求驱动目标注入（当前缺口：mood 只喂 LLM）
现状：`VillagerNeedsSystem.deriveMood()` 算出的 mood/饥饿/疲劳**只进 `buildNeedsDescription()` 喂给 LLM prompt**，从不驱动基线目标。
改造：基线规划器直接读 `agent.getHunger()/getFatigue()/getMood()` 注入目标：
- `hunger < EAT_THRESHOLD` 且库存无食物 → `gather(food)` 目标（priority 9，needs 级）。
- `fatigue` 高 + 夜晚 → `rest` 目标（priority 8）。
- `mood == DISTRESSED` → `socialize` 或 `retreat` 目标（缓解）。
这样饥饿的村民**无需 LLM 也会主动觅食**。

### 4.2 Chronotype 作息去同步（当前缺口：全村同步）
现状：疲劳直接绑全局夜晚（13000–23000），所有村民同一套作息，白天集体"下班"很假。
改造：仿 Settlements `Chronotype`，用 `hash(villagerId)` 派生每个村民的作息偏移（±1~2 小时）与"早睡/夜猫子"倾向。基线规划器据此错峰生成 routine 目标，打破同步、更自然，且**确定性可复现**。

### 4.3 ProfessionBehaviorPool 加权职业行为池（替换静态生成器）
现状：`ProfessionGoalGenerator.generateGoalsForProfession()` 是**静态**列表（农民永远那 4 条），不随时间/需求变化。
改造：仿 Settlements `ProfessionBehaviorPool` + `WeightedBehavior`，把职业行为建模为**带权选项**，输入 `(profession, level, dayTime, mood, knownResources)`，输出加权抽样的目标：
- 农民·白天 → 高权 `farm(maintain/reclaim)`；农民·黄昏 → 中权 `socialize`。
- 铁匠·白天 → 高权 `craft(iron_sword/axe)`，前提是记忆/八卦里有"铁矿@X"或库存有锭。
- 抽样用 `planSeed` 喂的 `Random`，同人同日一致。

### 4.4 世界事件响应式目标（无 LLM 反应力）
监听 `WorldEventBus`（Tier 0）：
- `CROP_MATURED` → 农民得 `gather` 目标。
- `ITEM_DROPPED`（在半径内）→ `pickup` 目标（已有 `ENABLE_AUTO_PICKUP`，收编为 goal）。
- `WORKSTATION_FREE` → 对应职业 `craft` 目标。
- `THREAT_APPEARED` → 触发 `VillageSignalBoard.respond()` 集结 + `defend` 目标（见 4.6）。

### 4.5 记忆 / 八卦驱动目标
若村民记忆或 gossip 含"资源点@X"（如矿、树林、水源），基线规划器可生成 `goto`/`gather` 目标——**这是"智能"的体现：基于已知信息主动行动，而非随机乱走**。

### 4.6 防御集结（已有，收编为 defend goal）
`VillageSignalBoard` 已能多村民合围威胁（groupDps ≥ threatDps 才冲锋）。将其从"临时动作"收编为正式 `defend` 目标，进入统一漏斗，优先级仅次于 needs。

### 确定性约束
`planSeed = longHash(agent.getVillagerId().toString() + day)`；所有随机（Chronotype、行为池抽样、routine 顺序）都走这一个 `Random(planSeed)`。保证可调试、可回归。

---

## 五、Tier 2 —— LLM 叠加规划器（增强，可关闭）

> 对应 Settlements `LlmOverlayPlanGenerator`。**它不生成"完整计划"，只在基线之上做增量。**

保留并升级现有 `VillagerSchedulePlanner`（LLM 路径）+ `HarnessDecisionPlanner`：
- **只增 / 改目标**：新增 `source = LLM` 的目标（建造指令、债务催收、长程交易），或**提升某基线目标 importance**（"今天特别想修水渠"）。
- **长程议程**：`LongTermAgenda`（债务/承诺）由 LLM 在反思时派生，落为 `ambition` 类目标，按 priority 进入漏斗（解决架构反思里"议程→goal 太被动"的缺口：基线也可在黎明把到期债务转成高优目标）。
- **社交/对话/思考**：`VillagerSocialSystem`、`tickThoughts`、`generateReflection` 维持 LLM 驱动——这些是"更像人"的部分，不是生存刚需。
- **细化目标**：把模糊描述填成 `targetLocation` / `targetItem` / `targetQuantity`，交给执行层。

**InferenceGate（单一开关）**
- 复用 `LLMGuard` 熔断：连续失败 → `isCircuitOpen` → 自动 rule-only。
- 新增 `ModConfig.ENABLE_LLM_OVERLAY`（默认 true）。关掉后，`VillagerSchedulePlanner` 的 LLM 路径整体跳过，只跑 `DeterministicPlanGenerator`。
- 叠加层**失败不影响基线**：即便 LLM 超时/拒绝，基线目标照常执行。

---

## 六、统一目标漏斗与仲裁（升级 `AgentGoal`）

扩展 `AgentGoal`（现有字段已有 `goalType/priority/importance`，再加）：
- `GoalSource source`：`BASELINE` / `EVENT` / `GOSSIP` / `LLM` / `AMBITION`。
- `boolean routine` + `long validFrom, validUntil`：例程目标带时间窗，到点**过期**而非被"完成"消费（解决 farming mode 冲突）；ambition 才被消费。
- `GoalState state`：`PENDING / ACTIVE / DONE / EXPIRED`。

`processGoals()`（现有主仲裁）调整为：
1. needs 级目标（吃/睡/防御）priority 顶格，任何 LLM ambition 都让路 → 村民先吃饱再"修水渠"。
2. 排序后取栈顶 → `BehaviorExecutor.findHandler` → `VillagerSkill`。
3. 每日修剪：`routine` 过期重建；`LLM/AMBITION` 按 `importance` 存活（低 importance 随机淘汰，呼应现有 `pruneLowImportanceGoals`）。
4. `scheduledActivity` 彻底只读派生（inferModeFromCurrentGoal），不再反向驱动。

---

## 七、执行层（已有，收编）

- **`BehaviorExecutor`**：已是"无 LLM 执行器"，注册 `VillagerSkill` 单例；状态落 `agent`（NBT 序列化）。保留。
- **待补 skill（统一入口）**：`CraftSkill` / `MoveSkill` / `TradeSkill` / `SocializeSkill` / `DefendSkill` / `GatherSkill` / `BuildSkill`，把现有散落于 `VillagerActivitySystem`、`executeCraftGoal` 等的逻辑收编进 skill，退休 legacy switch（呼应架构反思"待收敛项"）。
- **行为安全网**：仿 Settlements `PlanRunner` 的超时（如 120s 某行为未结束则 FAIL 并标记目标重试/放弃），避免卡死。

---

## 八、智能梯度对照表

| 能力 | 无 LLM（Tier 1 基线） | + LLM（Tier 2 叠加） |
|------|----------------------|----------------------|
| 饥饿觅食 / 疲劳就寝 | ✅ 需求驱动目标 | ✅（同，且会"抱怨"） |
| 按职业作息、错峰行动 | ✅ Chronotype + 行为池 | ✅（且会"计划"得更细） |
| 作物成熟去收 / 捡掉落物 | ✅ 事件响应式 | ✅ |
| 集体防御威胁 | ✅ SignalBoard 集结 | ✅（且会"呼救/分工"） |
| 基于已知资源点主动行动 | ✅ 记忆/八卦驱动 | ✅（且会"交易换资源"） |
| 村民间知识传播 | ✅ GossipChannel | ✅ |
| 建造复杂结构 | ⚠️ 仅简单 routine 建造 | ✅ LLM 设计 + 执行 |
| 长程债务/承诺追踪 | ⚠️ 基线把到期债务转高优，但无"谈判" | ✅ 议程+反思+催收 |
| 自然语言社交/内心独白 | ❌ | ✅ |
| 反思与自我目标演化 | ❌ | ✅ |

**结论**：无 LLM 时村民是"能自理、会协作、对环境有反应的社群"；LLM 让它变成"有志向、会表达、能搞复杂工程的人"。智能是**梯度**，不是开关。

---

## 九、落地建议（分阶段，复用现有类）

**Phase A — 让基线自洽（关掉 `ENABLE_DAILY_SCHEDULE` 即可验证无 LLM 智能）**
- 新建 `ai/planning/DeterministicPlanGenerator.java`：Chronotype(§4.2) + 需求注入(§4.1) + `ProfessionBehaviorPool`(§4.3)。
- `AgentGoal` 加 `source` / `routine` / `validFrom-validUntil`(§六)。
- `VillagerSchedulePlanner` 在 `ENABLE_LLM_OVERLAY=false`（或熔断）时改调 `DeterministicPlanGenerator`。
- 验证：断网/关 LLM，村民仍能吃睡农防。

**Phase B — InferenceGate 与叠加收编**
- `ModConfig` 加 `ENABLE_LLM_OVERLAY`；`LLMGuard` 熔断已可用。
- 把 `LongTermAgenda` 派生接入基线（到期债务→高优 ambition），解决被动缺口。
- 收编 `VillagerActivitySystem` / `executeCraftGoal` 进 `CraftSkill`/`MoveSkill`（退休 legacy switch）。

**Phase C — 感知深化（反应力 + 信息差）**
- 新建 `WorldEventBus` + `GossipChannel`（§三）。
- `VillageSignalBoard` 收编为 `defend` 目标。
- 加 `PlanRunner` 行为安全网。

---

## 十、现有类 → 设计角色映射

| 现有类 | 设计角色 | 改动 |
|--------|----------|------|
| `BehaviorExecutor` | 无 LLM 执行器（Tier 执行层） | 保留，补 skill 注册 |
| `VillagerSkill` / `SleepSkill` / `Farm*Skill` | 单例 FSM 执行单元 | 保留，扩 Craft/Move/Trade/... |
| `VillagerNeedsSystem` | 需求→mood 计算 | 改造：mood 直接注入基线目标（§4.1） |
| `VillagerSchedulePlanner` | 黎明规划入口 | 拆：LLM 路径→叠加；默认→`DeterministicPlanGenerator` |
| `ProfessionGoalGenerator` | 静态职业目标 | 替换为 `ProfessionBehaviorPool`（§4.3） |
| `VillageSignalBoard` | 战斗集结 | 收编为 `defend` 目标（§4.6） |
| `AgentGoal` | 统一目标 | 扩 `source`/`routine`/时间窗（§六） |
| `LLMGuard` | LLM 熔断/超时 | 直接复用为 InferenceGate 后半段 |
| `WorldStructureIndex` / `ChunkMemory` / `VillagerVisionSystem` | 感知（Tier 0） | 复用 |
| `LongTermAgenda` / `TodoParser` | 长程议程 | 接入基线派生（§五） |

---

## 十一、与 Settlements 的直接对应（便于对照源码）

| Settlements | 我们的对应 |
|-------------|-----------|
| `HeuristicAsyncPlanGenerator` | `DeterministicPlanGenerator`（新建，永远在线） |
| `LlmOverlayPlanGenerator` | `VillagerSchedulePlanner` LLM 路径 + `HarnessDecisionPlanner`（叠加） |
| `WorldEventBus` / `PerceptionGate` / `MemoryImportanceGate` | 新建 `WorldEventBus`（感知半径门） |
| `GossipSession` | 新建 `GossipChannel` |
| `Chronotype`（UUID-seed） | 新建 `Chronotype`（§4.2） |
| `ProfessionBehaviorPool` + `WeightedBehavior` | 新建 `ProfessionBehaviorPool`（§4.3） |
| `DayPlan` / `PlanSlot` | `AgentGoal[]`（扩展 routine 窗口） |
| `InferenceGate` | `ENABLE_LLM_OVERLAY` + `LLMGuard` 熔断 |
| `PlanRunner` 安全网 | 新建行为超时（§七） |
| `VillagerBrain` / `IBrain` | 我们的 `VillagerAgentData` + `BehaviorExecutor` |

> 说明：本文基于 `Reference/Settlements-Alpha-mainline` 的 AI 架构与 VillagerAgent 现有源码（`VillagerNeedsSystem` / `VillagerSchedulePlanner` / `ProfessionGoalGenerator` / `BehaviorExecutor` / `VillageSignalBoard` / `LLMGuard` 等）对照整理，所有"现状"描述均对应真实类名与机制。落地前建议先按 Phase A 做最小验证（关 LLM 看基线是否自洽），再叠加 LLM。

---

## 十二、交易系统重构（价值表 + 固定回复 + 双面板）

> **当前痛点**：玩家↔村民交易完全由 LLM 裁定——`TradeRequestPacket.evaluateTradeWithLLM` 把一段"价值指南"塞进 prompt，让模型输出 ACCEPT/REJECT + 理由。这直接违背"无 LLM 也有智能"的目标：断网 / 关 LLM 时交易直接瘫痪。
> **重构方向**：用 **物品价值表 + 规则化估值 + 固定回复表** 替代 LLM 裁定，使交易在没有 LLM 时也能正常讨价还价；LLM 退为**可选项**——只负责修饰价值表与回复口吻，绝不参与成交裁定（呼应 Tier 2"LLM 改数据、不碰执行"）。

### 12.1 物品价值表（每村民一份，可被 LLM 修改）

- **数据结构**：`Map<String,Float> itemValues`（registry id → 绿宝石等价价值点），存于 `VillagerAgentData`（NBT 序列化，跨重启保留）。
- **默认值种子**：`src/main/resources/villageragent/item_values.json` 提供一份**默认 MC 交易价值基准**（绿宝石计价），村民出生时深拷贝进自己的表。示例：
  ```json
  { "minecraft:emerald": 10, "minecraft:diamond": 120, "minecraft:gold_ingot": 20,
    "minecraft:iron_ingot": 8, "minecraft:iron_sword": 32, "minecraft:bread": 4,
    "minecraft:wheat": 1, "minecraft:dirt": 0, "minecraft:cobblestone": 0 }
  ```
- **可被 LLM / 其他途径修改**：Tier 2 叠加规划器可在反思时改写某村民的 `itemValues`（如"被坑过一次后觉得钻石更值钱"）；玩家长期行为（某村民总收不到某物）也可微调。改动写回 `VillagerAgentData`。
- **不在表里的东西 = 村民不想要**：价值表缺失的物品，村民视为"不属于我的经济"——交易时针对性回复（见 12.2）。
- **衔接原则**：价值表是**基线数据**（无 LLM 即用），LLM 只是其众多"编辑器"之一。

### 12.2 规则化估值 + 固定回复表（无 LLM 成交裁定）

**服务端评估器 `RuleBasedTradeEvaluator`（替换 `evaluateTradeWithLLM`）**：把一笔交易拆成两堆——玩家**给出 G**（村民收到）、玩家**索取 W**（村民付出），各自按价值表求和：
- `vG = Σ table[item]`（G 中缺失项 → 价值 0 且标记"不需要"）
- `vW = Σ table[item]`
- `ratio = vG / max(vW, ε)`

按阈值分支（阈值放 `ModConfig` 可调）：

| 情形 | 条件 | 触发固定回复表 | 行为 |
|------|------|----------------|------|
| 给出"不需要的东西" | G 中某项不在表 | `unwanted` | 拒："我不需要这个" |
| 索取"不卖的东西" | W 中某项不在表 | `not_for_sale` | 拒："这个我不卖" |
| 差很多 | `ratio < TRADE_RATIO_LOW`(0.5) | `too_low` | 拒 |
| 差一点 | `LOW ≤ ratio < TRADE_RATIO_FAIR_LOW`(0.9) | `almost` | 还价（提示再添多少） |
| 刚好成交 | `0.9 ≤ ratio ≤ 1.1` | `fair` | 接受 |
| 超出价值（玩家多给） | `ratio > 1.1` | `overpay` | 接受 + 惊喜/感激 |

**固定回复表 `trade_replies.json`（内容数据，JSON 存，不写死在代码）**：
```json
{
  "unwanted":     ["我不需要这个。", "这东西对我没用。"],
  "not_for_sale": ["这个我可不卖。", "这不属于能交易的东西。"],
  "too_low":      ["这点东西想换我的%s？再添点吧。", "太少了，我不换。"],
  "almost":       ["就差一点点，凑齐我就换。", "再加%s我就答应你。"],
  "fair":         ["成交！", "公平交易，多谢。"],
  "overpay":      ["哇，这太多了，谢谢！", "你太大方了，收下吧。"]
}
```
- 可选按 `personality` 分桶（greedy/kind/…）覆盖默认行，让不同性格用不同口吻。
- **无 LLM 时完全可用**：估值与选句是纯规则查表，不调任何模型。LLM（可选）仅在 Tier 2 里挑更贴性格的那一句，或在 `almost` 时生成一句定制还价——但它**不参与裁定**。

### 12.3 交易 UI 重构：双面板

`VillagerTradeScreen` 改为两区：

1. **村民交易面板（这是村民想要进行的交易）** —— 仿原版村民交易 UI
   - 由服务端 `VillagerOfferGenerator` 按 **职业目录 + 需求 + 价值表** 生成该村民的"常驻报价单"，每条 = `[村民给出 X] ⇄ [村民索取 Y]`。
     - 来源：`ProfessionCraftCatalog`（能造什么）+ `VillagerNeedsSystem`（缺什么）+ `itemValues`（定价）。
     - 例：农民 `20x wheat ⇄ 1x emerald`；铁匠 `1x iron_sword ⇄ 8x emerald`；饿了时出现 `food ⇄ emerald` 收购单。
   - 报价用同一张价值表定价，故对村民"公平"，玩家点选即**自动成交**（不再走估值）。
   - 随需求/等级变化刷新。
2. **自由交易面板（自由交易）** —— 当前的"你给 / 你想要"拖拽区
   - 玩家可提出任意组合；提交后经 `RuleBasedTradeEvaluator` 估值，按 12.2 的固定回复表回应（成交 / 还价 / 拒绝）。
   - 这是"议价"入口；村民报价单只是其中一种被预先定价好的提议。

**数据流**：自由面板提交 → `TradeRequestPacket` → 服务端 `RuleBasedTradeEvaluator`（查价值表 + 固定回复）→ `TradeResultPacket`（含 `replyKey` + 实际文本 + 成交/拒绝 + 库存快照）。若启用 LLM 叠加，仅在"选句口吻 / 生成定制还价"时轻量调用，失败也不影响规则裁定。

### 12.4 与两档智能模型的衔接

| 组件 | 无 LLM（基线） | + LLM（Tier 2 叠加） |
|------|----------------|----------------------|
| 价值表 | 默认 MC 基准 + 规则估值 | LLM 可改表（反思 / 被坑后调价） |
| 成交裁定 | 纯规则阈值 + 固定回复表 | 同（LLM 不裁定） |
| 回复口吻 | 固定回复表随机 / 按性格选 | LLM 可选挑更贴性格的一句 |
| 报价单 | 职业 + 需求 + 表生成 | LLM 可追加定制报价（如"帮我建个仓库"） |
| 村民↔村民交易 | `VillagerTradeSystem` 仍可用（建议也接价值表） | 同上 |

> 交易系统由此彻底脱离"LLM 才是大脑"的依赖：断网时村民照样能按自己的价值表跟玩家讨价还价、亮出报价单；LLM 只是让它更"有性格"、更"会调价"。

### 12.5 落地改动清单
- 新增 `ai/trade/ItemValueTable.java`：加载默认 JSON + 每村民覆盖，挂到 `VillagerAgentData`。
- 新增 `ai/trade/RuleBasedTradeEvaluator.java`：替换 `TradeRequestPacket.evaluateTradeWithLLM`。
- 新增 `ai/trade/VillagerOfferGenerator.java`：生成报价单喂 UI。
- 新增资源 `item_values.json` / `trade_replies.json`（内容数据）。
- `ModConfig` 加 `TRADE_RATIO_LOW` / `FAIR_LOW` / `FAIR_HIGH` 等阈值。
- `VillagerTradeScreen` 重构为双面板；`TradeResultPacket` 携带 `replyKey` + 实际文本。
- （可选）`VillagerTradeSystem`（村民↔村民）也接入价值表，统一经济。
