package me.foesio.foAuction.api.event;

import java.util.Objects;
import java.util.UUID;
import me.foesio.foAuction.api.AuctionListingSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** An unsold listing ended and its item was returned, taken, or discarded. Purchases use ListingPurchasedEvent. */
public final class ListingEndedEvent extends Event {
    public enum Reason { SELLER_CANCELLED, EXPIRED, ADMIN_RETURNED, ADMIN_TAKEN }
    private static final HandlerList HANDLERS = new HandlerList();
    private final AuctionListingSnapshot listing;
    private final Reason reason;
    private final UUID actorId;

    public ListingEndedEvent(AuctionListingSnapshot listing, Reason reason, UUID actorId) {
        super(!Bukkit.isPrimaryThread());
        this.listing = Objects.requireNonNull(listing, "listing");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.actorId = actorId;
    }

    public AuctionListingSnapshot getListing() { return listing; }
    public Reason getReason() { return reason; }
    /** Seller for cancellation, administrator for admin action, null for expiration. */
    public UUID getActorId() { return actorId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
