# VillagerAgent 技术力提升计划书
> 目标:把项目从「LLM 台词生成器」升级为「带 Harness 的 LLM Agent 引擎」,精准对齐网易雷火(Agent+RL)/伏羲(AI Agent)岗位能力模型。
> 编写日期:2026-08-16 · 基于 `src/main/java/com/github/AaronAA0721/villageragent/` 现有代码核实。

---

## 0. 现状诊断(基于真实代码)

| 能力维度 | 现状 | 问题 |
|---|---|---|
| LLM 输出形态 | `LLMService.queryLLM()` 返回 `String` 文本;决策主链(日程/社交/思绪/问候)当自然语言用 | LLM 是**装饰层**,不是驱动层 |
| 结构化输出 | 碎片:`StructureBuilder` 用 JSON(建筑规划)、`TodoParser` 正则抠 `TODO:` 行、`VillagerAction` 动作框架 | 决策主链无 schema;正则脆弱;动作执行器多为 `// TODO` 空壳 |
| 动作来源 | 规则代码直接 `new VillagerAction`(`VillagerActivitySystem`/`ProfessionGoalGenerator`),LLM 不产出动作 | 行为不可由 LLM 灵活决策 |
| 失败处理 | 异常返回字符串 `"I'm having trouble thinking right now."`,被下游当正常回复广播 | **稳定性 bug**:API 挂→村民对玩家念报错 |
| 记忆 | `VillagerAgentData.memories: List<String>`,满 40 条异步 LLM 压缩 20 条 | 扁平列表、无类型、无检索、无相关性 |
| 多体 | 村民各自独立;`Gossip Propagation` 30% 概率改第三方好感 | 只有"好感传播"种子,无共享黑板/事件总线 |

**结论**:项目已具备很好底座(分层感知、WorldSavedData 持久化、事件驱动扫描、调试 overlay),但 LLM 尚未成为行为引擎。提升技术力的关键不是"再调 prompt",而是**在 LLM 外面包一层 Harness**,让结构化决策可验证、可落地、可回退、可评测。

---

## 1. 模块一:决策 Harness(核心,直接决定稳定性)

**Harness 是什么**:夹在「感知→决策→执行」之间的控制层。它负责——把上下文按 schema 结构化喂给 LLM、把 LLM 输出解析成动作、**校验并接地(grounding)**、失败时回退、记录轨迹、并在出错时保护游戏线程不卡死。本质是把"调模型"变成"造系统"。

### 1.1 结构化决策协议(替代自由文本)
- 新增 `DecisionRequest`:`{ perception: VillagerObservation, blackboard: Map, availableActions: ActionType[], goals: List<AgentGoal> }`。
- 新增 `DecisionSchema`(JSON):`{ action: Enum, target?: string|blockpos, rationale: string }`,走 JSON mode / function-calling,Gson 解析成 `VillagerAction`。
- LLM 只定**高层决策**(如 `FLEE`/`DEFEND`/`TRADE`),具体执行仍由既有状态机(`VillagerActivitySystem`)完成——LLM 退居"大脑",规则守"手脚"。

### 1.2 校验与接地(`ActionValidator`)—— 稳定性的根本
解析出动作后,**先验证再执行**:
1. **Schema 校验**:action 是枚举成员?必填字段齐全?
2. **世界接地**:目标方块存在且可达?配方在本职业 `RecipeRegistry` 内?目标实体仍存活?
3. **安全校验**:不会走进熔岩/墙体?不会攻击无辜村民?
- 任一不通过 → **拒绝**,回退到规则默认(`ProfessionGoalGenerator` 产出)或让 LLM 重规划一次。这就保证了"LLM 抽风也不会让村民发疯"。

### 1.3 韧性层(`LLMGuard`)—— 对接 JD「工程/稳定性」
- **调用超时**:`future.orTimeout(8s)`,超时用上一次好决策或规则默认,**绝不阻塞服务端线程**(当前已异步,保持)。
- **熔断**:连续 N 次失败→该村民进入纯规则模式 M tick,防止 API 抖动引发全村卡顿/雪崩。
- **Schema 错误重试**:解析失败一次,追加"你的 JSON 不合法,请修正"再问一次。
- **修复现有 bug**:`queryLLM` 错误时返回带语义的失败标记而非普通字符串;下游(社交/问候)据此走 fallback 而非广播报错。

### 1.4 轨迹日志(`DecisionJournal`)
- 每次决策追加一条 JSONL:`{tick, perceptionHash, promptTokens, rawLLM, parsedAction, validation, outcome}`。
- 落盘到 `WorldSavedData` 或本地文件。用途:线上排错、回放、后续 SFT 数据。
- 设计可直接借鉴仓库内 `Reference/minecraft-numen-1.21.1` 的 `JsonlJournal` 模式(它已实现 `ToolCall`/`JsonlJournal`,是成熟参考)。

**对应 JD**:工具调用、多步规划、Agent 训练脚手架、工程稳定性。

---

## 2. 模块二:记忆系统升级(检索增强)

**当前**:扁平 `List<String>`,满 40 压缩 20,无类型无检索。
**目标**:轻量版检索增强记忆(对标 generative agents,但可落地)。

