package me.foesio.foAuction.gui.holder;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MyListingsHolder implements InventoryHolder {
    private final UUID viewerUuid;
    private final int page;
    private final int maxPage;
    private final Inventory inventory;
    private final Map<Integer, UUID> listingBySlot;

    public MyListingsHolder(UUID viewerUuid, int page, int maxPage, String title) {
        this.viewerUuid = viewerUuid;
        this.page = page;
        this.maxPage = maxPage;
        this.inventory = Bukkit.createInventory(this, 54, title);
        this.listingBySlot = new HashMap<>();
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public int getPage() {
        return page;
    }

    public int getMaxPage() {
        return maxPage;
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
