package me.foesio.foAuction.gui.holder;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class ConfirmationHolder implements InventoryHolder {
    public enum Type {
        SELL,
        BUY
    }

    private final UUID playerUuid;
    private final Type type;
    private final double price;
    private final double listingFee;
    private final UUID listingId;
    private final int returnPage;
    private final UUID sellerUuid;
    private final Inventory inventory;
    private long itemRevision = Long.MIN_VALUE;
    public long getItemRevision() { return itemRevision; }
    public void setItemRevision(long revision) { itemRevision = revision; }

    private ConfirmationHolder(
            UUID playerUuid,
            Type type,
            double price,
            double listingFee,
            UUID listingId,
            int returnPage,
            UUID sellerUuid,
            String title
    ) {
        this.playerUuid = playerUuid;
        this.type = type;
        this.price = price;
        this.listingFee = listingFee;
        this.listingId = listingId;
        this.returnPage = returnPage;
        this.sellerUuid = sellerUuid;
        this.inventory = Bukkit.createInventory(this, 27, title);
    }

    public static ConfirmationHolder forSell(UUID playerUuid, double price, double listingFee, String title) {
        return new ConfirmationHolder(
                playerUuid,
                Type.SELL,
                price,
                listingFee,
                null,
                0,
                null,
                title
        );
    }

    public static ConfirmationHolder forBuy(
            UUID playerUuid,
            UUID listingId,
            double price,
            int returnPage,
            UUID sellerUuid,
            String title
    ) {
        return new ConfirmationHolder(
                playerUuid,
                Type.BUY,
                price,
                0D,
                listingId,
                returnPage,
                sellerUuid,
                title
        );
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Type getType() {
        return type;
    }

    public boolean isSell() {
        return type == Type.SELL;
    }

    public boolean isBuy() {
        return type == Type.BUY;
    }

    public double getPrice() {
        return price;
    }

    public double getListingFee() {
        return listingFee;
    }

    public UUID getListingId() {
        return listingId;
    }

    public int getReturnPage() {
        return returnPage;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
