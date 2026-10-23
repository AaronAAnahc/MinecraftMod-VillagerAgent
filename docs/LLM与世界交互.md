# LLM 接入与世界交互

核对日期：2026-10-23。自主规则链路与资源订单见 [主循环](事件驱动大脑与资源订单.md)。

## LLM 的运行边界

`LLMService` 封装异步请求。玩家聊天、身份生成、内心独白、记忆压缩、村民对话和结构生成各有调用入口。村民社交由技能启动，具备本地台词和请求失败回退；异步结果需要在服务端线程核对实体与场景后应用。玩家聊天的入口是 `ChatMessagePacket` 和 manager 对话处理，`GoalParser`、`AgendaParser` 负责相应结构化指令。

`AgentGoal` 表示可调度的短期目标，`LongTermAgenda` 及其 debt/deal/acquire/generic 子类表示长期承诺。承诺存储与可执行目标生成应分别追踪；当前没有完整的通用 agenda 主动分解闭环。

## Harness 组件

| 组件 | 已有职责 |
|---|---|
| `LLMGuard` | Future 包装、超时、失败计数和熔断 |
| `DecisionRequest` | 组织职业、状态、环境、目标和动作菜单 |
| `DecisionSchema` | 解析 action、target、rationale |
| `ActionValidator` | 检查动作类型和部分物品、配方、坐标条件 |
| `DecisionJournal` | 可选 JSONL 轨迹 |
| `HarnessDecisionPlanner.decideDailyGoal` | 请求、校验并登记目标的方法入口 |

当前源码没有 `decideDailyGoal` 的外部调用方，`harness_drive_daily_goal` 配置存在但不构成主循环调用。`LLMGuard` 在村民对话等具体路径使用，各直接 `queryLLM` 路径的超时/线程行为需单独核对。Journal 是决策日志，完整离线回放、目标提案 DTO 与检索记忆系统均不在当前闭环中。

动作 schema 校验通过后仍需技能资格、当前世界状态和完成证据。行为支持范围以 `BehaviorExecutor` 映射及 handler 为准；建造具有独立命令与执行入口。

## 单块与结构建造

`BlockInteractionAction` 提供站位、接近、放置和破坏步骤。破坏使用 FakePlayer 上下文、合格工具检查、工具速度、逐 tick 进度、裂纹/挥手、真实掉落和耐久，并接 Forge 破坏事件。放置使用物品使用上下文、Forge 放置入口和真实背包消耗。距离按 `build_interact_range_sq` 等执行判定，默认平方距离参数为 2.0。

`StructureBuilder` 请求并解析结构 JSON，结构有 name、anchor 和相对 blocks。anchor 可表达相对或绝对坐标；每个 block 使用注册 ID。`BuildOrderPlanner.validateStructure` 将坐标落到世界中，检查空结构、重叠和库存，然后计算放置顺序，返回 errors、unreachable、steps。

顺序算法从与世界方块接触的候选开始，按 26 邻域扩展；优先最低 Y，再按距离 anchor 选择，给每步寻找站位并维护模拟 placed 集合。无站位或无法纳入顺序的块进入错误结果。静态站位与实际寻路能力有不同检查范围，任意连通结构的动态可达性需要游戏内验证。

校验结果可反馈给 LLM 有限回修，次数由 `build_max_retries` 控制。`BuildJob` 保存步骤、锚点和游标，具备 NBT 与世界核对方法；`tickBuilding` 推进结构执行。具体动作与资源采集临时计划的恢复策略不同：建造任务的数据可保存，资源订单则保存需求并重新承接。

`/va build place`、`/va build break`、`/va build structure` 是显式入口，参数见命令补全和 `VillagerAgentCommand`。`processGoals` 遇到非 goal-driven 动作时让出身体，战斗和显式建造有自己的推进路径。

当前支持原子交互和结构请求；自主选址、完整建造产业链、地下掘进、脚手架和第三方模组交互等能力需各自实现或验收。FakePlayer 计算不能自动证明所有附魔、药水或第三方行为与真实玩家完全一致。
