# VillagerAgent：房屋检测实现记录（当前落地版本）

> 配套设计文档：`docs/building_perception_design.md`（关系型结构解析的抽象方案）。
> 本文记录**已经落到代码里的** Stage-1 房屋检测与索引实现的真实做法——类、方法、常量、失效机制、已知坑，方便后续维护与二次开发。
>
> 涉及源码：
> - `ai/vision/BuildingLocator.java` — 单床 → 建筑记录（距离场 + Meyer 分水岭）
> - `ai/world/WorldStructureIndex.java` — 全维度建筑索引、事件驱动失效、队列式重扫
> - `ai/world/BuildingRecord.java` — 建筑记录（支持多床）
> - `ai/world/StructureIndexSavedData.java` — 索引持久化（`WorldSavedData`）
> - `events/VillagerEventHandler.java` — Forge 事件订阅 → 索引失效触发
> - `ai/VillagerAgentManager.java` — 每 tick 驱动 `processPending`
>
> 验证脚本：`AI scripts/building_detection_verify/verify_watershed.py`（分水岭/Meyer 对照）、`AI scripts/building_detection_verify/verify_update.py`（索引更新机制模拟）。

---

## 1. 总览

```
Forge 事件 (BlockEvent / ChunkLoad)
        │  offerBed / indexChunk / onBlockChanged / onBedRemoved
        ▼
WorldStructureIndex  ──(每tick processPending: FLOODS_PER_TICK=1)──►  BuildingLocator.locateBed(bed)
   byId / byChunk            ▲ 一次洪泛                       │ 距离场 + Meyer 分水岭
   claimedBeds              │ 一床只扫一次                    ▼
   rejectedBeds             └────────────── add / remove / markDirty ──►  BuildingRecord (多床)
   pending                                                       │
   (持久化到 StructureIndexSavedData)                            ▼
                                                      村民消费：queryNear() / getAt()
```

核心职责拆分：
- **`BuildingLocator`** 回答"**给定一张床，它所在的封闭建筑长什么样**"（粗粒度：AABB、类型、多床集合）。
- **`WorldStructureIndex`** 回答"**世界里有哪些建筑、任意坐标属于哪栋**"，并把发现过程做成**事件驱动、队列式、一次扫一次**的增量索引，跨重启持久化。
- 村民（`VillagerVisionSystem` / `VillagerAgentData`）只调用 `queryNear` / `getAt`，**自己从不对世界做洪泛**。

---

## 2. `BuildingLocator.locateBed`：单床 → 建筑

### 2.1 关键常量

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `REACH_CAP` | `16` | `hasOverheadCover` 向上查屋顶的封顶层数（仅 Step 0 用） |
| `MAX_SCAN_BLOCKS` | `2000` | **总扫描预算（世界方块数，非子格）**。扫描盒由该预算推导（`deriveBoxDims`，竖直偏置），不再设长/宽/高独立常量 |
| `SUBDIV` | `2` | 每方块轴 2 个采样点 → 0.5 格分辨率（每块 8 子体素） |
| `AIR_RUN` | `16` | **长程空气阈值（子格，=8 格）**。某轴**两向都严格大于** `AIR_RUN` 连续空气才算 long-run（见 §2.2 Step 5）。由 12→16：普通封闭房间不再命中，仅真正开阔空间命中 |
| `BIG_AIR_FRACTION` | `0.85` | Step 7 按**大气占比**判定哪一坨是房间——占比 **< 0.85** 的 blob 为室内（房间）、**≥ 0.85** 为露天大气 |
| `MIN_ROOM` | `8` | 房间块数少于此 → 不算房子，返回 `null` |
| `ATMOSPHERE_HALO` | `2` | Step 3 flood 大气穿透上限：从封闭空气跨入露天空气后，最多再扩 2 格大气即停（见 §2.2 Step 3） |
| `NON_SOLID_MATERIALS` | `Material.PLANT`, `Material.REPLACEABLE_PLANT` | 植物方块排除表：草/高草/蕨/树苗/花/死灌木等不算实心墙/屋顶（见 §2.3） |

