package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.HoeItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;

/** Explicit operator requests, backed by agent inventory and the normal skill executor. */
final class VillagerControlCommands {
    static LiteralArgumentBuilder<CommandSource> markets() {
        return Commands.literal("markets").executes(c -> {
            java.util.List<VillageMarkets.Market> markets=VillageMarkets.loaded(c.getSource().getLevel());
            for(VillageMarkets.Market m:markets) {
                c.getSource().sendSuccess(new StringTextComponent("市场 " + m.id + " 中心=" + m.center
                    + " 来源=" + (m.meetingPoint?"集合点":"家/工作站/位置归组")
                    + " 已加载成员=" + m.members().size()
                    + " 卖单=" + m.board.messagesOfType(VillageSignalBoard.MsgType.TRADE_HAVE).size()
                    + " 买单=" + m.board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT).size()),false);
            }
            c.getSource().sendSuccess(new StringTextComponent("当前维度活跃市场：" + markets.size()
                + "；未加载实体不参与，索引最多缓存 100 ticks。"),false);
            return markets.size();
        });
    }
    static LiteralArgumentBuilder<CommandSource> resetValues() {
        return Commands.literal("reset_values")
            .then(Commands.argument("villagers", EntityArgument.entities()).executes(c -> {
                int reset = 0, skipped = 0;
                for (Entity entity : EntityArgument.getEntities(c, "villagers")) {
                    if (!(entity instanceof VillagerEntity) || !entity.isAlive()) {
                        skipped++;
                        continue;
                    }
                    VillagerAgentData agent = VillagerAgentManager.getOrCreateAgent((VillagerEntity) entity);
                    VillagerEconomySystem.resetPersonalValueTable(agent);
                    reset++;
                }
                if (reset == 0) return fail(c, "没有选中存活的村民，价值表未修改。");
                c.getSource().sendSuccess(new StringTextComponent("已重置 " + reset
                    + " 个村民的价值表（共享默认值 + 当前职业价格），跳过 " + skipped + " 个实体。"), false);
                return reset;
            }));
    }

    static LiteralArgumentBuilder<CommandSource> trade() {
        return Commands.literal("trade")
            .then(Commands.argument("first", EntityArgument.entity())
            .then(Commands.argument("second", EntityArgument.entity())
            .then(Commands.argument("first_item", ItemArgument.item())
            .then(Commands.argument("first_count", IntegerArgumentType.integer(1, 64))
            .then(Commands.argument("second_item", ItemArgument.item())
            .then(Commands.argument("second_count", IntegerArgumentType.integer(1, 64))
            .executes(c -> TradeDemo.startExchange(c.getSource(), EntityArgument.getEntity(c, "first"),
                EntityArgument.getEntity(c, "second"), ItemArgument.getItem(c, "first_item").createItemStack(
                IntegerArgumentType.getInteger(c, "first_count"), false),
                ItemArgument.getItem(c, "second_item").createItemStack(
                IntegerArgumentType.getInteger(c, "second_count"), false)))))))));
    }
    static LiteralArgumentBuilder<CommandSource> items(String command) {
        return Commands.literal(command).then(Commands.argument("villager", EntityArgument.entity())
            .then(Commands.argument("item", ItemArgument.item())
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
            .executes(c -> item(c, command)))));
    }
    static LiteralArgumentBuilder<CommandSource> wearHoe() {
        return Commands.literal("wear_hoe").then(Commands.argument("villager", EntityArgument.entity())
            .then(Commands.argument("remaining", IntegerArgumentType.integer(1))
            .executes(c -> {
                VillagerEntity v = villager(c); if (v == null) return 0;
                VillagerAgentData a = VillagerAgentManager.getOrCreateAgent(v);
                int remaining = IntegerArgumentType.getInteger(c, "remaining");
                for (ItemStack stack : a.getInventory().getItems()) {
                    if (!(stack.getItem() instanceof HoeItem)) continue;
                    if (remaining > stack.getMaxDamage()) return fail(c, "剩余耐久超过这把锄头的最大耐久。");
                    stack.setDamageValue(stack.getMaxDamage() - remaining);
                    VillagerEquipmentHelper.refreshEquipment(v, a);
                    return ok(c, "已设置第一把库存锄头剩余耐久：" + remaining);
                }
                return fail(c, "村民背包没有锄头。");
            })));
    }
    static LiteralArgumentBuilder<CommandSource> till() {
        return Commands.literal("till").then(Commands.argument("villager", EntityArgument.entity())
            .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                VillagerEntity v = villager(c); if (v == null) return 0;
                VillagerAgentData a = VillagerAgentManager.getOrCreateAgent(v);
                BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
                boolean repair = FarmingAction.isRevertedFarmland(c.getSource().getLevel(), pos);
                if ((!repair && !FarmingAction.isCultivatableDirt(c.getSource().getLevel(), pos))
                        || a.getCapabilityPool().levelOf(CapabilityId.FARMING) < (repair ? 1 : 2)
                        || !FarmingAction.hasHoe(a)) return fail(c, "需要锄头、足够耕作等级及与已有耕地相连的有效泥土。");
                AgentGoal goal = new AgentGoal("till", "Player requested till", 9);
                goal.setTargetLocation(pos);
                return start(c, v, a, goal);
            })));
    }
    private static int item(CommandContext<CommandSource> c, String command) throws CommandSyntaxException {
        VillagerEntity v = villager(c); if (v == null) return 0;
        VillagerAgentData a = VillagerAgentManager.getOrCreateAgent(v);
        ItemStack stack = ItemArgument.getItem(c, "item").createItemStack(IntegerArgumentType.getInteger(c, "count"), false);
        if (stack.isEmpty()) return fail(c, "物品不能为空。");
        if ("give".equals(command)) {
            if (InventoryTransfer.spaceFor(a.getInventory(), stack) < stack.getCount()) return fail(c, "村民背包空间不足。");
            InventoryTransfer.insert(a.getInventory(), stack);
            VillagerEquipmentHelper.refreshEquipment(v, a);
            return ok(c, "已放入村民真实背包：" + stack.getCount() + " × " + stack.getHoverName().getString());
        }
        String id = stack.getItem().getRegistryName().toString();
        // Damageable ItemStacks acquire default Damage NBT during construction. Inspect the
        // parsed command argument instead, so only explicitly supplied NBT is rejected.
        boolean explicitNbt = c.getNodes().stream().anyMatch(node -> "item".equals(node.getNode().getName())
                && node.getRange().get(c.getInput()).indexOf('{') >= 0);
        if (explicitNbt) return fail(c, "合成目标只接受物品 ID；产物 NBT 由配方决定。");
        if (!CraftingPlan.supports(c.getSource().getLevel(), stack.getItem())) return fail(c, "该目标没有支持的工作台合成配方。");
        if (!a.getUnlockedRecipes().contains(id) && !SkillCraftCatalog.isUnlockedFor(a.getCapabilityPool(), a.getProfession(), id))
            return fail(c, "村民尚未解锁这个物品的配方。");
        AgentGoal goal = new AgentGoal("craft", "Player requested " + id, 9);
        goal.setTargetItem(id); goal.setTargetQuantity(stack.getCount());
        return start(c, v, a, goal);
    }
    private static VillagerEntity villager(CommandContext<CommandSource> c) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(c, "villager");
        if (!(e instanceof VillagerEntity) || !e.isAlive() || e.level != c.getSource().getLevel()
                || ((VillagerEntity)e).isBaby() || ((VillagerEntity)e).isNoAi() || TradeDemo.isActive(e.getUUID())) {
            fail(c, "请选择同维度、非 NoAI、未在交易中的成年村民。"); return null;
        }
        return (VillagerEntity)e;
    }
    private static int start(CommandContext<CommandSource> c, VillagerEntity v, VillagerAgentData a, AgentGoal goal) {
        if (!ModConfig.ENABLE_AI_AGENTS.get() || !ModConfig.ENABLE_WORLD_INTERACTION.get())
            return fail(c, "请先开启 aiagents 和 worldinteraction。");
        ActionLifecycle.cancel(a, v, c.getSource().getLevel());
        goal.setSource(AgentGoal.Source.PLAYER); goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        a.getGoals().add(goal); a.setRunningGoal(goal);
        a.brain().goalStartedTick = c.getSource().getLevel().getGameTime();
        a.setCurrentActivity(goal.getGoalType());
        return ok(c, "任务已开始：" + goal.getDescription() + "；缺材料或工作台时会等待/请求补给。");
    }
    private static int fail(CommandContext<CommandSource> c, String text) {
        c.getSource().sendFailure(new StringTextComponent(text)); return 0;
    }
    private static int ok(CommandContext<CommandSource> c, String text) {
        c.getSource().sendSuccess(new StringTextComponent(text), false); return 1;
    }
    private VillagerControlCommands() {}
}
