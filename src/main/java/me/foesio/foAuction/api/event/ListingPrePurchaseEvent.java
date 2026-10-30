package me.foesio.foAuction.api.event;

import java.util.Objects;
import me.foesio.foAuction.api.AuctionListingSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after purchase validation but before buyer withdrawal, seller payout, or item delivery. */
public final class ListingPrePurchaseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player buyer;
    private final AuctionListingSnapshot listing;
    private boolean cancelled;

    public ListingPrePurchaseEvent(Player buyer, AuctionListingSnapshot listing) {
        super(!Bukkit.isPrimaryThread());
        this.buyer = Objects.requireNonNull(buyer, "buyer");
        this.listing = Objects.requireNonNull(listing, "listing");
    }

    public Player getBuyer() { return buyer; }
    public AuctionListingSnapshot getListing() { return listing; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
