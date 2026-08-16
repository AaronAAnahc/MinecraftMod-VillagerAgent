# VillagerAgent × 前沿 AI Agent 研究 对标手册

> 用途：简历里 VillagerAgent 段被拔高到了前沿叙事（符号世界模型 / 双过程决策 / 神经-符号接地 / 可控涌现 / 终身智能体）。
> 本文档把每句话对应到**真实存在的前沿研究或网易工业实践**，面试被追问时用来展开，避免穿帮。
> ⚠️ 标注「规划中」的，是你**明确要做但尚未实现**的，面试务必说"设计中/已实现原型"而非"已上线"。

---

## 〇、诚实对照：你到底有什么，没有什么（必读）

**你项目真实构成**：一个 **LLM API 调用**（它本质是别人训好的神经网络，你没训练它）+ 一堆 **Java 规则代码**（感知、动作校验、持久化、已有的 Gossip 种子）。

| 概念 | 真实含义 | 你项目里的情况 |
|---|---|---|
| 神经网络 / Neural | 学出来的参数模型 | ✅ 有，但**是 LLM API，不是你训的**。你没有任何自训网络 |
| 神经-符号混合 | 神经网络(模糊) + 符号规则(约束) | ✅ 成立：LLM(神经) 提议 → 动作校验(符号) 约束。**前提是动作校验层真做了** |
| 双过程决策 | 快反射(System1)+慢规划(System2) | ✅ 是**架构分层类比**：行为树=System1，LLM规划=System2。不是新模型 |
| 符号世界模型 | 可推理的结构化环境表征 | ✅ 部分成立：测地距离场房间分割 → 空间语义先验（符号层、确定性） |
| 可控涌现 | 局部规则→全局协调自然出现 | ⚠️ 规划中：需 VillageBlackboard + 事件总线 + 局部策略；目前只有 Gossip 种子 |
| 检索增强记忆 | 按相关性检索注入上下文 | ⚠️ 规划中：扁平 List → type+importance+recency 打分检索 |
| Agent 训练脚手架 | 轨迹采集 + 评测闭环 | ⚠️ 规划中：DecisionJournal + Eval 模式 |
| 状态持久化 / 终身 | 跨重启保留状态 | ✅ 已有：WorldSavedData 持久化（是**持久化**，不是学习） |
| MoE 任务路由 | 多个子网络 + 门控路由（神经网络架构） | ❌ **没有，也别套**。你的"感知/规划/动作模块"只是普通软件模块化 |
| 强化学习 RL | 奖励信号训练策略 | ❌ **没有**。LLM 是被 prompt 的，不是被训练的。别写 RL |
| 多模态视觉输入 | 看像素 | ❌ 没有。你读游戏状态，不是看截图 |

**一句话纪律**：能讲"我用 LLM（神经网络）+ 规则校验 做了神经-符号混合、分层决策、空间语义先验、持久化记忆"；**不能**讲"我训练了网络 / 用了 RL / 用了 MoE / 做了自改进学习 / 看了像素"。

---

## 一、你项目里真正前沿的"锚点"（来自调研）

| 前沿概念 | 真实出处 | 你的项目如何对标 |
|---|---|---|
| **符号世界模型 / 场景图** | Agent2World (arXiv 2512.22336)、WALL-E 2.0 (2025.04) | 测地距离场房间分割 → 空间语义 → 可推理的世界模型 |
| **双过程决策 System1/System2** | Optimus-3 (arXiv 2506.10357, 哈工大深圳) | 行为树=System1(反射), LLM规划=System2(审慎) |
| **神经-符号接地 (neuro-symbolic grounding)** | WALL-E 2.0, VistaWise (EMNLP 2025) | LLM意图 → 动作校验/世界接地 → 合法动作 |
| **检索增强记忆 (retrieval-augmented memory)** | JARVIS-1 (IEEE TPAMI 2025), Generative Agents | 长期记忆按重要性/时效检索注入 |
| **可控涌现 / NPC→NPC 网状交互** | HIVE (2025.04, 2000 agent 协调), 网易伏羲群体智能 | 村庄共享黑板 + 事件总线 → 分工涌现 |
| **Agent 训练脚手架 (harness)** | 雷火 JD「Agent 训练脚手架 / Harness」 | 决策轨迹采集 + 场景化评测闭环 |
| **状态持久化 (persistent state)** | JARVIS-1「life-long」记忆持久化 | WorldSavedData 跨重启持久化（是持久化，非自改进学习） |
| **AOP 面向智能体编程** | 网易伏羲（逆水寒/永劫无间智能 NPC） | 感知→认知→决策全链路闭环 |

---

## 二、逐句答辩稿（面试可以直接讲）

### 1. "分层感知-认知 → 符号世界模型（symbolic world model）"
- **前沿依据**：Agent2World 证明"符号世界模型（PDDL / 可执行模拟器）是模型规划的核心"；WALL-E 2.0 用"世界对齐"把 LLM 先验与环境动力学对齐。
- **你的实现**：Far/Mid/Near 三级感知提取空间语义；测地距离场做房间分割 → 本质上是把原始方块世界**提炼成结构化、可推理的空间表征**，相当于给 LLM 一个轻量 world model 而非裸方块流。
- **被追问"这算 world model 吗？"**：老实说"是符号层的、确定性的空间先验，不是生成式 world model；我把论文里的 neuro-symbolic 思路落地成了游戏内的空间语义层"——这反而显得你读过论文且诚实。

