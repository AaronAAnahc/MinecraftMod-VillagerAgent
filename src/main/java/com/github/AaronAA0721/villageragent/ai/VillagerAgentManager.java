package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.ai.world.WorldEventBus;
import com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor;
import com.github.AaronAA0721.villageragent.ai.behavior.FailureReason;
import com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill;
import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import com.github.AaronAA0721.villageragent.ai.behavior.VillagerSkill;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages all AI villager agents in the world
 */
public class VillagerAgentManager {
    private static final Random RANDOM = new Random();

    /**
     * How often (in ticks) the combat state machine runs.
     * Every 2 ticks for responsive chasing/attacking.
     */
    private static final int COMBAT_TICK_INTERVAL = 2;

    /**
     * How often (in ticks) idle villagers scan for threats.
     * Every 10 ticks ≈ 0.5 seconds.
     */
    private static final int COMBAT_SCAN_INTERVAL = 10;

    /** How many threat scans (~0.5 s each) a fleeing villager keeps shouting before giving up. */
    private static final int FLEE_GIVE_UP_SCANS = 30;

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<UUID, VillagerAgentData> agents = new ConcurrentHashMap<>();

    /**
     * Get or create agent data for a villager
     */
    public static VillagerAgentData getOrCreateAgent(VillagerEntity villager) {
        UUID id = villager.getUUID();
        return agents.computeIfAbsent(id, uuid -> {
            LOGGER.info("Creating new AI agent for villager: " + uuid);
            return new VillagerAgentData(uuid, true);
        });
    }

    /**
     * Get agent data if it exists
     */
    public static VillagerAgentData getAgent(UUID villagerId) {
        return agents.get(villagerId);
    }

    /**
     * Add an agent directly (used when loading from saved data)
     */
    public static void addAgent(UUID villagerId, VillagerAgentData agent) {
        agents.put(villagerId, agent);
        StringBuilder caps = new StringBuilder();
        for (CapabilityId id : CapabilityId.values()) {
            caps.append(id).append("=").append(agent.getCapabilityPool().levelOf(id)).append(" ");
        }
        LOGGER.info("[CAP-DIAG] Added AI agent " + villagerId
                + " profession=" + agent.getProfession()
                + " caps[" + caps + "]");
    }

    /**
     * Remove agent data (when villager dies or is removed)
     */
    public static void removeAgent(UUID villagerId) {
        agents.remove(villagerId);
        LOGGER.info("Removed AI agent: " + villagerId);
    }

    /**
     * Update all agents in the world (slow tick — goals, restocking, etc.)
     */
    public static void tickAgents(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;

        // Drive the shared, per-dimension building index: drain up to FLOODS_PER_TICK
        // queued bed flood-fills. Beds were queued by ChunkEvent.Load / block-place events,
        // so this just trickles the discovery work across ticks — no periodic world sweep.
        if (world instanceof ServerWorld) {
            ServerWorld sw = (ServerWorld) world;
            WorldStructureIndex.instance(world).processPending(world, WorldStructureIndex.FLOODS_PER_TICK);

            // Sleep healing — every tick, only touches villagers that are actually sleeping.
            tickSleepHealing(sw);

            // Forget stale rally alarms (village "shouts" that nobody answered).
            VillageSignalBoard.get().pruneExpired(sw);
        }

        long currentTime = world.getGameTime();
        int thinkInterval = ModConfig.AGENT_THINK_INTERVAL.get();

        for (Map.Entry<UUID, VillagerAgentData> entry : agents.entrySet()) {
            VillagerAgentData agent = entry.getValue();
            if (TradeDemo.isActive(agent.getVillagerId())) continue;

            VillagerEntity loaded = findVillagerEntity(world, agent.getVillagerId());
            if (loaded == null || !loaded.isAlive() || loaded.isBaby()) continue;
            VillagerNeedsSystem.tickFatigue((ServerWorld) world, loaded, agent);
            VillagerNavigationSupport.tick(loaded, (ServerWorld) world, agent);
            // Check this dimension before updating shared timestamps.
            if (currentTime - agent.getLastThinkTime() >= thinkInterval) {
                agent.setLastThinkTime(currentTime);
                WorkbenchMemory.observe(loaded, (ServerWorld) world);
                updateAgent(world, agent);
                CraftingPlan.unlockDependencies(agent, (ServerWorld) world);
                BrainLog.snapshot(agent, loaded, (ServerWorld) world, "after-think");
            }
            processGoals(loaded, agent);
        }
    }

    /**
     * Sleep healing — run every tick. Only touches villagers that are actually asleep
     * (isSleeping() is driven by vanilla / another module, not by this mod).
     */
    private static void tickSleepHealing(ServerWorld world) {
        for (VillagerAgentData agent : agents.values()) {
            if (TradeDemo.isActive(agent.getVillagerId())) continue;
            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;
            VillagerActivitySystem.applySleepHealing(world, villager);
            // Rest ends when fatigue is relieved, independent of daylight.
        }
    }

