package com.github.AaronAA0721.villageragent.events;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.debug.DebugSync;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.github.AaronAA0721.villageragent.network.SyncVillagerDataPacket;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.PistonEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles all Minecraft events related to villager AI agents
 */
public class VillagerEventHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * When a villager spawns or joins the world, create an AI agent for it
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;

        if (event.getEntity() instanceof VillagerEntity) {
            VillagerEntity villager = (VillagerEntity) event.getEntity();
            if (!event.getWorld().isClientSide) {
                VillagerAgentData agent = VillagerAgentManager.getOrCreateAgent(villager);
                // Update profession from the actual villager entity
                updateVillagerProfession(villager, agent);

                // Display the agent's name above the villager's head
                villager.setCustomName(new StringTextComponent(agent.getName()));
                villager.setCustomNameVisible(true);

                // Mirror best armor from inventory → vanilla equipment slots (for rendering & damage reduction)
                VillagerEquipmentHelper.refreshEquipment(villager, agent);

                LOGGER.info("AI Agent created for villager: " + villager.getUUID()
                        + " (" + agent.getName() + ", " + agent.getProfession() + ")");
            }
        }
    }

    /**
     * Get the profession name from a villager entity
     */
    private String getVillagerProfessionName(VillagerEntity villager) {
        String professionKey = villager.getVillagerData().getProfession().toString();
        // Convert "minecraft:farmer" to "Farmer"
        if (professionKey.contains(":")) {
            professionKey = professionKey.substring(professionKey.indexOf(":") + 1);
        }
        // Capitalize first letter
        if (!professionKey.isEmpty()) {
            professionKey = professionKey.substring(0, 1).toUpperCase() + professionKey.substring(1).toLowerCase();
        }
        // Handle "none" profession
        if ("None".equals(professionKey)) {
            professionKey = "Unemployed Villager";
        }
        return professionKey;
    }

    /**
     * Update the agent's profession from the villager entity
     */
    private void updateVillagerProfession(VillagerEntity villager, VillagerAgentData agent) {
        String profession = getVillagerProfessionName(villager);
        agent.updateProfession(profession);
    }
    
    /**
     * When a villager dies, drop all items from inventory and remove AI agent data
     */
    @SubscribeEvent
    public void onVillagerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof VillagerEntity) {
            VillagerEntity villager = (VillagerEntity) event.getEntity();
            VillagerAgentData agent = VillagerAgentManager.getAgent(villager.getUUID());

            if (agent != null && !villager.level.isClientSide) {
                // Drop all items from the villager's inventory
                for (int i = 0; i < agent.getInventory().getItems().size(); i++) {
                    net.minecraft.item.ItemStack stack = agent.getInventory().getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        villager.spawnAtLocation(stack);
                        LOGGER.debug("Villager " + agent.getName() + " dropped: " + stack.getCount() + "x " + stack.getItem().getRegistryName());
                    }
                }

                LOGGER.info("Villager " + agent.getName() + " died and dropped all items");
            }

            VillagerAgentManager.removeAgent(villager.getUUID());
            LOGGER.info("AI Agent removed for villager: " + villager.getUUID());
        }
    }
    
    /**
     * Update all AI agents every tick and handle item pickup
     */
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;

        if (event.phase == TickEvent.Phase.END && !event.world.isClientSide) {
            // Slow tick — goals, restocking, AI decisions (gated by AGENT_THINK_INTERVAL)
            VillagerAgentManager.tickAgents(event.world);

            // Fast tick — farming state machine runs every tick for responsive walking/acting
            VillagerAgentManager.tickFarming(event.world);

            // Fast tick — combat state machine: scan for threats, chase, attack
            VillagerAgentManager.tickCombat(event.world);

            // Fast tick — world building: place/break blocks, drive BuildJobs
            VillagerAgentManager.tickBuilding(event.world);

            // Social tick — villager-to-villager conversations (internally gated to ~10 s)
            VillagerAgentManager.tickSocial(event.world);

            // Thought bubbles — LLM inner thoughts broadcast near players (gated per villager)
            VillagerAgentManager.tickThoughts(event.world);

            // Player greetings — villager says hello when player walks within 8 blocks
            VillagerAgentManager.tickGreeting(event.world);

            // Debug overlay — stream perception/agent snapshots to clients (gated by config)
            DebugSync.tick((ServerWorld) event.world);

            // Item attraction & pickup — runs every tick for smooth item magnetism
            handleVillagerItemPickup(event.world);
        }
    }

    /**
     * Make villagers pick up nearby items automatically using item attraction.
     * Items are smoothly pulled toward villagers and collected when they overlap
     * the villager's bounding box (like player pickup). Runs every tick.
     */
    private void handleVillagerItemPickup(net.minecraft.world.World world) {
        if (!ModConfig.ENABLE_AUTO_PICKUP.get()) return;

        try {
            // Only process if there are agents
            if (VillagerAgentManager.getAgentCount() == 0) return;

            // Iterate through all agents
            for (VillagerAgentData agent : VillagerAgentManager.getAllAgents()) {
                if (agent == null) continue;

                // Find the villager entity using ServerWorld
                if (!(world instanceof net.minecraft.world.server.ServerWorld)) continue;
                net.minecraft.world.server.ServerWorld serverWorld = (net.minecraft.world.server.ServerWorld) world;

                net.minecraft.entity.Entity entity = serverWorld.getEntity(agent.getVillagerId());
                if (!(entity instanceof VillagerEntity)) continue;

                VillagerEntity villager = (VillagerEntity) entity;
                if (!villager.isAlive()) continue;

                // Use the new item attraction system
                // Items are attracted to villager and automatically picked up
                ItemAttractionSystem.processItemAttraction(villager, world, agent);
            }
        } catch (Exception e) {
            LOGGER.error("Error in villager item pickup: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle player interaction with villagers
     * Right-click = Open Chat GUI with Trade Panel
     * Players can NO LONGER directly access villager inventory - must trade!
     */
    @SubscribeEvent
    public void onPlayerInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof VillagerEntity)) return;
        if (event.getHand() != Hand.MAIN_HAND) return;

        // Only process on server side to prevent double triggering
        if (event.getWorld().isClientSide) return;

        VillagerEntity villager = (VillagerEntity) event.getTarget();
        PlayerEntity player = event.getPlayer();
        VillagerAgentData agent = VillagerAgentManager.getAgent(villager.getUUID());

        if (agent == null) return;
        if (!ModConfig.ENABLE_VILLAGER_CHAT.get()) return;

        // Cancel vanilla villager interaction (trade menu)
        event.setCanceled(true);
        event.setResult(Event.Result.DENY);

        // Update profession from the actual villager entity (in case it changed)
        updateVillagerProfession(villager, agent);

        // Clear held item when talking — villager puts away tools/weapons
        VillagerAgentManager.equipEmptyHand(villager);

        if (player instanceof ServerPlayerEntity) {
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            String playerName = player.getName().getString();

            // Collect villager inventory items for display (read-only)
            List<ItemStack> inventoryItems = new ArrayList<>();
            for (ItemStack item : agent.getInventory().getItems()) {
                if (!item.isEmpty()) {
                    inventoryItems.add(item.copy());
                }
            }

            // Collect equipped armor (HEAD, CHEST, LEGS, FEET) from entity slots
            List<ItemStack> armorItems = new ArrayList<>();
            armorItems.add(villager.getItemBySlot(EquipmentSlotType.HEAD).copy());
            armorItems.add(villager.getItemBySlot(EquipmentSlotType.CHEST).copy());
            armorItems.add(villager.getItemBySlot(EquipmentSlotType.LEGS).copy());
            armorItems.add(villager.getItemBySlot(EquipmentSlotType.FEET).copy());

            // Send villager data to client to open chat GUI
            SyncVillagerDataPacket syncPacket = new SyncVillagerDataPacket(
                    villager.getUUID(),
                    agent.getName(),
                    agent.getProfession(),
                    agent.getPersonality(),
                    inventoryItems,
                    armorItems
            );
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), syncPacket);

            // NO automatic greeting - wait for player to say something first
            // Only reply when player sends a message

            // Log the interaction
            agent.addMemory("Started conversation with player " + playerName);
            LOGGER.info("Player " + playerName + " opened chat with " + agent.getProfession() + " " + agent.getName());
        }
    }

    /**
     * Load agent data when world loads
     */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        ServerWorld serverWorld = (ServerWorld) event.getWorld();
        VillagerAgentSavedData savedData = VillagerAgentSavedData.get(serverWorld);
        LOGGER.info("Loaded villager agent data from world save");
    }

    /**
     * Save agent data when world saves
     */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        ServerWorld serverWorld = (ServerWorld) event.getWorld();
        VillagerAgentSavedData savedData = VillagerAgentSavedData.get(serverWorld);
        savedData.setDirty(true);
        LOGGER.info("Saved villager agent data to world save");
    }

    /**
     * A chunk just loaded: hand every not-yet-resolved bed in it to the shared
     * {@link WorldStructureIndex}. The beds come straight from the chunk's
     * block-entity table (free — no scan), and each one is flood-filled at most
     * once by {@code WorldStructureIndex.processPending}. This is the primary,
     * event-driven seeding of the building index (no periodic world sweep).
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        WorldStructureIndex.instance((ServerWorld) event.getWorld()).indexChunk(event.getChunk());
    }

    /**
     * A block was placed: if it is a bed, queue it for house detection; otherwise
     * let the index react to the edit (a wall built around an open bed may now
     * enclose it, or an edit may have broken a known building).
     */
    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        BlockPos pos = event.getPos();
        WorldStructureIndex index = WorldStructureIndex.instance((ServerWorld) event.getWorld());
        if (event.getState().is(BlockTags.BEDS)) {
            index.offerBed(pos);
        } else {
            index.onBlockChanged(pos);
        }
    }

    /**
     * A block was broken: if it was a bed, drop whatever it anchored and forget it;
     * otherwise let the index react to the edit.
     */
    @SubscribeEvent
    public void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        BlockPos pos = event.getPos();
        WorldStructureIndex index = WorldStructureIndex.instance((ServerWorld) event.getWorld());
        if (event.getState().is(BlockTags.BEDS)) {
            index.onBedRemoved(pos);
        } else {
            index.onBlockChanged(pos);
        }
    }

    /**
     * A piston just moved a column of blocks. This does NOT fire BlockEvent.EntityPlaceEvent /
     * BreakEvent, so the building index would otherwise go stale — the exact reason
     * {@code markDirty} existed but had no callers (G3). Invalidate the pushed/pulled column so
     * any overlapping building is re-detected and nearby rejected beds get a second chance.
     */
    @SubscribeEvent
    public void onPistonPost(PistonEvent.Post event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        Direction dir = event.getDirection();
        BlockPos pos = event.getPos();
        // Piston head + the column it just moved (pistons push up to 12 blocks). A generous box
        // is fine — markDirty only re-floods beds that overlap it.
        BlockPos head = pos.offset(dir.getStepX(), dir.getStepY(), dir.getStepZ());
        BlockPos far = head.offset(dir.getStepX() * 12, dir.getStepY() * 12, dir.getStepZ() * 12);
        AxisAlignedBB region = new AxisAlignedBB(pos).minmax(new AxisAlignedBB(far));
        WorldStructureIndex.instance((ServerWorld) event.getWorld()).markDirty(region);
    }

    /**
     * An explosion just destroyed a set of blocks (again, no BlockEvent place/break fires for it).
     * Invalidate the bounding box of the affected blocks so buildings are re-detected.
     */
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected == null || affected.isEmpty()) return;
        AxisAlignedBB region = new AxisAlignedBB(affected.get(0));
        for (BlockPos p : affected) {
            region = region.minmax(new AxisAlignedBB(p));
        }
        WorldStructureIndex.instance((ServerWorld) event.getWorld()).markDirty(region.inflate(2));
    }

    /**
     * A fluid (water/lava) flowed and placed a new source-ish block — also outside the
     * place/break events. Invalidate a small region around the new liquid so any building it
     * floods into is re-detected.
     */
    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (event.getWorld().isClientSide()) return;

        BlockPos pos = event.getLiquidPos();
        WorldStructureIndex.instance((ServerWorld) event.getWorld()).markDirty(new AxisAlignedBB(pos).inflate(3));
    }

    /**
     * A world-edit command (/setblock, /fill, /clone) just changed blocks without firing
     * BlockEvent.EntityPlaceEvent/BreakEvent — the last gap in the "block change" invalidation.
     * We can't cheaply parse the exact affected region, so invalidate a generous box around the
     * command source. markDirty is idempotent: over-invalidating only costs a re-flood.
     *
     * <p>Note: a command-placed BED still won't be offered as a seed (that would need parsing the
     * args), but existing buildings near the edit won't go stale.
     */
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;

        String raw = event.getParseResults().getReader().getString();
        if (raw == null) return;
        String c = raw.trim().toLowerCase();
        if (!c.startsWith("/setblock") && !c.startsWith("/fill") && !c.startsWith("/clone")) return;

        Entity e = event.getParseResults().getContext().getSource().getEntity();
        if (!(e instanceof ServerPlayerEntity)) return; // only player-run edits (skip command blocks)
        ServerWorld world = (ServerWorld) e.level;
        WorldStructureIndex.instance(world).markDirty(new AxisAlignedBB(e.blockPosition()).inflate(48));
    }
}

