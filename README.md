# VillagerAgent

Minecraft 1.16.5 / Forge 36.2.42 的村民智能体模组。村民根据需求和环境机会选择目标，由规则技能逐 tick 执行动作，通过资源订单协作供货；LLM 为对话等交互提供可选内容。

## 文档入口

- [Agent 上手与当前文档导航](docs/README.md)
- [执行链与资源订单](docs/事件驱动大脑与资源订单.md)
- [源码导航](docs/CODE_INDEX.md)
- [配置、构建与运行](docs/配置与运行.md)
- [调试与验证](docs/DEBUG_VERIFICATION.md)
- [实现范围与验证状态](docs/项目现状与待办清单.md)

当前能力包括分层空间感知、床锚点建筑索引、农耕、休息、社交、制作、交易、资源采集与交付、显式建造入口。各能力的接入范围和限制见对应专题。

## 构建

项目使用 Java 8 toolchain，在项目根目录执行：

```powershell
.\gradlew.bat build --console=plain
```

产物位于 `build/libs/`，安装到对应 Forge 实例的 `mods/`。开发客户端使用 `.\gradlew.bat runClient`。规则行为可在未配置 LLM 时运行。
