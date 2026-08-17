package com.github.AaronAA0721.villageragent.ai.proficiency;

import com.github.AaronAA0721.villageragent.Villageragent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Attaches the {@link VillagerProficiency} capability to every villager so proficiency
 * exists for all villagers (not just AI-enabled ones). This is the "build on top of the
 * vanilla entity" hook — we extend the entity rather than replace its leveling.
 */
@Mod.EventBusSubscriber(modid = Villageragent.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ProficiencyEvents {

    private static final ResourceLocation KEY =
            new ResourceLocation(Villageragent.MOD_ID, "villager_proficiency");

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        Entity entity = event.getObject();
        if (!(entity instanceof VillagerEntity)) return;

        ICapabilityProvider provider = new VillagerProficiencyProvider();
        event.addCapability(KEY, provider);
    }
}
