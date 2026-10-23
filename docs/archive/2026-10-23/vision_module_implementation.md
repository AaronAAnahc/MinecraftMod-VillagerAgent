# VillagerAgent 视觉模块实现总结（v3 → v4 床判定重写）

> ⚠️ **大气判定章节已过时**：本文 Step 4 仍写"通天格 OR 大气方块占比(`AIR_RUN=4`)"，与 2026-08-16 实际代码不符——当前大气 = `skyOpen ∪ longRun`（两条独立测试 OR）：`skyOpen`=竖直列通天，`longRun`=某轴**双向严格大于 `AIR_RUN=16`** 的连续空气。`longRun`/`computeBigAir`/`growBigAir` 均保留，但 `AIR_RUN` 由 12→16 且改双向严格。以 `bed_room_search_implementation.md` / `building_detection_implementation.md` 为准。

> 配套文档：`vision_module_design.md`（设计草案）。
> 本文记录**已落地的代码**、如何接线、编译状态，以及剩余待办。
> 状态：**基础层 + 近景实时精扫 + v4 床判定（距离场+分水岭）已实现，并通过 `compileJava` 干净编译（0 error；仅 1 处 `hasChunksAt` 弃用告警）**。
>
> v3 变更（相对 v2）：按用户反馈做了三处修正——① chunk 记忆从「缓存每个显著方块」改为「只打标签 + 每类特征存 1 个代表坐标」；② 床泛洪改为**事件驱动**（加载 chunk / 放置床即播种，逐 tick 队列泛洪），移除了原有的暴力全块扫描与周期性重建；③ `WorldStructureIndex` **接入 NBT 持久化**，重启无需重算。同时修正了 BFS 的两个潜在正确性 bug。
>
> 床判断微调（v3 之后）：① 新增**屋顶预筛**——床正上方 `V_UP` 层内无实心即判露天床、直接拒；② 纵向窗口从对称 `V_SCAN=12` 改为不对称收紧 `V_BELOW=1` / `V_UP=8`。
>
> **v4 床判定重写（本论）**：弃用「自适应侵蚀 BFS」，改为 **3D 距离场 + 分水岭（watershed）房间分割**。动机——侵蚀 BFS 会在第一个门口掐断，导致「床在大房子的某间小屋里」只返回小房间；分水岭则把整屋切成多个房间 basin、再合并非大气 basin，从而返回整栋屋。细分取 `SUBDIV=2`（0.5 格 = 每块 8 子体素）；大气判定 = 含通天格 OR（大气方块占比 > `BIG_AIR_FRACTION=0.85`）。已 `compileJava` 通过。

---

## 0. 一句话结论

原 `VillagerVisionSystem` 的「随机采样 → 一段文字」已升级为**三层分层感知**：

```
远景 (Far)    = chunk 内容记忆（ChunkContentSampler 采样 → ChunkMemory）
中景 (Mid)    = 建筑结构识别（BuildingLocator 定位 → 共享 WorldStructureIndex）
近景 (Near)   = 视锥实时精扫（FrustumCuller + DetailedViewRecorder）
```

你提的四件事（chunk 记住主要内容 / 视锥剔除 / 区块结构检测 / 视野内更详细记录）**均已实现并编译通过**；你后续提的三点修正（内存只打标签 / 床泛洪事件驱动 / 索引持久化）**均已落地**。

---

## 1. 已实现的文件清单（对应设计步骤）

