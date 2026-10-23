# VillagerAgent 可视化调试与功能自检手册

> 配合 `debugoverlay` 叠加层使用。开启方式见 §1，各系统怎么判断"活着"见 §3，日志关键词见 §4。

## 1. 开启叠加层（二选一）

最稳的方式是在游戏内敲命令（自动持久化到 `config/villageragent-common.toml`）：

```
/villageragent set debugoverlay true
/villageragent list                          # 确认 Debug Overlay: ENABLED
```

细粒度开关（改 config 文件里的 `enable_debug_overlay` / `debug_show_hud` / `debug_show_buildings` / `debug_show_frustum`）：

| 配置项 | 默认 | 作用 |
|---|---|---|
| `enable_debug_overlay` | false | 总开关（服务端是否下发调试快照） |
| `debug_render_range` | 64 | 玩家周围绘制建筑盒/视锥的半径（8–256） |
| `debug_show_hud` | true | 左上 HUD 面板 |
| `debug_show_buildings` | true | 世界线框建筑盒 |
| `debug_show_frustum` | true | 村民视锥锥体 |

> ⚠️ 叠加层只在**服务端确实在下发数据**时才显示——你得先有村民被注册成 agent（自然刷出的村民，或召唤命令）。

## 2. 叠加层三大信息源（看什么 = 正常）

| 信息源 | 正常表现 | 说明 |
|---|---|---|
| **左上 HUD 面板** | 顶行 `agents=N  buildings(near)=M`；准星对准村民后只显示**身份标签**（名字/职业）+ **空间感知**（frustum 扫描 blocks/entities、env 摘要）。**不显示**村民的心理活动（心情/目标/记忆/计划/动作/需求） | 对准谁显示谁；空白说明没注册成 agent 或没对准 |
| **建筑线框盒** | 房屋周围出现线框盒（house=绿 / cave_house=蓝 / barn=橙 / 其它=黄） | 出现 = `WorldStructureIndex` + `BuildingLocator` 工作正常 |
| **青色视锥锥体** | 每个附近村民头顶沿朝向射出一段锥体 | 出现 = `FrustumCuller`/感知正常 |

**快速判健**：开 overlay → 走到村庄 → 看到房屋**世界线框盒**（会随相机正确贴在建筑上，而不是飘在屏幕固定位置）+ 村民青色视锥锥体 + 准星对准村民后 HUD 显示其身份与感知，就说明**感知系统正常**。村民的心理活动（心情/目标/记忆）不在 HUD 显示，属正常设计。

## 3. 各系统自检清单

| 系统 | 怎么验证 |
|---|---|
| **视觉 / 建筑检测** | 世界线框盒正确贴在房屋上（绿=house / 蓝=cave_house / 橙=barn / 黄=其它）；盒子位置应随相机移动而正确跟随建筑 |
| **分块记忆** | 服务端行为，日志/命令验证；叠加层不再以 HUD 形式展示 chunk 标签（建筑盒本身即检测证据） |
| **目标 / 记忆 / 心情** | 属村民内部状态，**HUD 不再显示**（设计如此）。需排查时看服务端日志或对应系统日志 |
| **LLM 集成** | 村民名字/性格由 LLM 生成、聊天有回复；日志见 `LLMService` 的 `=== OpenAI API Request/Response ===`（需 DEBUG 级日志） |
| **交易 / 种田 / 战斗 / 社交 / 日程** | 日志关键词见 §4（HUD 已不再显示 `action=`，以日志为准） |
| **物品吸引 / 装备** | 村民自动捡起/装备地面物品（日志 `ItemAttractionSystem` DEBUG） |

## 4. 关键日志行（控制台 / `logs/latest.log`）

```
# 行动执行（INFO，最直观）
Villager <名> is crafting: <配方>
<名> harvested <方块> at <坐标>
<名> planted <种子> at <坐标>
<名> is moving to: <描述>   /   is gathering: <物品>   /   is idle for N ticks

# 战斗 / 合成 / 种田异常（WARN）
Recipe not found / Cannot craft ... - missing ingredients
<名> cannot harvest: no world access   /   <名> cannot plant: no world access

# LLM（DEBUG，需开 debug 日志）
=== OpenAI API Request ===   /   === OpenAI API Response ===
Response Code: 200 ...        # 非 200 = API key / 网络问题
Unknown LLM API type: <x>     # 配置里 apiType 写错
```

## 5. 常见故障排查

- **HUD 完全不显示** → 检查 `enable_debug_overlay=true` 且 `debug_show_hud=true`；确认有村民被注册成 agent（无 agent 则 snapshot 全空）。
- **建筑盒不出现** → 村民还没走过/睡眠过那片区域（`WorldStructureIndex` 靠"床事件"播种，需要村民交互过）。多待一会儿或让村民睡觉。
- **视锥锥体不出现** → 附近没有"agent 村民"（普通村民不会画锥体，只看被注册成 agent 的）。
- **LLM 没反应** → 看 `LLMService` 日志：API key 是否为空、Response Code 是否 200、`apiType` 是否拼写正确（openai/anthropic/ollama/gemini）。
- **编译报错** → Forge 1.16.5（official 映射）下 `Tessellator`/`BufferBuilder`/`DefaultVertexFormats` 在 `net.minecraft.client.renderer*` 包，不是 `com.mojang.blaze3d.vertex`；`Tessellator` 必须用全限定名 `net.minecraft.client.renderer.Tessellator.getInstance()`。

## 6. 编译运行

```bash
# 出 jar（放进 .minecraft/mods/）
./gradlew.bat build -x test
# 或 直接起客户端跑（首次会下载依赖，较慢）
./gradlew.bat runClient
```

jar 在 `E:\project\VillagerAgent\build\libs\`。进去后 `/villageragent set debugoverlay true` 即可看叠加层。

## 附：调试子系统文件清单

- `network/DebugDataPacket.java` —— 服务器→客户端调试快照（含 encode/decode/handle）
- `debug/DebugSync.java` —— 服务端每 10 tick 为每个玩家打包并下发
- `client/DebugOverlay.java` —— 客户端 HUD 面板 + 世界线框
- `ModConfig.java` —— 5 个开关
- `ModNetworking.java` —— 注册 `DebugDataPacket`
- `VillagerEventHandler.java` —— 世界 tick 调 `DebugSync.tick`
- `VillagerAgentCommand.java` —— 加 `debugoverlay` 选项
