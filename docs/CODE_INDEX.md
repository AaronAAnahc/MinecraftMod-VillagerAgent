# 代码索引

核对日期：2026-10-23。以当前工作区文件生成；首读 [文档入口](README.md)，按任务读取对应源码。

## 任务入口

| 任务 | 源码 |
|---|---|
| 启动/事件接线 | [Villageragent](../src/main/java/com/github/AaronAA0721/villageragent/Villageragent.java)、[VillagerEventHandler](../src/main/java/com/github/AaronAA0721/villageragent/events/VillagerEventHandler.java) |
| 行为主循环/运行状态 | [VillagerAgentManager](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAgentManager.java)、[VillagerAgentData](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAgentData.java)、[BrainRuntime](../src/main/java/com/github/AaronAA0721/villageragent/ai/BrainRuntime.java) |
| 候选/选择 | [RoutineScheduler](../src/main/java/com/github/AaronAA0721/villageragent/ai/RoutineScheduler.java)、[OpportunitySensor](../src/main/java/com/github/AaronAA0721/villageragent/ai/OpportunitySensor.java)、[DeterministicPlanGenerator](../src/main/java/com/github/AaronAA0721/villageragent/ai/DeterministicPlanGenerator.java)、[GoalScheduler](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalScheduler.java) |
| 根任务/嵌套执行 | [TaskExecution](../src/main/java/com/github/AaronAA0721/villageragent/ai/TaskExecution.java)、[GoalQueue](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalQueue.java)、[FarmActionSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FarmActionSkill.java) |
| 目标退出/失败 | [GoalExitPolicy](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalExitPolicy.java)、[ActionLifecycle](../src/main/java/com/github/AaronAA0721/villageragent/ai/ActionLifecycle.java)、[FailureRecoveryPlanner](../src/main/java/com/github/AaronAA0721/villageragent/ai/FailureRecoveryPlanner.java) |
| 技能注册/门控 | [BehaviorExecutor](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/BehaviorExecutor.java)、[BehaviorCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/BehaviorCatalog.java)、[VillagerSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/VillagerSkill.java) |
| 资源订单/保存 | [WorkOrderBook](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/WorkOrderBook.java)、[WorkOrderSavedData](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/WorkOrderSavedData.java)、[ResourceWorkSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceWorkSystem.java) |
| 采集/交付 | [ResourceWorkSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/ResourceWorkSkill.java)、[ResourceSensor](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceSensor.java)、[ResourceCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceCatalog.java)、[InventoryTransfer](../src/main/java/com/github/AaronAA0721/villageragent/ai/InventoryTransfer.java) |
| 农田记忆/前往农田 | [FarmMemory](../src/main/java/com/github/AaronAA0721/villageragent/ai/FarmMemory.java)、[FarmSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FarmSkill.java) |
| 导航/Brain 接管 | [VillagerNavigationSupport](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerNavigationSupport.java)、[VillagerEntityMixin](../src/main/java/com/github/AaronAA0721/villageragent/mixin/VillagerEntityMixin.java) |
| 感知/建筑 | [VillagerVisionSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerVisionSystem.java)、[BuildingLocator](../src/main/java/com/github/AaronAA0721/villageragent/ai/vision/BuildingLocator.java)、[WorldStructureIndex](../src/main/java/com/github/AaronAA0721/villageragent/ai/world/WorldStructureIndex.java) |
| 制作/解锁 | [SkillCraftCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/SkillCraftCatalog.java)、[NativeRecipeResolver](../src/main/java/com/github/AaronAA0721/villageragent/ai/NativeRecipeResolver.java)、[CraftSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/CraftSkill.java) |
| 经济/玩家交易 | [VillagerEconomySystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerEconomySystem.java)、[TradeRequestPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/TradeRequestPacket.java)、[VillagerTradeScreen](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerTradeScreen.java) |
| 社交/对话 | [VillagerSocialSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerSocialSystem.java)、[SocializeSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/SocializeSkill.java)、[ChatMessagePacket](../src/main/java/com/github/AaronAA0721/villageragent/network/ChatMessagePacket.java) |
| 世界操作/建造 | [BlockInteractionAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/BlockInteractionAction.java)、[BuildOrderPlanner](../src/main/java/com/github/AaronAA0721/villageragent/ai/BuildOrderPlanner.java)、[StructureBuilder](../src/main/java/com/github/AaronAA0721/villageragent/ai/StructureBuilder.java)、[BuildJob](../src/main/java/com/github/AaronAA0721/villageragent/ai/BuildJob.java) |
| 配置/命令/日志 | [ModConfig](../src/main/java/com/github/AaronAA0721/villageragent/config/ModConfig.java)、[VillagerAgentCommand](../src/main/java/com/github/AaronAA0721/villageragent/commands/VillagerAgentCommand.java)、[BrainLog](../src/main/java/com/github/AaronAA0721/villageragent/ai/BrainLog.java) |

