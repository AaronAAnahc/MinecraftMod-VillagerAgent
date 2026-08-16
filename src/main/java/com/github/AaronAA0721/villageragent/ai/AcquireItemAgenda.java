package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.nbt.CompoundNBT;

/**
 * A persistent desire to acquire some item — often one that first requires gathering
 * materials or crafting. The LLM derives short-term todos (gather X, craft Y) from this
 * during reflection, but the acquisition itself stays until satisfied.
 */
public class AcquireItemAgenda extends LongTermAgenda {

    private String itemId;     // registry name of the item we want
    private int quantity;
    private String source;     // free-form: "craft", "gather", "trade with blacksmith", ...

    public AcquireItemAgenda(String title, long createdAt) {
        super("acquire", title, createdAt);
    }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("[acquire] ").append(getTitle());
        if (itemId != null) sb.append(" — want ").append(quantity).append("x ").append(shortName(itemId));
        if (source != null && !source.isEmpty()) sb.append(" (source: ").append(source).append(")");
        return sb.toString();
    }

    @Override
    protected void writeExtra(CompoundNBT nbt) {
        if (itemId != null) nbt.putString("ItemId", itemId);
        nbt.putInt("Quantity", quantity);
        if (source != null) nbt.putString("Source", source);
    }

    @Override
    protected void readExtra(CompoundNBT nbt) {
        if (nbt.contains("ItemId")) itemId = nbt.getString("ItemId");
        quantity = nbt.getInt("Quantity");
        if (nbt.contains("Source")) source = nbt.getString("Source");
    }
}
