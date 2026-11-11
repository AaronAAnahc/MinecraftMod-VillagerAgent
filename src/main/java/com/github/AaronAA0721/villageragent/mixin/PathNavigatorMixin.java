package com.github.AaronAA0721.villageragent.mixin;

import com.github.AaronAA0721.villageragent.ai.VillagerPathfinding;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigator;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooth path replacement for managed villagers.
 * <ul>
 *   <li>Every newly installed path skips the node the villager already stands in (no step back).</li>
 *   <li>Block-update recomputes (doors, harvested crops, felled logs) are searched off-thread while the
 *       villager keeps following its current path.</li>
 * </ul>
 * Injectors use {@code require = 0}: if a mapping ever differs, vanilla behaviour is kept instead of
 * failing to load.
 */
@Mixin(PathNavigator.class)
public abstract class PathNavigatorMixin {
    @Shadow @Final protected MobEntity mob;
    @Shadow protected Path path;
    @Shadow private BlockPos targetPos;
    @Shadow private int reachRange;

    @Inject(method = "moveTo(Lnet/minecraft/pathfinding/Path;D)Z", at = @At("HEAD"), require = 0)
    private void villageragent$smoothSwap(Path next, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (!VillagerPathfinding.managed(mob)) return;
        VillagerPathfinding.recordSpeed((PathNavigator) (Object) this, speed);
        if (next != null && next != path && !next.sameAs(path)) VillagerPathfinding.skipBehind(mob, next);
    }

    @Inject(method = "recomputePath()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void villageragent$asyncRecompute(CallbackInfo ci) {
        if (!VillagerPathfinding.managed(mob) || !VillagerPathfinding.asyncEnabled()) return;
        if (targetPos == null || path == null || path.isDone()) return; // nothing to keep following
        PathNavigator self = (PathNavigator) (Object) this;
        VillagerPathfinding.request((VillagerEntity) mob, targetPos, reachRange,
                VillagerPathfinding.lastSpeed(self), null, "block-update");
        ci.cancel();
    }

    /**
     * Vanilla advances to the node after next whenever that node lies "ahead" in 3-D. On a path that steps up
     * onto a block and down the other side (pumpkin/melon rows, raised beds, fences), the step-up node is
     * skipped and the villager walks straight into the block. Managed villagers only cut a corner when it is
     * level and the straight line is clear.
     */
    @Inject(method = "shouldTargetNextNodeInDirection", at = @At("HEAD"), cancellable = true, require = 0)
    private void villageragent$safeCornerCut(net.minecraft.util.math.vector.Vector3d mobPos,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (path == null || !VillagerPathfinding.managed(mob)) return;
        if (VillagerPathfinding.unsafeCornerCut(mob, path)) cir.setReturnValue(false);
    }

    @Inject(method = "recomputePath()V", at = @At("RETURN"), require = 0)
    private void villageragent$trimRecomputed(CallbackInfo ci) {
        if (path != null && VillagerPathfinding.managed(mob)) VillagerPathfinding.skipBehind(mob, path);
    }
}
