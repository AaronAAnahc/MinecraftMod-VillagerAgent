# 检索增强记忆实现方案（MemoryStore）

> 对应《技术力提升计划书》模块二 +《游戏AI技能规划》技能 #3（长期记忆 + 关系图谱）。
> 目标：把当前「扁平 `List<String>` + 满 40 条压缩」的记忆，升级为**类型化 + 打分检索**的检索增强记忆（retrieval-augmented memory），对标 JARVIS-1 / Generative Agents 的「重要性 + 时效 + 相关性」注入范式。
> 状态：**设计文档，未实现**。本文给出可落地的数据结构、检索打分、接线点与分阶段路线。

---

## 1. 目标与动机

- **现状**：`VillagerAgentData.memories: List<String>`，满 40 条异步 LLM 压缩 20 条。是**扁平字符串**，无类型、无重要性、无检索——拼 prompt 时只能「取最近 5 条」或「全量摘要」。
- **问题**：
  1. 「今天谁给过我省绿宝石」和「早上看到一朵花」被一视同仁地塞进 prompt，浪费 token 且稀释关键信息。
  2. 检索时**无相关性**——聊天提到「矿洞」，却注入一堆无关的社交记忆。
  3. 已新增 `LongTermAgenda`（债务/获取/契约）长期任务层，但**事件型记忆**仍是扁平列表，两者割裂。
- **目标**：类型化 `MemoryEntry`（事件/知识/信念）+ `importance·recency·relevance` 打分检索，决策/对话时按查询取 top-K 注入。

---

## 2. 可复用的现有底座

| 现有组件 | 如何复用 |
|---|---|
| `VillagerAgentData.memories` + 40 条压缩 | 迁移为 `MemoryStore` 的 `Episodic` 条目；压缩逻辑保留（当条目超上限时对低分条目 LLM 摘要合并） |
| `LongTermAgenda` / `AgendaParser` | **不冲突**——agenda 是「持久意图」，memory 是「事实/事件」，两者都注入 prompt，职责不同 |
| `relationships` / `playerReputation` | 关系数据可派生为 `Belief` 记忆（「我喜欢 X」），或保持独立字段（推荐保持独立，避免重复存储） |
| `VillagerAgentSavedData`（WorldSavedData） | `MemoryStore` 一并 NBT 持久化 |
| `DecisionRequest` / `generateChatResponse` / 反思 | 三个 LLM 调用点是检索注入的落点 |

---

## 3. `MemoryEntry` 类型化

```java
package ...ai.memory;

public class MemoryEntry {
    public enum Kind { EPISODIC, SEMANTIC, BELIEF }

    public Kind     kind;         // 事件 / 知识 / 信念
    public String   text;         // 正文（自然语言）
    public long     tick;         // 发生游戏刻（recency 用）
    public BlockPos location;     // 位置（Episodic 可 null）
    public float    importance;   // 0~1，LLM 或启发式给
    public int      accessCount;  // 被检索次数（记忆「越用越活」）
    public boolean  observation;  // true=观测（我看到 X），false=推断（我猜 Y）
    // 可选：public UUID relatedVillager;  // 关联村民（关系型记忆）
}
```

- **三类语义**（对齐计划书）：
  - `Episodic`（事件）：`"harvested 12x wheat near (x,z)"`、`"player Aaron attacked me"`。
  - `Semantic`（知识）：`"the cave north of the village has iron"`、`"crafting a pickaxe needs 3 iron + 2 sticks"`。
  - `Belief`（立场）：`"I trust the farmer"`、`"the night is dangerous"`。
- **观测 vs 推断**：`observation=true` 是确定性事实（代码/感知产出），`false` 是 LLM 推断——检索时观测优先，避免「把猜测当事实」。

---

## 4. 打分检索（替代「全量塞 prompt」）

```
score(entry, query) = α · recency(tick)
                     + β · importance
                     + γ · relevance(query, text)
                     + δ · accessCount  (可选，弱权重)
```

- **recency**：指数衰减 `exp(-Δt / τ)`，τ ≈ 半个游戏日（12000 tick）。
- **importance**：`addMemory` 时给（事件型可由启发式：战斗/交易/死亡=高，走路/看见花=低；或让 LLM 在反思时顺手打分）。
- **relevance**（Phase 1）：对 query 做关键词 / BM25 匹配记忆文本；有余力再上本地轻量 embedding（Phase 2，可选）。
- **accessCount**（可选）：检索命中后 `accessCount++`，让「常用记忆」更易浮出，模拟海马体的「复习强化」。

