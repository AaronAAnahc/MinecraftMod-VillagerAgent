# VillagerAgent 代码索引（Agent 导航文档）

> 快照日期：2026-09-03；**复核日期：2026-09-23**（源码自 2026-09-01 起无改动，本索引仍然准确）
> 覆盖范围：`src/main/java/com/github/AaronAA0721/villageragent/**`（122 个 Java 文件）+ `src/main/resources/**`
> 目标读者：需要在本项目里**快速定位文件/函数、理解模块边界**的 AI Agent（以及接手的人）
>
> **用法**：先用 §3 模块地图定位"这事归哪个模块"，再翻 §4 对应模块的**文件清单表**找文件，最后用 §6「任务 → 入口速查表」直接拿到改动的落点。§5 是主干调用链，读代码前先看一遍。
>
> **配套 skill**：项目级 skill `villager-agent-codebase-nav`（`.workbuddy/skills/`）是本文件的**压缩版导航入口**（模块地图 + 任务→落点 + 大文件 grep 锚点，约 150 行）。
> **省 token 的正确顺序**：先让 skill 定位文件 → 只在需要"某模块完整清单"时才回读本文件对应的 §4 小节 → **不要整篇读本文件**（522 行，仅按需跳读）。

---

## 0. 三十秒速览

| 问题 | 答案 |
|---|---|
| 这是什么 | Minecraft **1.16.5 / Forge 36.2.42** 的 Forge Mod，把原版村民改造成有记忆、目标、日程、能力值的自治 Agent |
| 代码规模 | 122 个 Java 文件，约 **1.6 万行**；主包 `com.github.AaronAA0721.villageragent` |
| Mod 入口 | `Villageragent.java`（`@Mod("villageragent")`） |
| 每 tick 入口 | `events/VillagerEventHandler#onWorldTick` |
| 中枢调度 | `ai/VillagerAgentManager`（1605 行，最大的文件） |
| 决策范式 | **LLM 只写目标（goal），不直接驱动行为**；目标由 `ai/behavior/*Skill` 无 LLM 地执行 |
| 感知范式 | Far / Mid / Near 三层，见 §4-M11 / M12 / M13 |
| 持久化 | 三套：`VillagerAgentSavedData`（agent 记忆）、`StructureIndexSavedData`（建筑索引）、Forge Capability（熟练度） |

---

## 1. 技术栈与硬约束

| 项 | 值 | 备注 |
|---|---|---|
| Minecraft | 1.16.5 | MCP 映射版本见 `build.gradle` |
| Forge | 36.2.42 | **1.16.5 的 API 与 1.17+ 差异极大**，不要凭新版经验写 |
| 构建 | Gradle（`gradlew.bat`），产物在 `build/` | **约定：AI 不编译，编译由人执行并把报错贴回** |
| LLM 后端 | `LLMService`，支持 `openai` / `anthropic` / `ollama` / `gemini` 四种 | 配置在 `config/villageragent-common.toml` |
| 网络 | Forge `SimpleChannel`（`network/ModNetworking`） | 9 个 packet |
| 数据表 | JSON（Gson），部分可运行时热改 | 见 §7 |

**1.16.5 已知映射坑**（详见项目级 skill `villager-agent-mod-dev`）：
- **没有** `BlockPos.offset(Direction)`，只有 `relative(Direction)` / `offset(int,int,int)`。
- 世界接口叫 `World` / `ServerWorld`（不是 `Level`）；NBT 是 `CompoundNBT`（不是 `CompoundTag`）。
- 物品栏是 `IInventory`；渲染是 `MatrixStack` + `IRenderTypeBuffer`。
- 行为层不能直接用新版 `Brain`/`MemoryModuleType` 的写法，本项目自己实现 goal/skill 调度。

---

## 2. 运行时主干：一 tick 的生命周期

所有自治行为的起点是 `VillagerEventHandler#onWorldTick`（`TickEvent.Phase.END`，仅服务端）。严格按此顺序执行：

```
onWorldTick (Phase.END, server side)
│
├─ 1  VillagerAgentManager.tickAgents(world)          ← 慢 tick，受 AGENT_THINK_INTERVAL 节流
│     ├─ WorldStructureIndex.processPending(...)       ← 每 tick 排空 N 个床 flood-fill（Mid 层索引）
│     ├─ tickSleepHealing(sw)                          ← 睡眠回血 + 天亮唤醒
│     ├─ VillageSignalBoard.pruneExpired(sw)           ← 清理过期集结令
│     └─ for each agent (节流后) → updateAgent(world, agent)
│           ├─ VillagerNeedsSystem.tick(...)           ← 饥饿/疲劳/情绪
│           ├─ VillagerSchedulePlanner.tick(...)        ← 日程：清晨规划 / 傍晚反思
│           ├─ VillagerActivitySystem.tick(...)         ← 按 scheduledActivity 散步/休息
│           └─ processGoals(villager, agent)            ← ★ 核心
│                 ├─ RoutineScheduler.ensureRepeatableGoals(agent)   ← 注入 sleep/farm 例行 goal
│                 ├─ 每日一次：pruneLowImportanceGoals / generateNewGoals
│                 ├─ 战斗/建造动作占用中 → 直接 return（不让 goal 抢）
│                 ├─ GoalScheduler.expireGoals(goals, gt)
│                 ├─ 离相（phaseWeight<=0）的 REPEATABLE goal → 让出 running 槽
│                 ├─ GoalScheduler.pickNextGoal / shouldPreempt        ← 两段软抽样仲裁
│                 ├─ 睡眠守卫：夜间只允许 sleep goal 驱动（防止躺着移动）
│                 └─ BehaviorExecutor.findHandler(...) → skill.execute(...)
│                       → SkillResult { SUCCESS | RUNNING | BLOCKED | FAIL }
│                       → FAIL → FailureRecoveryPlanner.handle(...)   → 生成 craft / trade 新 goal
│
├─ 2  VillagerAgentManager.tickCombat(world)          ← 快 tick（每 tick），威胁扫描/追击/攻击/逃跑/集结
├─ 3  VillagerAgentManager.tickBuilding(world)        ← 快 tick，BuildJob 逐块放置/破坏
├─ 4  VillagerAgentManager.tickSocial(world)          ← 村民对村民对话（内部 ~10s 节流）
├─ 5  VillagerEconomySystem.tickEconomy(world)        ← 无 LLM 的供需广播/撮合/交割
├─ 6  VillagerAgentManager.tickThoughts(world)        ← 内心想法气泡（LLM，默认关）
├─ 7  VillagerAgentManager.tickGreeting(world)        ← 玩家靠近打招呼
├─ 8  DebugSync.tick(world)                           ← 调试快照下发（配置门控）
└─ 9  handleVillagerItemPickup(world)                 ← 每 tick 物品吸附与拾取
```