> **大气判定（2026-08-16 修订）**：`longRun` 启发量**保留**，`AIR_RUN` 由 12 调高到 16，且改为**两向都严格大于**阈值（单边 >6 格、另一边 12 格这种单边缺口不算长程）。这样普通封闭房间（≤16 格宽）不再命中 long-run，不会被判成大气；只有真正开阔空间（或真开口）才命中。`computeBigAir`/`growBigAir` 随之保留。
>
> **扫描盒改为预算推导（2026-08-16 → 2026-08-16 再修订）**：删除 `BOX_FACTOR` / `MIN_SCAN_HALF` 与 `estimateReach` 自适应估距，并进一步取消独立 `MAX_SCAN_HALF` 水平半边长常量。现由单一旋钮 `MAX_SCAN_BLOCKS`(2000) 经 `deriveBoxDims` 推导盒尺寸（竖直偏置、让教堂塔楼仍能源到顶），扫描量被牢牢限制（约 6×44×6 世界方块 / 12672 子格）。`WorldStructureIndex` 的 chunk 加载守卫改用 `BuildingLocator.scanHorizontalHalf()`（由同一预算推导的保守水平半边长）作上限（详见 §4.3）。

### 2.2 执行步骤（全部在 0.5 格子栅格 `SUBDIV=2` 上运行）

0. **顶部遮挡预筛** `hasOverheadCover`：先 `collectBedBlocks` 取床两半（给定半格 + 水平相邻仍为 `BEDS` 的格），沿**每个**床半格正上方 `REACH_CAP` 层查非空气非液体块（玻璃也算顶），任一有顶即通过；全无 → 直接 `return null`（露天床，便宜早退）。
1. **预算推导建盒 + 逐块实心度**：盒由 `MAX_SCAN_BLOCKS`(2000) 经 `deriveBoxDims` 推导（竖直偏置、不再设长/宽/高独立常量），水平居中于床、竖直锚在床下方一格向上生长并 clamp 到 [0,255]。盒内每方块一次 `getBlockState`，`isWall` 判定实心（床格强制非实心，家具不是墙）。
2. **下采样子栅格**：`SUBDIV=2` 把每方块扩成 2×2×2 子体素（0.5 格分辨率）。线性化为一维数组 `solid[N]`；额外建 `bedMask[N]`：床两半每一子体素标床、`solid` 中对应格置 `false`（确保距离场不以床为墙）。
3. **3D 连通空气洪泛（带大气穿透上限）**：从 `bedMask` 床子格 seed `inC`（`atmoBudget=MAX_VALUE` 表示室内不限步），6 邻接 flood。**关键 cap**：一旦从封闭空气跨入露天空气（`openSkyUp[n]` 为真，即该格竖直列在盒内无实心块），给该大气格一个 `ATMOSPHERE_HALO`（默认 2）预算，此后每再走一格大气 `−1`，预算 ≤1 时**停止继续往大气扩散**。`inC` 因此被限制在"房间本体 + 每个开口外约 2 格薄壳"，不再灌满整片天空（既加速 flood，也让调试视图从"一大团云"变成"门口一圈薄壳"）。
4. **3D 距离场**：多源 BFS，从所有实心格出发，每空气格记"到最近墙步数" `D`。床非实心 → 不作为距离源，距离场忽略床（否则床会在房间中部打出一个假近墙凹陷）。
5. **大气信号（`skyOpen` ∪ `longRun`）**：`skyOpen[i] = openSkyUp[i] && inC[i]`（竖直列在盒内通透到天）。`longRun[i]`（来自 `computeBigAir`）：某轴**两向都严格大于 `AIR_RUN`(16 子格=8 格)**的连续空气才算长程（单边缺口不算）。`bigAir[i] = inC[i] && (skyOpen[i] || longRun[i])`，再经 `growBigAir` 区域生长闭合小缝隙。于是**大气 = 通天格 ∪ 长程空气格**：封闭大空间连不到天、也不满足长程 → 不是大气；只有真开口或真正开阔空间才命中。`AIR_RUN` 由 12→16 后普通封闭房间不再命中。
6. **同步双类别测地线分水岭**（详见 §3）：把 `D` 的**区域极大值平台**当种子盆地，每个盆地按是否大气分为 interior / exterior（大气 front 从 `D==max(室内种子D)` 的大气格起步，与室内 front 恰好在门口相遇）；多源 FIFO BFS 每轮各扩一步，相遇等距处即边界。
7. **盆地分类 → 房间并集**：按**大气占比**判定哪一坨是房间——室内 blob 大气占比 `< BIG_AIR_FRACTION(0.85)` 则判为房间，室外 blob 通常 `≥0.85`（模糊时占比低者胜、平局归室内）。所有房间块（非边界）并集出 AABB（仅覆盖空气格）。
8. **包围盒外扩 + 分类**：AABB 在 ±x/±y/±z 各 +1 把墙体壳包进屋子；**不再 clamp 回扫描盒**——扫描盒只是空气采样范围，而 AABB 是建筑包络、必须始终外扩一格包裹内部空气（否则床贴墙时空气贴到盒边、+1 被截断，那个角只相接不包围）。仅 Y clamp 到世界边界 [0,255]，X/Z 不受扫描盒限制。`classifyType` 按房间外接实心块 ≥50% 嵌入岩石（6 邻居中 ≥5 实心）判 `cave_house` / `house`。

