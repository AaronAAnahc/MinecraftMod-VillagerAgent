# 床、房间与建筑索引

核对日期：2026-10-23。算法入口 `ai/vision/BuildingLocator.locateBed`，索引入口 `ai/world/WorldStructureIndex`。

## 数据流与职责

`ChunkEvent.Load` 从区块方块实体表读取床；放置/破坏事件维护床和结构失效。床进入 `pending` 队列，由 `processPending` 按预算调用 `locateBed`。结果写入 `BuildingRecord`，供感知、睡眠与建筑导航查询。

床驱动检测回答“床周围的建筑包围盒是什么”。`SleepSkill` 的床选择与导航是消费路径，包含 HOME 记忆、agent 的 home bed 与附近建筑索引；选中后仍需检查实际床状态和可达性。

## 几何参数

| 常量 | 值 | 单位或用途 |
|---|---|---|
| `REACH_CAP` | 16 | 屋顶预筛的向上方块层数 |
| `MAX_SCAN_BLOCKS` | 2000 | 推导扫描盒的世界方块预算 |
| `SUBDIV` | 2 | 每轴子格数，每块 8 个子体素 |
| `AIR_RUN` | 16 | 子格；同一轴两向连续空气均严格超过此值 |
| `BIG_AIR_FRACTION` | 0.85 | 大气占比阈值 |
| `MIN_ROOM` | 8 | 最少房间方块数 |
| `ATMOSPHERE_HALO` | 2 | 洪泛进入露天空气后的子格扩展预算 |

`deriveBoxDims` 令 Y 约为预算平方根，X/Z 均分剩余体积，得到约 6×44×6 的扫描盒。水平以床为中心，竖直从床下方一格向上，Y 截到 0–255。体积预算限制了大宽度房屋的覆盖范围。

## locateBed 的执行步骤

1. 收集床的两半，任一半上方 16 层内存在屋顶即通过预筛。屋顶使用与墙相同的材料判定。
2. 逐块建立实心表，再扩为 0.5 格子栅格。床的子格标为非实心，同时作为洪泛种子；床不参与墙体距离源。
3. 从床作六邻接空气洪泛。`openSkyUp` 表示扫描盒内向上无实心遮挡；进入这类空气时受 `ATMOSPHERE_HALO` 预算限制。该信号受扫描盒范围约束。
4. 以所有实心子格为多源 BFS 起点，计算到墙的六邻接图距离 `D`。
5. 大气信号为 `inC && (skyOpen || longRun)`。`longRun` 要求某轴正负两侧都严格超过 `AIR_RUN`；`growBigAir` 在六邻居中至少四个为大气时作区域生长。
6. 在连通空气中寻找距离场的区域极大值平台。非大气平台为室内种子；室外种子优先取 `D == maxInteriorD` 的大气格，无命中时向下找最大可用距离，再向上找。
7. 所有种子从距离 0 开始同步 FIFO 多源 BFS。室内平台各有标签，室外共享标签。异源接触依据 `label[n] != cl && dist[n] >= cd` 标记边界。
8. 排除边界，分别统计室内/室外集合的大气占比。一个低于阈值、另一个达到阈值时选低者；两者同侧时取占比更低者，平局归室内。选中集合按方块去重并计算 AABB，少于 `MIN_ROOM` 返回空。
9. AABB 六个方向外扩一格包住墙体，仅 Y 限制在世界高度内。按外围实心块的嵌岩比例给出 `house/cave_house` 粗分类。

`BuildingRecord` 保存 id、seedBed、beds、bounds、sealRadius、roomBlocks、coarseType。它提供粗几何，内部房间、门窗、家具的关系模型尚无独立解析器。

## 阻挡规则

空气、液体、`Material.PLANT`、`REPLACEABLE_PLANT`、`BlockTags.CLIMBABLE` 视为可穿透。玻璃、树叶、石木、箱子以及未列入放行规则的装饰块视为墙。这个判定基于材料与 tag，不能等同于实体碰撞形状或视觉透明度。

## 索引与持久化

- `byId` 保存建筑，`byChunk` 提供范围反查；`claimedBeds`、`rejectedBeds`、`pending/pendingSet` 管理已归属、负缓存与待扫描床。
- 当前每 tick 洪泛预算为 1。周边区块加载守卫使用 `getChunkNow`；区域未加载时该床出队，后续加载/采样再次 offer 才有扫描机会。
- 已有建筑 AABB 内发现床时绑定到同一记录。方块变化命中建筑时移除记录并将种子重新入队；附近被拒绝床可重新参与检测。
- 拆床会匹配记录床及其另一半。显式拆床路径在仍有床时安排重扫，最后一张床消失时移除记录。
- `pruneStaleBeds` 以 100 ticks 节流核对床，未加载区域的床保留；全无床时移除记录。显式拆床路径和定期核对路径的具体更新动作分别以方法体为准。
- 活塞、爆炸、液体和 `/setblock /fill /clone` 的事件处理调用 `markDirty`。标记失效与发现新床是两种操作；命令生成新床的发现覆盖需要单独验收。
- `StructureIndexSavedData` 使用每维度 `WorldSavedData` 保存索引。`BuildingRecord` 的 NBT 支持 beds 列表，缺该字段时使用 seedBed。

## 几何和验证边界

宽建筑可被预算盒截断；通向有顶暗空间的窄洞可能把多个空间合并；测地边界取决于种子距离，不能保证精确落在门平面。AABB 外扩可能使相邻房屋重叠，多床归属需做贴墙场景测试。无床建筑不在这条发现路径的覆盖范围。

游戏内重点检查：露天床、玻璃屋顶、植物/梯子、双床拆任一半、最后一床移除、活塞/爆炸改变外壳、跨区块加载与相邻房屋。`AI scripts/building_detection_verify/` 下的 Python 模拟需与 Java 算法逐项对齐后才能作为当前算法证据。当前文档不将模拟脚本结果计为游戏内验收。