> **关键设计**：`scheduledActivity`（日程模式）**不再直接驱动行为**，它退化为两件事——① 例程 goal 生成器（farming / resting 时段注入带时间窗的 goal）；② 供 skill 挑选/动画用的派生上下文。所有"现在要做什么"统一收敛到 `AgentGoal` 列表，由 `GoalScheduler` 按优先级仲裁。详见 `docs/架构反思与精简方案.md`。

---

## 3. 模块地图（一级）

| # | 模块 | 包 | 文件数 | 一句话职责 |
|---|---|---|---|---|
| M1 | Mod 入口与生命周期 | `villageragent` | 1 | `@Mod`、注册 config / 事件 / 网络 / capability |
| M2 | 配置 | `config`, `ai.config` | 2 | Forge 配置规格 + 可编辑 JSON 的外部化目录 |
| M3 | 事件桥接 | `events` | 1 | Forge 事件 → AI 系统；也是 tick 总入口 |
| M4 | 网络 | `network` | 9 | C↔S 数据包（聊天/交易/合成/调试同步） |
| M5 | 客户端 | `client` | 6 | 聊天/交易 GUI、盔甲与手持物渲染层、调试 HUD |
| M6 | 调试同步 | `debug` | 1 | 服务端采集快照 → 下发 → 覆盖层 |
| M7 | 命令 | `commands` | 1 | `/va` 子命令 |
| M8 | Mixin | `mixin` | 2 | 村民实体/渲染器注入 |
| M9 | **Agent 数据与状态** | `ai`（根包部分） | ~20 | 记忆、目标、动作、日程、背包、议程、NBT |
| M10 | **调度与规划** | `ai`（根包部分） | ~10 | Manager 中枢、goal 仲裁、例行注入、失败重规划 |
| M11 | **行为 Skill 层** | `ai.behavior` | 20 | `VillagerSkill` 实现 + 失败语义 + 行为目录 |
| M12 | **动作原语** | `ai`（根包部分） | 5 | 农耕/战斗/合成/放破坏方块/装备 的静态方法库 |
| M13 | **感知三层** | `ai.memory` / `ai.vision` / `ai.world` | 18 | Far / Near / Mid 层 + 建筑与房间分割 |
| M14 | **世界交互系统** | `ai`（根包部分） | ~10 | 经济、社交、交易、需求、活动、建造 |
| M15 | **LLM 与护栏** | `ai`（根包部分）+ `ai.harness` | ~10 | 四种后端、决策 schema、超时/熔断/日志 |
| M16 | **熟练度与能力池** | `ai.proficiency` | 9 | Capability 熟练度（旧）+ 能力池（新，双轨并存） |

> 注意：`ai` 根包内文件较多（52 个），M9/M10/M12/M14/M15 是按**职责**对根包做的二次切分，不是 Java 包边界。

---

## 4. 模块详解

### M1 · Mod 入口与生命周期 · `villageragent`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `Villageragent.java` | 141 | `@Mod("villageragent")`。注册 COMMON 配置、事件总线；`setup()` 里打印 LLM 配置状态（含 **"brain offline" 警告**）、注册熟练度 capability、抽取可编辑 JSON 到 `config/villageragent/`、注册网络通道 | `MOD_ID`, `setup()`, `doClientStuff()`, `onRegisterCommands()` |

**注意**：`brain offline`（API Key 为空）的判定与告警就在 `setup()`（第 67–72 行）。排查"村民不说话/不思考"时先看这段日志。

---

### M2 · 配置 · `config` / `ai.config`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `config/ModConfig.java` | 402 | Forge `ForgeConfigSpec`。**所有行为开关的唯一真源**：LLM 参数、思考间隔、村民聊天、世界交互、建造、自动拾取、日程、社交、想法、harness（超时/熔断/日志）、调试覆盖层、熟练度同步 | 静态字段如 `LLM_API_KEY`, `AGENT_THINK_INTERVAL`, `ENABLE_AI_AGENTS`, `HARNESS_TIMEOUT_MS`, `DEBUG_SHOW_FIELD` … |
| `ai/config/VillagerAgentConfigDir.java` | 85 | 把内置 JSON 抽取到 `config/villageragent/` 供玩家改，运行时优先读外部文件 | `dir()`, `ensureDir()`, `path(String)`, `extractDefault(String)`, `open(String)` |

**约定**：`config/*.toml` 只放**行为开关**；**内容数据**（配方、价值表、解锁表）放 JSON，一个大类一个 JSON。

---

### M3 · 事件桥接 · `events`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerEventHandler.java` | 547 | Forge 事件 → AI 系统的**唯一入口**。包含 §2 的 tick 流水线、村民进世界时建 agent、死亡掉物、玩家右键开聊天、世界加载/保存、chunk 加载喂 `WorldStructureIndex`、方块放置/破坏/活塞/爆炸/流体 → 结构索引失效 | `onEntityJoinWorld`, `onWorldTick`, `onPlayerInteractEntity`, `onChunkLoad`, `onBlockPlaced`, `onBlockBroken`, `onVillagerDeath`, `onWorldSave`, `updateVillagerProfession`, `assignNearestJobSite` |

**含一个已知待办**：`updateVillagerProfession()` 在职业变更时刷新 agent 职业，但**是否触发工具补货（hoe 等）需要验证**——见 §8。

---

### M4 · 网络 · `network`

| 文件 | LOC | 职责 | 方向 |
|---|---|---|---|
| `ModNetworking.java` | 85 | SimpleChannel 注册与 id 分配 | — |
| `OpenChatPacket.java` | 93 | 请求打开聊天界面 | S→C |
| `ChatMessagePacket.java` | 158 | 玩家发送的一句话 | C→S |
| `VillagerResponsePacket.java` | 58 | 村民回复文本 | S→C |
| `SyncVillagerDataPacket.java` | 88 | 同步村民名/职业/性格/背包/装备 | S→C |
| `TradeRequestPacket.java` | **421** | 玩家↔村民交易请求、价值表估值、成交、还债 | C→S（最复杂的一个） |
| `TradeResultPacket.java` | 92 | 交易结果回传 | S→C |
| `CraftingRequestPacket.java` | 55 | 请求村民合成 | C→S |
| `DebugDataPacket.java` | 206 | 调试快照（建筑盒、watershed 种子、距离场、目标/记忆） | S→C |

**交易公平性**：`TradeRequestPacket#evaluateTradeWithValueTable` 用 `VillagerEconomySystem` 的买/卖价估值，`settleDebts()` 处理欠玩家的东西。

