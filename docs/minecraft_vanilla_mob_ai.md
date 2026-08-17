# Minecraft 原版生物 AI 系统研究

> 研究目标：厘清原版 Minecraft（Java 版）是如何构建生物 AI 的——是状态机还是别的系统构造。
> 结论先行：**原版并存两套系统**，二者都是“有限状态/优先级选择”思想的变体，但抽象层级不同：
> 1. **意向系统（Goal / GoalSelector）**——经典系统，绝大多数生物（僵尸、骷髅、苦力怕…）使用，本质是一个**按优先级 + 旗标互斥的响应式目标选择器**。
> 2. **记忆行为系统（Brain / Behavior）**——1.14 村民引入的现代系统，本质是**数据驱动（记忆黑板 + 传感器 + 行为 + 活动 + 时刻表）**的分层行为架构，比状态机更“声明式”。
>
> 下文按“概念 → 架构 → 生命周期 → 实例 → 对比 → 对 VillagerAgent 的启示”展开。

---

## 1. 总览：两套系统并存

| 维度 | 意向系统（Goal） | 记忆行为系统（Brain） |
|------|------------------|----------------------|
| 引入版本 | 远古即有（pre-1.13） | 1.14（村庄与掠夺），村民首发；之后几乎所有新生物（除蜜蜂、炽足兽）都改用它 |
| 典型使用生物 | 僵尸、骷髅、苦力怕、蜘蛛、狼、羊… | 村民、猪灵、猪灵蛮兵、疣猪兽、 axolotl、嗅探兽、监守者、悦灵… |
| 核心抽象 | `Goal`（一个小目标） + `GoalSelector`（按优先级调度） | `Brain`（总控） + `Memory`/`Sensor`/`Behavior`/`Activity`/`Schedule` |
| 调度思想 | 优先级队列 + 旗标互斥（“找一个当前最该做的动作”） | 记忆黑板驱动 + 活动切换（“根据记忆与当前活动选出可执行的任务集”） |
| 是否状态机 | **是**（离散的优先级目标切换，可视为扁平状态机） | **超越经典状态机**（数据驱动行为系统，活动=粗粒度状态，行为=原子动作） |
| 与控制器关系 | Goal 直接操控 Move/Look/Jump 控制器 | Behavior 通过 Navigation/Controls 间接行动，记忆做中介 |

关键点：**两套系统都控制同一个底层“控制器”（MoveControl / LookControl / JumpControl / Navigation）**，区别只是“上层决策怎么选”。

---

## 2. 意向系统（Goal / GoalSelector）

### 2.1 架构

每个 `Mob`（原版基类）持有 **两个** `GoalSelector`：

- `goalSelector`：动作意向（做什么动作）
- `targetSelector`：索敌意向（锁定谁为攻击目标）

注册方式：生物在 `registerGoals()` 中调用 `addGoal(int priority, Goal goal)`。每个 Goal 被包成 `PrioritizedGoal`（goal + priority）。**优先级数值越小越优先**（priority 1 最高）。

```
Mob
 ├── goalSelector   : GoalSelector  ← 动作意向集合
 │     └── PrioritizedGoal{ priority, goal }
 ├── targetSelector : GoalSelector  ← 索敌意向集合
 ├── moveControl    : MoveControl
 ├── lookControl    : LookControl
 └── jumpControl    : JumpControl
```

### 2.2 Goal 生命周期（相当于一个微型状态机）

| 方法 | 含义 | 触发 |
|------|------|------|
| `canUse()` | 能否启动 | 每轮选择时检查 |
| `start()` | 启动瞬间执行一次 | 选中时 |
| `tick()` | 每刻执行 | 运行中每刻（受奇偶刻限制） |
| `canContinueToUse()` | 是否继续 | 运行中每刻检查 |
| `stop()` | 终止清理 | 不再满足或让位给更高优先级时 |

### 2.3 旗标（Flag）互斥 —— 这是“不是简单状态机”的关键

Goal 构造时通过 `setFlags(EnumSet<Flag>)` 声明它要占用哪些控制器旗标。旗标共三种：

- `MOVE`：占用移动控制器（寻路行进）
- `LOOK`：占用视角控制器
- `JUMP`：占用跳跃控制器

**同一旗标同时最多只允许一个 Goal 运行**。因此：
- “近战攻击（MOVE+LOOK）”与“躲避实体（MOVE）”冲突 → 只能二选一；
- “看向玩家（LOOK）”与“随机游走（MOVE）”互不冲突 → 可同时运行。

这让系统呈现“多动作并行但资源互斥”的形态，比单一激活状态机更灵活。

