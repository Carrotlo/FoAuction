package me.foesio.foAuction.api.event;

import java.util.Objects;
import me.foesio.foAuction.api.AuctionListingSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A listing entered the market after any fee and item transfer. Never fired for rejected requests. */
public final class ListingCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final AuctionListingSnapshot listing;
    private final double listingFee;

    public ListingCreatedEvent(AuctionListingSnapshot listing, double listingFee) {
        super(!Bukkit.isPrimaryThread());
        this.listing = Objects.requireNonNull(listing, "listing");
        this.listingFee = listingFee;
    }

    public AuctionListingSnapshot getListing() { return listing; }
    public double getListingFee() { return listingFee; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
