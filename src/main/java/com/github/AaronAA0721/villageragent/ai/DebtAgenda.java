package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;

/**
 * A debt: some entity (usually the player) owes this villager items.
 *
 * <p>The debt itself persists across many collection attempts — each attempt is a separate
 * short-term todo, while the debt stays until settled. Settlement is driven by the trade
 * economy (when the debtor actually hands over the goods), not by the LLM alone.
 */
public class DebtAgenda extends LongTermAgenda {

    private String debtorId;       // UUID string of who owes us
    private String debtorName;     // display name (prompt-friendly)
    private String itemId;         // what is owed (registry name)
    private int quantity;
    private String reason;         // why it is owed
    private int collectionAttempts;
    private long lastCollectionTick;

    public DebtAgenda(String title, long createdAt) {
        super("debt", title, createdAt);
    }

    public String getDebtorId() { return debtorId; }
    public void setDebtorId(String debtorId) { this.debtorId = debtorId; }
    public String getDebtorName() { return debtorName; }
    public void setDebtorName(String debtorName) { this.debtorName = debtorName; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public int getCollectionAttempts() { return collectionAttempts; }
    public void setCollectionAttempts(int collectionAttempts) { this.collectionAttempts = collectionAttempts; }
    public long getLastCollectionTick() { return lastCollectionTick; }
    public void setLastCollectionTick(long lastCollectionTick) { this.lastCollectionTick = lastCollectionTick; }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("[debt] ").append(getTitle());
        if (itemId != null) {
            sb.append(" — owes ").append(quantity).append("x ").append(shortName(itemId));
        }
        if (debtorName != null) sb.append(" from ").append(debtorName);
        if (debtorId != null) sb.append(" [debtor uuid: ").append(debtorId).append("]");
        if (reason != null && !reason.isEmpty()) sb.append(" (").append(reason).append(")");
        return sb.toString();
    }

    @Override
    protected void writeExtra(CompoundNBT nbt) {
        if (debtorId != null) nbt.putString("DebtorId", debtorId);
        if (debtorName != null) nbt.putString("DebtorName", debtorName);
        if (itemId != null) nbt.putString("ItemId", itemId);
        nbt.putInt("Quantity", quantity);
        if (reason != null) nbt.putString("Reason", reason);
        nbt.putInt("Attempts", collectionAttempts);
        nbt.putLong("LastCollectionTick", lastCollectionTick);
    }

    @Override
    protected void readExtra(CompoundNBT nbt) {
        if (nbt.contains("DebtorId")) debtorId = nbt.getString("DebtorId");
        if (nbt.contains("DebtorName")) debtorName = nbt.getString("DebtorName");
        if (nbt.contains("ItemId")) itemId = nbt.getString("ItemId");
        quantity = nbt.getInt("Quantity");
        if (nbt.contains("Reason")) reason = nbt.getString("Reason");
        collectionAttempts = nbt.getInt("Attempts");
        lastCollectionTick = nbt.getLong("LastCollectionTick");
    }
}