### 2.4 动作意向 vs 索敌意向

| 类别 | 作用 | 互斥机制 | 同时运行数 |
|------|------|----------|-----------|
| 索敌意向（Target Goal） | 决定攻击/跟踪对象 | 无旗标，只用一个索敌控制器 | 仅 1 个 |
| 动作意向（Goal） | 具体动作（游走/攻击/逃跑） | 旗标互斥 | 旗标不冲突时可多个共存 |

每轮计算顺序：**先处理索敌意向，再处理动作意向**。

### 2.5 优先级选择流程（Java 版）

每轮（计算刻）对动作意向：
1. 不满足 `canUse()` → 忽略；
2. 已在运行 → 忽略；
3. 占用被禁用的控制器 → 忽略；
4. 对每个它要占用的旗标：若无人占用则成功；若已占用但自身优先级更高且占用方可中断 → 成功，否则跳过；
5. 停掉占用该旗标的旧 Goal，启动自身。

> **Java 版运行频率（重要性能细节）**：意向系统**每 2 个游戏刻才完整计算一次**（尝试启动/停止 Goal）。判定方式：将“当前维度游戏刻计数 + 实体 ID”相加——
> - **结果为偶数**：正常计算意向系统，并执行已启动意向；
> - **结果为奇数**：不计算（不挑选/启动新意向），仅执行带“持续计算”属性的已启动意向；
>
> 因此许多轻量意向（如看向玩家）并不在每一刻执行。这一机制是原版为了控制 AI 开销刻意做的节流（与 VillagerAgent 关心的“事件驱动 vs 周期性扫描”话题直接相关）。

### 2.6 实例：僵尸（Zombie）优先级表

| 优先级 | 索敌意向 | 动作意向 |
|--------|----------|----------|
| 1 | 复仇（被谁打就锁谁） | — |
| 2 | 锁定玩家 | 攻击当前目标 |
| 3 | 锁定铁傀儡 / 村民 / 流浪商人 | — |
| 4 | 锁定海龟（破坏龟蛋） | — |
| 5 | — | 远离水面远距游走 |
| 6 | — | 看向实体 / 随机视角 |

典型切换：僵尸追村民（pri 3）时，若玩家进入侦测范围 → 改为锁玩家（pri 2），因为优先级更高。这正是“优先级覆盖”的体现。

### 2.7 实例：苦力怕（Creeper）

- pri 1：`FloatGoal`（水中上浮，占 JUMP）
- pri 2：`SwellGoal`（膨胀爆炸，距离≤3格时压过近战）
- pri 3：`AvoidEntityGoal`（远离猫/豹猫，占 MOVE）
- pri 4：`MeleeAttackGoal`（占 MOVE+LOOK）
- pri 5：避水随机游走（MOVE）
- pri 6：`LookAtPlayerGoal` / 随机视角（LOOK）

可见同一旗标（如 MOVE）在不同优先级 Goal 间互斥切换，LOOK 上“看玩家”和“随机视角”同优先级可共存。

---

## 3. 记忆行为系统（Brain / Behavior）

> 来源与权威说明：1.14 村民 AI 完全独立于旧 Goal 系统，但**任何 `LivingEntity` 都能挂一个 Brain**（见 Forge 文档 gemwire、Pyrofab 的 1.14 解析、DeepWiki 反编译资料）。

### 3.1 五大组件

```
LivingEntity
 └── Brain
      ├── MemoryModuleType  ← 记忆黑板（key-value，可带过期时间）
      ├── Sensor[]          ← 感知：周期性扫描世界，写入记忆
      ├── Activity[]        ← 活动（粗粒度状态）：CORE/IDLE/WORK/FIGHT…
      │     └── Behavior[]  ← 行为（原子任务），带“记忆条件入口”
      └── Schedule          ← 时刻表：一天内何时切到哪个 Activity
```

- **MemoryModuleType**：记忆键（如 `ATTACK_TARGET`、`WALK_TARGET`、`LOOK_TARGET`、`NEAREST_VISIBLE_PLAYER`、`IS_PANICKING`、`GOLEM_DETECTED_RECENTLY`）。值可带过期时间（按 tick，默认 `Long.MAX_VALUE` 不过期）；带 Codec 的记忆可随实体存档持久化。
- **Sensor**：按设定间隔（默认约 20 游戏刻，部分旧资料记 10 刻，可在构造函数自定义）扫描世界，把结果写入记忆。例：`NearestLivingEntitiesSensor`、`VillagerHostilesSensor`、`GolemLastSeenSensor`、`SecondaryPoiSensor`。
- **Behavior（Task）**：原子逻辑单元。对应 Goal 的升级版。
- **Activity**：一组 Behavior 的集合 + 进入/退出条件，相当于“当前行为模式”。
- **Schedule**：一天 24000 tick 内“何时切换 Activity”的设定。