返回 `BuildingRecord(id, seedBed, 外扩后 bounds, sealRadius=maxRoomD/SUBDIV, roomBlocks, coarseType)`。

### 2.3 阻挡物定义（植物可穿过，花盆/装饰仍算墙）

```java
private static final Set<Material> NON_SOLID_MATERIALS = new HashSet<>(Arrays.asList(
        Material.PLANT, Material.REPLACEABLE_PLANT));

private static boolean isWall(BlockState st) {
    Material m = st.getMaterial();
    if (m == Material.AIR || m.isLiquid()) return false;            // 空气、液体不算墙
    if (NON_SOLID_MATERIALS.contains(m)) return false;              // 植物族放行
    if (st.getBlock().isIn(BlockTags.CLIMBABLE)) return false;      // 梯子/藤蔓：房屋判定视作透明
    return true;
}
private static boolean isRoof(BlockState st) { return isWall(st); }
```

规则：**空气、液体不算墙；植物族（草/高草/蕨/树苗/花/死灌木/甜浆果丛等）也不算墙（可穿过）；其余（玻璃、树叶、石头、木头、箱子、火把、牌子、地毯、铁轨…）一律算墙**。

- ✅ 草坪、花丛、树篱等植物**不再**把房间/房屋切开或封死——这正是旧版误判的来源之一。
- ✅ **梯子类方块**（`BlockTags.CLIMBABLE`：梯子、藤蔓等）视作透明，房屋判定不挡空气洪泛（细高塔楼的梯子不打断空腔）；同时也**不算屋顶**。
- ⚠️ 反向提醒：玻璃、树叶这类"透明但实心"的块**仍**算墙（设计决定：只有流体 + 植物族可穿过）；若日后想让薄装饰件也穿过，往 `NON_SOLID_MATERIALS` 加材料即可。

---

## 3. 距离场分水岭（同步双类别测地线）详解

> 原 Meyer 优先级队列(by D 降序) + `WSHED=-2` 实现已**移除**，改为下面的同步双类别测地线 BFS。改动动机见 `docs/bed_room_search_implementation.md` 顶部修订记录（四点重构）。

### 3.1 为什么是"同步双类别"（而非原版"先到先得"/Meyer）

把 `D` 当地形海拔：离墙越近海拔越低，房间中心/室外中心海拔最高。分水岭 = 模拟涨水，但**涨水方式**决定边界落在哪。

- **原版（已被替换）**：所有 `inC` 细胞按 `D` 降序出队，每个细胞取"第一个有标签邻居"为盆地。门口是低-D 瓶颈、最后出队，室外（D 最大、先处理）经洞口把近门室内细胞抢走 → 床可能掉进室外盆地。
- **当前（同步双类别）**：种子取 `D` 的**区域极大值平台**（每个平台 = 一个种子盆地）；各种子**同时从距离 0 起步、每轮各扩一步**（FIFO 多源 BFS）。这样当两个不同种子的前沿**在相等测地距离**处相遇，相遇格天然是"等距面" → 标为边界。门口正是一个 interior 盆地与一个 exterior 盆地的等距相遇面，边界精确落在门口脊线，室外不再漏入。
- 关键几何取舍：**室外 front 不从远处的区域极大大气格起步**，而是从 `D == max(室内种子D)` 的大气格起步（即"墙距等于最远室内点的那圈大气格"），保证两 front 恰好在门口相遇；若该环上无大气格，则向下扫描取最大的 `D < max`（再无则向上扫），始终把起点压在门口附近，避免从远处大气灌入把房间吞掉。