| 步骤 | 文件 | 职责 |
|------|------|------|
| 1 | `ai/memory/BlockCategory.java` | 方块粗分类枚举（DIRT/WOOD_LOG/ORE/BUILDING…） |
| 1 | `ai/memory/EntityCategory.java` | 实体分类枚举（HOSTILE/ANIMAL/VILLAGER/PLAYER/ITEM） |
| 1 | `ai/memory/ChunkTag.java`（**新增**） | chunk 高层标签位掩码（KNOWN/FOREST/FARMLAND/WATER/VILLAGE/ORE/DANGER_LAVA/HOSTILES/ANIMALS） |
| 1 | `ai/memory/ChunkFeature.java`（**新增**） | chunk 兴趣点种类（工作台/箱/床/门/农田/作物/各类矿…），每类 1 bit + 1 个代表坐标 |
| 1 | `ai/memory/BlockObservation.java` | 近景精扫产物（`DetailedViewRecorder` 用），**不**进 chunk 记忆 |
| 1 | `ai/memory/EntityObservation.java` | 近景精扫实体产物，`DetailedViewRecorder` 用 |
| 1 | `ai/memory/ChunkMemory.java`（**重写：瘦模型**） | 每 chunk 内容摘要 = 标签位掩码 + 特征位掩码(+1 代表坐标) + 分类计数 + 实体计数 + 已知建筑 id。**无逐方块列表** |
| 2 | `ai/vision/ChunkContentSampler.java`（**重写**） | 进 chunk 时对站立层 ±3~+4 的 16×16×8 slab 采样；结果**立即坍缩**为计数/标签/特征位，不缓存单个方块 |
| 3 | `ai/vision/FrustumCuller.java` | 视锥圆锥剔除（eye/forward/isInView），O(1) 每候选 |
| 4 / 4b | `ai/vision/BuildingLocator.java`（**重写 + 修 bug**） | 以床为种子 + **自适应侵蚀 BFS**；`bedsInChunk` 从 chunk 方块实体表免费取床；修正 BFS 距离场与床局部 Y |
| 4 / 4b | `ai/world/BuildingRecord.java` | 单栋建筑记录（id/床/包围盒/封口半径/容积/粗类型），含 NBT 读写 |
| 4 / 4b | `ai/world/WorldStructureIndex.java`（**重写**） | 每维度共享建筑索引；**事件驱动播种 + 每 tick 队列泛洪**；`claimed/rejected/pending` 负缓存；`writeNBT/loadNBT` 已接持久化 |
| 4 / 4b | `ai/world/StructureIndexSavedData.java`（**新增**） | 每维度的 `WorldSavedData`，持有 `WorldStructureIndex` 并在存档时落盘、加载时回灌 |
| 6 | `ai/vision/DetailedViewRecorder.java` | **近景实时精扫**：视锥内显著方块 + 实体 → `ViewSnapshot` |
| 7 | `ai/VillagerAgentData.java` | `visitedChunks` → `chunkMemories`(LinkedHashMap)；查询助手(findFeature/findOre/rememberNearbyHouses/remember*…)；NBT 改造为 `ChunkMemories` ListNBT |
| 8 | `ai/VillagerVisionSystem.java` | `buildEnvironmentSummary` 改为三层组装；远/中/近景分别用 ChunkMemory / WorldStructureIndex / DetailedViewRecorder |
| 接线 | `ai/VillagerAgentManager.java` | 每慢 tick 调 `updateChunkMemory`；每世界 tick 调一次 `WorldStructureIndex.instance(world).processPending(...)` 排空队列 |
| 接线 | `events/VillagerEventHandler.java` | `ChunkEvent.Load`→`indexChunk`；`BlockEvent` 放置/破坏床→`offerBed`/`onBedRemoved`；其它方块改动→`onBlockChanged` |

---

## 2. 关键算法

### 2.1 Chunk 内容采样（ChunkContentSampler）——只打标签，不存方块

- 进 chunk 时扫站立层 `[entryY-3, entryY+4]` × 16×16 = **2048 次 `getBlockState`**，结果缓存、不重复。**这是扫描，不是存储**：扫描结果立刻坍缩为：
  - 每个 `BlockCategory` 的计数（`addCount`）；
  - 命中兴趣点的 `ChunkFeature` 位掩码（`recordFeature`，**每类只记首次出现的 1 个代表坐标**，`localX(4)|y(8)|localZ(4)` 压成 16 bit）；
  - 由计数派生的高层 `ChunkTag`（forest/farmland/water/village/ore/danger_lava/hostiles/animals）；
  - 各 `EntityCategory` 的**数量**（不存坐标——坐标下一秒就过期）。
