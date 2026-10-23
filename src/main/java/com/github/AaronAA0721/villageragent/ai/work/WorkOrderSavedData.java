package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import java.util.UUID;

/** One order book per dimension. Claims are released on load; demand survives restarts. */
public final class WorkOrderSavedData extends WorldSavedData {
    public final WorkOrderBook book = new WorkOrderBook();
    public WorkOrderSavedData() { super("villageragent_work_orders"); }
    public static WorkOrderSavedData get(ServerWorld world) {
        return world.getDataStorage().computeIfAbsent(WorkOrderSavedData::new, "villageragent_work_orders");
    }
    @Override public void load(CompoundNBT nbt) {
        ListNBT list = nbt.getList("Orders", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT t = list.getCompound(i);
            if (!t.hasUUID("Id") || !t.hasUUID("Requester")) continue;
            WorkOrderBook.Order o = new WorkOrderBook.Order(t.getUUID("Id"), t.getUUID("Requester"),
                    t.getString("Item"), ResourceProfession.parse(t.getString("Profession")),
                    t.getInt("TargetStock"), t.getInt("Remaining"), t.getLong("ExpiresAt"));
            if (o.remaining > 0) book.restore(o);
        }
    }
    @Override public CompoundNBT save(CompoundNBT nbt) {
        ListNBT list = new ListNBT();
        for (WorkOrderBook.Order o : book.all()) {
            if (!o.active()) continue;
            CompoundNBT t = new CompoundNBT();
            t.putUUID("Id", o.id); t.putUUID("Requester", o.requester); t.putString("Item", o.item);
            t.putString("Profession", o.profession.name()); t.putInt("TargetStock", o.targetStock);
            t.putInt("Remaining", o.remaining); t.putLong("ExpiresAt", o.expiresAt);
            list.add(t);
        }
        nbt.put("Orders", list); return nbt;
    }
}
