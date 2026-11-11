package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.content.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

/** Client-only registrations, called from {@code FMLClientSetupEvent}. */
@OnlyIn(Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    public static void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.VILLAGER_BOBBER.get(), VillagerBobberRenderer::new);
    }
}
