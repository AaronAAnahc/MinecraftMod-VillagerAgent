package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;

/**
 * An inter-villager deal: another villager proposed a trade, and to fulfil it this villager
 * must first collect what the counterparty wants. Drives short-term acquisition todos until
 * the goods are gathered and delivered.
 */
public class DealAgenda extends LongTermAgenda {

    private String counterpartyId;    // UUID string of the other villager
    private String counterpartyName;  // display name
    private String wantedItem;        // what THEY want (we must gather & deliver)
    private int wantedQuantity;
    private String offeredItem;       // what we receive in return
    private int offeredQuantity;

    public DealAgenda(String title, long createdAt) {
        super("deal", title, createdAt);
    }

    public String getCounterpartyId() { return counterpartyId; }
    public void setCounterpartyId(String counterpartyId) { this.counterpartyId = counterpartyId; }
    public String getCounterpartyName() { return counterpartyName; }
    public void setCounterpartyName(String counterpartyName) { this.counterpartyName = counterpartyName; }
    public String getWantedItem() { return wantedItem; }
    public void setWantedItem(String wantedItem) { this.wantedItem = wantedItem; }
    public int getWantedQuantity() { return wantedQuantity; }
    public void setWantedQuantity(int wantedQuantity) { this.wantedQuantity = wantedQuantity; }
    public String getOfferedItem() { return offeredItem; }
    public void setOfferedItem(String offeredItem) { this.offeredItem = offeredItem; }
    public int getOfferedQuantity() { return offeredQuantity; }
    public void setOfferedQuantity(int offeredQuantity) { this.offeredQuantity = offeredQuantity; }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("[deal] ").append(getTitle());
        if (counterpartyName != null) sb.append(" — with ").append(counterpartyName);
        if (wantedItem != null) sb.append("; they want ").append(wantedQuantity).append("x ").append(shortName(wantedItem));
        if (offeredItem != null) sb.append("; in return I get ").append(offeredQuantity).append("x ").append(shortName(offeredItem));
        return sb.toString();
    }

    @Override
    protected void writeExtra(CompoundNBT nbt) {
        if (counterpartyId != null) nbt.putString("CounterpartyId", counterpartyId);
        if (counterpartyName != null) nbt.putString("CounterpartyName", counterpartyName);
        if (wantedItem != null) nbt.putString("WantedItem", wantedItem);
        nbt.putInt("WantedQuantity", wantedQuantity);
        if (offeredItem != null) nbt.putString("OfferedItem", offeredItem);
        nbt.putInt("OfferedQuantity", offeredQuantity);
    }

    @Override
    protected void readExtra(CompoundNBT nbt) {
        if (nbt.contains("CounterpartyId")) counterpartyId = nbt.getString("CounterpartyId");
        if (nbt.contains("CounterpartyName")) counterpartyName = nbt.getString("CounterpartyName");
        if (nbt.contains("WantedItem")) wantedItem = nbt.getString("WantedItem");
        wantedQuantity = nbt.getInt("WantedQuantity");
        if (nbt.contains("OfferedItem")) offeredItem = nbt.getString("OfferedItem");
        offeredQuantity = nbt.getInt("OfferedQuantity");
    }
}