## 调用链

`VillagerEventHandler.onWorldTick → tickAgents → 慢更新 updateAgent / 每 tick processGoals → GoalScheduler → BehaviorExecutor.executeRoot → TaskExecution → skill.execute / executeChild → ActionLifecycle / GoalExitPolicy`。战斗、显式建造、经济、调试各有 tick 入口，非 goal-driven 动作占用身体时目标执行让位。

## 按目录的完整 Java 文件清单

文件存在表示可定位；运行接线与实现边界见专题及调用方。

### `ai`

[AcquireItemAgenda](../src/main/java/com/github/AaronAA0721/villageragent/ai/AcquireItemAgenda.java)、[ActionExecutor](../src/main/java/com/github/AaronAA0721/villageragent/ai/ActionExecutor.java)、[ActionLifecycle](../src/main/java/com/github/AaronAA0721/villageragent/ai/ActionLifecycle.java)、[ActionRequest](../src/main/java/com/github/AaronAA0721/villageragent/ai/ActionRequest.java)、[ActionResponse](../src/main/java/com/github/AaronAA0721/villageragent/ai/ActionResponse.java)、[AgendaParser](../src/main/java/com/github/AaronAA0721/villageragent/ai/AgendaParser.java)、[AgentGoal](../src/main/java/com/github/AaronAA0721/villageragent/ai/AgentGoal.java)、[AgentInventory](../src/main/java/com/github/AaronAA0721/villageragent/ai/AgentInventory.java)、[BlockInteractionAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/BlockInteractionAction.java)、[BrainLog](../src/main/java/com/github/AaronAA0721/villageragent/ai/BrainLog.java)、[BrainRuntime](../src/main/java/com/github/AaronAA0721/villageragent/ai/BrainRuntime.java)、[BuildJob](../src/main/java/com/github/AaronAA0721/villageragent/ai/BuildJob.java)、[BuildOrderPlanner](../src/main/java/com/github/AaronAA0721/villageragent/ai/BuildOrderPlanner.java)、[CombatAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/CombatAction.java)、[CraftingAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/CraftingAction.java)、[CraftingRecipe](../src/main/java/com/github/AaronAA0721/villageragent/ai/CraftingRecipe.java)、[CustomRecipe](../src/main/java/com/github/AaronAA0721/villageragent/ai/CustomRecipe.java)、[CustomRecipeRegistry](../src/main/java/com/github/AaronAA0721/villageragent/ai/CustomRecipeRegistry.java)、[DealAgenda](../src/main/java/com/github/AaronAA0721/villageragent/ai/DealAgenda.java)、[DebtAgenda](../src/main/java/com/github/AaronAA0721/villageragent/ai/DebtAgenda.java)、[DefaultChatLines](../src/main/java/com/github/AaronAA0721/villageragent/ai/DefaultChatLines.java)、[DeterministicPlanGenerator](../src/main/java/com/github/AaronAA0721/villageragent/ai/DeterministicPlanGenerator.java)、[FailureRecoveryPlanner](../src/main/java/com/github/AaronAA0721/villageragent/ai/FailureRecoveryPlanner.java)、[FarmingAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/FarmingAction.java)、[GenericAgenda](../src/main/java/com/github/AaronAA0721/villageragent/ai/GenericAgenda.java)、[GoalExitPolicy](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalExitPolicy.java)、[GoalParser](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalParser.java)、[GoalScheduler](../src/main/java/com/github/AaronAA0721/villageragent/ai/GoalScheduler.java)、[GossipChannel](../src/main/java/com/github/AaronAA0721/villageragent/ai/GossipChannel.java)、[InventoryTransfer](../src/main/java/com/github/AaronAA0721/villageragent/ai/InventoryTransfer.java)、[ItemAttractionSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/ItemAttractionSystem.java)、[JobStarterItems](../src/main/java/com/github/AaronAA0721/villageragent/ai/JobStarterItems.java)、[LLMService](../src/main/java/com/github/AaronAA0721/villageragent/ai/LLMService.java)、[LLMSetupReminder](../src/main/java/com/github/AaronAA0721/villageragent/ai/LLMSetupReminder.java)、[LongTermAgenda](../src/main/java/com/github/AaronAA0721/villageragent/ai/LongTermAgenda.java)、[NativeRecipeResolver](../src/main/java/com/github/AaronAA0721/villageragent/ai/NativeRecipeResolver.java)、[OpportunitySensor](../src/main/java/com/github/AaronAA0721/villageragent/ai/OpportunitySensor.java)、[RecipeRegistry](../src/main/java/com/github/AaronAA0721/villageragent/ai/RecipeRegistry.java)、[ResolvedRecipe](../src/main/java/com/github/AaronAA0721/villageragent/ai/ResolvedRecipe.java)、[RoutineScheduler](../src/main/java/com/github/AaronAA0721/villageragent/ai/RoutineScheduler.java)、[SkillCraftCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/SkillCraftCatalog.java)、[StructureBuilder](../src/main/java/com/github/AaronAA0721/villageragent/ai/StructureBuilder.java)、[UnlockedRecipes](../src/main/java/com/github/AaronAA0721/villageragent/ai/UnlockedRecipes.java)、[VillagerAction](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAction.java)、[VillagerActivitySystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerActivitySystem.java)、[VillagerAgentData](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAgentData.java)、[VillagerAgentManager](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAgentManager.java)、[VillagerAgentSavedData](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerAgentSavedData.java)、[VillagerEconomySystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerEconomySystem.java)、[VillagerEquipmentHelper](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerEquipmentHelper.java)、[VillagerNavigationSupport](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerNavigationSupport.java)、[VillagerNeedsSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerNeedsSystem.java)、[VillagerObservation](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerObservation.java)、[VillagerSocialSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerSocialSystem.java)、[VillagerTradeSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerTradeSystem.java)、[VillagerVisionSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillagerVisionSystem.java)、[VillageSignalBoard](../src/main/java/com/github/AaronAA0721/villageragent/ai/VillageSignalBoard.java)