---

### M5 · 客户端 · `client`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerChatScreen.java` | 275 | 聊天 GUI（对话框 + 输入 + 交易结果提示） | `init()`, `addMessage()`, `render()`, `keyPressed()` |
| `VillagerTradeScreen.java` | **674** | 交易 GUI：玩家背包 / 村民背包 / 装备栏 / 两个卖出槽 + 两个买入槽 | `renderPanel`, `renderTradeSlot`, `mouseClicked`, `handleSlotInteraction`, `addToBuySlots`, `returnSellSlotsToPlayer` |
| `VillagerChatHandler.java` | 103 | 客户端侧的屏幕生命周期与消息路由 | `openChatScreen`, `receiveResponse`, `receiveTradeResult`, `isScreenOpen` |
| `DebugOverlay.java` | 225 | HUD 面板 + 世界内建筑线框 / seed 十字 / 距离场格子 | `onRenderHud`, `onRenderWorldLast`, `addBox`, `addCross` |
| `VillagerArmorLayer.java` | 161 | 村民盔甲渲染层 | `render()`, `renderSlot()` |
| `VillagerHeldItemLayer.java` | 67 | 村民手持物渲染层 | `render()` |

---

### M6 · 调试同步 · `debug`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `DebugSync.java` | 184 | 服务端按 `DEBUG_RENDER_RANGE` 找玩家注视的村民，采集 agent 快照打包成 `DebugDataPacket` 下发 | `tick(ServerWorld)`, `findLookedAtVillager(...)` |

---

### M7 · 命令 · `commands`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerAgentCommand.java` | 479 | `/va` 命令树 | `setConfig`, `getConfig`, `listConfig`, `showInfo`, `llmStatus`, `resolveAgent`, `clearGoals` |

> `llmStatus` 是排查 "brain offline" 的第一手段；配 key 用 `/va llm apikey <key>`。

---

### M8 · Mixin · `mixin`

| 文件 | LOC | 职责 |
|---|---|---|
| `VillagerEntityMixin.java` | 128 | 注入 `VillagerEntity`，在 AI 激活时抑制/改写原版行为（含 `isAgentActive(agent)` 判定） |
| `VillagerRendererMixin.java` | 49 | 注入村民渲染器，挂上 M5 的盔甲层与手持物层 |

配置在 `src/main/resources/villageragent.mixins.json`。

---

### M9 · Agent 数据与状态 · `ai`（根包）

**职责**：单个村民"是谁、记得什么、想要什么、正在做什么"的全部可变状态，以及 NBT 序列化。

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerAgentData.java` | **1488** | **Agent 主对象**：身份（名字/性格）、记忆、关系、目标列表、议程、背包、个人价值表、情绪、能力池、运行态（runningGoal / currentAction / 各类 tick 时间戳） | `getOrCreate` 在 Manager；`updateProfession()`, `addMemory()`, `getGoals()`, `setRunningGoal()`, `suspendRunningGoal()`, `restockAtJobBlock()`, `healProfessionCapability()`, `getCapabilityPool()`, 嵌套 `enum Mood`（HAPPY→DISTRESSED 五档） |
| `AgentGoal.java` | 184 | **统一目标对象**：类型/描述/优先级/权重/时间窗/冷却/产出物/失败记录 | 嵌套 `enum Lifecycle{ONE_SHOT, REPEATABLE, EXPIRING}`；`phaseWeight(dayTime)`, `isExpired()`, `isOnCooldown()`, `getOutcomeItems()`, `produces(itemId)`, `boostImportance()` |
| `VillagerAction.java` | 138 | **原子动作**（skill 每 tick 产出的东西）：类型 + 阶段 + 目标 + 卡住计数 | 嵌套 `enum ActionType`、`enum ActionPhase{SEARCH,WALK,ACT,WAIT}`；`getStandCell()`, `getBreakProgress()`, `setPendingReplant(...)` |
| `AgentInventory.java` | 216 | 27 格背包，同时实现 `IInventory` 以便接入 MC 容器 | `addItem/removeItem/countItem/hasItem`, `serializeNBT/deserializeNBT`, `getMinecraftInventory()` |
| `LongTermAgenda.java` | 104 | 长期议程抽象基类（NBT + 工厂） | `describe()`, `applyUpdate()`, `writeNBT()/fromNBT()` |
| `AcquireItemAgenda.java` | 48 | 议程子类：想要某物品 ×N，来源（craft/trade） | `getItemId/getQuantity/getSource` |
| `DealAgenda.java` | 64 | 议程子类：与某人达成的交易约定 | 对手/想要/提供 |
| `DebtAgenda.java` | 74 | 议程子类：某人欠我 X，含催收次数与冷却 | `getCollectionAttempts()`, `getLastCollectionTick()` |
| `GenericAgenda.java` | 73 | 议程子类：自由 KV + notes 的兜底 | `putField/getField`, `getNotes` |
| `DailySchedule.java` | 112 | 一天的 4 个时段（早/中/晚/夜）任务 | `MORNING_START` 等常量；`getCurrentTask(dayTimeTick)`, `toSummaryString()` |
| `FarmReclaimState`（在 behavior 包） | 53 | 开垦 FSM 的**状态 blob**（目标块数/待处理队列/已耕数） | 见 M11 说明 |
| `VillagerObservation.java` | 35 | 单条观察记录（类型/名称/坐标/详情） | 仅 `VillagerAgentData#recordObservation` 使用，**半遗留** |
| `VillagerAgentSavedData.java` | 79 | `WorldSavedData`：把所有 agent 落盘 | `get(ServerWorld)`, `save/load` |
| `BrainLog.java` | 102 | **行为日志门面**（goal 增/删/切/挂起、skill 派发、升级） | `goalAdded/goalEnded/goalSwitch/goalState/skillDispatch/decide/actionChange/levelUp` |

> **FSM 状态落点约定**：skill 是注册一次的**单例**，不能存每村民状态 → 多步 skill 的状态必须挂在 `agent` 或 `currentAction` 上（且可 NBT 序列化），如 `FarmReclaimState`。

---

### M10 · 调度与规划 · `ai`（根包）

