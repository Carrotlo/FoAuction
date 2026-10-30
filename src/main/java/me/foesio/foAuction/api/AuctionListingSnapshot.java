package me.foesio.foAuction.api;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** A detached view of one listing. The item is copied on construction and access. */
public record AuctionListingSnapshot(UUID id, UUID sellerId, ItemStack item, double price,
                                     long createdAt, long expiresAt, String sellerName,
                                     boolean virtualSeller) {
    public AuctionListingSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerId, "sellerId");
        item = Objects.requireNonNull(item, "item").clone();
        sellerName = Objects.requireNonNull(sellerName, "sellerName");
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}
