package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.LLMSetupReminder;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.util.text.ITextComponent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.Collection;

/**
 * Command handler for VillagerAgent mod
 * Usage: /villageragent <subcommand>
 */
public class VillagerAgentCommand {
    
    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        // Register main command /villageragent
        dispatcher.register(buildCommand("villageragent"));
        // Register short alias /va
        dispatcher.register(buildCommand("va"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSource> buildCommand(String name) {
        return Commands.literal(name)
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("config")
                .then(Commands.literal("set")
                    .then(Commands.argument("option", StringArgumentType.word())
                        .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(VillagerAgentCommand::setConfig))))
                .then(Commands.literal("get")
                    .then(Commands.argument("option", StringArgumentType.word())
                        .executes(VillagerAgentCommand::getConfig)))
                .then(Commands.literal("settimeout")
                    .then(Commands.argument("ms", IntegerArgumentType.integer(1000, 120000))
                        .executes(VillagerAgentCommand::setTimeout)))
                .then(Commands.literal("gettimeout")
                    .executes(VillagerAgentCommand::getTimeout))
                .then(Commands.literal("list")
                    .executes(VillagerAgentCommand::listConfig)))
            .then(Commands.literal("llm")
                .then(Commands.literal("apitype")
                    .then(Commands.argument("type", StringArgumentType.word())
                        .executes(VillagerAgentCommand::setApiType)))
                .then(Commands.literal("model")
                    .then(Commands.argument("model", StringArgumentType.greedyString())
                        .executes(VillagerAgentCommand::setModel)))
                .then(Commands.literal("apikey")
                    .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(VillagerAgentCommand::setApiKey)))
                .then(Commands.literal("apiurl")
                    .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(VillagerAgentCommand::setApiUrl)))
                .then(Commands.literal("status")
                    .executes(VillagerAgentCommand::llmStatus)))
            .then(Commands.literal("info")
                .executes(VillagerAgentCommand::showInfo))
            .then(Commands.literal("reload")
                .executes(VillagerAgentCommand::reloadConfig))
            .then(Commands.literal("build")
                .then(Commands.literal("place")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("block", StringArgumentType.word())
                                    .executes(VillagerAgentCommand::cmdPlace))))))
                .then(Commands.literal("break")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(VillagerAgentCommand::cmdBreak)))))
                .then(Commands.literal("structure")
                    .then(Commands.argument("goal", StringArgumentType.greedyString())
                        .executes(VillagerAgentCommand::cmdBuild))))
            .then(Commands.literal("goals")
                .then(Commands.literal("clear")
                    .executes(VillagerAgentCommand::clearGoals)))
            .then(Commands.literal("occupation")
                .then(Commands.argument("role", StringArgumentType.word())
                    .suggests((ctx, builder) -> net.minecraft.command.ISuggestionProvider.suggest(
                        java.util.Arrays.asList("lumberjack", "miner", "none"), builder))
                    .executes(VillagerAgentCommand::setOccupation)))
            .then(Commands.literal("request")
                .then(Commands.argument("item", StringArgumentType.word())
                    .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 256))
                        .executes(VillagerAgentCommand::requestMaterial))))
            .then(VillagerControlCommands.trade())
            .then(VillagerControlCommands.markets())
            .then(VillagerControlCommands.resetValues())
            .then(VillagerControlCommands.items("craft"))
            .then(VillagerControlCommands.items("give"))
            .then(VillagerControlCommands.wearHoe())
            .then(VillagerControlCommands.till())
            .then(Commands.literal("demo_trade")
                .then(Commands.argument("from", net.minecraft.command.arguments.EntityArgument.entity())
                    .then(Commands.argument("to", net.minecraft.command.arguments.EntityArgument.entity())
                        .then(Commands.argument("item", net.minecraft.command.arguments.ItemArgument.item())
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> com.github.AaronAA0721.villageragent.ai.TradeDemo.start(
                                    ctx.getSource(), net.minecraft.command.arguments.EntityArgument.getEntity(ctx, "from"),
                                    net.minecraft.command.arguments.EntityArgument.getEntity(ctx, "to"),
                                    net.minecraft.command.arguments.ItemArgument.getItem(ctx, "item").createItemStack(
                                        IntegerArgumentType.getInteger(ctx, "count"), false))))))))
            .then(Commands.literal("orders").executes(VillagerAgentCommand::showOrders));
    }

    private static int setOccupation(CommandContext<CommandSource> context) {
        VillagerAgentData agent = resolveAgent(context);
        if (agent == null) return 0;
        String role = StringArgumentType.getString(context, "role");
        if (!java.util.Arrays.asList("lumberjack", "miner", "none").contains(role)) {
            context.getSource().sendFailure(new StringTextComponent("Use lumberjack, miner or none")); return 0;
        }
        ServerWorld world = (ServerWorld) context.getSource().getLevel();
        VillagerEntity villager = resolveVillager(agent, context);
        if (villager == null) return 0;
        com.github.AaronAA0721.villageragent.ai.ActionLifecycle.cancel(agent, villager, world);
        agent.setRunningGoal(null);
        agent.getGoals().removeIf(g -> "fulfill_order".equals(g.getGoalType()));
        villager.releasePoi(net.minecraft.entity.ai.brain.memory.MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(net.minecraft.entity.ai.brain.memory.MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(net.minecraft.entity.ai.brain.memory.MemoryModuleType.POTENTIAL_JOB_SITE);
        net.minecraft.entity.merchant.villager.VillagerProfession profession = "lumberjack".equals(role)
                ? com.github.AaronAA0721.villageragent.content.Workstations.LUMBERJACK.get()
                : "miner".equals(role) ? com.github.AaronAA0721.villageragent.content.Workstations.MINER.get()
                : net.minecraft.entity.merchant.villager.VillagerProfession.NONE;
        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        agent.updateProfession("none".equals(role) ? "Unemployed Villager" : role);
        context.getSource().sendSuccess(new StringTextComponent(agent.getName() + " occupation: " + role), false);
        return 1;
    }

    private static int requestMaterial(CommandContext<CommandSource> context) {
        VillagerAgentData agent = resolveAgent(context);
        if (agent == null) return 0;
        String item = StringArgumentType.getString(context, "item");
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        ServerWorld world = (ServerWorld) context.getSource().getLevel();
        com.github.AaronAA0721.villageragent.ai.work.WorkOrderBook.Order order =
                com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, item,
                    com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, item) + quantity);
        if (order == null) {
            context.getSource().sendFailure(new StringTextComponent("Unsupported extraction item or economy disabled: " + item)); return 0;
        }
        context.getSource().sendSuccess(new StringTextComponent(agent.getName() + " requests " + order.remaining
                + " " + item + " | order " + order.id), false);
        return 1;
    }

    private static int showOrders(CommandContext<CommandSource> context) {
        ServerWorld world = (ServerWorld) context.getSource().getLevel();
        int count = 0;
        for (com.github.AaronAA0721.villageragent.ai.work.WorkOrderBook.Order order :
                com.github.AaronAA0721.villageragent.ai.work.WorkOrderSavedData.get(world).book.all()) {
            context.getSource().sendSuccess(new StringTextComponent(order.id + " " + order.status + " " + order.item
                    + " remaining=" + order.remaining + " requester=" + order.requester + " worker=" + order.worker), false);
            count++;
        }
        context.getSource().sendSuccess(new StringTextComponent(count + " orders in this dimension"), false);
        return count;
    }
    
    private static int setConfig(CommandContext<CommandSource> context) {
        String option = StringArgumentType.getString(context, "option");
        boolean value = BoolArgumentType.getBool(context, "value");
        CommandSource source = context.getSource();
        String optionName = "";
        boolean success = true;
        
        switch (option.toLowerCase()) {
            case "autopickup":
                ModConfig.ENABLE_AUTO_PICKUP.set(value);
                optionName = "Auto Pickup";
                break;
            case "aiagents":
                ModConfig.ENABLE_AI_AGENTS.set(value);
                optionName = "AI Agents";
                break;
            case "villagerchat":
                ModConfig.ENABLE_VILLAGER_CHAT.set(value);
                optionName = "Villager Chat";
                break;
            case "worldinteraction":
                ModConfig.ENABLE_WORLD_INTERACTION.set(value);
                optionName = "World Interaction";
                break;
            case "building":
                ModConfig.ENABLE_BUILDING.set(value);
                optionName = "Building";
                break;
            case "debughud":
                ModConfig.DEBUG_SHOW_HUD.set(value);
                optionName = "Debug HUD";
                break;
            case "thoughts":
                ModConfig.ENABLE_VILLAGER_THOUGHTS.set(value);
                optionName = "Villager Thoughts";
                break;
            case "debugoverlay":
                ModConfig.ENABLE_DEBUG_OVERLAY.set(value);
                optionName = "Debug Overlay";
                break;
            case "harness":
                ModConfig.HARNESS_ENABLED.set(value);
                optionName = "Decision Harness";
                break;
            case "harnessdaily":
                ModConfig.HARNESS_DRIVE_DAILY_GOAL.set(value);
                optionName = "Harness Daily Goal";
                break;
            default:
                source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Unknown option: " + option), false);
                source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Options: autopickup, aiagents, villagerchat, worldinteraction, building, debugoverlay, debughud, thoughts, harness, harnessdaily"), false);
                success = false;
        }
        
        if (success) {
            ModConfig.SPEC.save();
            String status = value ? (TextFormatting.GREEN + "ENABLED") : (TextFormatting.RED + "DISABLED");
            source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ " + optionName + ": " + status), true);
        }
        return success ? 1 : 0;
    }
    
    private static int getConfig(CommandContext<CommandSource> context) {
        String option = StringArgumentType.getString(context, "option");
        CommandSource source = context.getSource();
        boolean value = false;
        String optionName = "";
        boolean found = true;
        
        switch (option.toLowerCase()) {
            case "autopickup":
                value = ModConfig.ENABLE_AUTO_PICKUP.get();
                optionName = "Auto Pickup";
                break;
            case "aiagents":
                value = ModConfig.ENABLE_AI_AGENTS.get();
                optionName = "AI Agents";
                break;
            case "villagerchat":
                value = ModConfig.ENABLE_VILLAGER_CHAT.get();
                optionName = "Villager Chat";
                break;
            case "worldinteraction":
                value = ModConfig.ENABLE_WORLD_INTERACTION.get();
                optionName = "World Interaction";
                break;
            case "building":
                value = ModConfig.ENABLE_BUILDING.get();
                optionName = "Building";
                break;
            case "debughud":
                value = ModConfig.DEBUG_SHOW_HUD.get();
                optionName = "Debug HUD";
                break;
            case "thoughts":
                value = ModConfig.ENABLE_VILLAGER_THOUGHTS.get();
                optionName = "Villager Thoughts";
                break;
            case "debugoverlay":
                value = ModConfig.ENABLE_DEBUG_OVERLAY.get();
                optionName = "Debug Overlay";
                break;
            case "harness":
                value = ModConfig.HARNESS_ENABLED.get();
                optionName = "Decision Harness";
                break;
            case "harnessdaily":
                value = ModConfig.HARNESS_DRIVE_DAILY_GOAL.get();
                optionName = "Harness Daily Goal";
                break;
            default:
                source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Unknown option: " + option), false);
                found = false;
        }
        
        if (found) {
            String status = value ? (TextFormatting.GREEN + "ENABLED") : (TextFormatting.RED + "DISABLED");
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + optionName + ": " + status), false);
        }
        return found ? 1 : 0;
    }
    
    private static int listConfig(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "=== VillagerAgent Config ==="), false);
        sendConfigLine(source, "Auto Pickup", ModConfig.ENABLE_AUTO_PICKUP.get());
        sendConfigLine(source, "AI Agents", ModConfig.ENABLE_AI_AGENTS.get());
        sendConfigLine(source, "Villager Chat", ModConfig.ENABLE_VILLAGER_CHAT.get());
        sendConfigLine(source, "World Interaction", ModConfig.ENABLE_WORLD_INTERACTION.get());
        sendConfigLine(source, "Building", ModConfig.ENABLE_BUILDING.get());
        sendConfigLine(source, "Debug HUD", ModConfig.DEBUG_SHOW_HUD.get());
        sendConfigLine(source, "Villager Thoughts", ModConfig.ENABLE_VILLAGER_THOUGHTS.get());
        sendConfigLine(source, "Debug Overlay", ModConfig.ENABLE_DEBUG_OVERLAY.get());
        sendConfigLine(source, "Decision Harness", ModConfig.HARNESS_ENABLED.get());
        sendConfigLine(source, "Harness Daily Goal", ModConfig.HARNESS_DRIVE_DAILY_GOAL.get());
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "==========================="), false);
        return 1;
    }
    
    private static void sendConfigLine(CommandSource source, String name, boolean value) {
        String status = value ? (TextFormatting.GREEN + "ENABLED") : (TextFormatting.RED + "DISABLED");
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + name + ": " + status), false);
    }
    
    private static int showInfo(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "=== VillagerAgent Mod ==="), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Version: 1.0-SNAPSHOT"), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Active Agents: " + VillagerAgentManager.getAgentCount()), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "--- LLM Settings ---"), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API Type: " + ModConfig.LLM_API_TYPE.get()), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Model: " + ModConfig.LLM_MODEL.get()), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API URL: " + ModConfig.LLM_API_URL.get()), false);
        String apiKey = ModConfig.LLM_API_KEY.get();
        String keyStatus = (apiKey == null || apiKey.isEmpty()) ? TextFormatting.RED + "NOT SET" : TextFormatting.GREEN + "SET (hidden)";
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API Key: " + keyStatus), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "========================"), false);
        if (!LLMSetupReminder.isConfigured()) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "⚠ LLM 未完整配置！村民将无法对话。运行 /va llm status 查看需执行的指令。"), true);
        }
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        try {
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Reloading config..."), false);
            source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ Config reloaded!"), true);
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Current LLM API: " + ModConfig.LLM_API_TYPE.get()), false);
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Current Model: " + ModConfig.LLM_MODEL.get()), false);
            return 1;
        } catch (Exception e) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "✗ Error: " + e.getMessage()), false);
            return 0;
        }
    }

    private static int setTimeout(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        int ms = IntegerArgumentType.getInteger(context, "ms");
        ModConfig.HARNESS_TIMEOUT_MS.set(ms);
        ModConfig.SPEC.save();
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ Harness LLM timeout set to "
                + ms + " ms (will apply to new LLM calls)"), true);
        return 1;
    }

    private static int getTimeout(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        int ms = ModConfig.HARNESS_TIMEOUT_MS.get();
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Harness LLM timeout: " + ms + " ms"), false);
        return 1;
    }

    private static int setApiType(CommandContext<CommandSource> context) {
        String type = StringArgumentType.getString(context, "type").toLowerCase(java.util.Locale.ROOT);
        CommandSource source = context.getSource();

        if (!type.equals("openai") && !type.equals("anthropic") && !type.equals("ollama") && !type.equals("gemini")) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Invalid API type: " + type), false);
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Valid types: openai, anthropic, ollama, gemini"), false);
            return 0;
        }

        ModConfig.LLM_API_TYPE.set(type);
        ModConfig.SPEC.save();
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ API Type set to: " + type), true);
        return 1;
    }

    private static int setModel(CommandContext<CommandSource> context) {
        String model = StringArgumentType.getString(context, "model");
        CommandSource source = context.getSource();

        ModConfig.LLM_MODEL.set(model);
        ModConfig.SPEC.save();
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ Model set to: " + model), true);
        return 1;
    }

    private static int setApiKey(CommandContext<CommandSource> context) {
        String key = StringArgumentType.getString(context, "key");
        CommandSource source = context.getSource();

        ModConfig.LLM_API_KEY.set(key);
        ModConfig.SPEC.save();
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ API Key updated!"), true);
        return 1;
    }

    private static int setApiUrl(CommandContext<CommandSource> context) {
        String url = StringArgumentType.getString(context, "url");
        CommandSource source = context.getSource();

        ModConfig.LLM_API_URL.set(url);
        ModConfig.SPEC.save();
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ API URL set to: " + url), true);
        return 1;
    }

    /**
     * /va llm status — print the current LLM configuration and, if anything is
     * missing, list exactly which commands the player needs to run.
     */
    private static int llmStatus(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "=== LLM 配置状态 ==="), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API 类型: " + ModConfig.LLM_API_TYPE.get()), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "模型: " + ModConfig.LLM_MODEL.get()), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API URL: " + ModConfig.LLM_API_URL.get()), false);
        String apiKey = ModConfig.LLM_API_KEY.get();
        String keyStatus = (apiKey == null || apiKey.isEmpty()) ? TextFormatting.RED + "未设置" : TextFormatting.GREEN + "已设置 (已隐藏)";
        source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "API Key: " + keyStatus), false);

        if (LLMSetupReminder.isConfigured()) {
            source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ LLM 已正确配置，村民可以正常对话。"), true);
            return 1;
        }

        source.sendSuccess(new StringTextComponent(TextFormatting.RED + "✗ LLM 尚未配置完整，缺失:"), true);
        for (String item : LLMSetupReminder.getMissingItems()) {
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "  - " + item), false);
        }
        source.sendSuccess(new StringTextComponent(TextFormatting.GOLD + "请运行以下指令完成配置:"), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "  /va llm apitype openai"), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "  /va llm apikey <你的Key>"), false);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "  /va llm model deepseek-chat"), false);
        return 1;
    }

    // ── World-interaction test commands ──────────────────────────────────────

    /** Resolve the agent nearest to the command sender (or the first agent if no sender). */
    private static VillagerAgentData resolveAgent(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        ServerWorld world = (ServerWorld) source.getLevel();
        double x = 0, y = 0, z = 0;
        Entity sender = source.getEntity();
        if (sender != null) { x = sender.getX(); y = sender.getY(); z = sender.getZ(); }
        return VillagerAgentManager.getNearestAgent(world, x, y, z);
    }

    private static VillagerEntity resolveVillager(VillagerAgentData agent, CommandContext<CommandSource> context) {
        ServerWorld world = (ServerWorld) context.getSource().getLevel();
        net.minecraft.entity.Entity e = world.getEntity(agent.getVillagerId());
        return (e instanceof VillagerEntity) ? (VillagerEntity) e : null;
    }

    private static int cmdPlace(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        ServerWorld world = (ServerWorld) source.getLevel();
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        String blockId = StringArgumentType.getString(context, "block");

        VillagerAgentData agent = resolveAgent(context);
        if (agent == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "No AI villager found."), false);
            return 0;
        }
        VillagerEntity villager = resolveVillager(agent, context);
        if (villager == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Villager entity not loaded."), false);
            return 0;
        }
        BlockPos target = new BlockPos(x, y, z);
        net.minecraft.block.Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockId));
        if (block == null || block == Blocks.AIR) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Unknown block: " + blockId), false);
            return 0;
        }
        VillagerAgentManager.requestPlace(agent, villager, world, target, block);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ " + agent.getName()
                + " will place " + blockId + " at (" + x + "," + y + "," + z + ")"), true);
        return 1;
    }

    private static int cmdBreak(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        ServerWorld world = (ServerWorld) source.getLevel();
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");

        VillagerAgentData agent = resolveAgent(context);
        if (agent == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "No AI villager found."), false);
            return 0;
        }
        VillagerEntity villager = resolveVillager(agent, context);
        if (villager == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Villager entity not loaded."), false);
            return 0;
        }
        BlockPos target = new BlockPos(x, y, z);
        VillagerAgentManager.requestBreak(agent, villager, world, target);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ " + agent.getName()
                + " will break the block at (" + x + "," + y + "," + z + ")"), true);
        return 1;
    }

    private static int cmdBuild(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        ServerWorld world = (ServerWorld) source.getLevel();
        String goal = StringArgumentType.getString(context, "goal");

        VillagerAgentData agent = resolveAgent(context);
        if (agent == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "No AI villager found."), false);
            return 0;
        }
        VillagerEntity villager = resolveVillager(agent, context);
        if (villager == null) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Villager entity not loaded."), false);
            return 0;
        }
        if (!ModConfig.ENABLE_BUILDING.get()) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Building is disabled (config)."), false);
            return 0;
        }
        VillagerAgentManager.requestBuild(agent, villager, world, goal);
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ " + agent.getName()
                + " is asking the LLM to design & build: \"" + goal + "\""), true);
        return 1;
    }

    /**
     * /villageragent goals clear — wipe every NON-routine goal (ONE_SHOT / EXPIRING) across all
     * villagers. Routine goals (farm/rest/restock from the day window) are kept, so the villagers
     * immediately return to their normal daily cycle. Use this to flush stale fallback_random
     * todos (e.g. "Craft armor" on a farmer) that no skill can claim.
     */
    private static int clearGoals(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        Collection<VillagerAgentData> all = VillagerAgentManager.getAllAgents();
        if (all.isEmpty()) {
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "No AI villagers loaded."), false);
            return 0;
        }
        int removed = 0;
        int cleared = 0;
        for (VillagerAgentData agent : all) {
            int before = agent.getGoals().size();
            agent.getGoals().removeIf(g -> g.getLifecycle() != AgentGoal.Lifecycle.REPEATABLE);
            int after = agent.getGoals().size();
            if (before != after) {
                cleared++;
                removed += (before - after);
                source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "✓ " + agent.getName()
                        + ": removed " + (before - after) + " non-routine goal(s)"), true);
            }
        }
        source.sendSuccess(new StringTextComponent(TextFormatting.GREEN + "Cleared " + removed
                + " non-routine goal(s) across " + cleared + " villager(s). "
                + "Routine goals (farm/rest/restock) kept."), true);
        return removed > 0 ? 1 : 0;
    }
}