**职责**：决定"现在该跑哪个 goal"，以及 goal 从哪来、失败了怎么办。

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerAgentManager.java` | **1605** | **中枢**。agent 注册表 + 全部 tick 入口 + `processGoals` + 战斗状态机 + 建造推进 + 社交/想法/问候调度 + 目标生成/剪枝 | `tickAgents/tickCombat/tickBuilding/tickSocial/tickThoughts/tickGreeting`, `updateAgent`, `processGoals`, `driveMoveGoal`, `requestPlace/requestBreak/requestBuild`, `resolveVillagerPartner`, `matchesBuildingType`, `pickNextGoal` 的调用点；常量 `GOAL_ARRIVE_SQ=16`, `GOAL_STUCK_TIMEOUT=200`, `CRAFT_WORK_TICKS=200`, `MAX_GOALS=10`, `BLOCKED_MAX_STREAK=5` |
| `GoalScheduler.java` | 187 | **Goal 仲裁器**：过期清理、抢占判定、两段软抽样选择 | `expireGoals()`, `shouldPreempt(current, candidate)`, `pickNextGoal(agent, goals, tick)`, `effWeight()` |
| `RoutineScheduler.java` | 99 | 按当前时段注入/维持例行 goal（sleep / farm / gather / socialize），带冷却与昼夜权重 | `ensureRepeatableGoals(agent)`, `seed(type, agent)`, `apply(...)` |
| `DeterministicPlanGenerator.java` | 209 | **无 LLM 的规划兜底**：职业行为池 ∪ 能力池 → 加权采样生成 routine goals | `generateRoutineGoals(agent, day, world)`, `capabilityMultiplier()`, `chooseCraftableWithRecipe()` |
| `FailureRecoveryPlanner.java` | 176 | **失败驱动重规划**：goal 失败 → 补货路径 craft → 递归材料（深度 ≤2）→ 广播交易需求 | `handle(agent, failedGoal, villager, world)`, `recoverMissingItem(...)`, `postTradeWant(...)` |
| `VillagerSchedulePlanner.java` | 278 | LLM 日程：清晨生成 4 段计划、傍晚反思 | `tick(...)`, `generateDailyPlan`, `parseDailyPlan`, `createFallbackPlan`, `applyTaskToAgent`, `generateReflection` |
| `GoalParser.java` | 135 | 解析 LLM 输出里的 `GOAL:` 行 → `AgentGoal` | `parse(text, agent)`, `stripTodoLines(text)` |
| `AgendaParser.java` | 176 | 解析 LLM 输出里的议程行（`REMEMBER`/`AGENDA`…）→ `LongTermAgenda` | `parse(...)`, `stripRememberLines(...)`, `resolveById(...)` |
| `BehaviorCatalog`（在 behavior 包） | 89 | 行为 → 能力门控 + 基础权重 + 分类的目录表 | `get(id)`, `all()`, `isUnlocked(id, pool)`；见 M11 |

**仲裁规则要点**（`processGoals`，Manager 第 **967–1220** 行；`GOAL_STUCK_TIMEOUT`=953、`BLOCKED_MAX_STREAK`=957）：
1. 战斗/建造（`ATTACK/PLACE/BREAK/FLEE/BUILD`）占用中 → goal 完全不插手。
2. `REPEATABLE` 且 `priority < 9` 且 `phaseWeight(dayTime) <= 0` → **让出** running 槽（不是挂起，下个相位自动回来）。
3. 抢占：`GoalScheduler.shouldPreempt` 通过 → 旧 goal `setSuspended(true)` **保留 FSM 状态**。
4. 睡眠守卫：夜间 `villager.isSleeping()` 时只接受 sleep goal（**防止躺姿移动 bug 的修复点**）。
5. 派发：`BehaviorExecutor.findHandler` → `skill.execute` → `SkillResult`；连续 `BLOCKED` 5 次丢弃 goal。
6. 非 `farm` 类型 goal 运行时会清空 `scheduledActivity`（避免日程系统与 goal 抢移动权）。

---

### M11 · 行为 Skill 层 · `ai.behavior`

**职责**：把"一个 goal"翻译成"一串原子动作"。**skill = 状态机**，每 tick 读 `currentAction` 相位 → 派生下一个 `VillagerAction` → 返回终态 `SkillResult`。

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerSkill.java` | 58 | **接口**：`id()`, `canHandle(...)`, `execute(...)`，默认能力门控 | `requiredCapability()`, `minLevel()`, `passesCapabilityGate(agent)` |
| `BehaviorExecutor.java` | 88 | **注册表 + 派发器**。静态块注册 12 个 skill；找不到 handler 时给出诊断串 | `register(skill)`, `findHandler(...)`, `explainNoHandler(...)` |
| `SkillResult.java` | 24 | 枚举 `SUCCESS / RUNNING / BLOCKED / FAIL` | — |
| `Failure.java` | 44 | 失败载荷：原因 + 缺的物品 + 缺口能力 | `recoveryKey()` |
| `FailureReason.java` | 67 | 失败原因枚举 | `isItemRecoverable()`, `isCapabilityGap()`, `with(itemId[,qty])`, `with(cap, lvl)` |
| `BehaviorCatalog.java` | 89 | 行为目录：`sleep/rest/explore/move/goto/wander/farm/farm_maintain/farm_reclaim/gather/craft/socialize/trade/combat` → 所需能力 + 等级 + 权重 + 分类 | `get/all/isUnlocked`；`enum Category{SELF_CARE,LEISURE,WORK,SOCIAL,COMBAT}` |
| `SleepSkill.java` | 275 | 睡觉：找床 → 走向床 → 占用 → 睡到天亮；另供外部调用的 rest tick | `isNightTime()`, `isSleepGoal()`, `tickRest(...)`, `canOccupyBed(...)` |
| `FarmSkill.java` | 289 | 农耕：收获成熟作物+补种 / 维护退化耕地 / 泛洪开垦 | `workFocus(...)`, `planReclaim(world, origin)`, `firstReachable(...)`；**只处理 `goalType == "farm"`** |
| `FarmReclaimState.java` | 53 | 开垦 FSM 的状态 blob（挂在 goal/agent 上，可 NBT） | `getTargetCount/setTargetCount`, `getPending/addPending`, `getTilledCount`, `writeNBT/readNBT` |
| `CraftSkill.java` | 180 | 合成：选配方 → 走向工作台 → 停留 `CRAFT_WORK_TICKS` → 产出/附魔 | `canHandle/execute` |
| `GatherSkill.java` | 39 | 采集地面掉落物 | `canHandle/execute` |
| `TradeSkill.java` | 87 | 走向交易对象并触发 `VillagerTradeSystem` | `canHandle/execute` |
| `SocializeSkill.java` | 132 | 找附近村民 → 走近 → 对话/八卦/拜师 | `findPartner(...)`, `chat(...)` |
| `ConverseSkill.java` | 71 | 与**玩家**对话（走向玩家 + 说话） | `canHandle/execute` |
| `RestockSkill.java` | 154 | 在工作站补货（职业必需品） | `canHandle/execute`, **`outcomeFor(profession)`**（供 MISSING_ITEM 的 outcomeItems 匹配） |
| `AcquireItemSkill.java` | 109 | 通用"搞到某个物品"（走过去捡 / 触发获取链） | `canHandle/execute` |
| `MoveSkill.java` | 30 | 移动到坐标 | `canHandle/execute` |
| `GotoSkill.java` | 51 | 前往某类建筑/目标 | `canHandle/execute` |
| `WanderSkill.java` | 142 | 随机漫游（在已知 chunk 里挑远点） | `randomFarPoint(...)`, `surfaceY(...)` |

