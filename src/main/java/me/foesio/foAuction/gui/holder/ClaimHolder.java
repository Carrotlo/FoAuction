package me.foesio.foAuction.gui.holder;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClaimHolder implements InventoryHolder {
    private final UUID viewerUuid;
    private final int page;
    private final int maxPage;
    private final Inventory inventory;
    private final Map<Integer, UUID> claimIdBySlot;

    public ClaimHolder(UUID viewerUuid, int page, int maxPage, String title) {
        this.viewerUuid = viewerUuid;
        this.page = page;
        this.maxPage = maxPage;
        this.inventory = Bukkit.createInventory(this, 54, title);
        this.claimIdBySlot = new HashMap<>();
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

    public void mapSlot(int slot, UUID claimId) {
        claimIdBySlot.put(slot, claimId);
    }

    public UUID getClaimId(int slot) {
        return claimIdBySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