    /**
     * Fast tick for combat — runs every tick so chasing/attacking is responsive.
     * All villagers (not just farmers) will defend themselves against hostile mobs.
     */
    public static void tickCombat(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long currentTime = world.getGameTime();
        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            if (TradeDemo.isActive(agent.getVillagerId())) continue;
            // Tick attack cooldown every tick
            agent.tickAttackCooldown();

            // Check if this villager is already in a combat action
            VillagerAction current = agent.getCurrentAction();
            boolean inCombat = current != null && current.getActionType() == VillagerAction.ActionType.ATTACK;

            if (inCombat) {
                // Active combat — run every COMBAT_TICK_INTERVAL ticks
                if (currentTime % COMBAT_TICK_INTERVAL != 0) continue;
            } else {
                // Idle scan — run every COMBAT_SCAN_INTERVAL ticks
                if (currentTime % COMBAT_SCAN_INTERVAL != 0) continue;
            }

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            performCombatActions(villager, serverWorld, agent);
        }
    }

    /**
     * Combat state machine for a single villager.
     *
     * <p>States:
     * <ol>
     *   <li><b>Chasing</b> — walking toward a hostile target. Re-issue nav, check range.</li>
     *   <li><b>Attacking</b> — in melee range, deal damage on cooldown.</li>
     *   <li><b>Idle</b> — scan for nearby threats.</li>
     * </ol>
     */
    private static void performCombatActions(VillagerEntity villager, ServerWorld world, VillagerAgentData agent) {
        VillagerAction current = agent.getCurrentAction();

        // ── 1. Already in a combat action ──
        if (current != null && current.getActionType() == VillagerAction.ActionType.ATTACK) {
            continueCombatAction(villager, world, agent, current);
            return;
        }

        // ── 1b. Fleeing while raising a rally alarm ──
        if (current != null && current.getActionType() == VillagerAction.ActionType.FLEE) {
            continueFleeAction(villager, world, agent, current);
            return;
        }

        if (current != null && current.getActionType() == VillagerAction.ActionType.RALLY) {
            continueRallyAction(villager, world, agent, current);
            return;
        }

        // Threats and village alarms may interrupt ordinary work.

        // ── 3. Idle — scan for threats ──
            LivingEntity threat = CombatAction.findNearestThreat(villager, world);
        if (threat != null) {
            // Never fight alone: withdraw and wait for a physically assembled group.
            rallyOrFlee(villager, world, agent, threat);
            return;
        }

        // Distant calls never wake or divert villagers who should rest. Direct threats above
        // still trigger escape, so an exhausted villager does not sleep beside an attacker.
        if (!CombatAction.maySupport(villager, agent)) return;

        // ── 4. No direct threat — listen for a rally shout and come help ──
        VillageSignalBoard board = VillageSignalBoard.get();
        VillageSignalBoard.RallyCall rally = board.nearestRally(villager);
        if (rally != null) {
            Entity rallyThreat = world.getEntity(rally.threatUuid);
            if (rallyThreat instanceof LivingEntity && rallyThreat.isAlive()) {
                LivingEntity target = (LivingEntity) rallyThreat;
                board.respond(villager, agent, target);
                if (board.shouldCharge(target) && CombatAction.mayAttack(villager, agent)) {
                    startCombatAction(villager, agent, target);
                } else if (!CombatAction.mayAttack(villager, agent)) {
                    // Wounded/unarmed listeners must not approach the hostile rally point.
                    startFleeAction(villager, agent, target);
                } else {
                    ActionLifecycle.cancel(agent, villager, world);
                    agent.suspendRunningGoal();
                    VillagerAction response = new VillagerAction(VillagerAction.ActionType.RALLY, "Answering village alarm");
                    response.setTargetEntityId(target.getUUID());
                    response.setPhase(VillagerAction.ActionPhase.WALKING);
                    agent.setCurrentAction(response);
                    agent.setCurrentActivity("rallying");
                    continueRallyAction(villager, world, agent, response);
                }
            }
        }
    }

    private static void continueRallyAction(VillagerEntity villager, ServerWorld world,
                                            VillagerAgentData agent, VillagerAction action) {
        Entity entity = action.getTargetEntityId() == null ? null : world.getEntity(action.getTargetEntityId());
        if (!(entity instanceof LivingEntity) || !entity.isAlive()) {
            disengageCombat(villager, agent); return;
        }
        LivingEntity target = (LivingEntity) entity;
        if (!CombatAction.maySupport(villager, agent)) {
            if (villager.distanceToSqr(target) < CombatAction.FLEE_HOLD_DIST * CombatAction.FLEE_HOLD_DIST)
                startFleeAction(villager, agent, target);
            else disengageCombat(villager, agent);
            return;
        }
        VillageSignalBoard board = VillageSignalBoard.get();
        board.respond(villager, agent, target);
        if (!CombatAction.mayAttack(villager, agent)) {
            startFleeAction(villager, agent, target); return;
        }
        if (board.shouldCharge(target)) {
            startCombatAction(villager, agent, target); return;
        }
        if (villager.isSleeping()) villager.stopSleeping();
        if (villager.distanceToSqr(target) < CombatAction.FLEE_HOLD_DIST * CombatAction.FLEE_HOLD_DIST)
            CombatAction.flee(villager, target);
        else if (villager.distanceToSqr(target) > VillageSignalBoard.RALLY_READY_RADIUS * VillageSignalBoard.RALLY_READY_RADIUS
                || !villager.canSee(target))
            villager.getNavigation().moveTo(target, CombatAction.PANIC_SPEED);
        else villager.getNavigation().stop();
        action.incrementStuckTicks();
        if (action.getStuckTicks() > 120) disengageCombat(villager, agent);
    }

    /**
     * Start chasing a hostile target.
     */
    private static void startCombatAction(VillagerEntity villager, VillagerAgentData agent, LivingEntity target) {
        // A sleeping villager can't navigate — wake it before engaging.
        if (villager.isSleeping()) villager.stopSleeping();

        // Combat is a reactive override (Settlements-style PANIC): pause the plan so the running
        // goal waits instead of fighting the threat for the villager. It resumes on disengage.
        ActionLifecycle.cancel(agent, villager, (ServerWorld) villager.level);
        agent.suspendRunningGoal();

        VillagerAction action = new VillagerAction(VillagerAction.ActionType.ATTACK,
                "Attacking " + target.getType().getRegistryName());
        action.setTargetEntityId(target.getUUID());
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        agent.setCurrentAction(action);
        agent.setCurrentActivity("fighting");

        // Equip best weapon from inventory for damage and visual display
        CombatAction.findBestWeaponDamage(agent, villager);

        // Start walking toward the target (0.4 = reasonable combat speed,
        // faster than normal wander ~0.35 but not too fast)
        villager.getNavigation().moveTo(target, 0.4);

        agent.addMemory("Engaging hostile: " + target.getType().getRegistryName());
        LOGGER.debug("{} engaging {}", agent.getName(), target.getType().getRegistryName());
    }

    /**
     * Can't win solo → register on the village alarm and either flee (keeping the call alive) or,
     * if enough villagers have already answered, charge together.
     */
    private static void rallyOrFlee(VillagerEntity villager, ServerWorld world,
                                    VillagerAgentData agent, LivingEntity threat) {
        VillageSignalBoard board = VillageSignalBoard.get();
        board.respond(villager, agent, threat);
        if (board.shouldCharge(threat) && CombatAction.mayAttack(villager, agent)) {
            startCombatAction(villager, agent, threat);
        } else {
            startFleeAction(villager, agent, threat);
        }
    }

    /** Begin fleeing from a threat while raising the alarm (see {@link VillageSignalBoard}). */
    private static void startFleeAction(VillagerEntity villager, VillagerAgentData agent, LivingEntity threat) {
        // A sleeping villager can't navigate — wake it before fleeing (mirrors startCombatAction).
        if (villager.isSleeping()) villager.stopSleeping();

        ActionLifecycle.cancel(agent, villager, (ServerWorld) villager.level);
        agent.suspendRunningGoal(); // panic override pauses the plan
        VillagerAction action = new VillagerAction(VillagerAction.ActionType.FLEE,
                "Fleeing from " + threat.getType().getRegistryName() + " and calling for help");
        action.setTargetEntityId(threat.getUUID());
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        agent.setCurrentAction(action);
        agent.setCurrentActivity("fleeing");
        CombatAction.flee(villager, threat);
        LOGGER.debug("{} fleeing from {} and raising the alarm", agent.getName(), threat.getType().getRegistryName());
    }

    /**
     * Keep fleeing while the alarm is raised. Once enough villagers answer (see
     * {@link VillageSignalBoard#shouldCharge}), turn around and charge the threat together.
     * Give up after {@link #FLEE_GIVE_UP_SCANS} scans if nobody comes.
     */
    private static void continueFleeAction(VillagerEntity villager, ServerWorld world,
                                           VillagerAgentData agent, VillagerAction action) {
        UUID threatId = action.getTargetEntityId();
        if (threatId == null) { agent.setCurrentAction(null); agent.resumeSuspendedGoals(); return; }

        Entity raw = world.getEntity(threatId);
        if (!(raw instanceof LivingEntity) || !raw.isAlive()) {
            agent.setCurrentAction(null);
            agent.setCurrentActivity("idle");
            agent.resumeSuspendedGoals();
            LOGGER.debug("{} stopped fleeing (threat gone)", agent.getName());
            return;
        }

        LivingEntity threat = (LivingEntity) raw;
        if (!CombatAction.maySupport(villager, agent)
                && villager.distanceToSqr(threat) >= CombatAction.FLEE_HOLD_DIST * CombatAction.FLEE_HOLD_DIST) {
            disengageCombat(villager, agent);
            return;
        }
        VillageSignalBoard board = VillageSignalBoard.get();
        board.respond(villager, agent, threat); // keep the alarm alive while we kite

        if (board.shouldCharge(threat) && CombatAction.mayAttack(villager, agent)) {
            // Enough firepower AND this villager can fight — turn and mob the threat together.
            agent.setCurrentAction(null);
            startCombatAction(villager, agent, threat);
            return;
        }
        // else: not enough power yet, or this villager can't attack (bare hand / low health) →
        // keep kiting at a safe distance while the alarm stays alive (pin, not charge).

        action.incrementStuckTicks(); // +1 per scan (~0.5 s)
        if (action.getStuckTicks() > FLEE_GIVE_UP_SCANS
                && villager.distanceToSqr(threat) >= CombatAction.FLEE_HOLD_DIST * CombatAction.FLEE_HOLD_DIST) {
            agent.setCurrentAction(null);
            agent.setCurrentActivity("idle");
            villager.getNavigation().stop();
            agent.resumeSuspendedGoals();
            LOGGER.debug("{} gave up rallying against {}", agent.getName(), threat.getType().getRegistryName());
            return;
        }

        // Keep a safe distance rather than running forever: back off only while too close,
        // otherwise hold ground and keep the alarm alive so we can turn and charge together.
        if (villager.distanceToSqr(threat) < CombatAction.FLEE_HOLD_DIST * CombatAction.FLEE_HOLD_DIST) {
            CombatAction.flee(villager, threat);
        } else {
            villager.getNavigation().stop();
        }
    }

    /**
     * Continue an in-progress combat action: chase, attack, or disengage.
     */
    private static void continueCombatAction(VillagerEntity villager, ServerWorld world,
                                              VillagerAgentData agent, VillagerAction action) {
        UUID targetId = action.getTargetEntityId();
        if (targetId == null) {
            disengageCombat(villager, agent);
            return;
        }

        // Find the target entity
        Entity rawTarget = world.getEntity(targetId);
        if (rawTarget instanceof LivingEntity && !rawTarget.isAlive()) {
            // Killed it — combat practice.
            agent.gainCapabilityXp(CapabilityId.COMBAT, ModConfig.CAPABILITY_PRACTICE_XP.get(),
                    villager, world.getGameTime());
            disengageCombat(villager, agent);
            LOGGER.debug("{} eliminated a threat", agent.getName());
            return;
        }
        if (!(rawTarget instanceof LivingEntity)) {
            // Target despawned or gone — disengage
            disengageCombat(villager, agent);
            LOGGER.debug("{} target eliminated or lost", agent.getName());
            return;
        }

        LivingEntity target = (LivingEntity) rawTarget;
        double distSq = villager.distanceToSqr(target);

        // Eligibility is continuous, not just an entry check: stop chasing/striking when hurt
        // or disarmed, and keep calling for help while withdrawing.
        if (!CombatAction.mayAttack(villager, agent)) {
            BrainLog.diagnostic(agent, "combat-retreat", "reason=eligibility health=" + villager.getHealth()
                    + " fatigue=" + agent.getFatigue() + " night=" + SleepSkill.isNightTime(world.getDayTime()));
            VillageSignalBoard.get().respond(villager, agent, target);
            startFleeAction(villager, agent, target);
            return;
        }
        VillageSignalBoard.get().respond(villager, agent, target);
        if (!VillageSignalBoard.get().shouldCharge(target)) {
            BrainLog.diagnostic(agent, "combat-retreat", "reason=group_not_ready threat=" + target.getUUID());
            startFleeAction(villager, agent, target);
            return;
        }

        // Check if target moved out of chase range — disengage
        if (distSq > CombatAction.CHASE_MAX_DIST * CombatAction.CHASE_MAX_DIST) {
            disengageCombat(villager, agent);
            LOGGER.debug("{} lost sight of target, disengaging", agent.getName());
            return;
        }

        // In attack range — attack!
        if (distSq <= CombatAction.ATTACK_RANGE_SQ) {
            action.setPhase(VillagerAction.ActionPhase.ACTING);
            if (!agent.isOnAttackCooldown()) {
                CombatAction.attackEntity(villager, target, agent);
                agent.setAttackCooldownTicks(CombatAction.ATTACK_COOLDOWN_TICKS);
            }
            // Stay in combat action — keep attacking until target dies or flees
            return;
        }

        // Still chasing — check if stuck
        action.incrementStuckTicks();
        if (action.getStuckTicks() > CombatAction.CHASE_TIMEOUT_TICKS) {
            disengageCombat(villager, agent);
            LOGGER.debug("{} gave up chasing target (stuck)", agent.getName());
            return;
        }

        // Re-issue navigation toward moving target
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        villager.getNavigation().moveTo(target, 0.4);
    }

    /**
     * Clean up combat state: clear the action and reset activity.
     * Does NOT unequip the weapon — villagers keep holding whatever they had.
     * Equipment is only changed when starting a new contextual action
     * (e.g. equip hoe for farming, empty hand for talking).
     */
    private static void disengageCombat(VillagerEntity villager, VillagerAgentData agent) {
        agent.setCurrentAction(null);
        agent.setCurrentActivity("idle");
        agent.resumeSuspendedGoals(); // threat gone — resume the paused plan
    }

    // ===================================================================
    //  Building tick — placing & breaking single blocks, and driving BuildJobs
    // ===================================================================

    /**
     * Fast tick for world building. Runs every tick so placing/breaking is responsive and so a
     * multi-block {@link BuildJob} makes steady progress (one block per {@code BUILD_BLOCK_INTERVAL}
     * ticks). Never interrupts farming/combat/etc. — it only owns the villager when the current
     * action is one of PLACE / BREAK / BUILD, or when a BuildJob is active.
     */
    public static void tickBuilding(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        if (!ModConfig.ENABLE_BUILDING.get()) return;
        if (!(world instanceof ServerWorld)) return;

        ServerWorld serverWorld = (ServerWorld) world;
        long currentTime = world.getGameTime();

        for (VillagerAgentData agent : agents.values()) {
            if (TradeDemo.isActive(agent.getVillagerId())) continue;
            VillagerAction cur = agent.getCurrentAction();

            if (cur != null && cur.isGoalDriven()) continue;
            boolean ourAction = cur != null && (cur.getActionType() == VillagerAction.ActionType.PLACE
                    || cur.getActionType() == VillagerAction.ActionType.BREAK
                    || cur.getActionType() == VillagerAction.ActionType.BUILD);
            // A different system (farming/combat/activity) owns the villager right now — leave it.
            if (cur != null && !ourAction) continue;

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            if (ourAction) {
                BlockInteractionAction.StepResult res;
                if (cur.getActionType() == VillagerAction.ActionType.PLACE) {
                    res = BlockInteractionAction.tickPlace(villager, serverWorld, agent, cur);
                } else if (cur.getActionType() == VillagerAction.ActionType.BREAK) {
                    res = BlockInteractionAction.tickBreak(villager, serverWorld, agent, cur);
                } else {
                    res = BlockInteractionAction.StepResult.DONE; // BUILD marker — clear it
                }
                if (res == BlockInteractionAction.StepResult.DONE
                        || res == BlockInteractionAction.StepResult.ABANDONED) {
                    if (res == BlockInteractionAction.StepResult.ABANDONED) {
                        LOGGER.debug(agent.getName() + " abandoned block step at "
                                + cur.getTargetBlockPos());
                    }
                    advanceBuildCursor(agent, cur);
                    agent.setCurrentAction(null);
                }
                continue;
            }

            // No active world-action: drive the next BuildJob step (throttled).
            BuildJob job = agent.getBuildJob();
            if (job == null) continue;

            if (job.isComplete()) {
                finishBuildJob(agent, job);
                continue;
            }
            // Throttle: only begin a fresh block every BUILD_BLOCK_INTERVAL ticks.
            if (currentTime % ModConfig.BUILD_BLOCK_INTERVAL.get() != 0) continue;

            job.reconcileWithWorld(serverWorld);
            if (job.isComplete()) {
                finishBuildJob(agent, job);
                continue;
            }

            BuildOrderPlanner.Step step = job.currentStep();
            if (step == null) continue;

            VillagerAction act = new VillagerAction(VillagerAction.ActionType.PLACE,
                    "Placing block for " + job.getName());
            act.setTargetBlockPos(step.target);
            act.setPlaceBlock(step.block);
            act.setStandCell(step.stand);
            act.setJobCursor(job.getCursor());
            act.setPhase(VillagerAction.ActionPhase.WALKING);
            act.resetStuckTicks();
            agent.setCurrentAction(act);
            agent.setCurrentActivity("building");
        }
    }

    /** Advance the BuildJob cursor past the just-finished (or skipped) step. */
    private static void advanceBuildCursor(VillagerAgentData agent, VillagerAction cur) {
        BuildJob job = agent.getBuildJob();
        if (job == null) return;
        int idx = cur.getJobCursor();
        if (idx >= 0 && idx == job.getCursor()) {
            job.setCursor(idx + 1);
        }
    }

    /** The BuildJob is done — record it and clear it. */
    private static void finishBuildJob(VillagerAgentData agent, BuildJob job) {
        agent.addMemory("Finished building " + job.getName() + " (" + job.getTotal() + " blocks)");
        LOGGER.info(agent.getName() + " finished building '" + job.getName() + "'");
        agent.setCurrentActivity("idle");
        agent.setBuildJob(null);
    }

    // ── Command-facing request helpers (also usable by a future LLM action parser) ──

    /** Queue a single-block PLACE for the villager (walks to a legal standing cell first). */
    public static void requestPlace(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, BlockPos target, Block block) {
        ActionLifecycle.cancel(agent, villager, world);
        agent.setRunningGoal(null);
        BlockPos stand = BlockInteractionAction.findStandable(world, target);
        VillagerAction act = new VillagerAction(VillagerAction.ActionType.PLACE,
                "Placing " + block.getRegistryName() + " at " + target);
        act.setTargetBlockPos(target);
        act.setPlaceBlock(block);
        act.setStandCell(stand);
        act.setPhase(VillagerAction.ActionPhase.WALKING);
        act.resetStuckTicks();
        agent.setCurrentAction(act);
        agent.setCurrentActivity("placing");
    }

    /** Queue a single-block BREAK for the villager (walks to a legal standing cell first). */
    public static void requestBreak(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, BlockPos target) {
        ActionLifecycle.cancel(agent, villager, world);
        agent.setRunningGoal(null);
        BlockPos stand = BlockInteractionAction.findStandable(world, target);
        VillagerAction act = new VillagerAction(VillagerAction.ActionType.BREAK,
                "Breaking block at " + target);
        act.setTargetBlockPos(target);
        act.setStandCell(stand);
        act.setPhase(VillagerAction.ActionPhase.WALKING);
        act.resetStuckTicks();
        agent.setCurrentAction(act);
        agent.setCurrentActivity("breaking");
    }

    /** Ask the LLM to design and build a structure near the villager. */
    public static void requestBuild(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, String goalText) {
        StructureBuilder.requestStructure(agent, villager, world, goalText);
    }


    /** How often (in ticks) to automatically refresh the environment snapshot. ~1 Minecraft minute. */
    private static final int ENV_REFRESH_INTERVAL = 1200;

    /**
     * Update a single agent's AI (slow tick — goals, restocking, etc.)
     * Combat is handled separately by tickCombat().
     */
    private static void updateAgent(World world, VillagerAgentData agent) {
        // Find the actual villager entity
        VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
        if (villager == null) {
            return; // Villager not loaded or doesn't exist
        }

        // Daily schedule planner removed (Unified Behavior Generator Refactoring Plan): no more fixed time-slot plan.
        if (world instanceof ServerWorld) {
            ServerWorld sw = (ServerWorld) world;

            // Autonomous environment refresh (keeps snapshot current for planning and chat)
            long gameTime = world.getGameTime();
            // Always update chunk memory on every slow tick (cheap — just a Set.contains check)
            BlockPos vPos = villager.blockPosition();
            agent.updateChunkMemory(villager, world, vPos.getX() >> 4, vPos.getZ() >> 4);

            // Consume world events since this villager's cursor (perception-gated) → reactive goals
            processWorldEvents(sw, villager, agent, gameTime);

            if (gameTime - agent.getLastEnvRefreshTick() >= ENV_REFRESH_INTERVAL) {
                agent.setLastEnvRefreshTick(gameTime);
                String envSummary = VillagerVisionSystem.buildEnvironmentSummary(villager, sw, agent);
                agent.setEnvironmentSummary(envSummary);
            }

            // Activity system's scheduledActivity translator removed (was fed by the daily
            // schedule planner, now gone); sleep healing + wake-at-dawn remain in tickSleepHealing.

            // Needs system — hunger decay, eating, fatigue
            VillagerNeedsSystem.tick(sw, agent);

            // Capability decay + vanilla trade-level sync — once per in-game day (persisted, so it
            // never double-decays across a world reload).
            long today = gameTime / 24_000L;
            if (today != agent.getLastCapabilityDecayDay()) {
                agent.setLastCapabilityDecayDay(today);
                agent.getCapabilityPool().decayTo(today);
                agent.syncVanillaTradeLevel(villager);
                agent.refreshUnlockedRecipes(); // decay may drop levels → re-lock recipes
            }
        }

        // Process current goals (execution + daily prune + fallback generation if needed).
        // Restocking is now goal-driven (RestockSkill) rather than a passive proximity check —
        // all villager behaviour flows through the unified goal queue.
        OpportunitySensor.refresh(agent, villager, (ServerWorld) world);
        RoutineScheduler.ensureRepeatableGoals(agent, (ServerWorld) world);
        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.offerWork((ServerWorld) world, villager, agent);
    }

    // ── World event consumption (per-villager cursor + perception gate) ─────────

    /**
     * Drain the {@link WorldEventBus} for this villager up to a small budget, feed admitted events
     * through the perception gate, and turn them into reactive goals. The cursor is persisted in
     * NBT so an offline villager catches up on resume without re-reading evicted events.
     */
    private static void processWorldEvents(ServerWorld world, VillagerEntity villager,
                                           VillagerAgentData agent, long gameTick) {
        int radius = ModConfig.PERCEPTION_RADIUS_CHUNKS.get();
        int budget = ModConfig.EVENTS_PER_THINK.get();
        int vcx = villager.blockPosition().getX() >> 4;
        int vcz = villager.blockPosition().getZ() >> 4;

        WorldEventBus bus = WorldEventBus.instance(world);
        long cursor = agent.getEventCursor();
        long next = bus.consume(cursor, budget, vcx, vcz, radius, e -> onWorldEvent(agent, e));
        agent.setEventCursor(next);
    }

    /** Translate a single admitted environment event into behaviour (via the unified goal funnel). */
    private static void onWorldEvent(VillagerAgentData agent, WorldEventBus.Event e) {
        switch (e.type) {
            case NEW_VILLAGER: {
                // A new neighbour arrived — go socialise, unless one is already queued.
                for (AgentGoal g : agent.getGoals()) {
                    if (!g.isCompleted() && "socialize".equals(g.getGoalType())) return;
                }
                if (agent.getGoals().size() < MAX_GOALS) {
                    AgentGoal g = new AgentGoal("socialize", "Meet the new villager", 3);
                    g.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
                    agent.getGoals().add(g);
                    BrainLog.goalAdded(agent, g, "event:new_villager");
                }
                break;
            }
            case BLOCK_CHANGED:
            case ITEM_DROPPED:
            default:
                // v1 framework only: block changes are already handled by WorldStructureIndex
                // invalidation, item drops by ItemAttractionSystem pickup. Consumed for
                // observability; goal injection for these types follows the skill that owns them.
                break;
        }
    }

    /**
     * Social tick — scan for nearby villager pairs and trigger LLM-generated conversations.
     * Called every world tick but internally gated to every 200 ticks.
     */
    public static void tickSocial(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_SOCIAL.get()) return;
        if (!(world instanceof ServerWorld)) return;
        VillagerSocialSystem.tickSocial((ServerWorld) world);
    }

    // ── Spontaneous thought bubbles ──────────────────────────────────────────

    /** How far a player must be (squared) to trigger a thought bubble. */
    private static final double THOUGHT_PLAYER_RANGE_SQ = 400.0; // 20 blocks

    /** Minimum ticks between two thought bubbles for the same villager (~5 MC minutes). */
    private static final long THOUGHT_COOLDOWN_TICKS = 6_000L;

    /** How often (ticks) we scan for villagers that should emit a thought. */
    private static final int THOUGHT_SCAN_INTERVAL = 40;

    /**
     * Thought tick — periodically emits an LLM-generated inner thought as a nearby-player
     * bubble. This ambient feature is OFF by default (see {@link ModConfig#ENABLE_VILLAGER_THOUGHTS});
     * the underlying generation API ({@link #requestThought}) remains available for other
     * features (e.g. a future "mind-reader" item that reveals a villager's thoughts to the
     * holder only, instead of broadcasting to everyone nearby).
     */
    public static void tickThoughts(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_THOUGHTS.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long gameTime = world.getGameTime();
        if (gameTime % THOUGHT_SCAN_INTERVAL != 0) return;

        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            if (TradeDemo.isActive(agent.getVillagerId())) continue;
            // Per-villager cooldown
            if (gameTime - agent.getLastThoughtTick() < THOUGHT_COOLDOWN_TICKS) continue;

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            // Check whether any player is nearby
            boolean playerNearby = false;
            for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                if (player.distanceToSqr(villager) <= THOUGHT_PLAYER_RANGE_SQ) {
                    playerNearby = true;
                    break;
                }
            }
            if (!playerNearby) continue;

            // Mark the tick immediately to prevent concurrent calls
            agent.setLastThoughtTick(gameTime);

            final VillagerEntity finalVillager = villager;
            requestThought(agent, rawThought -> {
                // Marshal ALL game-state / memory touches back onto the server thread.
                // This callback runs on the LLM worker pool, and memories / player messages
                // are not thread-safe off the main thread.
                serverWorld.getServer().execute(() -> {
                    // The LLM may append a GOAL: directive. Strip it from what players see
                    // and turn it into an actual goal with the importance it assigned.
                    String displayed = rawThought;
                    List<AgentGoal> directives = parseGoalDirectives(rawThought);
                    if (!directives.isEmpty()) {
                        displayed = stripGoalLines(rawThought);
                        for (AgentGoal g : directives) {
                            if (agent.getGoals().size() < MAX_GOALS) {
                                agent.getGoals().add(g);
                                agent.addMemory("New goal from thought: " + g.getDescription()
                                        + " (importance " + g.getImportance() + ")");
                                BrainLog.goalAdded(agent, g, "thought_goal_directive");
                            } else {
                                BrainLog.goalSkipped(agent, g, "thought_goal_directive");
                            }
                        }
                    }

                    // Broadcast to nearby players (cleaned thought only)
                    StringTextComponent msg = new StringTextComponent(
                            "§7[" + agent.getName() + " thinks] §o" + displayed);
                    for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                        if (player.distanceToSqr(finalVillager) <= THOUGHT_PLAYER_RANGE_SQ) {
                            player.sendMessage(msg, Util.NIL_UUID);
                        }
                    }

                    // Store as a memory so it influences future behaviour
                    agent.addMemory("I thought: " + displayed);
                    LOGGER.debug("{} emitted thought: {}", agent.getName(), displayed);

                    // Each thinking may deepen commitment to a random subset of goals.
                    boostSomeGoals(agent);
                });
            });
        }
    }

    /**
     * Core inner-thought generation API. Builds a personality/mood/context-aware prompt and
     * asks the LLM for a single first-person thought, delivered asynchronously to
     * {@code onThought}. This method performs no broadcast of its own — the caller decides
     * what to do with the result.
     *
     * <p>Used by {@link #tickThoughts} for the ambient bubble. Intended for any future feature
     * that wants to read a villager's mind (e.g. a "mind-reader" item that shows the thought
     * only to the player holding it, rather than to everyone nearby).
     *
     * @param agent     the villager whose thoughts to generate
     * @param onThought callback receiving the trimmed thought text (empty responses are ignored)
     */
    public static void requestThought(VillagerAgentData agent, Consumer<String> onThought) {
        String env  = agent.getEnvironmentSummary();
        String recentMem = agent.getMemories().isEmpty() ? "nothing in particular"
                : agent.getMemories().get(agent.getMemories().size() - 1);
        String activity = agent.getCurrentActivity() != null ? agent.getCurrentActivity() : "idle";
        String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);

        String sysPrompt = "You are a Minecraft villager. Write a single short internal thought "
                + "(1 sentence, first person) that reflects what you are feeling or thinking "
                + "right now. Be specific, in-character, and vivid. No quotation marks, no labels.\n"
                + "If you decide you want to pursue a NEW goal right now, append a separate line "
                + "starting with GOAL: in the format: GOAL: <type>|<description>|<importance 1-10> "
                + "where <type> is one of gather, craft, trade, socialize, build, goto, and <importance> "
                + "is how strongly you want it (1 = passing fancy, 10 = burning need). Only add a "
                + "GOAL line if you genuinely want something new; otherwise just write the thought.";
        String userPrompt = "Name: " + agent.getName()
                + "\nProfession: " + agent.getProfession()
                + "\nPersonality: " + agent.getPersonality()
                + "\nMood: " + agent.getMood().name().toLowerCase()
                + (needsMood.isEmpty() ? "" : " (" + needsMood + ")")
                + "\nCurrent activity: " + activity
                + (env != null ? "\nEnvironment: " + env : "")
                + "\nMost recent memory: " + recentMem
                + "\nWrite your thought:";

        LLMService.queryLLM(sysPrompt, userPrompt).thenAccept(thought -> {
            if (thought == null || thought.trim().isEmpty() || LLMService.isFailure(thought)) return;
            onThought.accept(thought.trim());
        }).exceptionally(e -> {
            LOGGER.warn("Thought LLM call failed for {}: {}", agent.getName(), e.getMessage());
            return null;
        });
    }

    // ── Player proximity greeting ─────────────────────────────────────────────

    /** Player must be within this range (squared) to trigger a greeting (8 blocks). */
    private static final double GREETING_RANGE_SQ = 64.0;

    /** Minimum ticks between greetings to the same player (~2 MC minutes = 2400 ticks). */
    private static final long GREETING_COOLDOWN_TICKS = 2_400L;

    /** Radius (squared) within which we count "conversable targets" (other villagers + players)
     *  to dilute the greeting chance — roughly one chunk of vicinity (16 blocks). */
    private static final double GREETING_AWARENESS_SQ = 16.0 * 16.0;

    /**
     * Greeting tick — when a player walks within 8 blocks of a villager (after the
     * per-player cooldown has expired), the villager MAY emit a short context-aware greeting
     * in chat. Greeting is probabilistic, not guaranteed:
     *  - base chance ({@code greeting_base_probability}) when few other conversable targets exist;
     *  - diluted by the number of nearby villagers/players (a lone player gets the full base,
     *    but a crowded village rarely singles you out — so villagers "talk among themselves");
     *  - decays per prior meeting with this player (repeat greetings get rarer).
     * The scan itself runs at {@code greeting_scan_interval} ticks.
     */
    public static void tickGreeting(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_CHAT.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long gameTime = world.getGameTime();
        if (gameTime % ModConfig.GREETING_SCAN_INTERVAL.get() != 0) return;

        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            if (TradeDemo.isActive(agent.getVillagerId())) continue;
            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                if (player.distanceToSqr(villager) > GREETING_RANGE_SQ) continue;

                long lastGreeted = agent.getLastGreetedPlayer()
                        .getOrDefault(player.getUUID(), -GREETING_COOLDOWN_TICKS - 1L);
                if (gameTime - lastGreeted < GREETING_COOLDOWN_TICKS) continue;

                // ── Probability gate ──
                int targets = countConversableTargets(serverWorld, villager, agent);
                int meets   = agent.getGreetCount().getOrDefault(player.getUUID(), 0);
                double p = ModConfig.GREETING_BASE_PROBABILITY.get();
                p = p / (1.0 + (targets - 1) * ModConfig.GREETING_TARGET_DILUTION.get());
                p *= Math.pow(ModConfig.GREETING_FAMILIARITY_DECAY.get(), meets);
                p = Math.min(0.85, Math.max(0.03, p));
                if (RANDOM.nextDouble() >= p) {
                    // Skip this time — leave the cooldown untouched so it re-rolls on the next scan.
                    continue;
                }

                // Mark immediately to prevent concurrent calls + record familiarity
                agent.getLastGreetedPlayer().put(player.getUUID(), gameTime);
                agent.getGreetCount().put(player.getUUID(), meets + 1);

                // Snapshot context for the async call
                final String pName    = player.getName().getString();
                final String env      = agent.getEnvironmentSummary();
                final String activity = agent.getCurrentActivity() != null
                        ? agent.getCurrentActivity() : "idle";
                final String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);

                String sysPrompt = "You are a Minecraft villager. When a player walks up to you, "
                        + "greet them with a single short sentence (10–20 words), in character, "
                        + "reflecting what you are doing and how you feel. No quotation marks.";
                String userPrompt = "Name: " + agent.getName()
                        + "\nProfession: " + agent.getProfession()
                        + "\nPersonality: " + agent.getPersonality()
                        + "\nMood: " + agent.getMood().name().toLowerCase()
                        + (needsMood.isEmpty() ? "" : " (" + needsMood + ")")
                        + "\nCurrent activity: " + activity
                        + (env != null ? "\nEnvironment: " + env : "")
                        + "\nA player named " + pName + " just walked up to you. Greet them:";

                final VillagerEntity finalVillager = villager;
                LLMGuard.query(agent.getVillagerId(), gameTime, sysPrompt, userPrompt).thenAccept(greeting -> {
                    DecisionJournal.record(agent.getVillagerId(), DecisionJournal.Kind.GREETING,
                            gameTime, userPrompt, greeting, DecisionJournal.outcome(greeting));
                    if (greeting == null || greeting.trim().isEmpty() || LLMService.isFailure(greeting)) return;
                    String trimmed = greeting.trim();
                    // Marshal game-state / memory touches back onto the server thread —
                    // this callback runs on a worker pool thread, off the main thread.
                    serverWorld.getServer().execute(() -> {
                        // Broadcast in gold to nearby players as spoken dialogue
                        StringTextComponent msg = new StringTextComponent(
                                "§e" + agent.getName() + ": §f" + trimmed);
                        for (ServerPlayerEntity nearby : serverWorld.getServer().getPlayerList().getPlayers()) {
                            if (nearby.distanceToSqr(finalVillager) <= THOUGHT_PLAYER_RANGE_SQ) {
                                nearby.sendMessage(msg, net.minecraft.util.Util.NIL_UUID);
                            }
                        }

                        agent.addMemory("Greeted player " + pName + ": " + trimmed);
                        LOGGER.debug("{} greeted {}: {}", agent.getName(), pName, trimmed);
                    });
                }).exceptionally(e -> {
                    LOGGER.warn("Greeting LLM call failed for {}: {}", agent.getName(), e.getMessage());
                    return null;
                });

                break; // one greeting per villager per scan tick (serve one player at a time)
            }
        }
    }

    /**
     * Count nearby "conversable targets" around a villager — other agent villagers plus all
     * players within {@link #GREETING_AWARENESS_SQ}. Used to dilute the greeting probability so a
     * villager in a crowd is less likely to single out the player (and a lone player gets the full base).
     */
    private static int countConversableTargets(ServerWorld world, VillagerEntity villager, VillagerAgentData self) {
        int n = 0;
        for (ServerPlayerEntity p : world.getServer().getPlayerList().getPlayers()) {
            if (p.distanceToSqr(villager) <= GREETING_AWARENESS_SQ) n++;
        }
        for (VillagerAgentData other : agents.values()) {
            if (other == self) continue;
            VillagerEntity ve = findVillagerEntity(world, other.getVillagerId());
            if (ve != null && ve.distanceToSqr(villager) <= GREETING_AWARENESS_SQ) n++;
        }
        return n;
    }

    /**
     * Clear the villager's main hand (e.g. when talking to a player).
     */
    public static void equipEmptyHand(VillagerEntity villager) {
        villager.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
    }

    /**
     * Check if villager is at their job block and should restock
     * Mimics vanilla Minecraft behavior where villagers restock at their workstation
     */
    /**
     * Find a villager entity in the world by UUID
     */
    private static VillagerEntity findVillagerEntity(World world, UUID villagerId) {
        // In Minecraft 1.16.5, we need to use ServerWorld to get entities
        if (world instanceof ServerWorld) {
            ServerWorld serverWorld = (ServerWorld) world;
            Entity entity = serverWorld.getEntity(villagerId);
            if (entity instanceof VillagerEntity) {
                return (VillagerEntity) entity;
            }
        }
        return null;
    }

    // ── Goal lifecycle tuning ──
    /** Hard cap on simultaneous goals — defends against unbounded growth. */
    private static final int MAX_GOALS = 10;
    /** Goals at or below this importance are eligible for daily random pruning. */
    private static final int LOW_IMPORTANCE_THRESHOLD = 3;
    /** Probability a low-importance goal is dropped during the daily prune. */
    private static final double LOW_GOAL_DROP_CHANCE = 0.5;
    /** Probability that a thought also boosts a random subset of goals' importance. */
    private static final double GOAL_BOOST_CHANCE = 0.5;
    private static final Set<String> VALID_GOAL_TYPES = new HashSet<>(Arrays.asList(
            "craft", "trade", "socialize", "build", "move", "converse", "goto"));

    // ── Goal-execution tuning (shared with the ai.behavior skills) ──
    /** Squared arrival threshold for goal-driven movement (within 4 blocks). */
    public static final double GOAL_ARRIVE_SQ = 16.0;
    /** Ticks before a goal-driven walk gives up (~10 s). */
    public static final int GOAL_STUCK_TIMEOUT = 200;
    /** Ticks a villager must remain at the workstation before a craft completes (~10 s). */
    public static final int CRAFT_WORK_TICKS = 200;
    /** Consecutive BLOCKED returns before a goal is dropped so it stops hogging the running slot. */
    private static final int BLOCKED_MAX_STREAK = 5;
    /** Floor for the cooldown an abandoned REPEATABLE goal gets (~10 s). Without it, a goal whose
     *  own cooldown is 0 (e.g. {@code rest}) would be re-picked instantly and loop here forever. */
    private static final int MIN_ABANDON_COOLDOWN = 200;

    /**
     * Process the agent's current goals: daily maintenance, then execution.
     *
     * <p>This is the single unified dispatcher: the highest-priority unfinished goal is driven
     * to a {@link VillagerAction} by type (craft / move / gather / socialize / trade). When a
     * goal is driving the villager it owns movement, so the daily-schedule activity system
     * ({@code scheduledActivity}) yields for the duration.
     */
    private static void processGoals(VillagerEntity villager, VillagerAgentData agent) {
        ServerWorld world = (ServerWorld) villager.level;
        long now = world.getGameTime();
        VillagerAction action = agent.getCurrentAction();
        // Reactive combat and explicit construction own the body until they yield it.
        if (action != null && !action.isGoalDriven()) return;
        if (action != null && (action.getActionType() == VillagerAction.ActionType.MOVE
                || action.getPhase() == VillagerAction.ActionPhase.WALKING)) action.observeMovement(villager.position());
        List<AgentGoal> goals = agent.getGoals();
        AgentGoal current = agent.getRunningGoal();
        // Satisfied job searches must disappear even when their handler is no longer eligible.
        if (com.github.AaronAA0721.villageragent.ai.behavior.FindJobSiteSkill.hasValidJobSite(villager, world)
                || agent.getResourceProfession() != com.github.AaronAA0721.villageragent.ai.work.ResourceProfession.NONE) {
            goals.removeIf(g -> "find_job_site".equals(g.getGoalType()));
        }
        agent.brain().nightRest = SleepSkill.isNightTime(world.getDayTime());
        GoalScheduler.expireGoals(goals, now);
        if (current != null && (!goals.contains(current) || current.isCompleted() || current.isExpired(now))) {
            ActionLifecycle.cancel(agent, villager, world);
            agent.setRunningGoal(null); current = null;
        }
        if (current == null) resumeAllGoals(agent, goals);
        if (now < agent.brain().socialUntilTick) return;
        if (agent.isSocializing() && (current == null || !"socialize".equals(current.getGoalType()))) return;
        boolean reconsider = now - agent.brain().lastChoiceTick >= 20;
        if (current == null || reconsider) {
            agent.brain().lastChoiceTick = now;
            // Choose only actually executable candidates. Missing prerequisites of an existing
            // task are reported below, never confused with an unavailable handler.
            List<AgentGoal> runnable = new ArrayList<>();
            for (AgentGoal g : new ArrayList<>(goals)) {
                if (BehaviorExecutor.findHandler(agent, g, villager, world) != null) {
                    runnable.add(g);
                } else if (g != current && !g.isOnCooldown(now)) {
                    g.setLastFailure(BehaviorExecutor.diagnoseFailure(agent, g, villager, world));
                    FailureRecoveryPlanner.handle(agent, g, villager, world);
                    if (!GoalExitPolicy.retain(g, SkillResult.BLOCKED, now)) {
                        goals.remove(g);
                        if (g.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE)
                            agent.brain().retiredRoutines.put(g.getGoalType(), now + GoalExitPolicy.RETIRED_ROUTINE_DELAY);
                    }
                }
            }
            AgentGoal candidate = GoalScheduler.pickNextGoal(agent, runnable, now);
            if (candidate == null && current == null && (agent.brain().lastNoCandidateTick == Long.MIN_VALUE
                    || now - agent.brain().lastNoCandidateTick >= 100)) {
                agent.brain().lastNoCandidateTick = now;
                StringBuilder rejected = new StringBuilder();
                for (AgentGoal g : goals) {
                    if (GoalScheduler.utility(agent, g, now) <= 0
                            || BehaviorExecutor.findHandler(agent, g, villager, world) != null) continue;
                    if (rejected.length() > 0) rejected.append("; ");
                    rejected.append(g.getGoalType()).append(':')
                            .append(BehaviorExecutor.explainNoHandler(agent, g, villager, world));
                }
                BrainLog.diagnostic(agent, "no-candidate", "goals=" + goals.size()
                        + " runnable=" + runnable.size() + " action=" + agent.getCurrentAction()
                        + " farmOpportunities=" + agent.brain().farmOpportunities
                        + " socialPartner=" + agent.brain().socialPartner
                        + " positiveButUnrunnable=" + rejected);
            }
            boolean boundary = GoalScheduler.canYieldBetweenActions(current)
                    && !GoalScheduler.isNonUrgentRest(agent, candidate)
                    && agent.getCurrentAction() == null && !villager.isSleeping()
                    && now - agent.brain().goalStartedTick >= 200;
            if (candidate != null && candidate != current
                    && (current == null || GoalScheduler.shouldPreempt(agent, current, candidate) || boundary)) {
                AgentGoal previous = current;
                if (previous != null) {
                    ActionLifecycle.cancel(agent, villager, world);
                    previous.setCooldownUntilTick(now + Math.max(100, previous.getMinimumExitCooldownTicks()));
                }
                current = candidate;
                current.setSuspended(false);
                agent.setRunningGoal(current);
                agent.setCurrentActivity(current.getGoalType());
                agent.brain().goalStartedTick = now;
                if ("wander".equals(current.getGoalType())) {
                    StringBuilder reasons = new StringBuilder();
                    for (AgentGoal g : goals) reasons.append(g.getGoalType()).append("{score=")
                            .append(GoalScheduler.utility(agent, g, now)).append(",cooldown=")
                            .append(Math.max(0, g.getCooldownUntilTick() - now)).append(",handler=")
                            .append(BehaviorExecutor.findHandler(agent, g, villager, world) != null).append("} ");
                    BrainLog.diagnostic(agent, "wander-fallback", reasons + "farmAnchor=" + agent.brain().rememberedFarm
                            + " profession=" + agent.getProfession() + " role=" + agent.getResourceProfession());
                }
                BrainLog.decide(agent, "selected " + current.getGoalType()
                        + " score=" + GoalScheduler.utility(agent, current, now));
                BrainLog.goalSwitch(agent, previous, current, "utility");
            }
        }
        if (current == null) return;
        current.setSuspended(false);
        agent.setLastExecutorTick(now);
        VillagerSkill handler = BehaviorExecutor.findHandler(agent, current, villager, world);
        SkillResult result;
        if (handler == null) {
            current.setLastFailure(BehaviorExecutor.diagnoseFailure(agent, current, villager, world));
            BrainLog.noHandler(agent, current, BehaviorExecutor.explainNoHandler(agent, current, villager, world));
            result = SkillResult.BLOCKED;
        } else {
            current.setLastFailure(null);
            result = BehaviorExecutor.executeRoot(agent, current, villager, world);
            if (result == SkillResult.RUNNING && !current.isCompleted()) {
                VillagerAction moving = agent.getCurrentAction();
                if (moving != agent.brain().observedWalk) {
                    agent.brain().observedWalk = moving;
                    agent.brain().walkStartedTick = now;
                }
                if (!villager.isSleeping() && now - agent.brain().goalStartedTick >= 2400) {
                    current.setLastFailure(FailureReason.UNKNOWN.with("execution timeout"));
                    result = SkillResult.FAIL;
                }
            }
        }
        // Legacy skills that set completed must obey the same terminal protocol.
        if (result == SkillResult.RUNNING && current.isCompleted()) result = SkillResult.SUCCESS;
        if (result == SkillResult.RUNNING) return;
        BrainLog.diagnostic(agent, "skill-exit", "goal=" + current.getGoalType() + " result=" + result
                + " handler=" + (handler == null ? "none" : handler.id())
                + " failure=" + current.getLastFailure() + " action=" + agent.getCurrentAction()
                + " position=" + villager.blockPosition());
        if (result == SkillResult.FAIL && "find_job_site".equals(current.getGoalType())) {
            VillagerAction failedMove = agent.getCurrentAction();
            if (failedMove != null && failedMove.getTargetBlockPos() != null)
                agent.brain().rejectedJobSites.put(failedMove.getTargetBlockPos().immutable(), now + 1200);
        }
        if (result == SkillResult.BLOCKED || result == SkillResult.FAIL) {
            FailureRecoveryPlanner.handle(agent, current, villager, world);
        }
        ActionLifecycle.cancel(agent, villager, world);
        agent.setRunningGoal(null);
        BrainLog.goalState(agent, current, result.name(), "execution_result");
        if (!GoalExitPolicy.retain(current, result, now)) {
            goals.remove(current);
            if (current.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE && result != SkillResult.SUCCESS) {
                agent.brain().retiredRoutines.put(current.getGoalType(), now + GoalExitPolicy.RETIRED_ROUTINE_DELAY);
            }
        }
        if (result == SkillResult.SUCCESS) {
            agent.brain().lastFinishedType = current.getGoalType();
            agent.brain().lastFinishedTick = now;
        }
        resumeAllGoals(agent, goals);
    }

    /** Unsuspend every suspended goal, logging each transition. Called when a running goal finishes. */
    private static void resumeAllGoals(VillagerAgentData agent, List<AgentGoal> goals) {
        for (AgentGoal g : goals) {
            if (g.isSuspended()) {
                g.setSuspended(false);
                BrainLog.goalState(agent, g, "resumed", "running_goal_finished");
            }
        }
    }

    /** Find a runnable rest/sleep goal, or null. Used to keep sleep in control through the night. */
    private static AgentGoal findSleepGoal(List<AgentGoal> goals) {
        for (AgentGoal g : goals) {
            if (g.isCompleted() || g.isSuspended()) continue;
            if (SleepSkill.isSleepGoal(g)) return g;
        }
        return null;
    }

    /**
     * Shared MOVE state-machine for location-driven goals (move / gather / socialize / trade).
     * Clears any non-goal action, walks to {@code target}, and completes the goal on arrival
     * (or after a stuck timeout).
     */
    public static SkillResult driveMoveGoal(VillagerEntity villager, VillagerAgentData agent,
                                     AgentGoal goal, BlockPos target, String arriveMemory) {
        if (target == null) {
            agent.addMemory(arriveMemory + " (no target)");
            goal.setLastFailure(FailureReason.UNREACHABLE.with((String) null));
            return SkillResult.FAIL;
        }

        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && !cur.isGoalDriven()) { agent.setCurrentAction(null); cur = null; }

        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
            if (villager.blockPosition().distSqr(target) <= goal.getArrivalDistanceSq()) {
                agent.addMemory(arriveMemory);
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                return SkillResult.SUCCESS;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > GOAL_STUCK_TIMEOUT) {
                BrainLog.diagnostic(agent, "move-stuck", "goal=" + goal.getGoalType()
                        + " from=" + villager.blockPosition() + " target=" + target
                        + " navDone=" + villager.getNavigation().isDone());
                agent.addMemory("Couldn't reach " + target.getX() + "," + target.getZ());
                goal.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                return SkillResult.FAIL;
            }
            if (villager.getNavigation().isDone() || cur.getStuckTicks() == 80) {
                boolean started = villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
                if (!started) {
                    BrainLog.diagnostic(agent, "move-no-path", "goal=" + goal.getGoalType()
                            + " from=" + villager.blockPosition() + " target=" + target);
                    goal.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                    agent.setCurrentAction(null);
                    return SkillResult.FAIL;
                }
            }
            return SkillResult.RUNNING;
        }

        if (cur != null) return SkillResult.RUNNING; // some unrelated action in progress

        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE,
                "Walking for " + goal.getGoalType() + " toward " + target.getX() + "," + target.getZ());
        move.setTargetBlockPos(target);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        move.setGoalDriven(true);
        move.setRestMove(goal.getArrivalDistanceSq() <= 4);
        agent.setCurrentAction(move);
        boolean started = villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
        BrainLog.diagnostic(agent, "move-start", "goal=" + goal.getGoalType() + " from="
                + villager.blockPosition() + " target=" + target + " pathStarted=" + started);
        if (!started) {
            goal.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
            agent.setCurrentAction(null);
            return SkillResult.FAIL;
        }
        return SkillResult.RUNNING;
    }

    /**
     * Once per in-game day, drop a random portion of the lowest-importance goals so the
     * villager's agenda stays fresh instead of accumulating forever.
     */
    private static void pruneLowImportanceGoals(VillagerAgentData agent) {
        List<AgentGoal> goals = agent.getGoals();
        int before = goals.size();
        // REPEATABLE goals are the villager's job — never randomly pruned; the routine generator owns them.
        List<AgentGoal> pruned = new ArrayList<>();
        for (AgentGoal g : goals) {
            if (g.getLifecycle() != AgentGoal.Lifecycle.REPEATABLE
                    && g.getImportance() <= LOW_IMPORTANCE_THRESHOLD
                    && RANDOM.nextDouble() < LOW_GOAL_DROP_CHANCE) {
                pruned.add(g);
            }
        }
        goals.removeAll(pruned);
        for (AgentGoal g : pruned) BrainLog.goalEnded(agent, g, "pruned(low_importance)");
        if (!pruned.isEmpty()) {
            LOGGER.debug("{} pruned {} low-importance goals (kept {})",
                    agent.getName(), before - goals.size(), goals.size());
        }
    }

    /**
     * When a villager thinks, it may deepen its commitment to a random subset of its goals
     * by nudging their importance up — so goals the villager keeps thinking about survive
     * the daily prune, while neglected ones fade away.
     */
    private static void boostSomeGoals(VillagerAgentData agent) {
        List<AgentGoal> goals = agent.getGoals();
        if (goals.isEmpty()) return;
        if (RANDOM.nextDouble() >= GOAL_BOOST_CHANCE) return;
        List<AgentGoal> copy = new ArrayList<>(goals);
        Collections.shuffle(copy);
        int k = Math.max(1, copy.size() / 4);
        for (int i = 0; i < Math.min(k, copy.size()); i++) {
            copy.get(i).boostImportance();
        }
    }

    /**
     * Parse GOAL: directives the LLM may append to a thought. One per line:
     *   GOAL: &lt;type&gt;|&lt;description&gt;|&lt;importance 1-10&gt;[|&lt;targetItem&gt;]
     * Returns the parsed goals (the caller decides whether to add them, subject to MAX_GOALS).
     */
    private static List<AgentGoal> parseGoalDirectives(String thought) {
        List<AgentGoal> out = new ArrayList<>();
        for (String line : thought.split("\n")) {
            line = line.trim();
            if (!line.toUpperCase().startsWith("GOAL:")) continue;
            String body = line.substring(5).trim();
            String[] parts = body.split("\\|");
            if (parts.length < 2) continue;
            String type = parts[0].trim().toLowerCase();
            if (!VALID_GOAL_TYPES.contains(type)) continue;
            String desc = parts[1].trim();
            int importance = 5;
            if (parts.length >= 3) {
                try {
                    importance = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) { /* keep default */ }
            }
            AgentGoal g = new AgentGoal(type, desc, 5);
            g.setImportance(importance);
            if (type.equals("craft") && parts.length >= 4) {
                g.setTargetItem(parts[3].trim());
            } else if (type.equals("goto") && parts.length >= 4) {
                g.setTargetBuildingType(parts[3].trim());
            }
            out.add(g);
        }
        return out;
    }

    /** Remove GOAL: lines from a thought so players don't see the directive syntax. */
    private static String stripGoalLines(String thought) {
        StringBuilder sb = new StringBuilder();
        for (String line : thought.split("\n")) {
            if (line.trim().toUpperCase().startsWith("GOAL:")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /** Apply a random enchantment to the first matching stack in the inventory (Master-level craft). */
    public static void enchantFirstStack(VillagerAgentData agent, Item target, VillagerEntity villager) {
        AgentInventory inv = agent.getInventory();
        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack s = inv.getItems().get(i);
            if (!s.isEmpty() && s.getItem() == target) {
                // In 1.16.5 MCP this is named enchantItem (formerly addRandomEnchantment); it returns the enchanted stack.
                ItemStack enchanted = EnchantmentHelper.enchantItem(
                        villager.getRandom(), s, 5 + villager.getRandom().nextInt(15), false);
                inv.getItems().set(i, enchanted);
                return;
            }
        }
    }

    /** Pick the nearest idle villager to interact with (optionally filtered by profession/entityId). */
    public static VillagerAgentData resolveVillagerPartner(ServerWorld sw, VillagerEntity villager,
                                                         VillagerAgentData agent, AgentGoal goal) {
        UUID entityId = goal.getTargetEntityId();
        if (entityId != null) {
            VillagerAgentData a = getAgent(entityId);
            if (a != null && !a.getVillagerId().equals(agent.getVillagerId())) return a;
            return null;
        }
        String profession = goal.getTargetProfession();
        VillagerAgentData best = null;
        double bestD = Double.MAX_VALUE;
        for (VillagerAgentData other : getAllAgents()) {
            if (other.getVillagerId().equals(agent.getVillagerId())) continue;
            if (other.isSocializing() || other.getCurrentAction() != null) continue; // busy
            if (profession != null && !profession.isEmpty()
                    && !profession.equalsIgnoreCase(other.getProfession())) continue;
            Entity e = sw.getEntity(other.getVillagerId());
            if (e instanceof VillagerEntity) {
                double d = villager.distanceToSqr(e);
                if (d < bestD) { bestD = d; best = other; }
            }
        }
        return best;
    }

    public static BlockPos resolveConverseTarget(ServerWorld sw, VillagerEntity villager,
                                                  VillagerAgentData agent, AgentGoal goal) {
        String kind = goal.getTargetKind();
        UUID entityId = goal.getTargetEntityId();
        String profession = goal.getTargetProfession();

        // A specific entity, if named.
        if (entityId != null) {
            Entity e = sw.getEntity(entityId);
            return (e != null) ? e.blockPosition() : null;
        }

        if ("player".equals(kind)) {
            ServerPlayerEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayerEntity p : sw.getPlayers(pl -> true)) {
                double d = villager.distanceToSqr(p);
                if (d < best) { best = d; nearest = p; }
            }
            return nearest != null ? nearest.blockPosition() : null;
        }

        if ("villager".equals(kind)) {
            VillagerEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (VillagerAgentData other : getAllAgents()) {
                if (other.getVillagerId().equals(agent.getVillagerId())) continue;
                if (profession != null && !profession.isEmpty()
                        && !profession.equalsIgnoreCase(other.getProfession())) continue;
                Entity e = sw.getEntity(other.getVillagerId());
                if (e instanceof VillagerEntity) {
                    double d = villager.distanceToSqr(e);
                    if (d < best) { best = d; nearest = (VillagerEntity) e; }
                }
            }
            return nearest != null ? nearest.blockPosition() : null;
        }

        return null;
    }

    public static void onConverseArrive(VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        ServerWorld sw = (ServerWorld) villager.level;
        if ("player".equals(goal.getTargetKind())) {
            UUID playerId = goal.getTargetEntityId();
            if (playerId == null) {
                ServerPlayerEntity nearest = null;
                double best = Double.MAX_VALUE;
                for (ServerPlayerEntity p : sw.getPlayers(pl -> true)) {
                    double d = villager.distanceToSqr(p);
                    if (d < best) { best = d; nearest = p; }
                }
                if (nearest != null) playerId = nearest.getUUID();
            }
            if (playerId != null) {
                agent.addMemory("Talked with a player about my business");
                bumpCollectionAttempts(agent, playerId);
            }
        } else {
            agent.addMemory("Conversed with a fellow villager");
        }
    }

    private static void bumpCollectionAttempts(VillagerAgentData agent, UUID playerId) {
        String pid = playerId.toString();
        for (LongTermAgenda a : agent.getAgendas()) {
            if (a.isResolved() || !(a instanceof DebtAgenda)) continue;
            DebtAgenda debt = (DebtAgenda) a;
            if (pid.equals(debt.getDebtorId())) {
                debt.setCollectionAttempts(debt.getCollectionAttempts() + 1);
                if (debt.getCollectionAttempts() >= 3) {
                    agent.getPlayerReputation().merge(playerId, -5, Integer::sum);
                    agent.addMemory("This player keeps making me work without paying — I'm losing trust");
                    LOGGER.info("{} lowered reputation of {} (debt '{}' dodged {}x)",
                            agent.getName(), pid, debt.getTitle(), debt.getCollectionAttempts());
                }
            }
        }
    }

    /**
     * True if a building of type {@code actual} satisfies the requested type {@code want}.
     * "any"/"building"/null match everything; "home" is treated as "house".
     */
    public static boolean matchesBuildingType(String actual, String want) {
        if (want == null || want.isEmpty()) return true;
        String w = want.toLowerCase();
        if (w.equals("any") || w.equals("building")) return true;
        if (w.equals("home")) w = "house";
        return w.equals(actual == null ? "" : actual.toLowerCase());
    }

    /**
     * Get all agents (for debugging/admin purposes)
     */
    public static Collection<VillagerAgentData> getAllAgents() {
        return agents.values();
    }

    /** Find the agent whose villager is closest to a world position (for command targeting). */
    public static VillagerAgentData getNearestAgent(World world, double x, double y, double z) {
        VillagerAgentData best = null;
        double bestD = Double.MAX_VALUE;
        for (VillagerAgentData a : agents.values()) {
            VillagerEntity v = findVillagerEntity(world, a.getVillagerId());
            if (v == null || !v.isAlive()) continue;
            double dx = v.getX() - x, dy = v.getY() - y, dz = v.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    /**
     * Get the number of active agents
     */
    public static int getAgentCount() {
        return agents.size();
    }

    /** Hard cap on simultaneous goals (exposed for callers that inject goals, e.g. chat parsing). */
    public static int getMaxGoals() {
        return MAX_GOALS;
    }
}