**已注册的 12 个 skill**（`BehaviorExecutor` 静态块顺序即匹配优先级）：
`Sleep → Farm → Socialize → Gather → Move → Wander → Goto → Craft → Trade → Converse → Restock → AcquireItem`

> **⚠ 悬空条目**：`BehaviorCatalog` 声明了 `farm_maintain`（FARMING≥1）和 `farm_reclaim`（FARMING≥2），但**没有任何 skill 注册这两个 id**——`FarmSkill.id()` 返回 `"farm"` 且只接受 `goalType=="farm"`。同时 `AgentGoal.farmMode`（`"maintain"|"reclaim"`）由 `DeterministicPlanGenerator` 设为 `null`（auto），但 **FarmSkill 从不读取它**。即：农耕的三态细分目前只存在于目录与字段层面，未接通执行。

---

### M12 · 动作原语 · `ai`（根包）

**职责**：**无状态静态方法库**，被 skill 组合调用。这里是真正碰到世界的地方（破坏/放置/攻击/耕种）。

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `FarmingAction.java` | **637** | 农耕原语全集 | 查找：`findNearestMatureCrop`, `findNearestEmptyFarmland`, `findNearestGroundItem`, `findMatureCropsSorted`, `findEmptyFarmlandSorted`, `findRevertedFarmlandSorted`, `findNearestCultivatableDirt`；动作：`harvestBlockAt`, `plantSeedAt`, `plantSpecificCropAt`, `plantSmartAt`, `tillBlockAt`, `approachTarget`；判定：`isMatureCrop`, `isEmptyFarmland`, `isTillableDirt`, `isCultivatableDirt`, `isRevertedFarmland`, `hasWaterNearby`, `countFarmlandNeighbors`, `hasHoe`, `hasSeeds` |
| `CombatAction.java` | 306 | 战斗原语 + 战力评估 | `findNearestThreat`, `isHostile`, `attackEntity`, `findBestWeaponDamage`, `villagerDps`, `threatDps`, `combatPower`, `wouldWinSolo`, `mayAttack`, `flee` |
| `CraftingAction.java` | 77 | 合成执行 + 可用配方描述（喂 LLM） | `executeCraft(agent, recipeName, tablePos)`, `getAvailableRecipes(agent)`, `getAvailableRecipesDescription(agent)` |
| `BlockInteractionAction.java` | 225 | 放置/破坏方块的**多 tick 推进**（含站位选择与可达性） | `isStandable`, `findStandable`, `withinReach`, `tickPlace(...)`, `tickBreak(...)`；`enum StepResult` |
| `VillagerEquipmentHelper.java` | 222 | 装备管理：自动穿最佳盔甲、按方块选最佳工具、计算破坏耗时 | `refreshEquipment`, `tryEquipArmor`, `equipBestToolForBlock`, `computeBreakTicks` |
| `ItemAttractionSystem.java` | 214 | 物品吸附与拾取（并给对应能力加经验） | `processItemAttraction(...)`, `attractItemToVillager`, `pickupItem`, `capabilityForItem(stack)`, `getAttractionRange()` |

---

### M13 · 感知三层 · `ai.memory` / `ai.vision` / `ai.world`

| 层 | 包 | 文件 | LOC | 职责 |
|---|---|---|---|---|
| **Far** | `ai.memory` | `ChunkMemory.java` | 243 | 单 chunk 记忆：tag 位图、feature 位图（含矿脉锚点）、方块类别计数、实体计数、已知建筑 id、显著性、NBT |
| | | `ChunkTag.java` | 39 | chunk 级标签枚举（`forest`/`farmland`/`water`/`village`…），位运算存储 |
| | | `ChunkFeature.java` | 60 | 具体特征枚举（矿石/作物等），带 `bit()`、`isOre()` |
| | | `BlockCategory.java` | 25 | 方块粗分类枚举 |
| | | `EntityCategory.java` | 16 | 实体粗分类枚举 |
| | | `BlockObservation.java` | 55 | 单方块观察（局部坐标 → 全局） |
| | | `EntityObservation.java` | 42 | 单实体观察 |
| | `ai.vision` | `ChunkContentSampler.java` | 198 | **Far 层采样器**：扫一个 chunk → `ChunkMemory`；分类、提特征、采样实体、算显著性 |
| **Near** | `ai.vision` | `FrustumCuller.java` | 82 | 视锥判定：`eyePosition`, `forwardVector`, `isInView`, `isBlockInView`, `isEntityInView` |
| | | `DetailedViewRecorder.java` | 115 | 视锥内**只记录值得注意**的方块与实体，产出 `ViewSnapshot` |
| **Mid** | `ai.world` | `WorldStructureIndex.java` | 478 | **建筑索引**：事件驱动、队列式、每 tick 处理 `FLOODS_PER_TICK` 个床；一床只扫一次；按区域失效；NBT 持久化 |
| | | `BuildingRecord.java` | 151 | 单条建筑记录：包围盒、床列表、体积、粗分类；**含调试用的 `DebugSeed` / `DebugFieldCell`** |
| | | `StructureIndexSavedData.java` | 37 | `WorldSavedData` 包装，跨重启持久化 |
| | `ai.vision` | `BuildingLocator.java` | **757** | **床 → 房间**分割算法：多源 BFS 测地距离场 + 同步双类别测地线分水岭 + 大气占比判房间。常量 `REACH_CAP`, `MAX_SCAN_BLOCKS`, `SUBDIV`, `BIG_AIR_FRACTION=0.85`, `MIN_ROOM`, `AIR_RUN`, `ATMOSPHERE_HALO` |
| **汇总** | `ai`（根包） | `VillagerVisionSystem.java` | 314 | 三层融合 → 一段**注入每次 LLM 调用**的环境摘要文本（时间/天气/群系/坐标/chunk 记忆/可见建筑/近场细节） |

**`WorldStructureIndex` 关键 API**：`instance(world)`, `getAt(BlockPos)`, `queryNear(center, rChunks)`, `add/remove`, `markDirty(AABB)`, `onBlockChanged(pos)`, `onBedRemoved(bed)`, `indexChunk(chunk)`, `offerBed(bed)`, `processPending(world, budget)`, `writeNBT/loadNBT`