- **不再缓存 2000 个格子的信息**。一个采样过的 chunk 约占 **100~200 字节**（neighbor-only 占位 chunk 约 40 字节），与「平原空地」还是「256 格农田」无关。这正是用户要求的「不用存两千个格子的信息，只需要给 chunk 打上有什么重点方块/内容的标签」。
- 需要精确位置时（如「具体哪台工作台」），用近景视锥精扫现场重查，不在记忆里囤全量。
- 旧存档中 `ChunkMemory` 若仍是字符串 tag 列表 + 逐方块观察列表，`readNBT` 会自动把 tag 迁移、丢弃逐方块数据（向前兼容）。

### 2.2 距离场 + 分水岭房间分割（BuildingLocator，v4 重写，设计 §5.6）

- **床的获取不再暴力扫描**：`bedsInChunk(IChunk)` 直接读 chunk 的方块实体表（`getBlockEntitiesPos()` + `is(BlockTags.BEDS)`），零 `getBlockState` 扫描。种子由 `ChunkEvent.Load` 与放置床事件提供（见 §3）。
- **Step 0 屋顶预筛**：进入泛洪前先查床正上方 `V_UP` 层内是否存在实心方块（「头顶有遮盖」）。露天床直接判否，省掉整次扫描（大气判定也会拒，这是廉价早退）。
- 以床取 `SCAN_RADIUS=24` 包围盒，纵向窗口 `V_BELOW=2` / `V_UP=16`（回到 3D 后提上来，容纳多楼层）。把每方块切成 `SUBDIV=2` 采样 → **0.5 格子体素网格**（每块 8 子体素，即设计要的细分）。
- **Step 1 3D 连通空气泛洪**：从床邻接空气格出发，洪泛得到床所在的整片连通空气 `C`（这一步天然把小房间 + 穿过门道的主厅一并纳入）。
- **Step 2 3D 距离场**：多源 BFS 求每个空气子体素到最近实心点的距离 `D(p)`。封闭空间的「离墙最远点」即房间中心。
- **Step 3 分水岭（watershed）**：按 `D(p)` 降序做优先洪泛，每个局部极大值播种一个 **basin（=一个房间）**；两个 basin 相遇处即门/窗。
- **Step 4 大气判定**：某 basin 若为「大气（非房间）」则丢弃。判定 = 该 basin 含**通天格**（某空气格正上方整列到盒顶都是空气）`OR`（大气方块占比 > `BIG_AIR_FRACTION=0.85`）。其中「大气方块」= 某方向上正反两侧都有 ≥`AIR_RUN=4` 串空气（长串开放空间）。
- **Step 5 合并求 AABB**：所有**非大气** basin 并起来，其包围盒 = **整栋屋**。这正解决了「床在大房子的某间小屋里 → 之前只返回小房间」的问题：分水岭把每个房间都切成独立 basin，再合并封闭的那些。
- **为什么能回到 3D**：旧版 3D 距离场用于「侵蚀阈值」会在 `r=0.5` 把 2 格高房间整间蚀掉；新版 3D 距离场只用于「找极大值 + 生长 basin」，不涉及侵蚀阈值，所以 3D 没问题。
- **已知限制**：① 有天窗（天花板破洞）的房间会判为通天 → 被丢（与旧版一致，可接受）；② 室内无墙直连室外的房子 = 一整片大气 basin → 被丢；③ 超过扫描盒高度的楼会被裁切；④ 极庞大的**封闭**大厅可能误触补充信号（大气占比超 0.85）→ 把 `BIG_AIR_FRACTION` 调高或设 `1.0` 关掉补充信号即可。
- 天然覆盖**村庄房 / 玩家自建 / 山洞房**。

### 2.3 视锥剔除（FrustumCuller + DetailedViewRecorder）