### 3.2 算法骨架（`BuildingLocator` 第 362–477 行附近）

```
6a. 收集 D 的区域极大值平台：inC 内 D 不小于所有邻居者，连同其等-D 连通平台 = 一个 plateau；
    每个 plateau 按是否含大气格(skyOpen 或 longRun) 标记 interior / exterior。
6b. 室内 plateau 各领一个独立 labelId（所以 interior–interior 相遇也能被捕捉）。
    若至少存在一个 interior：
        exteriorLabel = 新 id；
        seedExteriorFront(maxInteriorD, …)：从 D==maxInteriorD 的大气格 seed 室外 front；
        若 0 个命中 → 向下扫 d=maxInteriorD-1…1，再无 → 向上扫 d=maxInteriorD+1…maxDAtmo。
    若无任何 interior（纯开阔）→ 退化为把所有大气 plateau 标为 exterior。
6c. 同步 FIFO 多源 BFS：所有 seed 距离 0、每轮各扩一步；
        label[n]==-1 → 归属当前种子、dist+1 入队；
        label[n]!=cl 且 dist[n]>=cd → 两 front 等距相遇 → boundary[n]=true。
```

> 下游 Step 7 用 `boundary[]` 排除边界格、`label[]==exteriorLabel` 区分大气、`label[]>=0` 区分室内各集合；最终按**大气占比**(`BIG_AIR_FRACTION`) 判哪一坨是房间。调试可视化（`DebugFieldCell`）即按 `label/boundary/atmospheric` 上色。

### 3.3 分水岭修不掉的部分（固有局限）

分水岭只管"边界画在等距面"，不管"两个合格盆地该不该并成同一栋"。若洞连通的是**有顶暗格**（非 sky、且连不到天），两个盆地都通过大气占比测试，Step 7 一并并入 AABB → 仍溢出（验证脚本 Scenario E）。根治需另加**连通颈宽判定**（见 §7 待办）。另：边界落在测地等距中点，对深房间而言未必精确贴合墙/门洞。

---

## 4. 建筑索引与更新机制 `WorldStructureIndex`

### 4.1 数据结构

```
byId:        Map<long id, BuildingRecord>          // 主表，id = seedBed.asLong() & 0x7FFF...
byChunk:     Map<chunkLong, List<BuildingRecord>>  // 按包围盒覆盖的 chunk 反查，加速 getAt/queryNear
claimedBeds: Set<long>        // 已属于某建筑的床（永不再扫）
rejectedBeds:Set<long>        // 扫过但不在房子里的床（负缓存）
pending:     Deque<long>      // 待洪泛的床；pendingSet 去重
```

持久化：`byId + 三个床集合` 全部写入 `StructureIndexSavedData`（经 `WorldSavedData`），重启不重扫。`BuildingRecord.writeNBT/readNBT` 用 `ListNBT(LongNBT)` 存多床（`beds` 字段；旧存档无该字段时 `readNBT` 回退到 `seedBed`，向后兼容）。

### 4.2 增量发现（无周期全扫）

- **chunk 加载** → `VillagerEventHandler` 订阅 `ChunkEvent.Load` → `indexChunk(chunk)` → 从 chunk 的 block-entity 表读床（`BuildingLocator.bedsInChunk`，零方块扫描）→ `offerBed`。
- **玩家放/拆方块** → `BlockEvent.EntityPlaceEvent` / `BreakEvent` → `offerBed`（床）/ `onBedRemoved`（床）/ `onBlockChanged`（其它块）。
- **每 tick** → `VillagerAgentManager.tickAgents` → `processPending(world, FLOODS_PER_TICK=1)`：每 tick 最多 1 次洪泛，整村发现摊到几秒内，不卡服。