**`BuildingLocator` 关键 API**：`locateBed(world, bed)` → `BuildingRecord`；`bedsInChunk(chunk)`；`scanHorizontalHalf()`

---

### M14 · 世界交互系统 · `ai`（根包）

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `VillagerEconomySystem.java` | 353 | **无 LLM 的经济**：扫描村民必需品缺口 → 广播 WANT/HAVE → 撮合 → 走过去交割 → 绿宝石结算 | `tickEconomy(world)`, `runScan`, `runMatching`, `runMatchedExchanges`, `transfer`, `payEmeralds`, `buyValue`, `sellValue`, `ensurePersonalValueTable(agent)`, `setPrice`, `isSelfObtainable`；嵌套 `Price{buy,sell}`、`Essential{item,minQty}` |
| `VillagerTradeSystem.java` | 321 | **村民↔村民**交易（LLM 生成报价 → 评估 → 成交/还价） | `initiate(...)`, `proposeTrade`, `evaluateTrade`, `executeExchange`, `executeCounter`, `parseOffer`, `grantSocial` |
| `VillagerSocialSystem.java` | 279 | 村民↔村民社交：寒暄、八卦传播、**拜师给经验** | `tickSocial(world)`, `startConversation`, `canSocialize`, `propagateGossip`, `grantMentorshipXp` |
| `VillagerNeedsSystem.java` | 214 | 需求：饥饿、疲劳 → 五档情绪；自动进食 | `tick(...)`, `tryEat(...)`, `deriveMood(agent)`, `buildNeedsDescription(agent)` |
| `VillagerActivitySystem.java` | 320 | 日程驱动的活动（exploring / resting）+ 睡眠回血 + 天亮唤醒 + 合成结算 | `tick(...)`, `handleExploring`, `handleResting`, `applySleepHealing`, `wakeAtDawn`, `finalizeCrafting`, `enchantFirstCrafted` |
| `VillageSignalBoard.java` | 294 | **村庄公告板**：WANT/HAVE/RALLY 消息广播与撮合；`RallyCall` 集结打怪（含 `shouldCharge` 群体战力判断） | `get()`, `post(...)`, `hear(pos, type, now)`, `ack`, `matchTrade`, `resolveTrade`, `removeByAuthor`, `clearExpiredMessages`, `respond(...)`, `nearestRally(...)`, `pruneExpired(...)` |
| `StructureBuilder.java` | 188 | LLM 设计结构 → 校验 → 生成 `BuildJob`；失败时让 LLM 修订 | `requestStructure(...)`, `reviseWithLLM(...)`, `parseStructure(json)` |
| `BuildOrderPlanner.java` | 252 | 结构 → 可执行的**放置顺序**（可达性/支撑约束）+ 站位求解 | `validateStructure(...)`；内部类 `BlockSpec`, `Structure`, `Step`, `ValidationResult` |
| `BuildJob.java` | 104 | 建造作业游标与进度，`reconcileWithWorld` 做世界对齐 | `currentStep()`, `advance`, `isComplete()`, `writeNBT/readNBT` |

---

### M15 · LLM 与护栏 · `ai`（根包）+ `ai.harness`

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `LLMService.java` | 459 | **唯一 HTTP 出口**：四种后端、失败前缀协议、debug 日志 | `queryLLM(system, user)`；`fail(reason)`, `isFailure(resp)`, `failureReason(resp)`；私有 `queryOpenAI/Anthropic/Ollama/Gemini` |
| `ai/harness/LLMGuard.java` | 144 | **护栏**：每村民超时 + 连续失败熔断 → 降级到纯规则模式 | `query(villagerId, tick, system, user)`, `isCircuitOpen(...)`, `recordFailure`, `resetFailures` |
| `ai/harness/DecisionRequest.java` | 86 | 决策请求装配（感知文本 + 黑板 + 可用动作 + goals → prompt） | `from(agent, envSummary, isNight, threatLevel)`, `toPrompt()` |
| `ai/harness/DecisionSchema.java` | 87 | LLM 输出的结构化 schema 与解析 | `parse(text)`, `normalizedAction()`, `decisionAction()`, `isKnown()`；`enum DecisionAction` |
| `ai/harness/HarnessDecisionPlanner.java` | 105 | 每日目标决策（受 `HARNESS_DRIVE_DAILY_GOAL` 开关控制） | `decideDailyGoal(...)`, `enqueueGoal(...)` |
| `ai/harness/ActionValidator.java` | 155 | 决策落世界前的合法性校验（尤其 move 的坐标解析与可达性） | `validate(schema, agent, villager, world)`, `validateMove(...)`, `parseCoords(...)` |
| `ai/harness/DecisionJournal.java` | 92 | JSONL 决策流水（prompt / 原始输出 / 解析结果 / 结局），用于回放与评测 | `record(...)` 两个重载，`outcome(rawLLM)`，`enum Kind` |
| `LLMSetupReminder.java` | 76 | 未配置时的游戏内提示 | `isConfigured()`, `getMissingItems()`, `buildReminder()` |
| `DefaultChatLines.java` | 58 | 无 LLM 时的兜底台词（读 `default_chat_lines.json`） | `random()` |

**LLM 调用点分布**（改 prompt 前先定位调用方）：
- 思考/内心独白 → `VillagerAgentManager#requestThought`
- 身份生成（性格/名字）→ `VillagerAgentData#generateWithLLM`
- 日程规划 / 反思 → `VillagerSchedulePlanner`
- 村民对话 → `VillagerSocialSystem#startConversation`、`VillagerTradeSystem#proposeTrade`
- 玩家对话 → `network/ChatMessagePacket#handle`
- 结构设计 → `StructureBuilder#requestStructure / reviseWithLLM`
- 每日目标 → `HarnessDecisionPlanner`

---

### M16 · 熟练度与能力池 · `ai.proficiency`

> **⚠ 双轨并存**，改动前务必分清你要动哪一套。

**轨道 A — Forge Capability 熟练度（旧，对齐原版等级）**

| 文件 | LOC | 职责 |
|---|---|---|
| `ProficiencyCapability.java` | 91 | Capability 定义与注册，读写 NBT，同步到原版等级 |
| `VillagerProficiency.java` | 111 | 经验/等级模型（XP→等级→下一级） |
| `ProficiencyLevelTable.java` | 137 | 等级阈值表（可从 `proficiency.json` 覆盖） |
| `VillagerProficiencyProvider.java` | 37 | `ICapabilitySerializable` Provider |
| `ProficiencyEvents.java` | 31 | `AttachCapabilitiesEvent` 挂到 `VillagerEntity` |

