package com.github.AaronAA0721.villageragent.content;

import com.github.AaronAA0721.villageragent.ai.VillageNoticeBoards;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Player-placed notice boards are linked to the nearest village; breaking the linked board unlinks it. */
@Mod.EventBusSubscriber(modid = "villageragent", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NoticeBoardEvents {
    private NoticeBoardEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getWorld() instanceof ServerWorld)
                || !event.getPlacedBlock().is(Workstations.NOTICE_BOARD.get())) return;
        Long village = VillageNoticeBoards.adopt((ServerWorld) event.getWorld(), event.getPos());
        if (event.getEntity() instanceof PlayerEntity) {
            PlayerEntity player = (PlayerEntity) event.getEntity();
            player.displayClientMessage(new StringTextComponent(village != null
                    ? TextFormatting.GREEN + "Notice board linked to this village."
                    : TextFormatting.YELLOW + "No village within 32 blocks; this board is not linked."), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getWorld() instanceof ServerWorld)
                || !event.getState().is(Workstations.NOTICE_BOARD.get())) return;
        VillageNoticeBoards.removed((ServerWorld) event.getWorld(), event.getPos());
    }
}
