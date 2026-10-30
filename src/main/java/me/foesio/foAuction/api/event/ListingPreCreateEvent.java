package me.foesio.foAuction.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Fired after listing validation but before any fee or item is taken. Cancellation has no side effects. */
public final class ListingPreCreateEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID sellerId;
    private final Player sellerPlayer;
    private final ItemStack item;
    private final double price;
    private boolean cancelled;

    public ListingPreCreateEvent(UUID sellerId, Player sellerPlayer, ItemStack item, double price) {
        super(!Bukkit.isPrimaryThread());
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.sellerPlayer = sellerPlayer;
        this.item = Objects.requireNonNull(item, "item").clone();
        this.price = price;
    }

    public UUID getSellerId() { return sellerId; }
    /** Null for a virtual NPC seller. */
    public Player getSellerPlayer() { return sellerPlayer; }
    public ItemStack getItem() { return item.clone(); }
    public double getPrice() { return price; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