### `ai/behavior`

[AcquireItemSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/AcquireItemSkill.java)、[BehaviorCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/BehaviorCatalog.java)、[BehaviorExecutor](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/BehaviorExecutor.java)、[ConverseSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/ConverseSkill.java)、[CraftSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/CraftSkill.java)、[Failure](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/Failure.java)、[FailureReason](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FailureReason.java)、[FarmReclaimState](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FarmReclaimState.java)、[FarmSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FarmSkill.java)、[FindJobSiteSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/FindJobSiteSkill.java)、[GatherSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/GatherSkill.java)、[GotoSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/GotoSkill.java)、[MoveSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/MoveSkill.java)、[ResourceWorkSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/ResourceWorkSkill.java)、[RestockSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/RestockSkill.java)、[SkillResult](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/SkillResult.java)、[SleepSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/SleepSkill.java)、[SocializeSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/SocializeSkill.java)、[TradeDeliverySkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/TradeDeliverySkill.java)、[TradeSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/TradeSkill.java)、[VillagerSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/VillagerSkill.java)、[WanderSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/WanderSkill.java)

### `ai/config`

[VillagerAgentConfigDir](../src/main/java/com/github/AaronAA0721/villageragent/ai/config/VillagerAgentConfigDir.java)

### `ai/debug`

[AgentDebugExporter](../src/main/java/com/github/AaronAA0721/villageragent/ai/debug/AgentDebugExporter.java)

### `ai/harness`

[ActionValidator](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/ActionValidator.java)、[DecisionJournal](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/DecisionJournal.java)、[DecisionRequest](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/DecisionRequest.java)、[DecisionSchema](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/DecisionSchema.java)、[HarnessDecisionPlanner](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/HarnessDecisionPlanner.java)、[LLMGuard](../src/main/java/com/github/AaronAA0721/villageragent/ai/harness/LLMGuard.java)

### `ai/memory`

[BlockCategory](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/BlockCategory.java)、[BlockObservation](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/BlockObservation.java)、[ChunkFeature](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/ChunkFeature.java)、[ChunkMemory](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/ChunkMemory.java)、[ChunkTag](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/ChunkTag.java)、[EntityCategory](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/EntityCategory.java)、[EntityObservation](../src/main/java/com/github/AaronAA0721/villageragent/ai/memory/EntityObservation.java)

### `ai/proficiency`

[CapabilityId](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/CapabilityId.java)、[CapabilityLevelTable](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/CapabilityLevelTable.java)、[CapabilityPool](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/CapabilityPool.java)、[ProficiencyCapability](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/ProficiencyCapability.java)、[ProficiencyEvents](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/ProficiencyEvents.java)、[ProficiencyLevelTable](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/ProficiencyLevelTable.java)、[VillagerProficiency](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/VillagerProficiency.java)、[VillagerProficiencyProvider](../src/main/java/com/github/AaronAA0721/villageragent/ai/proficiency/VillagerProficiencyProvider.java)

