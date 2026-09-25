package me.foesio.foAuction.gui;

import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.gui.FoButtonStyle;
import me.foesio.core.message.FoMessageService;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.gui.holder.AdminRemoveHolder;
import me.foesio.foAuction.gui.holder.AuctionMainHolder;
import me.foesio.foAuction.gui.holder.ClaimHolder;
import me.foesio.foAuction.gui.holder.ConfirmationHolder;
import me.foesio.foAuction.gui.holder.ContainerPreviewHolder;
import me.foesio.foAuction.gui.holder.HistoryHolder;
import me.foesio.foAuction.gui.holder.MyListingsHolder;
import me.foesio.foAuction.gui.holder.SellerViewHolder;
import me.foesio.foAuction.model.AuctionListing;
import me.foesio.foAuction.model.ClaimEntry;
import me.foesio.foAuction.model.FilterMode;
import me.foesio.foAuction.model.SoldAuctionHistoryEntry;
import me.foesio.foAuction.model.SortMode;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.PlayerNameCache;
import me.foesio.foAuction.utils.ColorPalette;
import me.foesio.foAuction.utils.FormatUtils;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuctionGuiManager {
    private static final String MAIN_GUI_TITLE = "Auction House";
    private static final String PREVIEWING_TITLE = "ᴘʀᴇᴠɪᴇᴡɪɴɢ";
    private static final String CONFIRMATION_TITLE = "ᴄᴏɴꜰɪʀᴍᴀᴛɪᴏɴ";
    public static final int CONFIRMATION_ITEM_SLOT = 13;
    public static final int CONFIRMATION_CANCEL_SLOT = 11;
    public static final int CONFIRMATION_CONFIRM_SLOT = 15;
    private static final int[] MAIN_CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int[] AUCTION_ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int[] HISTORY_CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    public static final int PREVIOUS_SLOT = 45;
    public static final int CLAIMS_SLOT = 46;
    public static final int SORT_SLOT = 47;
    public static final int FILTER_SLOT = 48;
    public static final int REFRESH_SLOT = 49;
    public static final int MANAGE_ORDERS_SLOT = 50;
    public static final int SEARCH_SLOT = 51;
    public static final int CLEAR_SEARCH_SLOT = 52;
    public static final int NEXT_SLOT = 53;
    public static final int BACK_SLOT = 49;
    public static final int ADMIN_RETURN_TO_SELLER_SLOT = 11;
    public static final int ADMIN_PREVIEW_SLOT = 13;
    public static final int ADMIN_TAKE_ITEM_SLOT = 15;
    public static final int ADMIN_BACK_SLOT = 22;
    public static final int CONTAINER_PREVIEW_ITEM_SLOT = 4;
    public static final int CONTAINER_PREVIEW_BACK_SLOT = 40;
    public static final int HISTORY_PREVIOUS_SLOT = 45;
    public static final int HISTORY_BACK_SLOT = 49;
    public static final int HISTORY_NEXT_SLOT = 53;

    private final AuctionService auctionService;
    private final AuctionSettings settings;
    private final FoMessageService messages;
    private final PlayerNameCache playerNameCache;
    private final GuiConfigService guiConfig;
    private final GuiButtonConfig buttons;

    public AuctionGuiManager(
            AuctionService auctionService,
            AuctionSettings settings,
            FoMessageService messages,
            PlayerNameCache playerNameCache,
            GuiConfigService guiConfig
    ) {
        this.auctionService = auctionService;
        this.settings = settings;
        this.messages = messages;
        this.playerNameCache = playerNameCache;
        this.guiConfig = guiConfig;
        this.buttons = GuiButtonConfig.defaults();
    }

    public int mainPreviousSlot() { return guiConfig.slot("main.yml", "items.previous.slot", PREVIOUS_SLOT); }
    public int mainNextSlot() { return guiConfig.slot("main.yml", "items.next.slot", NEXT_SLOT); }
    public int mainClaimsSlot() { return guiConfig.slot("main.yml", "items.claims.slot", CLAIMS_SLOT); }
    public int mainSortSlot() { return guiConfig.slot("main.yml", "items.sort.slot", SORT_SLOT); }
    public int mainFilterSlot() { return guiConfig.slot("main.yml", "items.filter.slot", FILTER_SLOT); }
    public int mainRefreshSlot() { return guiConfig.slot("main.yml", "items.refresh.slot", REFRESH_SLOT); }
    public int mainManageSlot() { return guiConfig.slot("main.yml", "items.manage.slot", MANAGE_ORDERS_SLOT); }
    public int mainSearchSlot() { return guiConfig.slot("main.yml", "items.search.slot", SEARCH_SLOT); }
    public int mainHistoryOrClearSlot() { return guiConfig.slot("main.yml", "items.history-or-clear.slot", CLEAR_SEARCH_SLOT); }

    public int listingsPreviousSlot() { return guiConfig.slot("listings.yml", "items.previous.slot", PREVIOUS_SLOT); }
    public int listingsNextSlot() { return guiConfig.slot("listings.yml", "items.next.slot", NEXT_SLOT); }
    public int listingsRefreshSlot() { return guiConfig.slot("listings.yml", "items.refresh.slot", 48); }
    public int listingsSortSlot() { return guiConfig.slot("listings.yml", "items.sort.slot", SORT_SLOT); }
    public int listingsBackSlot() { return guiConfig.slot("listings.yml", "items.back.slot", GuiSlots.bottomMiddleSlot(6)); }

    public int sellerPreviousSlot() { return guiConfig.slot("seller-view.yml", "items.previous.slot", PREVIOUS_SLOT); }
    public int sellerNextSlot() { return guiConfig.slot("seller-view.yml", "items.next.slot", NEXT_SLOT); }
    public int sellerRefreshSlot() { return guiConfig.slot("seller-view.yml", "items.refresh.slot", 48); }
    public int sellerSortSlot() { return guiConfig.slot("seller-view.yml", "items.sort.slot", SORT_SLOT); }
    public int sellerBackSlot() { return guiConfig.slot("seller-view.yml", "items.back.slot", GuiSlots.bottomMiddleSlot(6)); }

    public int claimsPreviousSlot() { return guiConfig.slot("claims.yml", "items.previous.slot", PREVIOUS_SLOT); }
    public int claimsNextSlot() { return guiConfig.slot("claims.yml", "items.next.slot", NEXT_SLOT); }
    public int claimsRefreshSlot() { return guiConfig.slot("claims.yml", "items.refresh.slot", 48); }
    public int claimsBackSlot() { return guiConfig.slot("claims.yml", "items.back.slot", GuiSlots.bottomMiddleSlot(6)); }

    public int adminReturnSlot() { return guiConfig.slot("admin-remove.yml", "items.return.slot", ADMIN_RETURN_TO_SELLER_SLOT); }
    public int adminTakeSlot() { return guiConfig.slot("admin-remove.yml", "items.take.slot", ADMIN_TAKE_ITEM_SLOT); }
    public int adminBackSlot() { return guiConfig.slot("admin-remove.yml", "items.back.slot", ADMIN_BACK_SLOT); }

    public int previewBackSlot() { return guiConfig.slot("preview.yml", "items.back.slot", GuiSlots.bottomMiddleSlot(5)); }

    public int historyPreviousSlot() { return guiConfig.slot("history.yml", "items.previous.slot", HISTORY_PREVIOUS_SLOT); }
    public int historyNextSlot() { return guiConfig.slot("history.yml", "items.next.slot", HISTORY_NEXT_SLOT); }
    public int historyBackSlot() { return guiConfig.slot("history.yml", "items.back.slot", GuiSlots.bottomMiddleSlot(6)); }

    public int confirmationItemSlot() { return guiConfig.slot("confirmation.yml", "items.preview.slot", CONFIRMATION_ITEM_SLOT); }
    public int confirmationCancelSlot() { return guiConfig.slot("confirmation.yml", "items.cancel.slot", CONFIRMATION_CANCEL_SLOT); }
    public int confirmationConfirmSlot() { return guiConfig.slot("confirmation.yml", "items.confirm.slot", CONFIRMATION_CONFIRM_SLOT); }

    public void openMainGui(Player player, int requestedPage) {
        AuctionService.MainGuiView view = auctionService.getMainGuiView(
                player.getUniqueId(),
                requestedPage,
                mainContentSlots().length
        );
        SortMode sortMode = view.sortMode();
        FilterMode filterMode = view.filterMode();
        List<AuctionListing> listings = view.listings();
        String searchQuery = view.searchQuery();
        int page = view.page();
        boolean adminView = player.hasPermission("foauction.admin");

        AuctionMainHolder holder = new AuctionMainHolder(
                player.getUniqueId(),
                page,
                  title(player, "main.yml", "title", MAIN_GUI_TITLE)
          );
        holder.setItemRevision(auctionService.getItemRevision());
        Inventory inventory = holder.getInventory();

        Material frameMaterial = guiConfig.fillerMaterial("main.yml", Material.GRAY_STAINED_GLASS_PANE);
        decorateFrame(inventory, frameMaterial);
        fillRow(inventory, 4, frameMaterial);
        populateListingPage(
                player,
                holder.getInventory(),
                listings,
                page,
                false,
                adminView,
                mainContentSlots(),
                holder::mapSlot,
                pane(guiConfig.contentFillerMaterial("main.yml", Material.LIGHT_GRAY_STAINED_GLASS_PANE))
        );

        int maxPage = maxPageFor(view.totalListings(), mainContentSlots().length);
        setPageButtons(player, inventory, "main.yml", page, maxPage, mainPreviousSlot(), mainNextSlot());

        inventory.setItem(mainClaimsSlot(), guiConfig.item(
                player,
                "main.yml",
                "items.claims",
                Material.CHEST,
                "{theme}" + GuiTitles.smallCaps("Claims"),
                List.of("{white}Open your claim box"),
                true
        ));
        inventory.setItem(mainSortSlot(), guiConfig.item(
                player,
                "main.yml",
                "items.sort",
                Material.HOPPER,
                "{theme}" + GuiTitles.smallCaps("Sort"),
                sortLore(player, "main.yml", sortMode),
                true
        ));
        inventory.setItem(mainFilterSlot(), guiConfig.item(
                player,
                "main.yml",
                "items.filter",
                Material.REDSTONE,
                "{theme}" + GuiTitles.smallCaps("Filter"),
                filterLore(player, "main.yml", filterMode),
                true
        ));
        inventory.setItem(mainRefreshSlot(), guiConfig.item(
                player,
                "main.yml",
                "items.refresh",
                Material.MAP,
                "{theme}" + GuiTitles.smallCaps("Refresh"),
                List.of("{white}Reload listings"),
                true
        ));
        inventory.setItem(mainManageSlot(), guiConfig.item(
                player,
                "main.yml",
                "items.manage",
                Material.CHEST,
                "{theme}" + GuiTitles.smallCaps("Manage Auctions"),
                List.of(
                        "{white}View all your active auctions",
                        "{white}Click any listing to cancel"
                ),
                true
        ));
        inventory.setItem(mainSearchSlot(), guiConfig.itemOrFallback(
                player,
                "main.yml",
                "items.search",
                buttons.search(player, searchQuery),
                "current", searchQuery == null || searchQuery.isBlank() ? "{muted}None" : "{theme}" + searchQuery
        ));
        if (settings.isPlayerOwnHistoryViewAllowed()) {
            inventory.setItem(mainHistoryOrClearSlot(), guiConfig.item(
                    player,
                    "main.yml",
                    "items.history",
                    Material.BOOK,
                    "{theme}" + GuiTitles.smallCaps("History"),
                    List.of("{white}Click to see your history of sold auctions"),
                    true
            ));
        } else {
            inventory.setItem(mainHistoryOrClearSlot(), guiConfig.itemOrFallback(
                    player,
                    "main.yml",
                    "items.clear-search",
                    buttons.clearSearch(player, "auctions"),
                    "target", "auctions"
            ));
        }

        openForViewer(player, inventory);
    }

    public void openMyListingsGui(Player player, int requestedPage) {
        AuctionService.ListingsView view = auctionService.getPlayerListingsView(
                 player.getUniqueId(),
                 requestedPage,
                  listingsContentSlots().length
          );
        SortMode sortMode = view.sortMode();
        List<AuctionListing> listings = view.listings();
        int page = view.page();

        String title = title(player, "listings.yml", "title", "Manage Auctions") + " | "
                + GuiTitles.smallCaps("Page") + " " + (page + 1);
        MyListingsHolder holder = new MyListingsHolder(player.getUniqueId(), page, title);
        Inventory inventory = holder.getInventory();

        Material frameMaterial = guiConfig.fillerMaterial("listings.yml", Material.GRAY_STAINED_GLASS_PANE);
        decorateFrame(inventory, frameMaterial);
        fillRow(inventory, 4, frameMaterial);
        populateListingPage(
                player,
                inventory,
                listings,
                page,
                true,
                false,
                listingsContentSlots(),
                holder::mapSlot,
                pane(guiConfig.contentFillerMaterial("listings.yml", Material.LIGHT_GRAY_STAINED_GLASS_PANE))
        );

        int maxPage = maxPageFor(view.totalListings(), listingsContentSlots().length);
        setPageButtons(player, inventory, "listings.yml", page, maxPage, listingsPreviousSlot(), listingsNextSlot());

        inventory.setItem(listingsBackSlot(), guiConfig.itemOrFallback(
                player,
                "listings.yml",
                "items.back",
                buttons.back(player)
        ));
        inventory.setItem(listingsSortSlot(), guiConfig.item(
                player,
                "listings.yml",
                "items.sort",
                Material.HOPPER,
                "{theme}" + GuiTitles.smallCaps("Sort"),
                sortLore(player, "listings.yml", sortMode),
                true
        ));
        inventory.setItem(listingsRefreshSlot(), guiConfig.item(
                player,
                "listings.yml",
                "items.refresh",
                Material.MAP,
                "{theme}" + GuiTitles.smallCaps("Refresh"),
                List.of("{white}Reload your listings"),
                true
        ));

        openForViewer(player, inventory);
    }

    public void openSellerViewGui(Player viewer, UUID sellerUuid, int requestedPage) {
        AuctionService.ListingsView view = auctionService.getSellerListingsView(
                  viewer.getUniqueId(),
                  sellerUuid,
                  requestedPage,
                  sellerContentSlots().length
          );
        SortMode sortMode = view.sortMode();
        List<AuctionListing> listings = view.listings();
        int page = view.page();
        String sellerName = resolvePlayerName(sellerUuid);

        String title = title(viewer, "seller-view.yml", "title", "Viewing") + " "
                + sellerName
                + " | "
                + GuiTitles.smallCaps("Page")
                + " "
                + (page + 1);
        SellerViewHolder holder = new SellerViewHolder(viewer.getUniqueId(), sellerUuid, page, title);
        holder.setItemRevision(auctionService.getItemRevision());
        Inventory inventory = holder.getInventory();
        boolean adminView = viewer.hasPermission("foauction.admin");

        Material frameMaterial = guiConfig.fillerMaterial("seller-view.yml", Material.GRAY_STAINED_GLASS_PANE);
        decorateFrame(inventory, frameMaterial);
        populateListingPage(viewer, inventory, listings, page, false, adminView, sellerContentSlots(), holder::mapSlot);

        int maxPage = maxPageFor(view.totalListings(), sellerContentSlots().length);
        setPageButtons(viewer, inventory, "seller-view.yml", page, maxPage, sellerPreviousSlot(), sellerNextSlot());

        inventory.setItem(sellerBackSlot(), guiConfig.itemOrFallback(
                viewer,
                "seller-view.yml",
                "items.back",
                buttons.back(viewer)
        ));
        inventory.setItem(sellerSortSlot(), guiConfig.item(
                viewer,
                "seller-view.yml",
                "items.sort",
                Material.HOPPER,
                "{theme}" + GuiTitles.smallCaps("Sort"),
                sortLore(viewer, "seller-view.yml", sortMode),
                true
        ));
        inventory.setItem(sellerRefreshSlot(), guiConfig.item(
                viewer,
                "seller-view.yml",
                "items.refresh",
                Material.MAP,
                "{theme}" + GuiTitles.smallCaps("Refresh"),
                List.of("{white}Reload seller listings"),
                true
        ));

        openForViewer(viewer, inventory);
    }

    public void openClaimsGui(Player player, int requestedPage) {
        List<ClaimEntry> claims = auctionService.getClaims(player.getUniqueId());
        int page = clampPageFor(requestedPage, claims.size(), claimsContentSlots().length);

        String title = title(player, "claims.yml", "title", "Claim Box") + " | "
                + GuiTitles.smallCaps("Page") + " " + (page + 1);
        ClaimHolder holder = new ClaimHolder(player.getUniqueId(), page, title);
        Inventory inventory = holder.getInventory();

        Material frameMaterial = guiConfig.fillerMaterial("claims.yml", Material.GRAY_STAINED_GLASS_PANE);
        decorateFrame(inventory, frameMaterial);
        fillRow(inventory, 4, frameMaterial);

        int[] contentSlots = claimsContentSlots();
        ItemStack emptySlotFiller = pane(guiConfig.contentFillerMaterial("claims.yml", Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        int start = page * contentSlots.length;
        for (int i = 0; i < contentSlots.length; i++) {
            int claimIndex = start + i;
            int slot = contentSlots[i];
            if (claimIndex >= claims.size()) {
                inventory.setItem(slot, emptySlotFiller.clone());
                continue;
            }

            ClaimEntry claim = claims.get(claimIndex);
            ItemStack item = claimDisplayItem(player, claim);

            inventory.setItem(slot, item);
            holder.mapSlot(slot, claim.getId());
        }

        int maxPage = maxPageFor(claims.size(), contentSlots.length);
        setPageButtons(player, inventory, "claims.yml", page, maxPage, claimsPreviousSlot(), claimsNextSlot());

        inventory.setItem(claimsBackSlot(), guiConfig.itemOrFallback(
                player,
                "claims.yml",
                "items.back",
                buttons.back(player)
        ));
        inventory.setItem(claimsRefreshSlot(), guiConfig.item(
                player,
                "claims.yml",
                "items.refresh",
                Material.MAP,
                "{theme}" + GuiTitles.smallCaps("Refresh"),
                List.of("{white}Reload claims"),
                true
        ));

        openForViewer(player, inventory);
    }

    public int getContentSlotsPerPage() {
        return listingsContentSlots().length;
    }

    public boolean isPlayerOwnHistoryViewAllowed() {
        return settings.isPlayerOwnHistoryViewAllowed();
    }

    public boolean isConfirmationGuiEnabled() {
        return settings.isConfirmationGuiEnabled();
    }

    public void openHistoryGui(Player player, UUID sellerUuid, int requestedPage, int returnPage) {
        List<SoldAuctionHistoryEntry> historyEntries = auctionService.getSoldHistory(sellerUuid);
        int[] contentSlots = historyContentSlots();
        int page = clampPageFor(requestedPage, historyEntries.size(), contentSlots.length);

        String sellerName = resolvePlayerName(sellerUuid);
        String title = title(player, "history.yml", "title", "History")
                          + " | "
                          + sellerName
                          + " | "
                          + GuiTitles.smallCaps("Page")
                          + " "
                          + (page + 1);

        HistoryHolder holder = new HistoryHolder(player.getUniqueId(), sellerUuid, page, returnPage, title);
        Inventory inventory = holder.getInventory();
        Material frameMaterial = guiConfig.fillerMaterial("history.yml", Material.GRAY_STAINED_GLASS_PANE);
        decorateHistoryFrame(inventory, frameMaterial);
        fillRow(inventory, 4, frameMaterial);

        ItemStack emptySlotFiller = pane(guiConfig.contentFillerMaterial("history.yml", Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        int start = page * contentSlots.length;
        for (int i = 0; i < contentSlots.length; i++) {
            int historyIndex = start + i;
            int slot = contentSlots[i];
            if (historyIndex >= historyEntries.size()) {
                inventory.setItem(slot, emptySlotFiller.clone());
                continue;
            }

            SoldAuctionHistoryEntry entry = historyEntries.get(historyIndex);
            inventory.setItem(slot, soldHistoryDisplayItem(player, entry));
        }

        if (historyEntries.isEmpty()) {
            int emptyMessageSlot = guiConfig.slot("history.yml", "items.empty.slot", 4);
            inventory.setItem(emptyMessageSlot, guiConfig.item(
                    player,
                    "history.yml",
                    "items.empty",
                    Material.PAPER,
                    "{muted}" + GuiTitles.smallCaps("No History Yet"),
                    List.of("{white}No sold listings were found."),
                    false
            ));
        }

        int maxPage = maxPageFor(historyEntries.size(), contentSlots.length);
        if (maxPage > 0) {
            if (page > 0) {
                inventory.setItem(historyPreviousSlot(), guiConfig.itemOrFallback(
                        player,
                        "history.yml",
                        "items.previous",
                        buttons.previousPage(player, page, maxPage),
                        "page", page + 1,
                        "max_page", maxPage + 1
                ));
            }
            if (page < maxPage) {
                inventory.setItem(historyNextSlot(), guiConfig.itemOrFallback(
                        player,
                        "history.yml",
                        "items.next",
                        buttons.nextPage(player, page, maxPage),
                        "page", page + 1,
                        "max_page", maxPage + 1
                ));
            }
        }

        inventory.setItem(historyBackSlot(), guiConfig.itemOrFallback(
                player,
                "history.yml",
                "items.back",
                buttons.back(player)
        ));

        openForViewer(player, inventory);
    }

    public boolean openContainerPreviewGui(Player player, UUID listingId, int returnPage) {
        return openContainerPreviewGui(player, listingId, returnPage, null);
    }

    public boolean openContainerPreviewGui(Player player, UUID listingId, int returnPage, UUID sellerUuid) {
        AuctionListing listing = auctionService.getListing(listingId);
        if (listing == null) {
            return false;
        }

        ItemStack listingItem = listing.getItem();
        ItemStack[] contents = extractContainerContents(listingItem);
        if (!hasContainerItems(contents)) {
            return false;
        }

        String title = title(player, "preview.yml", "title", "Previewing") + " '" + resolveContainerDisplayName(listingItem) + "'";
        ContainerPreviewHolder holder = new ContainerPreviewHolder(player.getUniqueId(), sellerUuid, returnPage, title);
        Inventory inventory = holder.getInventory();

        ItemStack border = pane(Material.LIME_STAINED_GLASS_PANE);
        for (int slot = 0; slot <= 3; slot++) {
            inventory.setItem(slot, border.clone());
        }
        for (int slot = 5; slot <= 8; slot++) {
            inventory.setItem(slot, border.clone());
        }
        for (int slot = 36; slot <= 44; slot++) {
            inventory.setItem(slot, border.clone());
        }

        inventory.setItem(guiConfig.slot("preview.yml", "items.preview.slot", CONTAINER_PREVIEW_ITEM_SLOT), listingItem.clone());
        inventory.setItem(previewBackSlot(), guiConfig.itemOrFallback(
                player,
                "preview.yml",
                "items.back",
                buttons.back(player)
        ));

        for (int i = 0; i < 27; i++) {
            ItemStack content = contents[i];
            if (content != null && !content.getType().isAir()) {
                inventory.setItem(9 + i, content.clone());
            }
        }

        openForViewer(player, inventory);
        return true;
    }

    public void openAdminRemoveGui(Player player, UUID listingId, int returnPage) {
        AuctionListing listing = auctionService.getListing(listingId);
        if (listing == null) {
            openMainGui(player, returnPage);
            return;
        }

        AdminRemoveHolder holder = new AdminRemoveHolder(
                  player.getUniqueId(),
                  listingId,
                  returnPage,
                  title(player, "admin-remove.yml", "title", "Admin Remove")
          );
        Inventory inventory = holder.getInventory();
        decorateFrame(inventory, guiConfig.fillerMaterial("admin-remove.yml", Material.GRAY_STAINED_GLASS_PANE));

        ItemStack preview = listingDisplayItem(player, listing, System.currentTimeMillis(), false, false, false);
        inventory.setItem(guiConfig.slot("admin-remove.yml", "items.preview.slot", ADMIN_PREVIEW_SLOT), preview);

        inventory.setItem(adminReturnSlot(), guiConfig.item(
                player,
                "admin-remove.yml",
                "items.return",
                Material.CHEST,
                "{theme}" + GuiTitles.smallCaps("Send To Seller Claims"),
                List.of(
                        "{white}Remove this listing",
                        "{white}Item goes to seller claims",
                        "{white}Seller gets a notification"
                ),
                true
        ));
        inventory.setItem(adminTakeSlot(), guiConfig.item(
                player,
                "admin-remove.yml",
                "items.take",
                Material.HOPPER,
                "{theme}" + GuiTitles.smallCaps("Take Item"),
                List.of(
                        "{white}Remove this listing",
                        "{white}Item goes to your inventory",
                        "{white}If full: sent to your claims"
                ),
                true
        ));
        inventory.setItem(adminBackSlot(), guiConfig.item(
                player,
                "admin-remove.yml",
                "items.back",
                Material.ARROW,
                "{theme}" + GuiTitles.smallCaps("Back"),
                List.of("{white}Return to auction house"),
                true
        ));

        openForViewer(player, inventory);
    }

    private void populateListingPage(
            Player viewer,
            Inventory inventory,
            List<AuctionListing> listings,
            int page,
            boolean myListingsView,
            boolean adminView,
            int[] contentSlots,
            SlotMapper slotMapper
    ) {
        populateListingPage(viewer, inventory, listings, page, myListingsView, adminView, contentSlots, slotMapper, null);
    }

    private void populateListingPage(
            Player viewer,
            Inventory inventory,
            List<AuctionListing> listings,
            int page,
            boolean myListingsView,
            boolean adminView,
            int[] contentSlots,
            SlotMapper slotMapper,
            ItemStack emptySlotFiller
    ) {
        long now = System.currentTimeMillis();

        for (int i = 0; i < contentSlots.length; i++) {
            int slot = contentSlots[i];
            if (i >= listings.size()) {
                if (emptySlotFiller != null) {
                    inventory.setItem(slot, emptySlotFiller.clone());
                    continue;
                }
                break;
            }

            AuctionListing listing = listings.get(i);
            inventory.setItem(slot, listingDisplayItem(viewer, listing, now, myListingsView, adminView, true));
            slotMapper.map(slot, listing.getId());
        }
    }

    private ItemStack listingDisplayItem(
            Player viewer,
            AuctionListing listing,
            long now,
            boolean myListingsView,
            boolean adminView,
            boolean includeActionHints
    ) {
        ItemStack item = listing.getItem();
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }

            String sellerName = resolvePlayerName(listing.getSellerUuid());

            lore.addAll(guiConfig.textList(
                    viewer,
                    "main.yml",
                    "listing-lore.details",
                    List.of(
                            "",
                            "{muted}Seller: {theme}{seller}",
                            "{muted}Price: {theme}{price}",
                            "{muted}Time Left: {theme}{time_left}"
                    ),
                    "seller", sellerName,
                    "price", formatPrice(listing.getPrice()),
                    "time_left", FormatUtils.formatDuration(listing.getExpiresAt() - now)
            ));
            if (includeActionHints) {
                lore.add(guiConfig.text(viewer, "main.yml", "listing-lore.action-separator", ""));
                if (myListingsView) {
                    lore.addAll(guiConfig.textList(
                            viewer,
                            "main.yml",
                            "listing-lore.owner-actions",
                            List.of("{theme}Click to cancel")
                    ));
                } else {
                    if (hasContainerItems(extractContainerContents(item))) {
                        lore.addAll(guiConfig.textList(
                                viewer,
                                "main.yml",
                                "listing-lore.container-actions",
                                List.of(
                                        "{theme}Left Click to buy",
                                        "{theme}Right Click to preview"
                                )
                        ));
                    } else {
                        lore.addAll(guiConfig.textList(
                                viewer,
                                "main.yml",
                                "listing-lore.buy-actions",
                                List.of("{theme}Click to buy")
                        ));
                    }
                    if (adminView) {
                        lore.addAll(guiConfig.textList(
                                viewer,
                                "main.yml",
                                "listing-lore.admin-actions",
                                List.of("{muted}Press Drop Key: {theme}Admin remove options")
                        ));
                    }
                }
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private ItemStack soldHistoryDisplayItem(Player viewer, SoldAuctionHistoryEntry entry) {
        ItemStack item = entry.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) {
            lore.addAll(meta.getLore());
        }
        lore.add(guiConfig.text(viewer, "history.yml", "entry-lore.separator", ""));
        lore.add(guiConfig.text(
                viewer,
                "history.yml",
                "entry-lore.seller",
                "{muted}Seller: {theme}{seller}",
                "seller", resolvePlayerName(entry.getSellerUuid())
        ));
        lore.add(guiConfig.text(
                viewer,
                "history.yml",
                "entry-lore.buyer",
                "{muted}Buyer: {theme}{buyer}",
                "buyer", resolvePlayerName(entry.getBuyerUuid())
        ));
        lore.add(guiConfig.text(
                viewer,
                "history.yml",
                "entry-lore.price",
                "{muted}Price: {theme}{price}",
                "price", formatPrice(entry.getPrice())
        ));
        lore.add(guiConfig.text(
                viewer,
                "history.yml",
                "entry-lore.sold",
                "{muted}Sold: {theme}{sold}",
                "sold", FormatUtils.formatDateTime(entry.getSoldAt())
        ));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack claimDisplayItem(Player viewer, ClaimEntry claim) {
        if (claim.getType() == ClaimEntry.Type.ITEM) {
            ItemStack item = claim.getItem();
            if (item == null || item.getType().isAir()) {
                return guiConfig.item(
                        viewer,
                        "claims.yml",
                        "claim-entry.invalid",
                        Material.BARRIER,
                        "{bad}" + GuiTitles.smallCaps("Invalid Claim"),
                        List.of("{white}This claim cannot be delivered."),
                        false
                );
            }

            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                if (meta.hasLore()) {
                    lore.addAll(meta.getLore());
                }
                lore.add(guiConfig.text(viewer, "claims.yml", "claim-entry.separator", ""));
                if (!claim.getNote().isBlank()) {
                    lore.add(guiConfig.text(
                            viewer,
                            "claims.yml",
                            "claim-entry.item.note",
                            "{white}Note: {theme}{note}",
                            "note", claim.getNote()
                    ));
                }
                lore.add(guiConfig.text(
                        viewer,
                        "claims.yml",
                        "claim-entry.item.created",
                        "{white}Created: {theme}{created}",
                        "created", FormatUtils.formatDateTime(claim.getCreatedAt())
                ));
                lore.add(guiConfig.text(viewer, "claims.yml", "claim-entry.action-separator", ""));
                lore.add(guiConfig.text(
                        viewer,
                        "claims.yml",
                        "claim-entry.item.action",
                        "{white}Click to claim this item"
                ));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            return display;
        }

        ItemStack item = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(guiConfig.text(
                    viewer,
                    "claims.yml",
                    "claim-entry.money.name",
                    "{theme}" + GuiTitles.smallCaps("Claim Money")
            ));
            List<String> lore = new ArrayList<>();
            lore.add(guiConfig.text(
                    viewer,
                    "claims.yml",
                    "claim-entry.money.amount",
                    "{white}Amount: {theme}{amount}",
                    "amount", formatPrice(claim.getMoney())
            ));
            if (!claim.getNote().isBlank()) {
                lore.add(guiConfig.text(
                        viewer,
                        "claims.yml",
                        "claim-entry.money.note",
                        "{white}Note: {theme}{note}",
                        "note", claim.getNote()
                ));
            }
            lore.add(guiConfig.text(
                    viewer,
                    "claims.yml",
                    "claim-entry.money.created",
                    "{white}Created: {theme}{created}",
                    "created", FormatUtils.formatDateTime(claim.getCreatedAt())
            ));
            lore.add(guiConfig.text(viewer, "claims.yml", "claim-entry.action-separator", ""));
            lore.add(guiConfig.text(
                    viewer,
                    "claims.yml",
                    "claim-entry.money.action",
                    "{white}Click to claim this money"
            ));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void decorateFrame(Inventory inventory) {
        decorateFrame(inventory, Material.GRAY_STAINED_GLASS_PANE);
    }

    private void decorateFrame(Inventory inventory, Material paneMaterial) {
        ItemStack border = new ItemStack(paneMaterial);
        ItemMeta meta = border.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorPalette.LIGHT_GRAY_COLOR + " ");
            border.setItemMeta(meta);
        }

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            boolean borderSlot = row == 0 || row == 5 || column == 0 || column == 8;
            if (borderSlot) {
                inventory.setItem(slot, border.clone());
            }
        }
    }

    private void fillRow(Inventory inventory, int row, Material material) {
        ItemStack item = pane(material);
        int startSlot = row * 9;
        for (int slot = startSlot; slot < startSlot + 9; slot++) {
            inventory.setItem(slot, item.clone());
        }
    }

    private void decorateHistoryFrame(Inventory inventory, Material paneMaterial) {
        decorateFrame(inventory, paneMaterial);
    }

    private ItemStack button(Material material, String label, List<String> lore, boolean highlighted) {
        return EditorItemFactory.item(
                material,
                (highlighted ? ColorPalette.THEME_COLOR : ColorPalette.LIGHT_GRAY_COLOR) + GuiTitles.smallCaps(label),
                lore
        );
    }

    private void openForViewer(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                inventory.setItem(slot, DialogIcons.forViewer(player, item));
            }
        }
        player.openInventory(inventory);
    }

    private void setPageButtons(
            Player viewer,
            Inventory inventory,
            String fileName,
            int page,
            int maxPage,
            int previousSlot,
            int nextSlot
    ) {
        if (maxPage <= 0) {
            return;
        }

        if (page > 0) {
            inventory.setItem(previousSlot, guiConfig.itemOrFallback(
                    viewer,
                    fileName,
                    "items.previous",
                    buttons.previousPage(viewer, page, maxPage),
                    "page", page + 1,
                    "max_page", maxPage + 1
            ));
        }
        if (page < maxPage) {
            inventory.setItem(nextSlot, guiConfig.itemOrFallback(
                    viewer,
                    fileName,
                    "items.next",
                    buttons.nextPage(viewer, page, maxPage),
                    "page", page + 1,
                    "max_page", maxPage + 1
            ));
        }
    }

    private List<String> sortLore(Player viewer, String fileName, SortMode selectedMode) {
        return FoButtonStyle.buttonLore(CycleOptions.information(messages, selectedMode.name(), configuredCycleOptions(
                viewer,
                fileName,
                "items.sort.lore-options",
                SortMode.cycleOptions()
        )), "cycle");
    }

    private List<String> filterLore(Player viewer, String fileName, FilterMode selectedMode) {
        return FoButtonStyle.buttonLore(CycleOptions.information(messages, selectedMode.name(), configuredCycleOptions(
                viewer,
                fileName,
                "items.filter.lore-options",
                FilterMode.cycleOptions()
        )), "cycle");
    }

    private List<CycleOption> configuredCycleOptions(
            Player viewer,
            String fileName,
            String path,
            List<CycleOption> defaults
    ) {
        List<String> labels = guiConfig.textList(
                viewer,
                fileName,
                path,
                defaults.stream().map(CycleOption::label).toList()
        );
        List<CycleOption> configured = new ArrayList<>(defaults.size());
        for (int index = 0; index < defaults.size(); index++) {
            CycleOption fallback = defaults.get(index);
            String label = index < labels.size() ? labels.get(index) : fallback.label();
            if (label == null || label.isBlank()) {
                label = fallback.label();
            }
            configured.add(new CycleOption(fallback.value(), label));
        }
        return configured;
    }

    private int clampPageFor(int requestedPage, int totalItems, int pageSize) {
        int maxPage = maxPageFor(totalItems, pageSize);
        return Math.max(0, Math.min(requestedPage, maxPage));
    }

    private int maxPageFor(int totalItems, int pageSize) {
        if (totalItems <= 0) {
            return 0;
        }
        return (totalItems - 1) / pageSize;
    }

    private int[] mainContentSlots() {
        return guiConfig.slots("main.yml", "content-slots", MAIN_CONTENT_SLOTS);
    }

    private int[] listingsContentSlots() {
        return guiConfig.slots("listings.yml", "content-slots", AUCTION_ENTRY_SLOTS);
    }

    private int[] sellerContentSlots() {
        return guiConfig.slots("seller-view.yml", "content-slots", CONTENT_SLOTS);
    }

    private int[] claimsContentSlots() {
        return guiConfig.slots("claims.yml", "content-slots", AUCTION_ENTRY_SLOTS);
    }

    private int[] historyContentSlots() {
        return guiConfig.slots("history.yml", "content-slots", HISTORY_CONTENT_SLOTS);
    }

    private String title(Player viewer, String fileName, String path, String fallback) {
        return GuiTitles.format(DialogIcons.fallbackText(guiConfig.text(viewer, fileName, path, fallback)));
    }

    private ItemStack pane(Material material) {
        ItemStack pane = new ItemStack(material);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(" ");
            pane.setItemMeta(paneMeta);
        }
        return pane;
    }

    private ItemStack[] extractContainerContents(ItemStack item) {
        ItemStack[] result = new ItemStack[27];
        if (item == null || !isPreviewContainerType(item.getType())) {
            return result;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> bundleItems = bundleMeta.getItems();
            for (int i = 0; i < result.length && i < bundleItems.size(); i++) {
                ItemStack content = bundleItems.get(i);
                if (content != null && !content.getType().isAir()) {
                    result[i] = content.clone();
                }
            }
            return result;
        }

        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return result;
        }

        if (!(blockStateMeta.getBlockState() instanceof Container container)) {
            return result;
        }

        ItemStack[] raw = container.getInventory().getContents();
        for (int i = 0; i < result.length && i < raw.length; i++) {
            ItemStack content = raw[i];
            if (content != null && !content.getType().isAir()) {
                result[i] = content.clone();
            }
        }
        return result;
    }

    private boolean hasContainerItems(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private boolean isPreviewContainerType(Material material) {
        return material == Material.BARREL
                || material == Material.BUNDLE
                || material.name().endsWith("SHULKER_BOX");
    }

    private String resolveContainerDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String stripped = ChatColor.stripColor(meta.getDisplayName());
            if (stripped != null && !stripped.isBlank()) {
                return stripped;
            }
        }

        String[] words = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(words[i].charAt(0)));
            if (words[i].length() > 1) {
                builder.append(words[i].substring(1));
            }
        }
        return builder.toString();
    }

    private String resolvePlayerName(UUID uuid) {
        return playerNameCache.resolve(uuid);
    }

    public void openConfirmationGui(Player player, ItemStack item, double price, double listingFee) {
        ConfirmationHolder holder = ConfirmationHolder.forSell(
                  player.getUniqueId(),
                  price,
                  listingFee,
                  title(player, "confirmation.yml", "sell-title", CONFIRMATION_TITLE)
          );
        Inventory inventory = holder.getInventory();

        // Create the item being sold with lore
        ItemStack displayItem = item.clone();
        ItemMeta meta = displayItem.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }
            lore.add(guiConfig.text(player, "confirmation.yml", "preview-lore.separator", ""));
            lore.addAll(guiConfig.textList(
                    player,
                    "confirmation.yml",
                    "preview-lore.sell",
                    List.of(
                            "{white}Selling for: {theme}{price}",
                            "{white}Listing fee: {theme}{fee}"
                    ),
                    "price", formatPrice(price),
                    "fee", formatPrice(listingFee)
            ));
            meta.setLore(lore);
            displayItem.setItemMeta(meta);
        }
        inventory.setItem(confirmationItemSlot(), displayItem);

        // Cancel button
        ItemStack cancelButton = guiConfig.item(
                player,
                "confirmation.yml",
                "items.cancel",
                Material.RED_STAINED_GLASS_PANE,
                "{bad}" + GuiTitles.smallCaps("cancel"),
                List.of(
                        "{white}Selling for: {theme}" + formatPrice(price),
                        "{white}Listing fee: {theme}" + formatPrice(listingFee)
                ),
                false,
                "price", formatPrice(price),
                "fee", formatPrice(listingFee)
        );
        inventory.setItem(confirmationCancelSlot(), cancelButton);

        // Confirm button
        ItemStack confirmButton = guiConfig.item(
                player,
                "confirmation.yml",
                "items.confirm",
                Material.LIME_STAINED_GLASS_PANE,
                "{good}" + GuiTitles.smallCaps("confirm"),
                List.of(
                        "{white}Selling for: {theme}" + formatPrice(price),
                        "{white}Listing fee: {theme}" + formatPrice(listingFee)
                ),
                false,
                "price", formatPrice(price),
                "fee", formatPrice(listingFee)
        );
        inventory.setItem(confirmationConfirmSlot(), confirmButton);

        openForViewer(player, inventory);
    }

    public boolean openBuyConfirmationGui(Player player, UUID listingId, int returnPage) {
        return openBuyConfirmationGui(player, listingId, returnPage, null);
    }

    public boolean openBuyConfirmationGui(Player player, UUID listingId, int returnPage, UUID sellerUuid) {
        AuctionListing listing = auctionService.getListing(listingId);
        if (listing == null) {
            return false;
        }

        ConfirmationHolder holder = ConfirmationHolder.forBuy(
                player.getUniqueId(),
                listingId,
                  listing.getPrice(),
                  returnPage,
                  sellerUuid,
                  title(player, "confirmation.yml", "buy-title", CONFIRMATION_TITLE)
          );
        holder.setItemRevision(auctionService.getItemRevision());
        Inventory inventory = holder.getInventory();

        ItemStack displayItem = listing.getItem().clone();
        ItemMeta itemMeta = displayItem.getItemMeta();
        if (itemMeta != null) {
            List<String> lore = new ArrayList<>();
            if (itemMeta.hasLore()) {
                lore.addAll(itemMeta.getLore());
            }
            lore.add(guiConfig.text(player, "confirmation.yml", "preview-lore.separator", ""));
            lore.addAll(guiConfig.textList(
                    player,
                    "confirmation.yml",
                    "preview-lore.buy",
                    List.of(
                            "{white}Seller: {theme}{seller}",
                            "{white}Buying for: {theme}{price}"
                    ),
                    "seller", resolvePlayerName(listing.getSellerUuid()),
                    "price", formatPrice(listing.getPrice())
            ));
            itemMeta.setLore(lore);
            displayItem.setItemMeta(itemMeta);
        }
        inventory.setItem(confirmationItemSlot(), displayItem);

        ItemStack cancelButton = guiConfig.item(
                player,
                "confirmation.yml",
                "items.cancel",
                Material.RED_STAINED_GLASS_PANE,
                "{bad}" + GuiTitles.smallCaps("cancel"),
                List.of("{white}Buying for: {theme}" + formatPrice(listing.getPrice())),
                false,
                "price", formatPrice(listing.getPrice()),
                "fee", ""
        );
        inventory.setItem(confirmationCancelSlot(), cancelButton);

        ItemStack confirmButton = guiConfig.item(
                player,
                "confirmation.yml",
                "items.confirm",
                Material.LIME_STAINED_GLASS_PANE,
                "{good}" + GuiTitles.smallCaps("confirm"),
                List.of("{white}Buying for: {theme}" + formatPrice(listing.getPrice())),
                false,
                "price", formatPrice(listing.getPrice()),
                "fee", ""
        );
        inventory.setItem(confirmationConfirmSlot(), confirmButton);

        openForViewer(player, inventory);
        return true;
    }

    private String formatPrice(double value) {
        return FormatUtils.formatPrice(value, settings.isCompactPriceFormatEnabled());
    }

    @FunctionalInterface
    private interface SlotMapper {
        void map(int slot, UUID listingId);
    }
}
