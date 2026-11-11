package com.github.AaronAA0721.villageragent.mixin;

import com.github.AaronAA0721.villageragent.ai.VillagerPathfinding;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.world.IBlockReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Open (upright) trapdoors for villagers. Vanilla classifies every trapdoor as {@link PathNodeType#TRAPDOOR},
 * which costs nothing, so a path runs straight through an open trapdoor standing on the ground; the thin,
 * full-height plate then stops the villager, and since the node is "level" he never jumps — he pushes against
 * it forever. For villagers only, a cell holding an open trapdoor at body height is {@link PathNodeType#BLOCKED}
 * (like a fence), so paths go around it. Other mobs keep vanilla behaviour (mob farms rely on it).
 * Covers both the navigator's own searches and the isolated searches of {@link VillagerPathfinding}.
 */
@Mixin(WalkNodeProcessor.class)
public abstract class WalkNodeProcessorMixin {

    @Inject(method = "getBlockPathType(Lnet/minecraft/world/IBlockReader;IIILnet/minecraft/entity/MobEntity;IIIZZ)Lnet/minecraft/pathfinding/PathNodeType;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void villageragent$blockOpenTrapdoors(IBlockReader world, int x, int y, int z, MobEntity mob,
                                                  int xSize, int ySize, int zSize, boolean canBreakDoors,
                                                  boolean canEnterDoors, CallbackInfoReturnable<PathNodeType> cir) {
        if (!(mob instanceof VillagerEntity)) return;
        PathNodeType type = cir.getReturnValue();
        if (type == PathNodeType.BLOCKED) return;
        if (VillagerPathfinding.openTrapdoorInBody(world, x, y, z, xSize, ySize, zSize))
            cir.setReturnValue(PathNodeType.BLOCKED);
    }
}