### 3.2 每 tick 执行流程（`Brain#tick`）

顺序执行（都在主线程）：

1. **tickMemories**：所有记忆过期时间 -1，到期则遗忘；
2. **tickSensors**：按各自周期运行传感器，刷新记忆（多个传感器错峰执行，不在同一 tick 全跑）；
3. **startTasks**：遍历当前 Activity 下、且处于 STOPPED 的 Behavior，按最低优先级→最高依次尝试 `tryStart`（先校验记忆状态 + 额外启动条件）；
4. **updateTasks**：对所有 RUNNING 的 Behavior 调用 tick，若 `canStillUse()` 为假则 `stop()`。

### 3.3 Behavior 的“状态机”

Behavior 只有两个状态：**`STOPPED`** 与 **`RUNNING`**。

- 启动时先检查“记忆状态条件”，再做任务专属检查，通过则转 `RUNNING`；
- **默认是一次性（one-shot）**：`run` 执行一次，下一刻即 STOPPED；
- 若重写 `shouldKeepRunning()`，则可跨多 tick 持续；
- **Behavior 没有内置互斥概念**（不像 Goal 用旗标互斥）——并发靠记忆状态与 Activity 内的优先级来约束。

方法名对应关系（与 Goal 对照，便于理解）：

| Goal（旧） | Behavior（新） | 含义 |
|------------|---------------|------|
| `canUse()` | `checkExtraStartConditions()` + 记忆状态校验 | 能否启动 |
| `start()` | `start()` | 启动 |
| `tick()` | `tick()` | 每刻 |
| `canContinueToUse()` | `canStillUse()` | 是否继续 |
| `stop()` | `stop()` | 终止 |

### 3.4 Activity（活动状态）

常见 Activity（不同生物子集不同）：
`CORE`（永远运行：移动/看/恐慌）、`IDLE`、`WORK`、`PLAY`、`REST`、`MEET`（钟楼聚集）、`FIGHT`、`AVOID`、`PANIC`、`HIDE`、`GATHER`、`CELEBRATE`、`ADMIRE_ITEM`、`FIND_NEW_HOME`、`GO_TO_BED` 等。

Activity 本身无逻辑，只是“任务清单的标签”。Brain 根据记忆状态（如 `IS_PANICKING`）或 Schedule 切换当前 Activity。

### 3.5 Schedule（时刻表）

一天 24000 tick，按固定 tick 切换 Activity。四个默认表：

- `EMPTY`：所有实体默认，只有一个空 Activity（啥也不做）；
- `SIMPLE`：未使用，work@5000 / rest@11000；
- `VILLAGER_BABY`：idle → play → idle → play → rest；
- `VILLAGER_DEFAULT`（成年村民）：**idle → work → meet → idle → rest**（清晨工作、正午钟楼聚集、傍晚歇息）。

### 3.6 实例：村民（Villager）

- Sensors：`SecondaryPoiSensor`（找工作站/钟楼 POI）、`GolemDetectedSensor`、`VillagerHostilesSensor`、`NearestLivingEntitiesSensor`…
- Memory：工作站位置、钟楼位置、`MEETING_POINT`、交易对象、恐慌状态等；
- Gossip 系统（`GossipContainer`）：村民间口碑传播，影响交易价与铁傀儡生成；
- 行为随职业 + 日程动态切换（CORE 永远跑，WORK 时去工作站，MEET 时去钟楼）。

### 3.7 实例：铁傀儡生成（说明 Brain 的真实运转）

村民的 `PanicTask` 触发恐慌记忆 → `VillagerHostilesSensor` 检测到敌对生物写入 `NEAREST_HOSTILE` → 恐慌满足阈值 → 尝试生成铁傀儡。`GOLEM_DETECTED_RECENTLY` 记忆带 600 tick 过期，避免重复触发。这是“传感器 → 记忆 → 行为”闭环的绝佳样例。

---

## 4. 两套系统对比总结