**检索接口**：

```java
public class MemoryStore {
    private final List<MemoryEntry> entries = new ArrayList<>(); // 上限 ~200

    public void add(MemoryEntry e) { ... }                     // 超上限时对最低分条目 LLM 摘要合并
    public List<MemoryEntry> retrieve(String query, int topK) {
        // 按 score 降序取 top-K
    }
    public List<MemoryEntry> recent(int n) { ... }              // 纯 recency（无 query 时兜底）

    // NBT 序列化（写 kind/text/tick/location/importance/accessCount/observation）
}
```

- **存储上限**：`entries` 上限 ~200，超限时对「低 importance + 低 recency」的 Episodic 条目做 LLM 摘要合并（复用现有 40→20 压缩思路，但改成「按分淘汰」而非「按数量对半砍」）。

---

## 5. 与现有系统对接

### 5.1 迁移（向前兼容）

- `VillagerAgentData.memories` 从 `List<String>` 改为 `MemoryStore`（或并存：`memories` 保留作「最近事件快照」，`memoryStore` 作类型化主存）。
- 旧存档的 `memories` 字符串列表 → 读档时迁移为 `Episodic`（`observation=true`, `importance=0.5`, `tick=当前`）。
- `addMemory(String)` 保持签名不变（内部包成 `MemoryEntry`，`importance` 由启发式给），**避免改动所有调用点**。

### 5.2 检索注入点（三处 LLM 调用）

| 调用点 | 现在的注入 | 改为 |
|---|---|---|
| `generateChatResponse` | `memories` 最近 5 条 | `memoryStore.retrieve(playerMessage, 5)` —— 按玩家说的话检索相关记忆 |
| `generateReflection`（反思） | 最近 8 条 | `retrieve("today", 8)` + 未了结 `agendas` |
| `DecisionRequest`（决策 harness） | 无 / 最近记忆 | `retrieve(当前目标 + 环境, 5)` |

- **为什么相关性有用**：玩家问「矿洞在哪」，检索会命中 `Semantic("cave north has iron")` 而不是「今天和铁匠聊了天」——信噪比和 token 都更优。

### 5.3 持久化

- `MemoryStore` 包进 `VillagerAgentSavedData`，随 `VillagerAgentData.writeToNBT/readFromNBT` 落盘。`accessCount`/`tick` 都要存（recency 跨重启仍正确）。

---

## 6. 分阶段实现（按风险从低到高）

| 阶段 | 内容 | 产出 |
|---|---|---|
| **Phase 0** | `MemoryEntry` + `MemoryStore`（类型化 + recency/importance 打分 + `recent(n)`）；`addMemory` 内部包成 entry；NBT 迁移 | 类型化存储 + 时效/重要性排序 |
| **Phase 1** | `relevance`：关键词/BM25 匹配 + `retrieve(query, topK)`；三处注入点改走检索 | 检索增强记忆，命中 JD「Agent Memory」 |
| **Phase 2** | 本地 embedding（可选，低优先级）+ `accessCount` 强化 | 语义级检索（不必真做，看岗位需要） |

---

## 7. 风险与注意事项

- **迁移兼容**：旧 `List<String>` 存档必须能读（迁移为 `Episodic`），否则坏档。`readNBT` 里做 `contains("memoryStore") ? 读新 : 迁移旧` 分支。
- **压缩行为变化**：现有「满 40 压缩 20」逻辑要改成「按分淘汰 + LLM 摘要低分条目」，避免「重要记忆被对半砍掉」。
- **relevance 的性能**：关键词/BM25 对 200 条文本 O(n) 可承受；**别**每次 tick 都算，只在三处 LLM 调用点算。
- **importance 来源**：初版用启发式（战斗/交易/死亡=高，走路/采花=低）即可，不必每次 addMemory 都调 LLM 打分（省 token）。
- **与 LongTermAgenda 分工**：agenda =「未了结的持久意图」（债务/契约），memory =「事实/事件/知识」；两者都在 prompt 注入，但**不要**把 agenda 塞进 MemoryStore（否则持久意图会被 recency 衰减淹没）。