- **类型化条目** `MemoryEntry`:
  - `Episodic`(事件+时间+位置)、`Semantic`(关于世界/玩家的知识)、`Belief`(当前立场);
  - 区分**观测 vs 推断**。
- **打分检索**(替代"全量塞进 prompt"或"朴素摘要"):
  - 每条带 `importance`(0-1,LLM 或启发式给)、`recency`(衰减)、`accessCount`;
  - 决策/拼 prompt 时按 `α·recency + β·importance + γ·relevance(query)` 取 top-K 注入。
- **持久化**:包进已有 `VillagerAgentSavedData`(WorldSavedData),新增 `MemoryStore` 管理类型列表+检索。
- **相关性**:先用关键词/BM25 过记忆文本;有余力再上本地轻量 embedding。

**对应 JD**:Agent Memory(伏羲)、长期记忆、关系图谱。

---

## 3. 模块三:多智能体编排(可控涌现)

**当前**:村民各自为战,仅 `Gossip Propagation` 改好感。
**目标**:村庄社会——共享黑板 + 事件总线,做出"可控涌现 / NPC→NPC 网状交互"。

- **`VillageBlackboard`**(WorldSavedData,全村共享):威胁等级、食物储备、是否入夜、共享目标、各村民位置/状态。
- **`VillageEventBus`**(发布/订阅):事件 `ThreatDetected(entity,pos)` / `ResourceSurplus(item)` / `NightFalling` / `VillagerMissing`。订阅者反应:
  - 威胁→战斗村民获 `DEFEND` 目标,非战斗村民获 `FLEE` 回家,志愿者任 `ALARMER`;
  - 资源盈余→广播招揽采集。
- **涌现演示**:「袭击发生→一人发现→广播→其他人逃跑/防御/治疗」——这就是 JD 里"可控涌现"的活例子。
- **角色自分配**:基于职业+黑板需求自领角色,减少重复目标。
- **复用 gossip**:把 `propagateGossip` 从"只传好感"扩展为"也传意图(谁在做什么)",喂给黑板。

**对应 JD**:多智能体、可控涌现、NPC 网状交互、群体 AI。

---

## 4. 模块四:可观测性与评测(让前面三者可证明)

- **调试可视化复用**:你已有 `DebugOverlay`/`DebugSync` + 配置化开关——直接扩展显示:黑板状态、每村民当前决策+理由、威胁图、校验失败数。对接 JD「调试可视化」。
- **Eval Harness(开发期专属模式)**:用 `DecisionJournal` 回放轨迹,在脚本化场景(第 T tick 袭击 / 玩家交易请求 / 作物成熟)下给每个场景打成功率。这就是雷火要的"Agent 训练脚手架"雏形。
- **可插拔后端 + A/B**:`LLMService` 已支持 gemini/openai/anthropic/ollama,加一层"同场景换 prompt/模型比好坏"的能力,体现工程严谨。

---

## 5. 实施路线图(按风险从低到高)

| 阶段 | 内容 | 风险 | 产出 |
|---|---|---|---|
| **P0 稳定性** | `LLMGuard`(超时+熔断)+ `DecisionJournal` 包住现有调用;修报错广播 bug | 低 | 立刻更稳、可排错 |
| **P1 决策 Harness** | `DecisionRequest/Schema` + `ActionValidator`,先接管"每日目标/日程"一步, flavor 文本 LLM 保留 | 中 | LLM 真正成为决策引擎 |
| **P2 记忆升级** | `MemoryStore` 类型化 + 检索注入 | 中 | Agent Memory 能力 |
| **P3 多体编排** | `VillageBlackboard` + `VillageEventBus` + 袭击涌现场景 | 中高 | 多智能体/涌现演示 |
| **P4 评测+可视化** | Eval 场景打分 + 调试 overlay 扩展 + A/B | 低 | 可证明、可交付 |

> 建议 P0→P1 先落地(投入小、收益大、风险低),P2/P3 可并行推进。

---

## 6. 简历话术映射(每项对应一条 JD 关键词)

- 决策 Harness + 工具调用协议 → **「设计 LLM 决策 Harness,以结构化工具调用替代自由文本,通过 schema 校验与世界接地保证行为可控」**
- `ActionValidator` 接地 → **「实现动作校验与接地层,将 LLM 输出约束于物理可达与职业权限内,杜绝幻觉行为」**
- `LLMGuard` 熔断/超时 → **「引入调用超时与熔断机制,保障 LLM 抖动下 Agent 集群不雪崩」**
- 检索增强记忆 → **「构建检索增强的长期记忆,按相关性/重要性/时效性注入上下文,支撑 Agent Memory」**
- 黑板+事件总线涌现 → **「构建村庄共享黑板与事件总线,实现威胁下的多智能体分工与可控涌现」**
- 轨迹日志+Eval → **「建立决策轨迹日志与场景化评测闭环,形成可回放、可度量、可迭代的 Agent 训练脚手架」**

---

## 7. 一句话故事(面试用)
> 「我把一个 LLM 驱动的村民智能体,从『台词生成器』重构成完整的 SENSE→THINK→ACT 闭环:用决策 Harness 把 LLM 输出结构化为可校验、可接地的动作,配韧性层防雪崩;叠检索增强记忆与村庄多体编排,做出可控涌现;并用轨迹日志+场景评测把整套系统变成可度量、可迭代的 Agent 脚手架。」
