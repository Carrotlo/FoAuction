package me.foesio.foAuction.service;

import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.model.AuctionListing;
import me.foesio.foAuction.model.ClaimEntry;
import me.foesio.foAuction.model.FilterMode;
import me.foesio.foAuction.model.PlayerData;
import me.foesio.foAuction.model.SoldAuctionHistoryEntry;
import me.foesio.foAuction.model.SortMode;
import me.foesio.foAuction.storage.IUserDataRepository;
import me.foesio.foAuction.FoAuction;
import me.foesio.foAuction.utils.SoundFeedback;
import me.foesio.foAuction.utils.ColorPalette;
import me.foesio.foAuction.utils.FormatUtils;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.inventory.InventoryDepositService;
import me.foesio.core.economy.VaultEconomyBridge;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuctionService {
    private static final int MAX_BLACKLIST_SCAN_DEPTH = 4;
    private static final int MAX_BLACKLIST_SCAN_ITEMS = 128;

    public record MainGuiView(
            SortMode sortMode,
            FilterMode filterMode,
            String searchQuery,
            List<AuctionListing> listings,
            int totalListings,
            int page
    ) {}

    public record ListingsView(
            SortMode sortMode,
            List<AuctionListing> listings,
            int totalListings,
            int page
    ) {}

    private record ListingPage(List<AuctionListing> listings, int totalListings, int page) {}

    private final JavaPlugin plugin;
    private final IUserDataRepository userDataRepository;
    private final VaultEconomyBridge economyService;
    private final AuctionSettings settings;
    private final DiscordWebhookService discordWebhookService;
    private final PlayerNameCache playerNameCache;
    private final FoMessageService messages;
    private final InventoryDepositService inventoryDeposits;

    private final Object transactionLock;
    private final Map<UUID, AuctionListing> listingsById;
    private final ConcurrentMap<UUID, String> searchQueryByViewer;
    private final Set<UUID> pendingPurchaseTransactions;
    private final ConcurrentMap<UUID, Long> claimCooldowns;
    private final ConcurrentMap<UUID, Long> searchQueryTimestamps;
    private static final long CLAIM_COOLDOWN_MS = 1000L; // 1 second cooldown
    private static final long SEARCH_QUERY_EXPIRY_MS = 300000L; // 5 minutes

    public AuctionService(
            JavaPlugin plugin,
            IUserDataRepository userDataRepository,
            VaultEconomyBridge economyService,
            AuctionSettings settings,
            DiscordWebhookService discordWebhookService,
            PlayerNameCache playerNameCache,
            FoMessageService messages,
            InventoryDepositService inventoryDeposits
    ) {
        this.plugin = plugin;
        this.userDataRepository = userDataRepository;
        this.economyService = economyService;
        this.settings = settings;
        this.discordWebhookService = discordWebhookService;
        this.playerNameCache = playerNameCache;
        this.messages = messages;
        this.inventoryDeposits = inventoryDeposits;
        this.transactionLock = new Object();
        this.listingsById = new HashMap<>();
        this.searchQueryByViewer = new ConcurrentHashMap<>();
        this.pendingPurchaseTransactions = ConcurrentHashMap.newKeySet();
        this.claimCooldowns = new ConcurrentHashMap<>();
        this.searchQueryTimestamps = new ConcurrentHashMap<>();
    }

    public void rebuildIndex() {
        synchronized (transactionLock) {
            listingsById.clear();
            for (PlayerData playerData : userDataRepository.getAllCached()) {
                for (AuctionListing listing : playerData.getAuctions()) {
                    listingsById.put(listing.getId(), listing.copy());
                }
            }
        }
    }

    public void syncPlayerListings(UUID playerUuid) {
        synchronized (transactionLock) {
            listingsById.entrySet().removeIf(entry -> entry.getValue().getSellerUuid().equals(playerUuid));
            PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
            for (AuctionListing listing : playerData.getAuctions()) {
                listingsById.put(listing.getId(), listing.copy());
            }
        }
    }

    public SortMode getSortMode(UUID playerUuid) {
        return userDataRepository.getOrCreate(playerUuid).getSortMode();
    }

    public long getGuiRefreshCooldownMillis() {
        return settings.getGuiRefreshCooldownMillis();
    }

    public SortMode cycleSortMode(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        SortMode nextMode = playerData.getSortMode().next();
        playerData.setSortMode(nextMode);
        userDataRepository.saveAsync(playerUuid);
        return nextMode;
    }

    public FilterMode getFilterMode(UUID playerUuid) {
        return userDataRepository.getOrCreate(playerUuid).getFilterMode();
    }

    public FilterMode cycleFilterMode(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        FilterMode nextMode = playerData.getFilterMode().next();
        playerData.setFilterMode(nextMode);
        userDataRepository.saveAsync(playerUuid);
        return nextMode;
    }

    public String getSearchQuery(UUID playerUuid) {
        return searchQueryByViewer.getOrDefault(playerUuid, "");
    }

    public void setSearchQuery(UUID playerUuid, String searchQuery) {
        String normalized = normalizeSearchQuery(searchQuery);
        if (normalized.isEmpty()) {
            searchQueryByViewer.remove(playerUuid);
            searchQueryTimestamps.remove(playerUuid);
            return;
        }

        searchQueryByViewer.put(playerUuid, normalized);
        searchQueryTimestamps.put(playerUuid, System.currentTimeMillis());

        // Cleanup expired search queries periodically
        cleanupExpiredSearchQueries();
    }

    public void clearSearchQuery(UUID playerUuid) {
        searchQueryByViewer.remove(playerUuid);
        searchQueryTimestamps.remove(playerUuid);
    }

    public void deliverPendingNotifications(Player player) {
        PlayerData playerData = userDataRepository.getOrCreate(player.getUniqueId());
        if (playerData.getPendingNotifications().isEmpty()) {
            return;
        }

        for (String notification : playerData.getPendingNotifications()) {
            player.sendMessage(notification);
        }
        SoundFeedback.pendingNotifications(player);
        playerData.getPendingNotifications().clear();
        userDataRepository.saveAsync(player.getUniqueId());
    }

    public MainGuiView getMainGuiView(UUID viewerUuid, int requestedPage, int pageSize) {
        PlayerData playerData = userDataRepository.getOrCreate(viewerUuid);
        SortMode sortMode = playerData.getSortMode();
        FilterMode filterMode = playerData.getFilterMode();
        String searchQuery = getSearchQuery(viewerUuid);
        String loweredQuery = searchQuery.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        List<AuctionListing> pageListings;
        int totalListings;
        int page;
        synchronized (transactionLock) {
            Map<UUID, AuctionListing> visibleListingsById = new LinkedHashMap<>();
            for (AuctionListing listing : listingsById.values()) {
                if (!listing.isExpired(now)) {
                    visibleListingsById.put(listing.getId(), listing);
                }
            }

            for (AuctionListing listing : playerData.getAuctions()) {
                if (!listing.isExpired(now)) {
                    visibleListingsById.putIfAbsent(listing.getId(), listing);
                }
            }

            List<AuctionListing> filteredListings = new ArrayList<>(visibleListingsById.size());
            for (AuctionListing listing : visibleListingsById.values()) {
                if (!filterMode.matches(listing.getItemType())) {
                    continue;
                }
                if (!searchQuery.isBlank() && !matchesSearchQuery(listing, loweredQuery)) {
                    continue;
                }
                filteredListings.add(listing);
            }

            filteredListings.sort(comparatorFor(sortMode));
            totalListings = filteredListings.size();
            page = clampPageFor(requestedPage, totalListings, pageSize);
            pageListings = copyListingPage(filteredListings, page, pageSize);
        }

        return new MainGuiView(sortMode, filterMode, searchQuery, pageListings, totalListings, page);
    }

    public boolean isListingOwnedBy(UUID listingId, UUID playerUuid) {
        synchronized (transactionLock) {
            AuctionListing listing = listingsById.get(listingId);
            if (listing != null) {
                return listing.getSellerUuid().equals(playerUuid);
            }
        }

        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        for (AuctionListing listing : playerData.getAuctions()) {
            if (listing.getId().equals(listingId)) {
                return true;
            }
        }
        return false;
    }

    public ListingsView getPlayerListingsView(UUID viewerUuid, int requestedPage, int pageSize) {
        PlayerData playerData = userDataRepository.getOrCreate(viewerUuid);
        SortMode sortMode = playerData.getSortMode();
        ListingPage listingPage = getSortedListingsForSellerPage(viewerUuid, sortMode, requestedPage, pageSize);
        return new ListingsView(sortMode, listingPage.listings(), listingPage.totalListings(), listingPage.page());
    }

    public ListingsView getSellerListingsView(UUID viewerUuid, UUID sellerUuid, int requestedPage, int pageSize) {
        PlayerData playerData = userDataRepository.getOrCreate(viewerUuid);
        SortMode sortMode = playerData.getSortMode();
        ListingPage listingPage = getSortedListingsForSellerPage(sellerUuid, sortMode, requestedPage, pageSize);
        return new ListingsView(sortMode, listingPage.listings(), listingPage.totalListings(), listingPage.page());
    }

    public List<AuctionListing> getSortedActiveListings(SortMode sortMode) {
        long now = System.currentTimeMillis();
        synchronized (transactionLock) {
            return listingsById.values().stream()
                    .filter(listing -> !listing.isExpired(now))
                    .map(AuctionListing::copy)
                    .sorted(comparatorFor(sortMode))
                    .toList();
        }
    }

    public List<AuctionListing> getFilteredActiveListings(UUID viewerUuid) {
        SortMode sortMode = getSortMode(viewerUuid);
        FilterMode filterMode = getFilterMode(viewerUuid);
        String query = getSearchQuery(viewerUuid);
        String loweredQuery = query.toLowerCase(Locale.ROOT);
        return getSortedActiveListings(sortMode).stream()
                .filter(listing -> filterMode.matches(listing.getItemType()))
                .filter(listing -> query.isBlank() || matchesSearchQuery(listing, loweredQuery))
                .toList();
    }

    public List<AuctionListing> getSortedListingsForSeller(UUID sellerUuid, SortMode sortMode) {
        long now = System.currentTimeMillis();
        synchronized (transactionLock) {
            return listingsById.values().stream()
                    .filter(listing -> listing.getSellerUuid().equals(sellerUuid))
                    .filter(listing -> !listing.isExpired(now))
                    .map(AuctionListing::copy)
                    .sorted(comparatorFor(sortMode))
                    .toList();
        }
    }

    private ListingPage getSortedListingsForSellerPage(
            UUID sellerUuid,
            SortMode sortMode,
            int requestedPage,
            int pageSize
    ) {
        long now = System.currentTimeMillis();
        synchronized (transactionLock) {
            List<AuctionListing> sellerListings = new ArrayList<>();
            for (AuctionListing listing : listingsById.values()) {
                if (!listing.getSellerUuid().equals(sellerUuid) || listing.isExpired(now)) {
                    continue;
                }
                sellerListings.add(listing);
            }

            sellerListings.sort(comparatorFor(sortMode));
            int totalListings = sellerListings.size();
            int page = clampPageFor(requestedPage, totalListings, pageSize);
            return new ListingPage(copyListingPage(sellerListings, page, pageSize), totalListings, page);
        }
    }

    private List<AuctionListing> copyListingPage(List<AuctionListing> listings, int page, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int start = Math.min(page * safePageSize, listings.size());
        int end = Math.min(start + safePageSize, listings.size());
        List<AuctionListing> pageListings = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            pageListings.add(listings.get(i).copy());
        }
        return pageListings;
    }

    private int clampPageFor(int requestedPage, int totalItems, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int maxPage = totalItems <= 0 ? 0 : (totalItems - 1) / safePageSize;
        return Math.max(0, Math.min(requestedPage, maxPage));
    }

    public AuctionListing getListing(UUID listingId) {
        synchronized (transactionLock) {
            AuctionListing listing = listingsById.get(listingId);
            return listing == null ? null : listing.copy();
        }
    }

    public List<ClaimEntry> getClaims(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        return playerData.getClaims().stream()
                .map(ClaimEntry::copy)
                .sorted(Comparator.comparingLong(ClaimEntry::getCreatedAt).reversed())
                .toList();
    }

    public List<SoldAuctionHistoryEntry> getSoldHistory(UUID sellerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(sellerUuid);
        return playerData.getSoldHistory().stream()
                .map(SoldAuctionHistoryEntry::copy)
                .sorted(Comparator.comparingLong(SoldAuctionHistoryEntry::getSoldAt).reversed())
                .toList();
    }

    public ClaimResult claim(Player player, UUID claimId) {
        UUID playerUuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Check claim cooldown
        Long lastClaimTime = claimCooldowns.get(playerUuid);
        if (lastClaimTime != null && (now - lastClaimTime) < CLAIM_COOLDOWN_MS) {
            return ClaimResult.ON_COOLDOWN;
        }

        synchronized (transactionLock) {
            PlayerData data = userDataRepository.getOrCreate(playerUuid);
            int claimIndex = indexOfClaim(data, claimId);
            if (claimIndex < 0) {
                return ClaimResult.NOT_FOUND;
            }

            ClaimEntry claimEntry = data.getClaims().get(claimIndex);
            if (claimEntry.getType() == ClaimEntry.Type.ITEM) {
                ItemStack item = claimEntry.getItem();
                if (item == null || item.getType().isAir()) {
                    data.getClaims().remove(claimIndex);
                    userDataRepository.saveAsync(player.getUniqueId());
                    return ClaimResult.NOT_FOUND;
                }

                if (!hasEnoughInventorySpace(player, item)) {
                    return ClaimResult.INVENTORY_FULL;
                }

                data.getClaims().remove(claimIndex);
                DeliveryType deliveryType = deliverItemToPlayerOrClaims(
                        playerUuid,
                        player,
                        item,
                        claimEntry.getNote().isBlank() ? "@foauction:claim-delivery-fallback" : claimEntry.getNote()
                );
                userDataRepository.saveNow(playerUuid);

                // Update claim cooldown on successful claim
                claimCooldowns.put(playerUuid, now);

                discordWebhookService.send(
                        DiscordWebhookService.EventType.CLAIMING_LISTINGS,
                        player.getName() + " claimed item " + resolveItemName(item) + " from claims."
                );
                return deliveryType == DeliveryType.CLAIMS
                        ? ClaimResult.INVENTORY_FULL
                        : ClaimResult.SUCCESS_ITEM;
            }

            double amount = claimEntry.getMoney();
            if (!Double.isFinite(amount) || amount <= 0D) {
                data.getClaims().remove(claimIndex);
                userDataRepository.saveAsync(player.getUniqueId());
                return ClaimResult.SUCCESS_MONEY;
            }
            if (!economyService.deposit(player, amount)) {
                return ClaimResult.ECONOMY_ERROR;
            }

            data.getClaims().remove(claimIndex);
            userDataRepository.saveNow(playerUuid);

            // Update claim cooldown on successful claim
            claimCooldowns.put(playerUuid, now);

            discordWebhookService.send(
                    DiscordWebhookService.EventType.CLAIMING_LISTINGS,
                    player.getName() + " claimed " + formatEconomyPrice(amount) + " from claims."
            );
            return ClaimResult.SUCCESS_MONEY;
        }
    }

    public SellResult createListing(Player player, double price) {
        if (!Double.isFinite(price) || price < settings.getMinPrice() || price > settings.getMaxPrice()) {
            return SellResult.priceOutOfRange(settings.getMinPrice(), settings.getMaxPrice());
        }

        synchronized (transactionLock) {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (inHand.getType().isAir()) {
                return SellResult.noItem();
            }

            if (isBlacklistedForListing(inHand)) {
                return SellResult.blacklisted();
            }

            PlayerData sellerData = userDataRepository.getOrCreate(player.getUniqueId());
            int maxSlots = resolveMaxSlots(player);
            if (sellerData.getAuctions().size() >= maxSlots) {
                return SellResult.noSlots(maxSlots);
            }

            double listingFee = price * (settings.getListingFeePercent() / 100D);
            double requiredUpfrontBalance = listingFee;
            if (requiredUpfrontBalance > 0D && !economyService.has(player, requiredUpfrontBalance)) {
                return SellResult.insufficientFunds(requiredUpfrontBalance);
            }

            // Create a snapshot of item to prevent modification during transaction
            ItemStack itemSnapshot = inHand.clone();

            // Withdraw fee first - if this fails, we don't proceed
            if (listingFee > 0D && !economyService.withdraw(player, listingFee)) {
                return SellResult.economyFailed();
            }

            // Remove item from hand immediately after successful fee withdrawal
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

            long now = System.currentTimeMillis();
            long expireTime = now + settings.getExpireMillis();
            AuctionListing listing = new AuctionListing(
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    itemSnapshot,
                    price,
                    now,
                    expireTime
            );

            // Add to player data and global index atomically
            sellerData.getAuctions().add(listing);
            listingsById.put(listing.getId(), listing.copy());

            userDataRepository.saveNow(player.getUniqueId());

            discordWebhookService.send(
                    DiscordWebhookService.EventType.LISTING,
                    player.getName()
                            + " listed "
                            + resolveItemName(listing.getItem())
                            + " for "
                            + formatEconomyPrice(listing.getPrice())
                            + "."
            );
            FoAuction.fileLogger().info(
                    "Listing created: "
                            + listing.getId()
                            + " seller="
                            + player.getName()
                            + " price="
                            + formatEconomyPrice(price)
                            + "."
            );
            return SellResult.success(listing, maxSlots);
        }
    }

    public PurchaseResult buyListing(Player buyer, UUID listingId) {
        // Atomic transaction tracking - prevent concurrent purchases of same listing
        if (!pendingPurchaseTransactions.add(listingId)) {
            return PurchaseResult.TRANSACTION_IN_PROGRESS;
        }

        try {
            synchronized (transactionLock) {
                // Double-check that listing still exists and is valid
                AuctionListing listing = listingsById.get(listingId);
                if (listing == null) {
                    return PurchaseResult.NOT_FOUND;
                }

                if (listing.getSellerUuid().equals(buyer.getUniqueId())) {
                    return PurchaseResult.OWN_LISTING;
                }

                if (listing.isExpired(System.currentTimeMillis())) {
                    expireListingInternal(listing, false);
                    return PurchaseResult.EXPIRED;
                }

                double listingPrice = listing.getPrice();
                if (!Double.isFinite(listingPrice) || listingPrice <= 0D) {
                    expireListingInternal(listing, false);
                    return PurchaseResult.NOT_FOUND;
                }

                if (!economyService.has(buyer, listingPrice)) {
                    return PurchaseResult.INSUFFICIENT_FUNDS;
                }

                // Critical: Remove listing from market before processing economy
                // This prevents double-spending if economy operations fail
                removeListingInternal(listing);

                // Process economy transaction
                if (!economyService.withdraw(buyer, listingPrice)) {
                    // Rollback: restore listing if economy withdrawal fails
                    restoreListingInternal(listing);
                    return PurchaseResult.ECONOMY_ERROR;
                }

                double sellerPayout = listingPrice;
                OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.getSellerUuid());
                if (!economyService.deposit(seller, sellerPayout)) {
                    boolean refunded = economyService.deposit(buyer, listingPrice);
                    restoreListingInternal(listing);
                      if (!refunded) {
                          plugin.getLogger().warning(
                                  ColorPalette.log(
                                          "Failed to refund "
                                                  + buyer.getName()
                                                  + " after seller payout failed for listing "
                                                  + listing.getId()
                                                  + "."
                                  )
                          );
                          FoAuction.fileLogger().warn("Failed to refund buyer after seller payout failed for listing " + listing.getId() + ".");
                      }
                    return PurchaseResult.ECONOMY_ERROR;
                }

                DeliveryType buyerDelivery = deliverItemToPlayerOrClaims(
                        buyer.getUniqueId(),
                        buyer,
                        listing.getItem(),
                        "@foauction:purchased-from:" + listing.getSellerUuid()
                );
                recordSoldListingInternal(listing, buyer.getUniqueId(), System.currentTimeMillis());
                notifySellerOfSale(listing, buyer);
                userDataRepository.saveNow(listing.getSellerUuid());
                userDataRepository.saveNow(buyer.getUniqueId());

                String sellerName = resolvePlayerName(listing.getSellerUuid());
                String buyerDeliveryDescription = switch (buyerDelivery) {
                    case INVENTORY -> "Buyer item was added to inventory";
                    case CLAIMS -> "Buyer inventory was full, so the item was sent to claims";
                    case PARTIAL_CLAIMS -> "Buyer received what fit, and the rest was sent to claims";
                };
                discordWebhookService.send(
                        DiscordWebhookService.EventType.SELLING,
                        buyer.getName()
                                + " bought "
                                + resolveItemName(listing.getItem())
                                + " from "
                                + sellerName
                                + " for "
                                + formatEconomyPrice(listingPrice)
                                + ". "
                                + buyerDeliveryDescription
                                + "; seller payout was deposited directly."
                );
                FoAuction.fileLogger().info(
                        "Listing sold: "
                                + listing.getId()
                                + " buyer="
                                + buyer.getName()
                                + " seller="
                                + sellerName
                                + " price="
                                + formatEconomyPrice(listingPrice)
                                + "."
                );

                return switch (buyerDelivery) {
                    case INVENTORY -> PurchaseResult.SUCCESS_INVENTORY;
                    case CLAIMS -> PurchaseResult.SUCCESS_CLAIMS;
                    case PARTIAL_CLAIMS -> PurchaseResult.SUCCESS_PARTIAL_CLAIMS;
                };
            }
        } finally {
            pendingPurchaseTransactions.remove(listingId);
        }
    }

    public CancelResult cancelListing(Player player, UUID listingId) {
        synchronized (transactionLock) {
            AuctionListing listing = listingsById.get(listingId);
            if (listing == null) {
                return CancelResult.NOT_FOUND;
            }

            if (!listing.getSellerUuid().equals(player.getUniqueId())) {
                return CancelResult.NOT_OWNER;
            }

            if (!hasEnoughInventorySpace(player, listing.getItem())) {
                return CancelResult.INVENTORY_FULL;
            }

            removeListingInternal(listing);
            player.getInventory().addItem(listing.getItem().clone());
            userDataRepository.saveNow(player.getUniqueId());

            discordWebhookService.send(
                    DiscordWebhookService.EventType.CANCELLING_LISTINGS,
                    player.getName()
                            + " cancelled listing of "
                            + resolveItemName(listing.getItem())
                            + " priced at "
                            + formatEconomyPrice(listing.getPrice())
                            + "."
            );
            FoAuction.fileLogger().info("Listing cancelled by seller " + player.getName() + ": " + listingId + ".");

            return CancelResult.SUCCESS_INVENTORY;
        }
    }

    public AdminActionResult adminReturnListingToSellerClaims(UUID listingId, Player admin) {
        // Enhanced permission validation - check at execution point
        if (admin == null || !admin.hasPermission("foauction.admin")) {
            return AdminActionResult.NO_PERMISSION;
        }

        // Additional permission check for specific admin action
        if (!admin.hasPermission("foauction.admin.return")) {
            return AdminActionResult.INSUFFICIENT_PERMISSION;
        }

        synchronized (transactionLock) {
            AuctionListing listing = listingsById.get(listingId);
            if (listing == null) {
                return AdminActionResult.NOT_FOUND;
            }

            // Final permission check inside synchronized block
            if (!admin.hasPermission("foauction.admin") || !admin.hasPermission("foauction.admin.return")) {
                return AdminActionResult.NO_PERMISSION;
            }

            removeListingInternal(listing);
            addItemClaimInternal(listing.getSellerUuid(), listing.getItem(), "@foauction:admin-removed");
            userDataRepository.saveNow(listing.getSellerUuid());

            Player seller = Bukkit.getPlayer(listing.getSellerUuid());
            if (seller != null) {
                sendOwnerMessage(seller, "auction.admin-removed-notification");
                SoundFeedback.listingRemoved(seller);
            }

            String adminName = admin != null ? admin.getName() : "Unknown";
            discordWebhookService.send(
                    DiscordWebhookService.EventType.ADMIN_REMOVING,
                    adminName
                            + " removed listing from "
                            + resolvePlayerName(listing.getSellerUuid())
                            + " and sent item to seller claims: "
                            + resolveItemName(listing.getItem())
                            + "."
            );
            discordWebhookService.send(
                    DiscordWebhookService.EventType.CLAIMS,
                    "Admin removal sent item to seller claims: " + resolveItemName(listing.getItem()) + "."
            );
            FoAuction.fileLogger().info(
                    "Admin "
                            + admin.getName()
                            + " removed listing "
                            + listingId
                            + " and sent item to seller claims."
            );

            return AdminActionResult.SUCCESS_SELLER_CLAIMS;
        }
    }

    public AdminActionResult adminTakeListing(UUID listingId, Player admin) {
        // Enhanced permission validation - check at execution point
        if (admin == null || !admin.hasPermission("foauction.admin")) {
            return AdminActionResult.NO_PERMISSION;
        }

        // Additional permission check for specific admin action
        if (!admin.hasPermission("foauction.admin.take")) {
            return AdminActionResult.INSUFFICIENT_PERMISSION;
        }

        synchronized (transactionLock) {
            AuctionListing listing = listingsById.get(listingId);
            if (listing == null) {
                return AdminActionResult.NOT_FOUND;
            }

            // Final permission check inside synchronized block
            if (!admin.hasPermission("foauction.admin") || !admin.hasPermission("foauction.admin.take")) {
                return AdminActionResult.NO_PERMISSION;
            }

            removeListingInternal(listing);
            DeliveryType deliveryType = deliverItemToPlayerOrClaims(admin.getUniqueId(), admin, listing.getItem());
            userDataRepository.saveNow(listing.getSellerUuid());
            userDataRepository.saveNow(admin.getUniqueId());
            discordWebhookService.send(
                    DiscordWebhookService.EventType.ADMIN_REMOVING,
                    admin.getName()
                            + " removed listing from "
                            + resolvePlayerName(listing.getSellerUuid())
                            + " and took item: "
                            + resolveItemName(listing.getItem())
                            + "."
            );
            if (deliveryType == DeliveryType.CLAIMS) {
                discordWebhookService.send(
                        DiscordWebhookService.EventType.CLAIMS,
                        "Admin inventory was full, removed listing item sent to admin claims: " + resolveItemName(listing.getItem()) + "."
                );
            }
            FoAuction.fileLogger().info(
                    "Admin "
                            + admin.getName()
                            + " removed listing "
                            + listingId
                            + " and took item. delivery="
                            + deliveryType.name()
                            + "."
            );
            return deliveryType == DeliveryType.INVENTORY
                    ? AdminActionResult.SUCCESS_ADMIN_INVENTORY
                    : AdminActionResult.SUCCESS_ADMIN_CLAIMS;
        }
    }

    public int purgeExpired() {
        synchronized (transactionLock) {
            long now = System.currentTimeMillis();
            List<AuctionListing> expired = listingsById.values().stream()
                    .filter(listing -> listing.isExpired(now))
                    .map(AuctionListing::copy)
                    .toList();
            for (AuctionListing listing : expired) {
                expireListingInternal(listing, true);
            }

            return expired.size();
        }
    }

    public int resolveMaxSlots(Player player) {
        int highest = 0;
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            if (!permission.getValue()) {
                continue;
            }

            String node = permission.getPermission().toLowerCase(Locale.ROOT);
            if (!node.startsWith("foauction.slots.")) {
                continue;
            }

            String suffix = node.substring("foauction.slots.".length());
            if ("*".equals(suffix)) {
                return Integer.MAX_VALUE;
            }

            try {
                int parsed = Integer.parseInt(suffix);
                if (parsed > highest) {
                    highest = parsed;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed slot permissions.
            }
        }

        return highest > 0 ? highest : settings.getDefaultMaxSlots();
    }

    public int getSellLimit(OfflinePlayer player) {
        if (player == null) {
            return settings.getDefaultMaxSlots();
        }

        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return resolveMaxSlots(onlinePlayer);
        }
        return settings.getDefaultMaxSlots();
    }

    public int getSellCount(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        return playerData.getAuctions().size();
    }

    public int getExpiredCount(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        int expired = 0;
        for (ClaimEntry claim : playerData.getClaims()) {
            if (claim.getType() != ClaimEntry.Type.ITEM) {
                continue;
            }
            if (isExpiredClaimNote(claim.getNote())) {
                expired++;
            }
        }
        return expired;
    }

    public int getActiveCount(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        long now = System.currentTimeMillis();
        int active = 0;
        for (AuctionListing listing : playerData.getAuctions()) {
            if (!listing.isExpired(now)) {
                active++;
            }
        }
        return active;
    }

    public int getTotalListings() {
        long now = System.currentTimeMillis();
        synchronized (transactionLock) {
            int count = 0;
            for (AuctionListing listing : listingsById.values()) {
                if (!listing.isExpired(now)) {
                    count++;
                }
            }
            return count;
        }
    }

    public int getTotalSoldCount(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        return playerData.getSoldHistory().size();
    }

    public double getTotalMoneyEarned(UUID playerUuid) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        double total = 0D;
        for (SoldAuctionHistoryEntry entry : playerData.getSoldHistory()) {
            total += entry.getPrice();
        }
        return total;
    }

    public String getSortModeName(UUID playerUuid) {
        List<String> labels = me.foesio.foAuction.utils.OwnerMessages.optionalList(
                messages, "gui-options.main.sort");
        int selected = userDataRepository.getOrCreate(playerUuid).getSortMode().ordinal();
        return selected < labels.size() ? labels.get(selected) : "";
    }

    public String getFilterModeName(UUID playerUuid) {
        List<String> labels = me.foesio.foAuction.utils.OwnerMessages.optionalList(
                messages, "gui-options.main.filter");
        int selected = userDataRepository.getOrCreate(playerUuid).getFilterMode().ordinal();
        return selected < labels.size() ? labels.get(selected) : "";
    }

    public double getMinPrice() {
        return settings.getMinPrice();
    }

    public double getMaxPrice() {
        return settings.getMaxPrice();
    }

    public double getListingFeePercent() {
        return settings.getListingFeePercent();
    }

    public String formatDisplayPrice(double value) {
        return FormatUtils.formatPrice(value, settings.isCompactPriceFormatEnabled());
    }

    public boolean isExpiredClaim(UUID playerUuid, UUID claimId) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        for (ClaimEntry claim : playerData.getClaims()) {
            if (!claim.getId().equals(claimId)) {
                continue;
            }
            return claim.getType() == ClaimEntry.Type.ITEM && isExpiredClaimNote(claim.getNote());
        }
        return false;
    }

    private void expireListingInternal(AuctionListing listing, boolean notifyPlayer) {
        removeListingInternal(listing);
        addItemClaimInternal(listing.getSellerUuid(), listing.getItem(), "@foauction:listing-expired");
        userDataRepository.saveNow(listing.getSellerUuid());

        Player seller = Bukkit.getPlayer(listing.getSellerUuid());
        if (notifyPlayer && seller != null) {
            sendOwnerMessage(seller, "auction.expired-notification");
            SoundFeedback.listingExpired(seller);
        }

        discordWebhookService.send(
                DiscordWebhookService.EventType.CLAIMS,
                "Expired listing item sent to seller claims: " + resolveItemName(listing.getItem()) + "."
        );
        FoAuction.fileLogger().info("Listing expired and moved to seller claims: " + listing.getId() + ".");
    }

    private void removeListingInternal(AuctionListing listing) {
        listingsById.remove(listing.getId());

        PlayerData sellerData = userDataRepository.getOrCreate(listing.getSellerUuid());
        sellerData.getAuctions().removeIf(existing -> existing.getId().equals(listing.getId()));
    }

    private void restoreListingInternal(AuctionListing listing) {
        listingsById.put(listing.getId(), listing.copy());

        PlayerData sellerData = userDataRepository.getOrCreate(listing.getSellerUuid());
        boolean alreadyPresent = sellerData.getAuctions().stream()
                .anyMatch(existing -> existing.getId().equals(listing.getId()));
        if (!alreadyPresent) {
            sellerData.getAuctions().add(listing);
        }
    }

    private void recordSoldListingInternal(AuctionListing listing, UUID buyerUuid, long soldAt) {
        PlayerData sellerData = userDataRepository.getOrCreate(listing.getSellerUuid());
        sellerData.getSoldHistory().add(
                new SoldAuctionHistoryEntry(
                        listing.getSellerUuid(),
                        buyerUuid,
                        listing.getItem(),
                        listing.getPrice(),
                        soldAt
                )
        );
    }

    private void notifySellerOfSale(AuctionListing listing, Player buyer) {
        String path = "auction.sold-notification";
        String genericItem = messages.render("gui-values.generic-item", "");
        String price = formatEconomyPrice(listing.getPrice());
        if (me.foesio.foAuction.utils.OwnerMessages.isConfiguredList(path)) {
            List<String> lines = me.foesio.foAuction.utils.OwnerMessages.optionalList(messages, path, Map.of(
                    "item", genericItem, "buyer", buyer.getName(), "price", price));
            if (lines.isEmpty()) return;
            Player online = Bukkit.getPlayer(listing.getSellerUuid());
            if (online != null && online.isOnline()) {
                sendOwnerMessage(online, path, "item", genericItem,
                        "buyer", buyer.getName(), "price", price);
            } else {
                for (String line : lines) {
                    if (!line.isBlank()) queueNotificationInternal(listing.getSellerUuid(), line);
                }
            }
            return;
        }
        if (!me.foesio.foAuction.utils.OwnerMessages.isConfiguredScalar(path)) return;
        String scalar = messages.render(path, "");
        if (scalar.isBlank()) return;
        Player sellerOnline = Bukkit.getPlayer(listing.getSellerUuid());
        if (sellerOnline != null && sellerOnline.isOnline()) {
            try {
                PaperSoldNotification.send(sellerOnline, listing.getItem(), buyer.getName(),
                        price, messages);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                sendOwnerMessage(sellerOnline, path, "item", genericItem,
                        "buyer", buyer.getName(), "price", price);
            }
            return;
        }
        String message = messages.render(path, "",
                "item", genericItem, "buyer", buyer.getName(), "price", price);
        queueNotificationInternal(listing.getSellerUuid(), message);
    }

    private void sendOwnerMessage(Player player, String path, String... placeholders) {
        me.foesio.foAuction.utils.OwnerMessages.send(messages, player, path, placeholders);
    }

    /** Isolated so a Spigot runtime can use the configured text fallback. */
    private static final class PaperSoldNotification {
        private static void send(Player seller, ItemStack item, String buyer, String price,
                                 FoMessageService messages) throws ReflectiveOperationException {
            String marker = "%%foauction_item%%";
            ItemMeta meta = item.getItemMeta();
            net.kyori.adventure.text.Component itemName = meta != null && meta.hasDisplayName()
                    ? me.foesio.core.dialog.DialogIcons.render(seller, meta.getDisplayName())
                    : net.kyori.adventure.text.Component.translatable(item.getTranslationKey());
            net.kyori.adventure.text.Component rendered = messages.renderComponent(seller,
                    "auction.sold-notification", "", Map.of("item", marker,
                            "buyer", buyer, "price", price))
                    .replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                            .matchLiteral(marker).replacement(itemName).build());
            seller.getClass().getMethod("sendMessage", net.kyori.adventure.text.Component.class)
                    .invoke(seller, rendered);
        }
    }

    public String formatEconomyPrice(double value) {
        return settings.isCompactPriceFormatEnabled()
                ? FormatUtils.formatPrice(value, true)
                : economyService.format(value);
    }

    private boolean addItemClaimInternal(UUID playerUuid, ItemStack item, String note) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return false;
        }
        ItemStack itemToStore = item.clone();
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        playerData.getClaims().add(ClaimEntry.item(itemToStore, note));
        return true;
    }

    private int indexOfClaim(PlayerData playerData, UUID claimId) {
        List<ClaimEntry> claims = playerData.getClaims();
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).getId().equals(claimId)) {
                return i;
            }
        }
        return -1;
    }

    private void queueNotificationInternal(UUID playerUuid, String message) {
        PlayerData playerData = userDataRepository.getOrCreate(playerUuid);
        playerData.getPendingNotifications().add(message);
    }

    private boolean hasEnoughInventorySpace(Player player, ItemStack item) {
        return inventoryDeposits.canFitAll(player, item);
    }

    private boolean isBlacklistedForListing(ItemStack item) {
        return isBlacklistedForListing(item, 0, new int[]{0});
    }

    private boolean isBlacklistedForListing(ItemStack item, int depth, int[] scannedItems) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (depth > MAX_BLACKLIST_SCAN_DEPTH || scannedItems[0] >= MAX_BLACKLIST_SCAN_ITEMS) {
            return false;
        }
        scannedItems[0]++;

        if (isSingleItemBlacklisted(item)) {
            return true;
        }

        if (depth >= MAX_BLACKLIST_SCAN_DEPTH) {
            return false;
        }

        for (ItemStack nested : extractNestedContents(item)) {
            if (isBlacklistedForListing(nested, depth + 1, scannedItems)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSingleItemBlacklisted(ItemStack item) {
        if (settings.getBlacklistedMaterials().contains(item.getType())) {
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }

        String stripped = ChatColor.stripColor(meta.getDisplayName());
        if (stripped == null || stripped.isBlank()) {
            return false;
        }

        String lowered = stripped.toLowerCase(Locale.ROOT);
        for (String token : settings.getBlacklistedNameContains()) {
            if (lowered.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpiredClaimNote(String note) {
        if (note == null || note.isBlank()) {
            return false;
        }
        return note.equals("@foauction:listing-expired")
                || note.toLowerCase(Locale.ROOT).contains("expired");
    }

    private String resolvePlayerName(UUID playerUuid) {
        return playerNameCache.resolve(playerUuid);
    }

    private String resolveItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String stripped = ChatColor.stripColor(meta.getDisplayName());
            if (stripped != null && !stripped.isBlank()) {
                return stripped;
            }
        }

        String[] words = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }

    private DeliveryType deliverItemToPlayerOrClaims(UUID playerUuid, Player onlinePlayer, ItemStack item) {
        String claimNote = onlinePlayer != null && onlinePlayer.isOnline()
                ? "@foauction:inventory-full"
                : "@foauction:player-offline";
        return deliverItemToPlayerOrClaims(playerUuid, onlinePlayer, item, claimNote);
    }

    private DeliveryType deliverItemToPlayerOrClaims(UUID playerUuid, Player onlinePlayer, ItemStack item, String claimNote) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return DeliveryType.INVENTORY;
        }

        int originalAmount = item.getAmount();
        ItemStack itemToDeliver = item.clone();
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            Map<Integer, ItemStack> leftovers = onlinePlayer.getInventory().addItem(itemToDeliver);
            if (leftovers.isEmpty()) {
                return DeliveryType.INVENTORY;
            }

            int leftoverAmount = 0;
            for (ItemStack leftover : leftovers.values()) {
                if (addItemClaimInternal(playerUuid, leftover, claimNote)) {
                    leftoverAmount += leftover.getAmount();
                }
            }

            return leftoverAmount >= originalAmount ? DeliveryType.CLAIMS : DeliveryType.PARTIAL_CLAIMS;
        }
        addItemClaimInternal(playerUuid, itemToDeliver, claimNote);
        return DeliveryType.CLAIMS;
    }

    private Comparator<AuctionListing> comparatorFor(SortMode mode) {
        return switch (mode) {
            case PRICE_HIGH_LOW -> Comparator.comparingDouble(AuctionListing::getPrice).reversed()
                    .thenComparing(Comparator.comparingLong(AuctionListing::getCreatedAt).reversed());
            case PRICE_LOW_HIGH -> Comparator.comparingDouble(AuctionListing::getPrice)
                    .thenComparingLong(AuctionListing::getCreatedAt);
            case OLDEST -> Comparator.comparingLong(AuctionListing::getCreatedAt);
            case NEWEST -> Comparator.comparingLong(AuctionListing::getCreatedAt).reversed();
        };
    }

    private boolean matchesSearchQuery(AuctionListing listing, String queryLowered) {
        try {
            if (queryLowered.isBlank()) {
                return true;
            }

            if (textMatchesQuery(resolvePlayerName(listing.getSellerUuid()), queryLowered)) {
                return true;
            }

            return matchesItemRecursively(listing.getItem(), queryLowered, 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean matchesItemRecursively(ItemStack item, String queryLowered, int depth) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (matchesSingleItem(item, queryLowered)) {
            return true;
        }

        if (depth >= 3) {
            return false;
        }

        for (ItemStack nested : extractNestedContents(item)) {
            if (matchesItemRecursively(nested, queryLowered, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleItem(ItemStack item, String queryLowered) {
        if (textMatchesQuery(item.getType().name(), queryLowered)) {
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        if (meta.hasDisplayName()) {
            if (textMatchesQuery(meta.getDisplayName(), queryLowered)) {
                return true;
            }
        }

        if (matchesEnchantments(item.getEnchantments().keySet(), queryLowered)) {
            return true;
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta
                && matchesEnchantments(storageMeta.getStoredEnchants().keySet(), queryLowered)) {
            return true;
        }

        if (meta.hasLore()) {
            for (String loreLine : meta.getLore()) {
                if (textMatchesQuery(loreLine, queryLowered)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean matchesEnchantments(Set<Enchantment> enchantments, String queryLowered) {
        if (enchantments == null || enchantments.isEmpty()) {
            return false;
        }

        for (Enchantment enchantment : enchantments) {
            try {
                if (enchantment == null) {
                    continue;
                }

                NamespacedKey key = enchantment.getKey();
                if (key == null) {
                    continue;
                }

                String keyRaw = key.getKey();
                if (keyRaw == null || keyRaw.isBlank()) {
                    continue;
                }
                if (textMatchesQuery(keyRaw, queryLowered)) {
                    return true;
                }

                String keyReadable = keyRaw.replace('_', ' ');
                if (textMatchesQuery(keyReadable, queryLowered)) {
                    return true;
                }

                String fullKey = key.toString();
                if (textMatchesQuery(fullKey, queryLowered)) {
                    return true;
                }
            } catch (Throwable ignored) {
                continue;
            }
        }
        return false;
    }

    private List<ItemStack> extractNestedContents(ItemStack parent) {
        List<ItemStack> nested = new ArrayList<>();
        ItemMeta meta = parent.getItemMeta();
        if (meta == null) {
            return nested;
        }

        try {
            if (meta instanceof BlockStateMeta blockStateMeta
                    && blockStateMeta.getBlockState() instanceof Container container) {
                for (ItemStack content : container.getInventory().getContents()) {
                    if (content != null && !content.getType().isAir()) {
                        nested.add(content.clone());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (meta instanceof BundleMeta bundleMeta) {
                for (ItemStack content : bundleMeta.getItems()) {
                    if (content != null && !content.getType().isAir()) {
                        nested.add(content.clone());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return nested;
    }

    private boolean textMatchesQuery(String value, String queryLowered) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = normalizeSearchText(value);
        if (normalized.contains(queryLowered)) {
            return true;
        }

        return normalized.replace(" ", "").contains(queryLowered.replace(" ", ""));
    }

    private String normalizeSearchText(String value) {
        String stripped = ChatColor.stripColor(value);
        if (stripped == null || stripped.isBlank()) {
            return "";
        }

        return stripped
                .replace('_', ' ')
                .replace('-', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeSearchQuery(String searchQuery) {
        if (searchQuery == null) {
            return "";
        }

        // Remove leading/trailing whitespace
        String trimmed = searchQuery.trim();

        // Length validation - prevent excessively long queries
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }

        // Remove potentially dangerous characters that could cause issues
        // Remove HTML/XML tags, script content, and special characters
        String sanitized = trimmed
            .replaceAll("<[^>]*>", "") // Remove HTML tags
            .replaceAll("[<>\"'&]", "") // Remove special HTML characters
            .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "") // Remove control characters except newline, tab, return
            .replaceAll("[\\p{So}\\p{Sk}]", ""); // Remove symbols and modifiers

        // Normalize whitespace
        sanitized = sanitized.replaceAll("\\s+", " ").trim();

        // Additional security: check for potential injection patterns
        String lowerSanitized = sanitized.toLowerCase();
        if (lowerSanitized.contains("javascript:") ||
            lowerSanitized.contains("data:") ||
            lowerSanitized.contains("vbscript:") ||
            lowerSanitized.contains("file:") ||
            lowerSanitized.contains("ftp:")) {
            // Return empty string if potentially dangerous patterns found
            return "";
        }

        return sanitized;
    }

    private enum DeliveryType {
        INVENTORY,
        CLAIMS,
        PARTIAL_CLAIMS
    }

    public enum ClaimResult {
        SUCCESS_ITEM,
        SUCCESS_MONEY,
        INVENTORY_FULL,
        NOT_FOUND,
        ECONOMY_ERROR,
        ON_COOLDOWN
    }

    public enum PurchaseResult {
        SUCCESS_INVENTORY,
        SUCCESS_CLAIMS,
        SUCCESS_PARTIAL_CLAIMS,
        INSUFFICIENT_FUNDS,
        OWN_LISTING,
        EXPIRED,
        NOT_FOUND,
        ECONOMY_ERROR,
        TRANSACTION_IN_PROGRESS
    }

    public enum CancelResult {
        SUCCESS_INVENTORY,
        INVENTORY_FULL,
        NOT_OWNER,
        NOT_FOUND
    }

    public enum AdminActionResult {
        SUCCESS_SELLER_CLAIMS,
        SUCCESS_ADMIN_INVENTORY,
        SUCCESS_ADMIN_CLAIMS,
        NOT_FOUND,
        NO_PERMISSION,
        INSUFFICIENT_PERMISSION
    }

    public record SellResult(
            Type type,
            AuctionListing listing,
            double minPrice,
            double maxPrice,
            double missingFee,
            int maxSlots
    ) {
        public static SellResult success(AuctionListing listing, int maxSlots) {
            return new SellResult(Type.SUCCESS, listing, 0D, 0D, 0D, maxSlots);
        }

        public static SellResult noItem() {
            return new SellResult(Type.NO_ITEM, null, 0D, 0D, 0D, 0);
        }

        public static SellResult priceOutOfRange(double minPrice, double maxPrice) {
            return new SellResult(Type.PRICE_OUT_OF_RANGE, null, minPrice, maxPrice, 0D, 0);
        }

        public static SellResult insufficientFunds(double missingFee) {
            return new SellResult(Type.INSUFFICIENT_FUNDS, null, 0D, 0D, missingFee, 0);
        }

        public static SellResult noSlots(int maxSlots) {
            return new SellResult(Type.NO_SLOTS, null, 0D, 0D, 0D, maxSlots);
        }

        public static SellResult blacklisted() {
            return new SellResult(Type.BLACKLISTED, null, 0D, 0D, 0D, 0);
        }

        public static SellResult economyFailed() {
            return new SellResult(Type.ECONOMY_ERROR, null, 0D, 0D, 0D, 0);
        }

        public enum Type {
            SUCCESS,
            NO_ITEM,
            PRICE_OUT_OF_RANGE,
            INSUFFICIENT_FUNDS,
            NO_SLOTS,
            BLACKLISTED,
            ECONOMY_ERROR
        }
    }

    private void cleanupExpiredSearchQueries() {
        long now = System.currentTimeMillis();
        searchQueryTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= SEARCH_QUERY_EXPIRY_MS) {
                return false;
            }
            searchQueryByViewer.remove(entry.getKey());
            return true;
        });
    }
}
