package com.github.AaronAA0721.villageragent.content;

import net.minecraft.block.*;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.state.*;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.*;
import net.minecraft.world.IBlockReader;

/** Two-sided wooden notice panel; generated above a separate oak fence. */
public final class NoticeBoardBlock extends HorizontalBlock {
    public NoticeBoardBlock() {
        super(AbstractBlock.Properties.copy(Blocks.OAK_PLANKS).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    // AbstractBlock.use is deprecated only for direct calls; overriding it is the supported hook.
    // BlockState.use forwards here, so suppressing the warning is safe.
    @SuppressWarnings("deprecation")
    @Override public ActionResultType use(BlockState state, net.minecraft.world.World world, BlockPos pos,
                                          net.minecraft.entity.player.PlayerEntity player, Hand hand,
                                          net.minecraft.util.math.BlockRayTraceResult hit) {
        if (!world.isClientSide && player instanceof net.minecraft.entity.player.ServerPlayerEntity)
            com.github.AaronAA0721.villageragent.network.NoticeBoardPagePacket.send(
                    (net.minecraft.entity.player.ServerPlayerEntity)player,pos,true,0,true);
        return ActionResultType.sidedSuccess(world.isClientSide);
    }
    @Override protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockItemUseContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @SuppressWarnings("deprecation")
    @Override public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X
                ? Block.box(6, 0, 0, 10, 16, 16) : Block.box(0, 0, 6, 16, 16, 10);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.mirror(mirror); }
}
