package me.foesio.foAuction.gui.holder;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AuctionMainHolder implements InventoryHolder {
    private final UUID viewerUuid;
    private final int page;
    private final Inventory inventory;
    private long itemRevision = Long.MIN_VALUE;
    public long getItemRevision() { return itemRevision; }
    public void setItemRevision(long revision) { itemRevision = revision; }
    private final Map<Integer, UUID> listingBySlot;

    public AuctionMainHolder(UUID viewerUuid, int page, String title) {
        this.viewerUuid = viewerUuid;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, title);
        this.listingBySlot = new HashMap<>();
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public int getPage() {
        return page;
    }

    public void mapSlot(int slot, UUID listingId) {
        listingBySlot.put(slot, listingId);
    }

    public UUID getListingId(int slot) {
        return listingBySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
