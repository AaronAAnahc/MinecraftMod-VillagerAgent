package com.github.AaronAA0721.villageragent.ai.proficiency;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Capability provider that owns the {@link VillagerProficiency} instance for a villager.
 * Implementing {@link ICapabilitySerializable} means Forge auto-saves/restores it with
 * the entity — no extra save/load hooks required.
 */
public class VillagerProficiencyProvider implements ICapabilitySerializable<CompoundNBT> {

    private final VillagerProficiency instance = new VillagerProficiency();

    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        return ProficiencyCapability.CAPABILITY.orEmpty(cap, LazyOptional.of(() -> instance));
    }

    @Override
    public CompoundNBT serializeNBT() {
        return instance.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundNBT nbt) {
        instance.deserializeNBT(nbt);
    }
}