- 前向向量由 `yRot`/`xRot` 手算（MC 约定：yaw=0→+Z，xRot>0 抬头），不依赖 `getLookAngle()` 跨映射版本差异。
- 圆锥测试：`cos(夹角) ≥ cos(fov/2)` 且 `dist ∈ [0.5, range]`。
- `DetailedViewRecorder.record()` 对村民周围小盒逐体素做 isInView，仅对落在锥内的方块 `getBlockState`，挑显著物并实时查询视锥内实体。产物用 `BlockObservation`/`EntityObservation`（**仅本次上下文使用，不写入 chunk 长期记忆**）。

---

## 3. 数据流 / 接线

```
事件驱动播种（用户修正点 2）：
  ChunkEvent.Load(chunk)  ──► WorldStructureIndex.instance(world).indexChunk(chunk)
      └─ bedsInChunk(chunk) 读方块实体表，对每个未决议的床 offerBed(...)
  BlockEvent.EntityPlaceEvent（床）  ──► offerBed(pos)
  BlockEvent.BreakEvent（床）        ──► onBedRemoved(pos)
  BlockEvent.*（其它方块改动）        ──► onBlockChanged(pos)   // 已知建筑内改动→重扫；附近被拒床→给第二次机会

每世界 tick（VillagerAgentManager.tickAgents，仅一次 / 世界 / tick）：
  WorldStructureIndex.instance(world).processPending(world, FLOODS_PER_TICK=1)
      └─ 从队列取至多 1 张床，先做 hasChunksAt 预检（避免强制加载 chunk），
         确认床仍在 → BuildingLocator.locateBed → 封闭则 add（写入索引 + claimedBeds），
         否则加入 rejectedBeds 负缓存。发现整片村庄的成本被摊到若干秒而非一次性卡服。

每慢 tick（VillagerAgentManager.updateAgent）：
  agent.updateChunkMemory(villager, world, cx, cz)
      └─ 首次进 chunk → ChunkContentSampler 采样 → 写 ChunkMemory（只标签/计数/特征位）
      └─ 8 邻居标记 KNOWN（轻量占位，不采样）

对话 / 决策前（约每 1200 tick 或聊天时）：
  VillagerVisionSystem.buildEnvironmentSummary(villager, world, agent)
      └─ [时间/天气/biome]（保留）
      └─ [远景] 读个人 chunkMemories 摘要（森林/农田/水/村庄/矿/熔岩 + 记忆到的特征）
      └─ [中景] WorldStructureIndex.queryNear(pos,3) 附近建筑（含山洞房，从持久化索引直接取）
      └─ [近景] DetailedViewRecorder.record() 实时视锥精扫（显著方块 + 实体）
      └─ 注入 LLM prompt

存档 / 读档（用户修正点 3）：
  WorldEvent.Save  ──► DimensionSavedDataManager 自动调用 StructureIndexSavedData.save
      └─ index.writeNBT：buildings + claimed/rejected/pending 三组床坐标全部落盘
  WorldEvent.Load  ──► StructureIndexSavedData.load ──► index.loadNBT 回灌
      └─ 重启后索引与负缓存完整恢复，绝不重泛洪
```

`VillagerAgentData.serializeNBT/deserializeNBT` 已改造为持久化 `ChunkMemories`（`VisitedChunks` 旧存档自动忽略，不崩溃）。

---

## 4. 编译状态与修复记录

- **最终状态**：`gradlew compileJava -x processResources` → **BUILD SUCCESSFUL，无 warning**（v3）。
- v2 已修复的 1.16.5 API 不符（仍成立）：
  - `BuildingLocator.seedBeds` 原 `Blocks.BED` 不存在 → 改为 `world.getBlockState(p).is(BlockTags.BEDS)`（v3 进一步彻底移除 seedBeds 暴力扫描）。
  - `ChunkContentSampler` 原 `BlockTags.DIRT_LIKE` 不存在 → 显式 dirt/coarse_dirt/podzol/grass_block/grass_path。
  - `isAir()` 在两处 deprecated → 统一改用 `Material.AIR`。