### 4.3 失效 / 重扫路径

```
方块变化 (onBlockChanged)
   → getAt(pos) 找到所属建筑 → remove(id) + enqueue(seedBed)   // 该建筑丢弃，种子床重扫
   → 找不到 → 12 格内被拒床复活重扫（可能刚补上最后一道墙）

床被拆 (onBedRemoved)
   → 找到含此床的记录（含"床的另一半"也匹配，见下）→ removeBed(bed)
        · 床集空 → remove(整屋)              // 最后一张床没了 = 整屋消失
        · 否则 → remove(整屋) + enqueue(存活床)   // 丢弃冻结几何的旧记录，
                                                   // 从存活床重新洪泛，房间随世界更新
   → 不在任何记录 → 清 claimed/rejected/pending 缓存

每次更新 (pruneStaleBeds, 挂于 processPending, 节流 ~5s)
   → 遍历每栋楼的 beds，逐张用 world.getBlockState 核对是否仍存在（含"另一半"兜底）
        · 某床的世界里已不存在 → 从 beds / claimedBeds 剔除
        · 存活床为空 → remove(整屋)          // 幽灵"无床"楼彻底消失
        · 仅部分床没了 → 保留存活床，必要时把 seedBed 提升为存活床
   → 目的：兜底所有"拆床事件漏掉"的路径（拆的是没被记录的那一半、活塞/爆炸、
           存档漂移），让无床残留永远不可能长期存在。

整屋重扫 (processPending)
   · 周边 ±scanHorizontalHalf() chunk 未全加载 → 该床出队，留待下次 chunk 加载/采样重入队
   · 床已属于某建筑(claimed)或已在记录内 → 若记录内则 addBed 绑定同一屋，跳过
   · 否则 locateBed → add(record) / rejectedBeds.add(key)
```

> **效果**：结构性改动（敲墙、开门、玩家手放/拆方块）会触发重扫更新——这条机制是接通的、可用的。**拆床现在也会让房间更新**（见下，2026-08-16 修复）。

### 4.4 已知缺陷（更新机制的真实坑）

| # | 缺陷 | 现象 | 根因 / 位置 | 严重度 | 状态 |
| --- | --- | --- | --- | --- | --- |
| G1 | **拆非种子床 / 多床屋不更新** | 拆一张床后房间记录仍冻结在首次检测几何 | 旧 `onBedRemoved` 删除/提升种子后**从不重洪泛**，几何停在第 1 次检测 | 中 | **已修（2026-08-16）**：存活床即触发重新洪泛 |
| G2 | **内部变化不可见** | 屋里放箱/家具，记录无变化 | `BuildingRecord` 只存粗 AABB+type+seedBed+多床；无内部模型；重扫结果通常相同 | 中 | 未改（设计取舍） |
| G3 | **机械改动不失效** | 活塞推墙、爆炸、液体流动后索引陈旧 | 这些**不触发 `BlockEvent`**；而 `markDirty` 是**死代码（全工程无调用方）**，本应作此用途 | 高 | 仍待修 |
| G4 | **未加载即丢弃（backoff）** | 周边 chunk 未加载时该床出队 | `processPending` chunk 加载守卫出队后**不加回**；仅当后续 chunk 加载或采样再次 `offerBed` 才重试 | 低-中 | 未改 |
| G5 | **无床残留（幽灵楼）** | 场景里出现"没有床却仍被识别为房子"的建筑；放床再拆也不消失 | `BuildingRecord.beds` 只记录床的**其中一半**（seedBed）；拆掉没被记录的那一半时 `hasBed` 查不到 → 走"清缓存"分支建筑不动；原版自动移除另一半但不保证再触发 `BreakEvent`；存档重载时 `loadNBT` 只从 `seedBed` 重建 `claimedBeds`，幽灵床永久残留 | 中 | **已修（2026-08-16）**：见下 |

