package me.foesio.foAuction.api.event;

import java.util.Objects;
import java.util.UUID;
import me.foesio.foAuction.api.AuctionListingSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Buyer charged and item delivered; player sellers were paid, while NPC proceeds were discarded. */
public final class ListingPurchasedEvent extends Event {
    public enum Delivery { INVENTORY, CLAIMS, PARTIAL_CLAIMS }
    private static final HandlerList HANDLERS = new HandlerList();
    private final AuctionListingSnapshot listing;
    private final UUID buyerId;
    private final Delivery delivery;

    public ListingPurchasedEvent(AuctionListingSnapshot listing, UUID buyerId, Delivery delivery) {
        super(!Bukkit.isPrimaryThread());
        this.listing = Objects.requireNonNull(listing, "listing");
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public AuctionListingSnapshot getListing() { return listing; }
    public UUID getBuyerId() { return buyerId; }
    public Delivery getDelivery() { return delivery; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
