package me.foesio.foAuction.api;

import java.util.Objects;

/** Result of an external listing request. Listing is present only on success. */
public record CreateListingResult(Status status, AuctionListingSnapshot listing,
                                  int maxSlots) {
    public enum Status {
        SUCCESS, UNAVAILABLE, SELLER_OFFLINE, NO_PERMISSION, INVALID_NPC_NAME, INVALID_PRICE, INVALID_ITEM,
        BLACKLISTED, NO_SLOTS, INSUFFICIENT_FUNDS, ECONOMY_ERROR, CANCELLED, STORAGE_ERROR
    }

    public CreateListingResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.SUCCESS) != (listing != null)) {
            throw new IllegalArgumentException("listing must be present exactly on success");
        }
    }

    public static CreateListingResult failure(Status status) {
        return new CreateListingResult(status, null, 0);
    }
}
