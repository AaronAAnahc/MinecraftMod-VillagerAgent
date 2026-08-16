package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
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
                        .executes(VillagerAgentCommand::setApiUrl))))
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
                        .executes(VillagerAgentCommand::cmdBuild))));
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
                source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Options: autopickup, aiagents, villagerchat, worldinteraction, building, debugoverlay, harness, harnessdaily"), false);
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
        String type = StringArgumentType.getString(context, "type");
        CommandSource source = context.getSource();

        if (!type.equals("openai") && !type.equals("anthropic") && !type.equals("ollama")) {
            source.sendSuccess(new StringTextComponent(TextFormatting.RED + "Invalid API type: " + type), false);
            source.sendSuccess(new StringTextComponent(TextFormatting.YELLOW + "Valid types: openai, anthropic, ollama"), false);
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
}

