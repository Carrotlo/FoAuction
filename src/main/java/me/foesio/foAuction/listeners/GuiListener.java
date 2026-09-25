package me.foesio.foAuction.listeners;

import me.foesio.core.FoCoreContext;
import me.foesio.core.dialog.ConfiguredTextDialogs;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.foAuction.gui.AuctionGuiManager;
import me.foesio.foAuction.gui.holder.AdminRemoveHolder;
import me.foesio.foAuction.gui.holder.AuctionMainHolder;
import me.foesio.foAuction.gui.holder.ClaimHolder;
import me.foesio.foAuction.gui.holder.ConfirmationHolder;
import me.foesio.foAuction.gui.holder.ContainerPreviewHolder;
import me.foesio.foAuction.gui.holder.HistoryHolder;
import me.foesio.foAuction.gui.holder.MyListingsHolder;
import me.foesio.foAuction.gui.holder.SellerViewHolder;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.utils.SoundFeedback;
import me.foesio.core.message.FoMessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class GuiListener implements Listener {
    private final JavaPlugin plugin;
    private final AuctionGuiManager guiManager;
    private final AuctionService auctionService;
    private final Supplier<FoCoreContext> coreProvider;
    private final ConfiguredTextDialogs dialogTexts;
    private final FoMessageService messages;
    private final Map<UUID, Long> refreshCooldownExpiryByPlayer;
    private final Map<UUID, Long> lastGuiActionTime;
    private final Map<UUID, Integer> guiActionCount;
    private final Set<UUID> pendingSearchInputs;
    private static final long RATE_LIMIT_WINDOW_MS = 10000L; // 10 seconds
    private static final int MAX_ACTIONS_PER_WINDOW = 20;

    public GuiListener(
            JavaPlugin plugin,
            AuctionGuiManager guiManager,
            AuctionService auctionService,
            Supplier<FoCoreContext> coreProvider,
            ConfiguredTextDialogs dialogTexts,
            FoMessageService messages
    ) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.coreProvider = coreProvider;
        this.dialogTexts = dialogTexts;
        this.messages = messages;
        this.refreshCooldownExpiryByPlayer = new ConcurrentHashMap<>();
        this.lastGuiActionTime = new ConcurrentHashMap<>();
        this.guiActionCount = new ConcurrentHashMap<>();
        this.pendingSearchInputs = ConcurrentHashMap.newKeySet();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (!isFoAuctionHolder(holder)) {
            return;
        }

        // Check rate limiting only for auction GUIs
        if (isPlayerRateLimited(player)) {
            event.setCancelled(true);
            message(player, "gui.slow-down");
            SoundFeedback.denied(player);
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInventory)) {
            return;
        }

        int slot = event.getRawSlot();
        if (holder instanceof AuctionMainHolder auctionMainHolder) {
            handleMainClick(player, auctionMainHolder, slot, event);
            return;
        }

        if (holder instanceof MyListingsHolder myListingsHolder) {
            handleMyListingsClick(player, myListingsHolder, slot);
            return;
        }

        if (holder instanceof SellerViewHolder sellerViewHolder) {
            handleSellerViewClick(player, sellerViewHolder, slot, event);
            return;
        }

        if (holder instanceof ClaimHolder claimHolder) {
            handleClaimClick(player, claimHolder, slot);
            return;
        }

        if (holder instanceof AdminRemoveHolder adminRemoveHolder) {
            handleAdminRemoveClick(player, adminRemoveHolder, slot);
            return;
        }

        if (holder instanceof ContainerPreviewHolder containerPreviewHolder) {
            handleContainerPreviewClick(player, containerPreviewHolder, slot);
            return;
        }

        if (holder instanceof HistoryHolder historyHolder) {
            handleHistoryClick(player, historyHolder, slot);
            return;
        }

        if (holder instanceof ConfirmationHolder confirmationHolder) {
            handleConfirmationClick(player, confirmationHolder, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (isFoAuctionHolder(topInventory.getHolder())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!isFoAuctionHolder(event.getInventory().getHolder())) {
            return;
        }

        FoCoreContext core = coreProvider.get();
        if (core != null && core.inventoryCloseSuppressor().consumeSuppressedClose(player)) {
            return;
        }

        runNextTick(() -> clearSearchAfterPurposefulClose(player));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        refreshCooldownExpiryByPlayer.remove(playerUuid);
        lastGuiActionTime.remove(playerUuid);
        guiActionCount.remove(playerUuid);
        FoCoreContext core = coreProvider.get();
        if (core != null) {
            core.dialogInputs().clear(player);
        }
        pendingSearchInputs.remove(playerUuid);
        auctionService.clearSearchQuery(playerUuid);
    }

    private void handleMainClick(Player player, AuctionMainHolder holder, int slot, InventoryClickEvent event) {
        UUID listingId = holder.getListingId(slot);
        if (listingId != null) {
            if (isAdminDeleteClick(player, event)) {
                SoundFeedback.menuOpen(player);
                runNextTick(() -> guiManager.openAdminRemoveGui(player, listingId, holder.getPage()));
                return;
            }
            if (event.isRightClick()) {
                boolean opened = guiManager.openContainerPreviewGui(player, listingId, holder.getPage());
                if (opened) {
                    SoundFeedback.menuOpen(player);
                    return;
                }
            }

            if (auctionService.isListingOwnedBy(listingId, player.getUniqueId())) {
                message(player, "gui.own-listing");
                SoundFeedback.denied(player);
                return;
            }

            if (guiManager.isConfirmationGuiEnabled()) {
                boolean opened = guiManager.openBuyConfirmationGui(player, listingId, holder.getPage());
                if (opened) {
                    SoundFeedback.menuOpen(player);
                } else {
                    message(player, "gui.listing-unavailable");
                    SoundFeedback.denied(player);
                    reopenMain(player, holder.getPage());
                }
                return;
            }

            AuctionService.PurchaseResult result = auctionService.buyListing(player, listingId, holder.getItemRevision());
            handlePurchaseResult(player, result);

            reopenMain(player, holder.getPage());
            return;
        }

        if (slot == guiManager.mainPreviousSlot()) {
            SoundFeedback.previousPage(player);
            reopenMain(player, holder.getPage() - 1);
            return;
        }

        if (slot == guiManager.mainNextSlot()) {
            SoundFeedback.nextPage(player);
            reopenMain(player, holder.getPage() + 1);
            return;
        }

        if (slot == guiManager.mainRefreshSlot()) {
            if (isRefreshOnCooldown(player)) {
                return;
            }
            SoundFeedback.click(player);
            reopenMain(player, holder.getPage());
            return;
        }

        if (slot == guiManager.mainSortSlot()) {
            auctionService.cycleSortMode(player.getUniqueId());
            SoundFeedback.sort(player);
            reopenMain(player, 0);
            return;
        }

        if (slot == guiManager.mainFilterSlot()) {
            auctionService.cycleFilterMode(player.getUniqueId());
            SoundFeedback.filter(player);
            reopenMain(player, 0);
            return;
        }

        if (slot == guiManager.mainManageSlot()) {
            SoundFeedback.menuOpen(player);
            runNextTick(() -> guiManager.openMyListingsGui(player, 0));
            return;
        }

        if (slot == guiManager.mainSearchSlot()) {
            FoCoreContext core = coreProvider.get();
            String currentSearch = auctionService.getSearchQuery(player.getUniqueId());
            boolean opened = EditorDialogInputs.openTextFromInventory(
                    plugin,
                    core.inventoryCloseSuppressor(),
                    core.dialogInputs().dialogs(),
                    player,
                    searchRequest(currentSearch),
                    query -> {
                        pendingSearchInputs.remove(player.getUniqueId());
                        if (query.isBlank()) {
                            auctionService.clearSearchQuery(player.getUniqueId());
                            message(player, "command.search-cleared");
                            SoundFeedback.clearSearch(player);
                        } else {
                            auctionService.setSearchQuery(player.getUniqueId(), query);
                            message(player, "command.searching", "query", query);
                            SoundFeedback.search(player);
                        }
                        guiManager.openMainGui(player, 0);
                    },
                    () -> {
                        pendingSearchInputs.remove(player.getUniqueId());
                        SoundFeedback.cancel(player);
                        guiManager.openMainGui(player, holder.getPage());
                    }
            );

            if (opened) {
                SoundFeedback.menuOpen(player);
            } else {
                pendingSearchInputs.add(player.getUniqueId());
            }
            return;
        }

        if (slot == guiManager.mainHistoryOrClearSlot()) {
            if (guiManager.isPlayerOwnHistoryViewAllowed()) {
                SoundFeedback.menuOpen(player);
                runNextTick(() -> guiManager.openHistoryGui(player, player.getUniqueId(), 0, holder.getPage()));
                return;
            }

            if (auctionService.getSearchQuery(player.getUniqueId()).isBlank()) {
                message(player, "gui.no-search-filter");
                SoundFeedback.denied(player);
                return;
            }
            auctionService.clearSearchQuery(player.getUniqueId());
            message(player, "command.search-cleared");
            SoundFeedback.clearSearch(player);
            reopenMain(player, 0);
            return;
        }

        if (slot == guiManager.mainClaimsSlot()) {
            SoundFeedback.menuOpen(player);
            runNextTick(() -> guiManager.openClaimsGui(player, 0));
        }
    }

    private void handleMyListingsClick(Player player, MyListingsHolder holder, int slot) {
        UUID listingId = holder.getListingId(slot);
        if (listingId != null) {
            AuctionService.CancelResult result = auctionService.cancelListing(player, listingId);
            switch (result) {
                case SUCCESS_INVENTORY -> {
                    message(player, "gui.cancel-returned");
                    SoundFeedback.listingCancelled(player);
                }
                case INVENTORY_FULL -> {
                    message(player, "gui.cancel-inventory-full");
                    SoundFeedback.denied(player);
                }
                case NOT_OWNER -> {
                    message(player, "gui.cancel-not-owner");
                    SoundFeedback.denied(player);
                }
                case NOT_FOUND -> {
                    message(player, "gui.listing-unavailable");
                    SoundFeedback.denied(player);
                }
            }
            runNextTick(() -> guiManager.openMyListingsGui(player, holder.getPage()));
            return;
        }

        if (slot == guiManager.listingsPreviousSlot()) {
            SoundFeedback.previousPage(player);
            runNextTick(() -> guiManager.openMyListingsGui(player, holder.getPage() - 1));
            return;
        }

        if (slot == guiManager.listingsNextSlot()) {
            SoundFeedback.nextPage(player);
            runNextTick(() -> guiManager.openMyListingsGui(player, holder.getPage() + 1));
            return;
        }

        if (slot == guiManager.listingsRefreshSlot()) {
            if (isRefreshOnCooldown(player)) {
                return;
            }
            SoundFeedback.click(player);
            runNextTick(() -> guiManager.openMyListingsGui(player, holder.getPage()));
            return;
        }

        if (slot == guiManager.listingsSortSlot()) {
            auctionService.cycleSortMode(player.getUniqueId());
            SoundFeedback.sort(player);
            runNextTick(() -> guiManager.openMyListingsGui(player, 0));
            return;
        }

        if (slot == guiManager.listingsBackSlot()) {
            SoundFeedback.back(player);
            reopenMain(player, 0);
        }
    }

    private void handleSellerViewClick(Player player, SellerViewHolder holder, int slot, InventoryClickEvent event) {
        UUID listingId = holder.getListingId(slot);
        if (listingId != null) {
            if (isAdminDeleteClick(player, event)) {
                SoundFeedback.menuOpen(player);
                runNextTick(() -> guiManager.openAdminRemoveGui(player, listingId, holder.getPage()));
                return;
            }
            if (event.isRightClick()) {
                boolean opened = guiManager.openContainerPreviewGui(
                        player,
                        listingId,
                        holder.getPage(),
                        holder.getSellerUuid()
                );
                if (opened) {
                    SoundFeedback.menuOpen(player);
                    return;
                }
            }

            if (guiManager.isConfirmationGuiEnabled()) {
                boolean opened = guiManager.openBuyConfirmationGui(
                        player,
                        listingId,
                        holder.getPage(),
                        holder.getSellerUuid()
                );
                if (opened) {
                    SoundFeedback.menuOpen(player);
                } else {
                    message(player, "gui.listing-unavailable");
                    SoundFeedback.denied(player);
                    runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getPage()));
                }
                return;
            }

            AuctionService.PurchaseResult result = auctionService.buyListing(player, listingId, holder.getItemRevision());
            handlePurchaseResult(player, result);

            runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getPage()));
            return;
        }

        if (slot == guiManager.sellerPreviousSlot()) {
            SoundFeedback.previousPage(player);
            runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getPage() - 1));
            return;
        }

        if (slot == guiManager.sellerNextSlot()) {
            SoundFeedback.nextPage(player);
            runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getPage() + 1));
            return;
        }

        if (slot == guiManager.sellerRefreshSlot()) {
            if (isRefreshOnCooldown(player)) {
                return;
            }
            SoundFeedback.click(player);
            runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getPage()));
            return;
        }

        if (slot == guiManager.sellerSortSlot()) {
            auctionService.cycleSortMode(player.getUniqueId());
            SoundFeedback.sort(player);
            runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), 0));
            return;
        }

        if (slot == guiManager.sellerBackSlot()) {
            SoundFeedback.back(player);
            reopenMain(player, 0);
        }
    }

    private void handleClaimClick(Player player, ClaimHolder holder, int slot) {
        UUID claimId = holder.getClaimId(slot);
        if (claimId != null) {
            boolean expiredClaim = auctionService.isExpiredClaim(player.getUniqueId(), claimId);
            AuctionService.ClaimResult result = auctionService.claim(player, claimId);
            switch (result) {
                case SUCCESS_ITEM -> {
                    message(player, "gui.claim-item");
                    if (expiredClaim) {
                        SoundFeedback.expiredClaimSuccess(player);
                    } else {
                        SoundFeedback.claimSuccess(player);
                    }
                }
                case SUCCESS_MONEY -> {
                    message(player, "gui.claim-money");
                    SoundFeedback.claimSuccess(player);
                }
                case INVENTORY_FULL -> {
                    message(player, "gui.cancel-inventory-full");
                    SoundFeedback.denied(player);
                }
                case NOT_FOUND -> {
                    message(player, "gui.claim-not-found");
                    SoundFeedback.denied(player);
                }
                case ECONOMY_ERROR -> {
                    message(player, "gui.claim-economy-error");
                    SoundFeedback.denied(player);
                }
                case ON_COOLDOWN -> {
                    message(player, "gui.claim-cooldown");
                    SoundFeedback.denied(player);
                }
            }
            runNextTick(() -> guiManager.openClaimsGui(player, holder.getPage()));
            return;
        }

        if (slot == guiManager.claimsPreviousSlot()) {
            SoundFeedback.previousPage(player);
            runNextTick(() -> guiManager.openClaimsGui(player, holder.getPage() - 1));
            return;
        }

        if (slot == guiManager.claimsNextSlot()) {
            SoundFeedback.nextPage(player);
            runNextTick(() -> guiManager.openClaimsGui(player, holder.getPage() + 1));
            return;
        }

        if (slot == guiManager.claimsRefreshSlot()) {
            if (isRefreshOnCooldown(player)) {
                return;
            }
            SoundFeedback.click(player);
            runNextTick(() -> guiManager.openClaimsGui(player, holder.getPage()));
            return;
        }

        if (slot == guiManager.claimsBackSlot()) {
            SoundFeedback.back(player);
            reopenMain(player, 0);
        }
    }

    private void handleAdminRemoveClick(Player player, AdminRemoveHolder holder, int slot) {
        if (!player.hasPermission("foauction.admin")) {
            message(player, "gui.admin-no-permission");
            SoundFeedback.denied(player);
            reopenMain(player, holder.getReturnPage());
            return;
        }

        if (slot == guiManager.adminReturnSlot()) {
            AuctionService.AdminActionResult result = auctionService.adminReturnListingToSellerClaims(holder.getListingId(), player);
            switch (result) {
                case SUCCESS_SELLER_CLAIMS -> {
                    message(player, "gui.admin-remove-seller");
                    SoundFeedback.adminAction(player);
                }
                case NOT_FOUND -> {
                    message(player, "gui.listing-unavailable");
                    SoundFeedback.denied(player);
                }
                case SUCCESS_ADMIN_INVENTORY, SUCCESS_ADMIN_CLAIMS -> {
                    message(player, "gui.admin-remove-seller");
                    SoundFeedback.adminAction(player);
                }
                case NO_PERMISSION -> {
                    message(player, "gui.admin-no-permission");
                    SoundFeedback.denied(player);
                }
                case INSUFFICIENT_PERMISSION -> {
                    message(player, "gui.admin-insufficient-permission");
                    SoundFeedback.denied(player);
                }
            }
            reopenMain(player, holder.getReturnPage());
            return;
        }

        if (slot == guiManager.adminTakeSlot()) {
            AuctionService.AdminActionResult result = auctionService.adminTakeListing(holder.getListingId(), player);
            switch (result) {
                case SUCCESS_ADMIN_INVENTORY -> {
                    message(player, "gui.admin-take-inventory");
                    SoundFeedback.adminAction(player);
                }
                case SUCCESS_ADMIN_CLAIMS -> {
                    message(player, "gui.admin-take-claims");
                    SoundFeedback.adminAction(player);
                }
                case NOT_FOUND -> {
                    message(player, "gui.listing-unavailable");
                    SoundFeedback.denied(player);
                }
                case SUCCESS_SELLER_CLAIMS -> {
                    message(player, "gui.admin-take-inventory");
                    SoundFeedback.adminAction(player);
                }
                case NO_PERMISSION -> {
                    message(player, "gui.admin-no-permission");
                    SoundFeedback.denied(player);
                }
                case INSUFFICIENT_PERMISSION -> {
                    message(player, "gui.admin-insufficient-permission");
                    SoundFeedback.denied(player);
                }
            }
            reopenMain(player, holder.getReturnPage());
            return;
        }

        if (slot == guiManager.adminBackSlot()) {
            SoundFeedback.back(player);
            reopenMain(player, holder.getReturnPage());
        }
    }

    private void handleContainerPreviewClick(Player player, ContainerPreviewHolder holder, int slot) {
        if (slot == guiManager.previewBackSlot()) {
            SoundFeedback.back(player);
            if (holder.getSellerUuid() != null) {
                runNextTick(() -> guiManager.openSellerViewGui(player, holder.getSellerUuid(), holder.getReturnPage()));
            } else {
                reopenMain(player, holder.getReturnPage());
            }
        }
    }

    private void handleHistoryClick(Player player, HistoryHolder holder, int slot) {
        if (slot == guiManager.historyPreviousSlot()) {
            SoundFeedback.previousPage(player);
            runNextTick(() -> guiManager.openHistoryGui(
                    player,
                    holder.getSellerUuid(),
                    holder.getPage() - 1,
                    holder.getReturnPage()
            ));
            return;
        }

        if (slot == guiManager.historyNextSlot()) {
            SoundFeedback.nextPage(player);
            runNextTick(() -> guiManager.openHistoryGui(
                    player,
                    holder.getSellerUuid(),
                    holder.getPage() + 1,
                    holder.getReturnPage()
            ));
            return;
        }

        if (slot == guiManager.historyBackSlot()) {
            SoundFeedback.back(player);
            reopenMain(player, holder.getReturnPage());
        }
    }

    private void reopenMain(Player player, int page) {
        runNextTick(() -> guiManager.openMainGui(player, page));
    }

    private boolean isRefreshOnCooldown(Player player) {
        long cooldownMillis = auctionService.getGuiRefreshCooldownMillis();
        if (cooldownMillis <= 0L) {
            return false;
        }

        long now = System.currentTimeMillis();
        UUID playerUuid = player.getUniqueId();
        Long nextAllowedAt = refreshCooldownExpiryByPlayer.get(playerUuid);
        if (nextAllowedAt != null && nextAllowedAt > now) {
            message(player, "gui.refresh-cooldown", "time", nextAllowedAt - now);
            SoundFeedback.denied(player);
            return true;
        }

        refreshCooldownExpiryByPlayer.put(playerUuid, now + cooldownMillis);
        return false;
    }

    private boolean isAdminDeleteClick(Player player, InventoryClickEvent event) {
        ClickType click = event.getClick();
        return player.hasPermission("foauction.admin")
                && (click == ClickType.DROP || click == ClickType.CONTROL_DROP);
    }

    private boolean isFoAuctionHolder(InventoryHolder holder) {
        return holder instanceof AuctionMainHolder
                || holder instanceof MyListingsHolder
                || holder instanceof SellerViewHolder
                || holder instanceof ClaimHolder
                || holder instanceof AdminRemoveHolder
                || holder instanceof ContainerPreviewHolder
                || holder instanceof HistoryHolder
                || holder instanceof ConfirmationHolder;
    }

    private void clearSearchAfterPurposefulClose(Player player) {
        if (!player.isOnline() || pendingSearchInputs.contains(player.getUniqueId())) {
            return;
        }

        InventoryHolder openHolder = player.getOpenInventory().getTopInventory().getHolder();
        if (isFoAuctionHolder(openHolder)) {
            return;
        }

        auctionService.clearSearchQuery(player.getUniqueId());
    }

    private TextDialogRequest searchRequest(String currentSearch) {
        String current = currentSearch == null ? "" : currentSearch;
        return dialogTexts.request("search", Map.of(
                "current", current,
                "theme", messages.renderTemplate("{theme}", Map.of()),
                "muted", messages.renderTemplate("{muted}", Map.of()),
                "white", messages.renderTemplate("{white}", Map.of()),
                "good", messages.renderTemplate("{good}", Map.of()),
                "bad", messages.renderTemplate("{bad}", Map.of())
        ));
    }

    private void handlePurchaseResult(Player player, AuctionService.PurchaseResult result) {
        switch (result) {
            case SUCCESS_INVENTORY -> {
                message(player, "gui.purchase-inventory");
                SoundFeedback.purchaseSuccess(player);
            }
            case SUCCESS_CLAIMS -> {
                message(player, "gui.purchase-claims");
                SoundFeedback.purchaseSuccess(player);
            }
            case SUCCESS_PARTIAL_CLAIMS -> {
                message(player, "gui.purchase-partial-claims");
                SoundFeedback.purchaseSuccess(player);
            }
            case INSUFFICIENT_FUNDS -> {
                message(player, "gui.insufficient-funds");
                SoundFeedback.insufficientFunds(player);
            }
            case OWN_LISTING -> {
                message(player, "gui.own-listing");
                SoundFeedback.denied(player);
            }
            case EXPIRED -> {
                message(player, "gui.listing-expired");
                SoundFeedback.denied(player);
            }
            case ITEM_CHANGED -> {
                message(player, "gui.item-updated");
                SoundFeedback.denied(player);
            }
            case NOT_FOUND -> {
                message(player, "gui.listing-unavailable");
                SoundFeedback.denied(player);
            }
            case ECONOMY_ERROR -> {
                message(player, "command.economy-error");
                SoundFeedback.denied(player);
            }
            case TRANSACTION_IN_PROGRESS -> {
                message(player, "gui.transaction-progress");
                SoundFeedback.denied(player);
            }
        }
    }

    private void runNextTick(Runnable runnable) {
        FoCoreContext core = coreProvider.get();
        if (core != null) {
            core.scheduler().runGlobal(runnable);
            return;
        }
        runnable.run();
    }

    private void message(Player player, String path, Object... placeholders) {
        messages.sendConfigured(player, path, stringPlaceholders(placeholders));
    }

    private String[] stringPlaceholders(Object... placeholders) {
        String[] values = new String[placeholders.length];
        for (int i = 0; i < placeholders.length; i++) {
            values[i] = String.valueOf(placeholders[i]);
        }
        return values;
    }

    private boolean isPlayerRateLimited(Player player) {
        UUID playerUuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        // Get or initialize player's action tracking
        Long lastActionTime = lastGuiActionTime.get(playerUuid);
        Integer actionCount = guiActionCount.getOrDefault(playerUuid, 0);
        
        // If this is the first action or window has expired, reset
        if (lastActionTime == null || (now - lastActionTime) > RATE_LIMIT_WINDOW_MS) {
            lastGuiActionTime.put(playerUuid, now);
            guiActionCount.put(playerUuid, 1);
            return false;
        }
        
        // Update action count
        actionCount++;
        guiActionCount.put(playerUuid, actionCount);
        
        // Check if player exceeded rate limit
        if (actionCount > MAX_ACTIONS_PER_WINDOW) {
            return true;
        }
        
        return false;
    }

    private void handleConfirmationClick(Player player, ConfirmationHolder holder, int slot) {
        if (!player.getUniqueId().equals(holder.getPlayerUuid())) {
            return;
        }

        if (slot == guiManager.confirmationCancelSlot()) {
            SoundFeedback.cancel(player);
            if (holder.isBuy()) {
                reopenAfterBuyConfirmation(player, holder);
            } else {
                player.closeInventory();
            }
            return;
        }

        if (slot == guiManager.confirmationConfirmSlot()) {
            if (holder.isBuy()) {
                handleBuyConfirmation(player, holder);
                return;
            }

            handleSellConfirmation(player, holder);
        }
    }

    private void handleBuyConfirmation(Player player, ConfirmationHolder holder) {
        UUID listingId = holder.getListingId();
        if (listingId == null) {
            message(player, "gui.listing-unavailable");
            SoundFeedback.denied(player);
            reopenAfterBuyConfirmation(player, holder);
            return;
        }

        SoundFeedback.confirm(player);
        AuctionService.PurchaseResult result = auctionService.buyListing(player, listingId, holder.getItemRevision());
        handlePurchaseResult(player, result);
        reopenAfterBuyConfirmation(player, holder);
    }

    private void handleSellConfirmation(Player player, ConfirmationHolder holder) {
        SoundFeedback.confirm(player);
          ItemStack item = player.getInventory().getItemInMainHand();
          if (item == null || item.getType().isAir()) {
              message(player, "command.hold-item");
              SoundFeedback.denied(player);
              player.closeInventory();
              return;
        }

        AuctionService.SellResult result = auctionService.createListing(player, holder.getPrice());
          switch (result.type()) {
              case SUCCESS -> {
                  message(
                          player,
                          "command.listed",
                          "price", auctionService.formatEconomyPrice(result.listing().getPrice()),
                          "active", auctionService.getSellCount(player.getUniqueId()),
                          "max", result.maxSlots()
                  );
                  SoundFeedback.listingCreated(player);
                  player.closeInventory();
              }
              case NO_ITEM -> {
                  message(player, "command.hold-item");
                  SoundFeedback.denied(player);
                  player.closeInventory();
              }
              case PRICE_OUT_OF_RANGE -> {
                  message(
                          player,
                          "command.price-range",
                          "min", auctionService.formatDisplayPrice(result.minPrice()),
                          "max", auctionService.formatDisplayPrice(result.maxPrice())
                  );
                  SoundFeedback.denied(player);
                  player.closeInventory();
              }
              case INSUFFICIENT_FUNDS -> {
                  message(player, "command.need-fee", "amount", auctionService.formatEconomyPrice(result.missingFee()));
                  SoundFeedback.insufficientFunds(player);
                  player.closeInventory();
              }
              case NO_SLOTS -> {
                  message(player, "command.no-slots", "max", result.maxSlots());
                  SoundFeedback.denied(player);
                  player.closeInventory();
              }
              case BLACKLISTED -> {
                  message(player, "command.blacklisted");
                  SoundFeedback.denied(player);
                  player.closeInventory();
              }
              case ECONOMY_ERROR -> {
                  message(player, "command.economy-error");
                  SoundFeedback.denied(player);
                  player.closeInventory();
              }
        }
    }

    private void reopenAfterBuyConfirmation(Player player, ConfirmationHolder holder) {
        UUID sellerUuid = holder.getSellerUuid();
        if (sellerUuid != null) {
            runNextTick(() -> guiManager.openSellerViewGui(player, sellerUuid, holder.getReturnPage()));
            return;
        }

        reopenMain(player, holder.getReturnPage());
    }
}