> **G1 修复说明（2026-08-16）**：原 `onBedRemoved` 在移除床后，若仍有存活床就把记录保留（最多把 seed 提升为存活床），但**那栋楼的几何从第 1 次 `locateBed` 起就再没重算过**。后果：多床屋拆掉一张床、或拆掉非种子床时，房间 AABB/类型不变（`queryNear`/调试视图"看起来没更新"）。现改为：只要还有存活床，就 `remove(整屋)` 并从存活床 `enqueue` 重新洪泛——与 `onBlockChanged`（敲墙也丢整屋重扫）行为一致，房间随世界刷新。最后一张床仍走 `remove(整屋)` 让建筑彻底消失。
>
> **G5 修复说明（2026-08-16）**：根因是床有头/尾两格，但 `beds` 只存了被 offer 的那一格。拆掉**未记录**的那一格 → `onBedRemoved` 用 `hasBed(k)` 查不到 → 建筑纹丝不动；原版移除另一格时也不保证再发 `BreakEvent`；存档重载时 `loadNBT` 仅按 `seedBed` 重建 `claimedBeds`，于是"无床楼"永久残留。两层修复：
> 1. **即时**：`onBedRemoved` 的匹配扩展为"`beds` 中的任一格 **或它的另一半**（水平相邻）"——拆哪一半都能即时命中并删楼/重洪泛。
> 2. **兜底（每次更新）**：新增 `pruneStaleBeds`，挂在 `processPending` 每 tick 调用、内部节流（`PRUNE_INTERVAL=100` tick ≈5s）。它对每栋楼的每张床用 `world.getBlockState` 核对是否仍存在（未加载 chunk 视为"在"，不误删），不存在就剔除；最后一张床没了就 `remove(整屋)`。这覆盖了所有"拆床事件漏掉"的路径（拆错半格、活塞/爆炸、存档漂移），保证无床残留不可能长期存在。
>
> **关于 G3**：`markDirty(AxisAlignedBB)` 方法已写好（作用：把重叠建筑删除并重扫、复活 `scanHorizontalHalf()` 内被拒床），但**没有任何调用点**——活塞/爆炸/液体应在此处调用它才能生效。

### 4.5 多床合并（一间房两张床 → 同一栋）

`BuildingRecord` 持有 `List<BlockPos> beds`（`seedBed` 始终是首个/稳定 id 源；`beds[0]` 恒含 seedBed）。

- **`add`**：新建记录时，包围盒内其它待处理床直接 `addBed` 绑进同一屋。
- **`processPending`**：一张床开始检测时若 `getAt(bed) != null`（已在某屋包围盒内）→ 把它 `addBed` 到那个屋，**而非像旧版只标记 claimed 后丢弃**。这正是"发现床已在已有房子包围盒内"的场景。
- **`onBedRemoved`**：匹配"记录里的任一床 **或它的另一半**（水平相邻）"→ 命中后按上移除；删最后一张床→整屋消失；否则丢旧记录并从存活床 `enqueue` 重新洪泛（几何随世界刷新，见 §4.4 G1 修复）。`onBedRemovedInternal` 为 `processPending` 内"队列床已不是床"时的同义清理。
- **`pruneStaleBeds`（每次更新兜底）**：挂在 `processPending`（每 tick，节流 ~5s）。遍历每栋楼的 `beds`，逐张用 `world.getBlockState` 核对是否仍存在（含"另一半"兜底；未加载 chunk 视为在），不存在就剔除；全没了就 `remove(整屋)`。覆盖所有"拆床事件漏掉"的路径，见 §4.4 G5 修复。

行为：9×9 房两床 → 先定位的床生成整屋（包围盒已含两床连通空气），后定位的床发现自己在盒内 → 并入同一 `BuildingRecord.beds`，两床同属一栋。

---

## 5. 村庄 POI 结论（未实现增强）

查 1.16.5 真实 API：
- 每张床生成/放置时即写入 `minecraft:home` POI，存于 `ServerWorld.getPoiManager()`。
- 本模组 `bedsInChunk` 在 chunk 加载时**直接从方块实体表读床**——拿到的就是同一份数据，且比回头查 `PoiManager` 更省（不强制加载 section）。
- 1.16.5 村庄系统**不存"每栋房的 AABB"**，房屋边界由 POI 簇动态推导，无单调用 API 能直接拿到"某村所有房子的坐标盒"。