### 2. "双过程决策 System1/System2"
- **前沿依据**：Optimus-3 明确用 Dual-Process Theory（系统1快/直觉，系统2慢/分析）组织架构，分 Fast Path / Deep Path 满足 20Hz 实时。
- **你的实现**：行为树+黑板 = 反射式快速响应（System1，低延迟）；LLM 结构化工具调用 = 审慎规划（System2）。
- **差异化点**：你用**规则层兜底 LLM**，而不是纯端到端——这恰好对应 WALL-E 2.0「符号知识约束 LLM 策略」的神经-符号范式，比"全靠 LLM"更稳。

### 3. "神经-符号接地（neuro-symbolic grounding）"
- **前沿依据**：VistaWise（EMNLP 2025）用跨模态知识图谱把 LLM 推理"接地"到当前游戏状态；WALL-E 2.0 用语义规则编码成可执行代码约束 Agent 策略。
- **你的实现**：LLM 输出动作后过 `ActionValidator`（目标可达？配方在职业内？不会走进墙/熔岩？），任一不过则回退规则默认——这就是"符号校验层约束神经输出"，直接对标 neuro-symbolic grounding。

### 4. "检索增强长期记忆 / Agent Memory"
- **前沿依据**：JARVIS-1 的 multimodal memory 支持终身自改进；Generative Agents 用反思+检索驱动行为。
- **你的实现（规划中）**：把扁平 List 升级为 type+importance+recency 打分的检索注入。
- **被追问**：说"对标 JARVIS-1 的检索式记忆，目前完成结构化存储，top-K 检索注入是下一步"。

### 5. "可控涌现 / 多智能体编排"
- **前沿依据**：HIVE 让单人对 2000 个 agent 做自然语言协调；网易伏羲强调"群体智能/可控涌现"。
- **你的实现（规划中）**：在已有 Gossip Propagation 种子上做 VillageBlackboard + VillageEventBus，威胁下分工（战斗/逃跑/报警）。
- **卖点**：开放世界天然多体，你能演示"一个发现威胁→广播→其他人分工"的**可控涌现**，这正是 JD 里"NPC→NPC 网状交互"的具象化。

### 6. "Agent 训练脚手架 / 终身运行"
- **前沿依据**：雷火 JD 点名要"Agent 训练脚手架 / Harness"；JARVIS-1 做 lifelong learning。
- **你的实现（规划中）**：DecisionJournal 记轨迹 + Eval 模式打分，形成可回放/可度量闭环；WorldSavedData 持久化支撑跨重启终身运行。

---

## 三、哪些词可以放心用，哪些要收着点

✅ **放心用（有真实实现/研究支撑）**：
- LLM Agent、分层感知、空间语义、场景图/世界模型（符号层）、行为树+黑板、工具调用、动作校验接地、神经-符号（解释用）、事件驱动、持久化、帧预算。

⚠️ **收着点（目前是规划/原型，别当成品吹）**：
- 检索增强记忆的 top-K 检索、多智能体可控涌现的完整演示、Agent 训练脚手架的评测闭环、终身自改进。
- 建议话术："我设计了 X 框架并实现了核心模块 Y，Z 正在补全；完整 pipeline 对标 JARVIS-1 / Optimus-3 的 X 能力。"

❌ **别硬套（容易翻车）**：
- "端到端训练了一个 Minecraft 通用 Agent"（你没有训练，是调用 LLM）。
- "基于强化学习优化 NPC / 用了 RL"（目前没有 RL，除非你另起 SB3 项目；论文和网易用 RL 做低级技能，你用 LLM+规则）。
- "多模态视觉输入 / 看像素"（你是读游戏状态，不是看截图；VistaWise/JARVIS-1 是多模态视觉，别混）。
- "MoE 任务路由"（这是神经网络架构——多个子网络+门控路由，你项目里完全没有；你的模块分层只是普通软件架构，不是 MoE）。
- "终身自改进学习 / 自我进化"（你只有 WorldSavedData 持久化，记忆不重算；不是权重更新或策略自改进）。

---

## 四、一句话价值主张（终版，可直接放求职意向）

> "我构建了一个 LLM 驱动的村民智能体，以**神经-符号混合架构**打通 SENSE→THINK→ACT 闭环：用**符号世界模型**提供空间先验，用**双过程决策**（行为树反射 + LLM 审慎规划）兼顾实时性与复杂性，并以**检索增强记忆**与**多智能体编排**实现人格演化与可控涌现——完整对标工业级游戏 AI（网易伏羲 AOP）与前沿 open-world agent（JARVIS-1 / Optimus-3）的核心范式。"

---

*调研来源（2025–2026 真实论文/会议/工业分享）：*
- *JARVIS-1, IEEE TPAMI 2025 — open-world Minecraft agent with multimodal memory*
- *Optimus-3, arXiv 2506.10357 — generalist multimodal Minecraft agent, System1/2 + MoE*
- *WALL-E 2.0, 2025.04 — neuro-symbolic world alignment + MPC agent*
- *Agent2World, arXiv 2512.22336 — symbolic world model generation via multi-agent feedback*
- *VistaWise, EMNLP 2025 — cross-modal knowledge graph for Minecraft*
- *HIVE, 2025.04 — LLM-driven multi-agent (2000) coordination*
- *网易伏羲 / 雷火 CGDC 2025、云栖 2025 — AOP、智能 NPC、Agent 训练脚手架、群体智能*