**轨道 B — 能力池（新，9 维能力，按村民持久化在 `VillagerAgentData`）**

| 文件 | LOC | 职责 | 关键 API |
|---|---|---|---|
| `CapabilityId.java` | 53 | 9 个能力枚举（FARMING / GATHERING / CRAFTING / SOCIAL / COMBAT …） | `mainForProfession(prof)`, `fromId(id)` |
| `CapabilityPool.java` | 148 | 每村民的等级/XP/最后练习日；含**锈蚀衰减** | `levelOf/xpOf/lastPracticed`, `setLevel`, `addLevels`, `addXp(id, amount, today)`, `decayTo(today)`, `vanillaLevelFor(id)`；常量 `RUST_DAYS`, `RUST_GRACE_DAYS` |
| `CapabilityLevelTable.java` | 119 | 能力等级的 XP 阈值表 | `defaults()`, `instance()`, `xpForLevel`, `levelForXp` |

**能力值的读写路径**：
- 加经验：`ItemAttractionSystem#capabilityForItem`（拾取）、`VillagerSocialSystem#grantMentorshipXp`（拜师）、`FarmingAction`/`CraftSkill` 等完成时。
- 门控：`VillagerSkill#passesCapabilityGate(agent)`（默认实现读 `BehaviorCatalog` 的 `requiredCapability/minLevel`）。
- 升级日志：`BrainLog#levelUp(...)`。

---

## 5. 关键调用链（速查）

```
【玩家右键村民】
onPlayerInteractEntity → VillagerAgentCommand? no →
  ModNetworking.send(OpenChatPacket) → client/VillagerChatHandler.openChatScreen
  → VillagerChatScreen → 输入 → ChatMessagePacket(C→S)
  → LLMService.queryLLM(prompt 由 VillagerVisionSystem 环境摘要 + 记忆 + 关系组装)
  → VillagerResponsePacket(S→C) → 屏幕显示

【村民自己想做事】
RoutineScheduler / DeterministicPlanGenerator / LLM thought / FailureRecoveryPlanner
  → 生成 AgentGoal → agent.getGoals()
  → GoalScheduler.pickNextGoal（两段软抽样，EMERGENCY≥9 硬优先）
  → BehaviorExecutor.findHandler（按注册顺序问 canHandle）
  → Skill.execute（每 tick 读 currentAction 相位 → 派生下一个 VillagerAction）
       └ 调用 M12 原语：FarmingAction / CombatAction / CraftingAction / BlockInteractionAction
       └ 需要装备时：VillagerEquipmentHelper
  → SkillResult.SUCCESS → goal.setCompleted(true)
  → SkillResult.BLOCKED → runningStreak++（≥5 丢弃）
  → SkillResult.FAIL → Failure 载荷 → FailureRecoveryPlanner
       ├ 缺物品 → craft goal（NativeRecipeResolver 解析配方）
       ├ 材料也缺 → 递归 ≤2 层
       └ 都搞不到 → VillageSignalBoard.post(WANT) → VillagerEconomySystem 撮合 → trade goal

【床 → 房间 → 建筑】
ChunkEvent.Load → WorldStructureIndex.indexChunk(chunk)
  → BuildingLocator.bedsInChunk(chunk) → 每张床 offerBed(bed) 入队
  → processPending（每 tick 处理 FLOODS_PER_TICK 个）
  → BuildingLocator.locateBed(world, bed)
       ├ 收集床块 + 头顶覆盖判定
       ├ 多源 BFS 测地距离场（限 REACH_CAP / MAX_SCAN_BLOCKS）
       ├ 同步双类别测地线分水岭（interior vs exterior）
       └ 大气占比 ≥ BIG_AIR_FRACTION(0.85) → 判为房间，否则判室外
  → BuildingRecord → 加入索引 → StructureIndexSavedData 持久化（markDirty 时）
```

---

## 6. 任务 → 入口速查表

| 我想…… | 先看 / 改这里 |
|---|---|
| 加一个新行为（skill） | 实现 `ai/behavior/VillagerSkill`，在 `BehaviorExecutor` 静态块 `register`；若需能力门控，在 `BehaviorCatalog` 加条目 |
| 改 goal 的取舍/优先级规则 | `GoalScheduler`（仲裁）+ `VillagerAgentManager#processGoals`（抢占/让位/睡眠守卫）+ `AgentGoal#phaseWeight`（昼夜权重） |
| 改"村民什么时候想干什么" | `RoutineScheduler`（例行注入）+ `DeterministicPlanGenerator`（无 LLM 兜底）+ `VillagerSchedulePlanner`（LLM 日程） |
| 加/改一个农耕动作 | `FarmingAction`（原语）→ `FarmSkill`（编排） |
| 村民不睡觉 / 躺姿移动 | `SleepSkill`（+ `VillagerActivitySystem#applySleepHealing/wakeAtDawn`）+ `processGoals` 里的**睡眠守卫**段 |
| 村民卡住不动 | `VillagerAction#getStuckTicks` + `GOAL_STUCK_TIMEOUT` + `FarmingAction#approachTarget` + `BlockInteractionAction#findStandable` |
| 改合成/配方/解锁 | `SkillCraftCatalog`（职业×等级产物表）、`craft_unlocks.json`、`custom_recipes.json`、`CustomRecipeRegistry`、`NativeRecipeResolver`（原版配方解析）、`UnlockedRecipes` |
| 缺东西不自己去搞（MISSING_ITEM） | `AgentGoal#outcomeItems` → `RestockSkill#outcomeFor(profession)` → `FailureRecoveryPlanner` |
| 改交易/经济数值 | `item_values.json` / `profession_essentials.json` / `item_sources.json`；逻辑在 `VillagerEconomySystem`（村民间）+ `TradeRequestPacket`（玩家↔村民） |
| 改 LLM prompt | 定位 §M15 的调用点；环境文本统一由 `VillagerVisionSystem#buildEnvironmentSummary` 产出；请求装配在 `DecisionRequest` |
| LLM 慢/挂了导致卡 | `ai/harness/LLMGuard`（超时+熔断）、`HarnessDecisionPlanner`、`DecisionJournal` |
| 改感知范围/精度 | Far：`ChunkContentSampler` + `ChunkMemory`；Near：`FrustumCuller`/`DetailedViewRecorder`；Mid：`BuildingLocator` 常量 + `WorldStructureIndex.FLOODS_PER_TICK` |
| 房间分割不准 | `BuildingLocator#locateBed`，重点调 `BIG_AIR_FRACTION` / `MIN_ROOM` / `REACH_CAP` / `ATMOSPHERE_HALO`；用 `DEBUG_SHOW_SEEDS`/`DEBUG_SHOW_FIELD` 看调试可视化 |
| 加调试可视化 | `DebugDataPacket`（传输）→ `debug/DebugSync`（采集）→ `client/DebugOverlay`（渲染）→ `ModConfig.DEBUG_*` 开关 |
| 加配置项 | `config/ModConfig`（开关）+ `ai/config/VillagerAgentConfigDir`（JSON 数据） |
| 加网络包 | `network/ModNetworking#register` + 新 Packet 类（encode/decode/handle 三件套） |
| 存档丢了/没持久化 | 三处：`VillagerAgentSavedData`（agent）、`StructureIndexSavedData`（建筑）、`VillagerProficiency`（capability） |
| 看运行时到底发生了什么 | 日志：`BrainLog`（行为主线）+ `LLMService`（LLM 出入参，`LLM_DEBUG` 开关）；游戏内：`DebugOverlay`；回放：`DecisionJournal` 的 JSONL |

