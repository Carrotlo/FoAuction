package me.foesio.foAuction.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** FoAuction's supported Java boundary. Look it up through Bukkit's ServicesManager. */
public interface AuctionApi {
    /** Returns an active listing, or empty if missing or expired. Server execution context only. */
    Optional<AuctionListingSnapshot> findActiveListing(UUID listingId);

    /**
     * Returns newest active listings first. Offset must be nonnegative; limit must be 1..100.
     * This is a point-in-time page, not a stable cursor across concurrent market changes.
     */
    List<AuctionListingSnapshot> getActiveListings(int offset, int limit);

    /** Lists and removes the online player's main-hand stack through the normal sell path. */
    CreateListingResult createListingFromHand(Player seller, double price);

    /**
     * Lists an API-supplied item for an online player. The caller owns item removal from its
     * source; FoAuction clones it, checks the player's permission and slots, and charges the
     * configured listing fee. A successful request must not be retried blindly.
     */
    CreateListingResult createListingForPlayer(Player seller, ItemStack item, double price);

    /**
     * Lists an API-supplied item under a named virtual seller. No player slot or fee is charged;
     * buyer payment is removed from circulation. An expired item is discarded. The caller owns
     * item removal from its source and must avoid retrying a successful request blindly.
     */
    CreateListingResult createListingForNpc(String npcName, ItemStack item, double price);
}
