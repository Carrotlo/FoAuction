package me.foesio.foAuction.api;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** Main-thread-only, cache-only item maintenance. No economy or ownership changes. */
public interface StoredItemApi {
    int MAX_BATCH = 32;
    enum Kind { LISTING, CLAIM }
    enum Result { UPDATED, UNCHANGED, STALE, MISSING, INVALID, BUSY }

    record StoredItem(Kind kind, int index, UUID id, ItemStack item) {
        public StoredItem { item = item.clone(); }
        @Override public ItemStack item() { return item.clone(); }
    }
    record Page(List<StoredItem> items, int nextOffset, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
    record Replacement(StoredItem expected, ItemStack item) {
        public Replacement { item = item.clone(); }
        @Override public ItemStack item() { return item.clone(); }
    }

    /** Weakly consistent iterator; consume on the main thread, without retaining player data. */
    Iterator<UUID> cachedOwners();
    /** Offsets include money claims, but money claims are never returned or mutable here. */
    Page readItems(UUID owner, Kind kind, int offset, int limit);
    /**
     * Compare-and-replace each entry independently; results follow request order.
     * Quantity must remain unchanged. UPDATED means cache/index changed and one owner
     * save was queued, not that asynchronous disk persistence has finished.
     * A moved/removed/changed snapshot is rejected; read a fresh page before retrying.
     */
    List<Result> replaceItems(UUID owner, List<Replacement> replacements);
}