---

## 7. 资源与配置文件

| 路径 | 用途 | 是否可运行时改 |
|---|---|---|
| `src/main/resources/META-INF/mods.toml` | Mod 元数据 | 否 |
| `src/main/resources/pack.mcmeta` | 资源包描述 | 否 |
| `src/main/resources/villageragent.mixins.json` | Mixin 配置 | 否 |
| `src/main/resources/villageragent/craft_unlocks.json` | **按能力×等级的合成解锁表**（gate 是解锁集合，不是通用 CRAFTING 等级） | ✅ 会抽取到 `config/villageragent/` |
| `src/main/resources/villageragent/custom_recipes.json` | 无原版配方的自定义合成（如锁链甲） | ✅ |
| `src/main/resources/villageragent/proficiency.json` | 熟练度/能力等级阈值（`max_level`、`thresholds`、`capability_thresholds`） | ✅ |
| `src/main/resources/villageragent/default_chat_lines.json` | 无 LLM 时的兜底台词 | ✅ |
| `src/main/resources/data/villageragent/economy/item_values.json` | **按职业的买/卖价表**（绿宝石锚点 10.0） | 内置资源 |
| `src/main/resources/data/villageragent/economy/profession_essentials.json` | 各职业"必需品清单"（item + minQty），驱动经济扫描 | 内置资源 |
| `src/main/resources/data/villageragent/economy/item_sources.json` | 物品 → 获取途径（FARM/GATHER/CRAFT…），`isSelfObtainable` 用 | 内置资源 |
| `config/villageragent-common.toml` | 所有行为开关（运行时生成） | ✅ |

---

## 8. 已知缺口 / 孤儿代码 / 双轨系统

**孤儿或半遗留（引用数为 0 或仅自引用）**

| 文件 | 状态 | 说明 |
|---|---|---|
| `ai/ActionExecutor.java` (135) | **完全孤儿** | 早期"LLM 直接产出 VillagerAction"的派发器，已被 goal+skill 架构取代；无任何外部引用 |
| `ai/ActionResponse.java` (29) | **完全孤儿** | 同上，配套的响应封装 |
| `ai/ActionRequest.java` (112) | 仅 1 处引用 | 同上体系的 prompt 装配器 |
| `ai/VillagerObservation.java` (35) | 半遗留 | 只被 `VillagerAgentData#recordObservation` 使用，未进入感知主链路 |
| `ai/RecipeRegistry.java` (126) | 疑似被 `SkillCraftCatalog` 取代 | 老的硬编码配方表 |

**已知缺口（活跃开发中）**

1. **农耕三态未接通**：`BehaviorCatalog` 有 `farm_maintain` / `farm_reclaim`，但无对应 skill 注册；`AgentGoal.farmMode` 被写入却无人读取（见 M11 警告框）。
2. **职业变更后的工具补货未确认**：`VillagerEventHandler#updateVillagerProfession` 更新职业并调用 `healProfessionCapability()` / `refreshUnlockedRecipes()`，但**新职业村民是否拿到锄头等工具**需要实测确认。
3. **消费断层**：村民"回家睡觉"的导航**仍走原版 `MemoryModuleType.HOME` POI**，未接回 `WorldStructureIndex`（Mid 层已算出房间，但没被睡眠导航消费）。
4. **Agenda → Goal 只桥接了傍晚反思**：`LongTermAgenda` 体系存在，但债务/交易约定**不会主动变成 goal**，偏被动。
5. **双轨熟练度**：Forge Capability 熟练度（轨道 A）与能力池（轨道 B）并存，语义有重叠，改动时容易只改一边。

**待读的背景文档**（`docs/`，按相关性排序）
- `架构反思与精简方案.md` —— 统一目标漏斗的设计动机（**改调度前必读**）
- `失败驱动重规划.md` —— FailureRecoveryPlanner 的设计
- `capability_pool_plan.md` —— 能力池设计（M16 轨道 B）
- `bed_room_search_implementation.md` / `building_detection_implementation.md` —— BuildingLocator 算法细节
- `world_interaction_design.md` / `village_orchestration_design.md` / `profession_crafting_design.md`
- `项目现状与待办清单.md` —— 当前 TODO
- `SPEC.md` / `SPEC_ECONOMY.md` —— 顶层规格
- `world_event_bus_gossip_design.md` —— EventBus / Gossip 信道设计（**设计态，代码未实现**，勿当已有 API）
- `presentation/` —— 面试展示材料（已 gitignore，不属于 mod 本体）

---

## 9. 维护说明

本文件是**人工 + 扫描生成**的混合索引：模块职责与调用链为人工归纳；文件清单、LOC、方法签名由全量源码扫描得到（2026-09-03 快照）。

**需要更新本文档的时机**：
- 新增/删除 Java 文件，或某个文件行数变化超过 ±30%
- 新增/移除 skill（影响 M11 的注册表清单）
- 改动 `BehaviorExecutor` 的注册顺序或 `processGoals` 的仲裁顺序
- 新增配置项分组、新增网络包、新增 JSON 数据表

**⚠ 改动时同时更新**：项目级 skill `villager-agent-codebase-nav/SKILL.md`（模块地图与任务→落点表的压缩版）。两者是同一份知识的两个粒度，**不同步会让导航 skill 把 AI 指到错误的文件**。

**已核实数字（2026-09-23）**：Java 文件 122 个；`ai` 包 100 个（根包 52 / behavior 19 / memory 7 / vision 4 / world 3 / proficiency 8 / harness 6 / config 1）；`BehaviorExecutor` 注册 **12** 个 skill（曾有误记为 13 的说法，以静态块为准）。

**不要**把本文档当作行为真源——仲裁/阈值/常量的真源永远是代码；本文档只负责"去哪找"。