- v3 修正的运行时/正确性：
  - BFS 床局部 Y（`VR`→`bed.getY()-yMin`）与 3D 距离场→逐层 2D 水平距离场（见 §2.2）。
  - 移除 `rebuildAround`（暴力 ~145×145×49≈百万方块扫描 / 6000 tick）与 `seedBeds`，改为事件驱动 + 队列泛洪。
  - `ChunkMemory` 从 `List<BlockObservation> notableBlocks` / `List<EntityObservation> entities` 改为位掩码 + 计数 + 每特征 1 代表坐标，消除每 chunk 数 KB 的逐方块缓存。
  - 新增 `StructureIndexSavedData` 把 `WorldStructureIndex` 接入 `WorldSavedData` 持久化。

---

## 5. 用户需求对照

| 需求 | 实现 | 文件 |
|------|------|------|
| 1. 行走过的 chunk 记住主要内容 | slab 采样 → ChunkMemory（**标签位掩码 + 分类计数 + 特征位**，不存单格） | ChunkContentSampler / ChunkMemory |
| 2. 当前视野方块用视锥剔除 | 圆锥剔除 + 实时精扫 | FrustumCuller / DetailedViewRecorder |
| 3. 每区块结构检测与记录 | 床+封闭性 BFS → 共享索引（**事件驱动播种 + 持久化**） | BuildingLocator / WorldStructureIndex / StructureIndexSavedData |
| 4. 视野内方块更详细记录 | 实时视锥精扫显著物 + 实体 | DetailedViewRecorder / appendFrustumView |
| **5. 内存不缓存 2000 格** | chunk 只打标签，扫描结果坍缩为计数/位掩码 | ChunkMemory / ChunkContentSampler |
| **6. 床泛洪事件驱动** | `ChunkEvent.Load`/`BlockEvent` 播种，逐 tick 队列泛洪，每床最多 1 次 | WorldStructureIndex / VillagerEventHandler |
| **7. 索引持久化防重算** | `StructureIndexSavedData` 落盘 buildings + 负缓存，重启不重泛洪 | StructureIndexSavedData / WorldStructureIndex |

---

## 6. 剩余待办（已建任务 #8、#11，原 #9/#10 已完成）

| 任务 | 内容 | 状态 |
|------|------|------|
| #9 | **优化 seedBeds**：~百万方块暴力扫描找床 | **已完成** — 改为 `bedsInChunk` 读方块实体表 + 事件驱动队列泛洪 |
| #10 | **接入 WorldStructureIndex NBT 持久化** | **已完成** — 新增 `StructureIndexSavedData`，buildings + claimed/rejected/pending 全量落盘 |
| #8 | **StructureParser Stage 2 体积精细解析**：进场才触发，落地房间/门窗/柱 → StructureRecord(detailed=true) | 中（描述更丰富） |
| #11 | **PlayerBuildMonitor**（可选）：监听放置/破坏事件提前 markDirty，缩小扫描范围 | 低（加速器，非主路径；现已有 `onBlockChanged`/`markDirty` 增量刷新兜底） |
| — | 视锥遮挡（墙后物体仍判可见）属已知简化，进阶可加 `world.clip` 射线遮挡 | 低 |

---

## 7. 已知限制

1. chunk slab 仅覆盖站立层 ±3.5 格：树冠/地下/高层结构由近景精扫与建筑索引补足，符合「先做简单版」取舍。
2. 视锥为纯圆锥，不处理遮挡。
3. Stage 2 精细解析（房间/门窗/柱）尚未实现，中景目前只给「房子 + 床坐标」粗粒度信息。
4. `onBlockChanged` 对每次非床方块改动都会遍历 `rejectedBeds` 负缓存以给被拒床「第二次机会」，常态 `rejectedBeds` 较小、开销可忽略；但若长期在大量开放床附近高频施工可能出现 O(rejectedBeds) 量级开销——目前视为可接受，必要时可改为按 chunk 分桶。
5. 建筑索引为每维度一份、由 `WorldSavedData` 持久化；客户端不持有索引（仅服务端计算与查询）。