| 比较项 | 意向系统（Goal） | 记忆行为系统（Brain） |
|--------|------------------|----------------------|
| 决策核心 | 优先级 + 旗标互斥 | 记忆状态 + 活动 + 时刻表 |
| 状态表达 | 哪个 Goal 在跑（隐式状态） | Activity（显式粗状态）+ 记忆黑板 |
| 并发模型 | 旗标允许有限并发（多 Goal 共存） | 单 Activity 下多 Behavior 可并行，无硬互斥 |
| 数据共享 | 直接读实体字段 / 全局 | 统一通过 MemoryModuleType 黑板 |
| 感知 | Goal 内自行 `world.getNearest...`（散落） | Sensor 集中感知、定期刷新记忆 |
| 配置方式 | 硬编码 `registerGoals()` | 数据驱动（Profile 声明记忆/传感器，Activity 装配行为） |
| 持久化 | 无（瞬时） | 记忆可随实体存档（Codec） |
| 典型适用 | 简单/中等复杂度的生物 | 高复杂度、需长期记忆与日程的生物 |

**一句话定性**：
- 意向系统 ≈ **“带优先级与资源互斥的扁平响应式状态机”**；
- 记忆行为系统 ≈ **“记忆黑板 + 传感器 + 活动状态 + 日程的数据驱动行为架构”**，是经典状态机的升级形态，更解耦、更可配置、更利于复杂决策。

---

## 5. 对 VillagerAgent（本项目）的启示

结合本项目分层感知 + “Skill = 状态机” + 双轴（Goal 轴 / Mode 轴）现状，映射到原版两套系统：

| VillagerAgent 现状 | 原版对应 | 启发 |
|--------------------|----------|------|
| `Skill` 单例 + FSM 状态放在 `agent`/`currentAction`（NBT 可序列化） | Brain 的 `MemoryModuleType` 可持久化 | 项目把 FSM 状态序列化到 NBT，对应原版“记忆可存档”——思路一致，但原版用类型化记忆黑板而非单一 blob |
| 基础 action 原语（`FarmingAction`/`CombatAction`…）+ 每 tick 选下一个 `VillagerAction` | `Goal.canUse()` 选动作 | 等价于 Goal 选择，但项目用“每 tick 派生下一动作”更像 Behavior 的 `tryStart` |
| 双轴：Goal 轴（深思，agenda→todo→Skill）+ Mode 轴（反射，DailySchedule→ActivitySystem） | Brain 的 `Schedule`(日程) + `Activity`(模式) + `Behavior`(任务) | **原版的 DailySchedule/Activity 正是 Mode 轴的现成范本**——把“每日计划写 mode 不写 todo”对齐到 `Schedule`+`Activity` 更自然 |
| 分层感知（Far/Mid/Near） | Brain 的 `Sensor`（按周期扫描世界写记忆） | 可用“传感器”概念统一 Near 层 frustum scan / Mid 层 WorldStructureIndex，产出记忆供决策层消费，而不是让 Skill 各自去查世界 |
| `WorldStructureIndex`（事件驱动、一床只扫一次、跨重启持久化） | Brain 记忆可持久化 + Sensor 错峰 | 与“记忆不过期 + 传感器错峰执行”高度吻合，是降低每 tick 开销的正确方向 |
| LLM todo → action 流水线未闭环 | 原版靠 `Schedule`/`Activity` 自动切换驱动行为 | 可把 LLM 产出的 todo 当作“写入记忆（如 `CURRENT_TASK`）”，由行为层像 `Behavior` 一样消费，从而闭合流水线 |

**核心结论**：VillagerAgent 当前更接近“增强版 Goal 系统”（优先级/状态机驱动），而原版村民本身已迁移到 Brain。若要收编 Mode 轴、闭合 LLM→action 流水线，**直接借鉴 Brain 的“记忆黑板 + 活动 + 日程”三层**是当前架构最自然的演进方向，而非继续在 Goal 范式里堆叠。

---

## 参考来源

- Minecraft Wiki（中文）：[生物 AI — 意向系统](https://zh.minecraft.wiki/w/%E7%94%9F%E7%89%A9AI?variant=zh-cn)
- Pyrofab, *Brains, Schedules and Activities: An overview of the new 1.14 AI system*（gist，2019）
- Forge 文档（gemwire）：[Brains](https://forge.gemwire.uk/wiki/Brains)
- DeepWiki：Entity AI: Goals & Brain System（minecraft-merged / minecraft-common）
- techmcdocs：Iron Golem Spawning Mechanics（Brain 实际运转示例）
- DeepWiki：AI System Architecture（PumpkinTracker，Goal/Brain 双系统源码层级）

> 注：方法名以 Java 版 1.13–1.20 反编译资料为准；不同版本（如 Behavior 的 `tick`/`canStillUse`/`shouldKeepRunning` 命名）存在轻微演化，生命周期语义保持一致。传感器周期在 10–20 刻间有版本差异，本文以“可配置、默认约 20 刻”表述。
