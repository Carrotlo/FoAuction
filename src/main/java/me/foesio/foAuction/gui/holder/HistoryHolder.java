package me.foesio.foAuction.gui.holder;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class HistoryHolder implements InventoryHolder {
    private final UUID viewerUuid;
    private final UUID sellerUuid;
    private final int page;
    private final int maxPage;
    private final int returnPage;
    private final Inventory inventory;

    public HistoryHolder(UUID viewerUuid, UUID sellerUuid, int page, int maxPage, int returnPage, String title) {
        this.viewerUuid = viewerUuid;
        this.sellerUuid = sellerUuid;
        this.page = page;
        this.maxPage = maxPage;
        this.returnPage = returnPage;
        this.inventory = Bukkit.createInventory(this, 54, title);
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public int getPage() {
        return page;
    }

    public int getMaxPage() {
        return maxPage;
    }

    public int getReturnPage() {
        return returnPage;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
