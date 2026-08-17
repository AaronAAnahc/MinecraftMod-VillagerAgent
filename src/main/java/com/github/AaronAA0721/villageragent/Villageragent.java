package com.github.AaronAA0721.villageragent;

import com.github.AaronAA0721.villageragent.commands.VillagerAgentCommand;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.github.AaronAA0721.villageragent.ai.proficiency.ProficiencyCapability;
import com.github.AaronAA0721.villageragent.events.VillagerEventHandler;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import net.minecraft.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.stream.Collectors;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("villageragent")
public class Villageragent {

    public static final String MOD_ID = "villageragent";

    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();

    public Villageragent() {
        // Register the setup method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        // Register the enqueueIMC method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueueIMC);
        // Register the processIMC method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::processIMC);
        // Register the doClientStuff method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::doClientStuff);

        // Register configuration
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC, "villageragent-common.toml");

        // Register event handler for villager AI
        MinecraftForge.EVENT_BUS.register(new VillagerEventHandler());

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        // Initialize VillagerAgent mod
        LOGGER.info("===========================================");
        LOGGER.info("VillagerAgent Mod - AI-Powered Villagers");
        LOGGER.info("===========================================");
        LOGGER.info("Initializing AI agent system...");
        LOGGER.info("LLM API Type: " + ModConfig.LLM_API_TYPE.get());
        LOGGER.info("AI Agents Enabled: " + ModConfig.ENABLE_AI_AGENTS.get());
        LOGGER.info("Villager Chat Enabled: " + ModConfig.ENABLE_VILLAGER_CHAT.get());
        LOGGER.info("World Interaction Enabled: " + ModConfig.ENABLE_WORLD_INTERACTION.get());
        LOGGER.info("===========================================");

        // Register the villager proficiency (experience / level) capability before any world loads.
        ProficiencyCapability.register();
        LOGGER.info("VillagerAgent proficiency capability registered!");

        // Extract editable config files into <config>/villageragent/ on first run (only if absent),
        // so players / modpacks can tune recipes, the proficiency table, and per-level unlocks.
        VillagerAgentConfigDir.ensureDir();
        VillagerAgentConfigDir.extractDefault("custom_recipes.json");
        VillagerAgentConfigDir.extractDefault("proficiency.json");
        LOGGER.info("VillagerAgent editable configs are in config/villageragent/");

        // Register network packets
        event.enqueueWork(() -> {
            ModNetworking.register();
            LOGGER.info("VillagerAgent network packets registered!");
        });
    }

    private void doClientStuff(final FMLClientSetupEvent event) {
        // do something that can only be done on the client
        LOGGER.info("Got game settings {}", event.getMinecraftSupplier().get().options);
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {
        // some example code to dispatch IMC to another mod
        InterModComms.sendTo("villageragent", "helloworld", () -> {
            LOGGER.info("Hello world from the MDK");
            return "Hello world";
        });
    }

    private void processIMC(final InterModProcessEvent event) {
        // some example code to receive and process InterModComms from other mods
        LOGGER.info("Got IMC {}", event.getIMCStream().map(m -> m.getMessageSupplier().get()).collect(Collectors.toList()));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        // do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // Register commands
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VillagerAgentCommand.register(event.getDispatcher());
        LOGGER.info("VillagerAgent commands registered!");
    }

    // You can use EventBusSubscriber to automatically subscribe events on the contained class (this is subscribing to the MOD
    // Event bus for receiving Registry Events)
    @Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class RegistryEvents {
        @SubscribeEvent
        public static void onBlocksRegistry(final RegistryEvent.Register<Block> blockRegistryEvent) {
            // register a new block here
            LOGGER.info("HELLO from Register Block");
        }
    }
}