### `ai/vision`

[BuildingLocator](../src/main/java/com/github/AaronAA0721/villageragent/ai/vision/BuildingLocator.java)、[ChunkContentSampler](../src/main/java/com/github/AaronAA0721/villageragent/ai/vision/ChunkContentSampler.java)、[DetailedViewRecorder](../src/main/java/com/github/AaronAA0721/villageragent/ai/vision/DetailedViewRecorder.java)、[FrustumCuller](../src/main/java/com/github/AaronAA0721/villageragent/ai/vision/FrustumCuller.java)

### `ai/work`

[ResourceCatalog](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceCatalog.java)、[ResourceProfession](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceProfession.java)、[ResourceSensor](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceSensor.java)、[ResourceWorkSystem](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/ResourceWorkSystem.java)、[WorkOrderBook](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/WorkOrderBook.java)、[WorkOrderSavedData](../src/main/java/com/github/AaronAA0721/villageragent/ai/work/WorkOrderSavedData.java)

### `ai/world`

[BuildingRecord](../src/main/java/com/github/AaronAA0721/villageragent/ai/world/BuildingRecord.java)、[StructureIndexSavedData](../src/main/java/com/github/AaronAA0721/villageragent/ai/world/StructureIndexSavedData.java)、[WorldEventBus](../src/main/java/com/github/AaronAA0721/villageragent/ai/world/WorldEventBus.java)、[WorldStructureIndex](../src/main/java/com/github/AaronAA0721/villageragent/ai/world/WorldStructureIndex.java)

### `client`

[DebugOverlay](../src/main/java/com/github/AaronAA0721/villageragent/client/DebugOverlay.java)、[VillagerArmorLayer](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerArmorLayer.java)、[VillagerChatHandler](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerChatHandler.java)、[VillagerChatScreen](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerChatScreen.java)、[VillagerHeldItemLayer](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerHeldItemLayer.java)、[VillagerTradeScreen](../src/main/java/com/github/AaronAA0721/villageragent/client/VillagerTradeScreen.java)

### `commands`

[VillagerAgentCommand](../src/main/java/com/github/AaronAA0721/villageragent/commands/VillagerAgentCommand.java)

### `config`

[ModConfig](../src/main/java/com/github/AaronAA0721/villageragent/config/ModConfig.java)

### `debug`

[DebugSync](../src/main/java/com/github/AaronAA0721/villageragent/debug/DebugSync.java)

### `events`

[VillagerEventHandler](../src/main/java/com/github/AaronAA0721/villageragent/events/VillagerEventHandler.java)

### `mixin`

[VillagerEntityMixin](../src/main/java/com/github/AaronAA0721/villageragent/mixin/VillagerEntityMixin.java)、[VillagerRendererMixin](../src/main/java/com/github/AaronAA0721/villageragent/mixin/VillagerRendererMixin.java)

### `network`

[ChatMessagePacket](../src/main/java/com/github/AaronAA0721/villageragent/network/ChatMessagePacket.java)、[CraftingRequestPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/CraftingRequestPacket.java)、[DebugDataPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/DebugDataPacket.java)、[ModNetworking](../src/main/java/com/github/AaronAA0721/villageragent/network/ModNetworking.java)、[OpenChatPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/OpenChatPacket.java)、[SyncVillagerDataPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/SyncVillagerDataPacket.java)、[TradeRequestPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/TradeRequestPacket.java)、[TradeResultPacket](../src/main/java/com/github/AaronAA0721/villageragent/network/TradeResultPacket.java)、[VillagerResponsePacket](../src/main/java/com/github/AaronAA0721/villageragent/network/VillagerResponsePacket.java)

### `.`

[Villageragent](../src/main/java/com/github/AaronAA0721/villageragent/Villageragent.java)

## 测试与资源

- [GoalExitPolicyChecks](../src/test/java/com/github/AaronAA0721/villageragent/ai/GoalExitPolicyChecks.java)
- [GoalSchedulerChecks](../src/test/java/com/github/AaronAA0721/villageragent/ai/GoalSchedulerChecks.java)
- [WorkOrderBookChecks](../src/test/java/com/github/AaronAA0721/villageragent/ai/work/WorkOrderBookChecks.java)

资源、构建与配置见 [配置与运行](配置与运行.md)，测试操作见 [调试手册](DEBUG_VERIFICATION.md)。

主动物品操作入口：丢出见 [DropItemSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/DropItemSkill.java)，前往并拾取见 [PickupItemSkill](../src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/PickupItemSkill.java)。协议及限制见 [执行链与资源订单](事件驱动大脑与资源订单.md) 的“丢出与主动拾取原语”。
