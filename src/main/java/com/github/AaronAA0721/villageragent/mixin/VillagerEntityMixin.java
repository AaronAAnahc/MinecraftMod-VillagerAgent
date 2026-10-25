package com.github.AaronAA0721.villageragent.mixin;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Managed adult villagers use the rule brain for all intentions. Vanilla navigation and
 * physics still advance normally; vanilla activity scheduling never resumes between actions. */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {

    /**
     * Intercept the vanilla brain tick. If our agent system is actively controlling
     * this villager, cancel the entire method so vanilla AI doesn't interfere.
     */
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void villageragent$onCustomServerAiStep(CallbackInfo ci) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) {
            return; // Mod disabled — let vanilla run
        }

        VillagerEntity self = (VillagerEntity) (Object) this;
        VillagerAgentData agent = VillagerAgentManager.getAgent(self.getUUID());

        if (agent == null) {
            return; // No agent data — let vanilla run
        }

        // Suppress vanilla brain when our agent is actively doing something.
        // This prevents vanilla WORK/IDLE/MEET/REST activities from issuing
        // competing navigation commands.
        // A sleeping villager also counts as "active": SleepSkill drives sleep, and vanilla
        // wandering at night would otherwise drag the lying villager around.
        if (!self.isBaby()) {
            self.getNavigation().setCanFloat(true);
            net.minecraft.world.server.ServerWorld world = (net.minecraft.world.server.ServerWorld) self.level;
            net.minecraft.entity.ai.brain.task.SwimTask swim = agent.brain().swim;
            if (swim.getStatus() == net.minecraft.entity.ai.brain.task.Task.Status.STOPPED)
                swim.tryStart(world, self, world.getGameTime());
            if (swim.getStatus() == net.minecraft.entity.ai.brain.task.Task.Status.RUNNING)
                swim.tickOrStop(world, self, world.getGameTime());
            ci.cancel();
        }
    }

    /**
     * Prevent vanilla from picking up items into the hidden 8-slot vanilla inventory
     * when our agent system is active.
     *
     * <p>Without this, vanilla {@code VillagerEntity.pickUpItem()} (called from
     * {@code MobEntity.serverAiStep()}, which is separate from {@code customServerAiStep()})
     * grabs crop drops (wheat, seeds, carrots, potatoes, etc.) into the vanilla inventory.
     * Those items become invisible to our {@code AgentInventory} — they appear "lost".
     *
     * <p>By returning false here, all item pickup goes through our
     * {@code ItemAttractionSystem} into the {@code AgentInventory} instead.
     */
    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void villageragent$onWantsToPickUp(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) {
            return;
        }

        VillagerEntity self = (VillagerEntity) (Object) this;
        VillagerAgentData agent = VillagerAgentManager.getAgent(self.getUUID());

        if (agent != null) {
            // Always block vanilla pickup for managed villagers.
            // Our ItemAttractionSystem handles all item collection.
            cir.setReturnValue(false);
        }
    }
}