结论：**"认出村庄里的房子并把床绑过去"已被现有"床驱动发现 + §4.5 多床合并"覆盖**。直读 `PoiType.HOME` 仅在"想给建筑打村庄分组 tag"时有价值——属可选增强，当前未做。

---

## 6. 验证脚本

| 脚本 | 验证内容 | 关键结论 |
| --- | --- | --- |
| `AI scripts/building_detection_verify/verify_watershed.py` | `locateBed` 距离场 + 分水岭的 Python 移植 + Meyer 对照（**仍用旧 long-run 大气模型，未同步 2026-08-16 的"连天"判定，仅作历史参考**） | 旧版对有孔房溢出（A 仅 63/324、床落 hasSky 盆地）；long-run+AIR_RUN=12 → 满房 326/324、B/C 无误检、E 仍溢出（固有局限） |
| `AI scripts/building_detection_verify/verify_update.py` | `WorldStructureIndex` 缓存/失效机制的 Python 模拟 + 6 场景 | 确认 G1–G4 四类"不更新"缺口（拆床删整屋 / 内部无模型 / 机械改动无失效 `markDirty` 死代码 / 未加载即丢弃） |

> 注：`AI scripts/building_detection_verify/verify_update.py` 的 `locate_bed` 替身为简化不过门口（整盒洪泛成 `open`），仅用于压测**缓存层**逻辑，不影响 G1–G4 结论（那四个缺口与几何检测无关）。

---

## 7. 已知问题与待办

- [x] **G3（高）**：**已修（2026-08-16）**——`VillagerEventHandler` 新增 `onPistonPost`(`PistonEvent.Post`)、`onExplosion`(`ExplosionEvent.Detonate`)、`onFluidPlace`(`BlockEvent.FluidPlaceBlockEvent`)、`onCommand`(`CommandEvent`，覆盖 `/setblock` `/fill` `/clone`) 四个订阅，各自算出受影响区域后调用 `markDirty`。仅剩：命令放新床不播种、火灾/龙/凋灵自然破坏（低频，可选）。
- [ ] **G4**：`processPending` 在 chunk 未加载时**重新入队（backoff 计数）**而非丢弃，避免静态小区漏检。
- [x] **G1（拆床不更新）**：**已修（2026-08-16）**——存活床触发重新洪泛，房间几何随世界刷新；最后一张床仍整屋消失。剩余可选增强："无床也识屋"（shell-only / 多锚点）让拆掉全部床后壳仍被记住，属可选。
- [x] **G5（无床残留/幽灵楼）**：**已修（2026-08-16）**——`onBedRemoved` 现在能匹配床的"另一半"（即时删楼/重洪泛），并新增 `pruneStaleBeds` 每次更新（节流 ~5s）核对每张床是否仍在世界、不在则剔除、全没了删整楼；覆盖拆错半格/活塞/爆炸/存档漂移等所有漏事件路径。
- [ ] **E 溢出**：分水岭固有局限，洞口连通有顶暗格时两盆地都合格 → 加"连通颈宽判定"（1×1 洞作隔断/壁橱，≥2 格才算门）。
- [ ] **可选**：村庄 POI 直读，给建筑打村庄分组 tag。
- [ ] **反向提示**：当前规则把火把/牌子/地毯/铁轨等薄装饰也当墙；如需可穿过，加放行清单。

---

## 8. 编译 / 验证备忘

按约定，**AI 不跑 `gradlew` 以省 token**，由用户本地验证：

```
gradlew compileJava
```

重点盯：
1. `BuildingRecord.writeNBT/readNBT` 的 `Constants.NBT.TAG_LONG` / `LongNBT.valueOf` / `ListNBT.add` 映射名（1.16.5 标准，应无误）。
2. 多床 NBT 读写与旧存档兼容（`readNBT` 已对缺 `beds` 字段回退到 `seedBed`）。
3. 包围盒外扩后，相邻房屋的 `getAt` 会不会误并（外扩 +1 在相邻建筑贴合时有小概率重叠，必要时按 id 最近优先）。
4. 游戏内实测：有门/有洞房子 bed 识别，拆一张床（多床屋应保留、删最后一张消失），活塞推墙后索引是否过期（确认 G3 仍待修）。
