package me.foesio.foAuction.gui;

import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.gui.GuiTitles;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final PlayerNameCache playerNameCache;
    private final GuiConfigService guiConfig;
    private final GuiButtonConfig buttons;
    private PublicAuctionGuis publicGuis;

    public AuctionGuiManager(
            AuctionService auctionService,
            AuctionSettings settings,
            PlayerNameCache playerNameCache,
            GuiConfigService guiConfig
    ) {
        this.auctionService = auctionService;
        this.settings = settings;
        this.playerNameCache = playerNameCache;
        this.guiConfig = guiConfig;
        this.buttons = GuiButtonConfig.defaults();
    }

    public void usePublicGuis(PublicAuctionGuis publicGuis) {
        this.publicGuis = java.util.Objects.requireNonNull(publicGuis, "publicGuis");
    }

    public PublicAuctionGuis publicGuis() {
        return publicGuis;
    }

    public int mainPreviousSlot() { return mainControls().get("items.previous.slot"); }
    public int mainNextSlot() { return mainControls().get("items.next.slot"); }
    public int mainClaimsSlot() { return mainControls().get("items.claims.slot"); }
    public int mainSortSlot() { return mainControls().get("items.sort.slot"); }
    public int mainFilterSlot() { return mainControls().get("items.filter.slot"); }
    public int mainRefreshSlot() { return mainControls().get("items.refresh.slot"); }
    public int mainManageSlot() { return mainControls().get("items.manage.slot"); }
    public int mainSearchSlot() { return mainControls().get("items.search.slot"); }
    public int mainHistoryOrClearSlot() { return mainControls().get("items.history-or-clear.slot"); }

    public int listingsPreviousSlot() { return listingsControls().get("items.previous.slot"); }
    public int listingsNextSlot() { return listingsControls().get("items.next.slot"); }
    public int listingsRefreshSlot() { return listingsControls().get("items.refresh.slot"); }
    public int listingsSortSlot() { return listingsControls().get("items.sort.slot"); }
    public int listingsBackSlot() { return listingsControls().get("items.back.slot"); }

    public int sellerPreviousSlot() { return sellerControls().get("items.previous.slot"); }
    public int sellerNextSlot() { return sellerControls().get("items.next.slot"); }
    public int sellerRefreshSlot() { return sellerControls().get("items.refresh.slot"); }
    public int sellerSortSlot() { return sellerControls().get("items.sort.slot"); }
    public int sellerBackSlot() { return sellerControls().get("items.back.slot"); }

    public int claimsPreviousSlot() { return claimsControls().get("items.previous.slot"); }
    public int claimsNextSlot() { return claimsControls().get("items.next.slot"); }
    public int claimsRefreshSlot() { return claimsControls().get("items.refresh.slot"); }
    public int claimsBackSlot() { return claimsControls().get("items.back.slot"); }

    public int adminReturnSlot() { return adminControls().get("items.return.slot"); }
    public int adminPreviewSlot() { return adminControls().get("items.preview.slot"); }
    public int adminTakeSlot() { return adminControls().get("items.take.slot"); }
    public int adminBackSlot() { return adminControls().get("items.back.slot"); }

    public int previewBackSlot() { return previewControls().get("items.back.slot"); }
    public int previewItemSlot() { return previewControls().get("items.preview.slot"); }

    public int historyPreviousSlot() { return historyControls().get("items.previous.slot"); }
    public int historyNextSlot() { return historyControls().get("items.next.slot"); }
    public int historyBackSlot() { return historyControls().get("items.back.slot"); }

    public int confirmationItemSlot() { return confirmationControls().get("items.preview.slot"); }
    public int confirmationCancelSlot() { return confirmationControls().get("items.cancel.slot"); }
    public int confirmationConfirmSlot() { return confirmationControls().get("items.confirm.slot"); }

    public void openMainGui(Player player, int requestedPage) {
        publicGuis.openMain(player, requestedPage);
    }

    public void openMyListingsGui(Player player, int requestedPage) {
        publicGuis.openListings(player, requestedPage);
    }

    public void openSellerViewGui(Player viewer, UUID sellerUuid, int requestedPage) {
        publicGuis.openSeller(viewer, sellerUuid, requestedPage);
    }

    public void openClaimsGui(Player player, int requestedPage) {
        publicGuis.openClaims(player, requestedPage);
    }

    public int getContentSlotsPerPage() {
        if (publicGuis != null) {
            return publicGuis.listingPageSize();
        }
        return listingsContentSlots().length;
    }

    public boolean isPlayerOwnHistoryViewAllowed() {
        return settings.isPlayerOwnHistoryViewAllowed();
    }

    public boolean isConfirmationGuiEnabled() {
        return settings.isConfirmationGuiEnabled();
    }

    public void openHistoryGui(Player player, UUID sellerUuid, int requestedPage, int returnPage) {
        publicGuis.openHistory(player, sellerUuid, requestedPage, returnPage);
    }

    public boolean openContainerPreviewGui(Player player, UUID listingId, int returnPage) {
        return openContainerPreviewGui(player, listingId, returnPage, null);
    }

    public boolean openContainerPreviewGui(Player player, UUID listingId, int returnPage, UUID sellerUuid) {
        return publicGuis.openPreview(player, listingId, returnPage, sellerUuid);
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
        inventory.setItem(adminPreviewSlot(), preview);

        inventory.setItem(adminReturnSlot(), guiConfig.item(
                player,
                "admin-remove.yml",
                "items.return",
                Material.CHEST_MINECART,
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
        return guiConfig.item(
                viewer,
                "claims.yml",
                "claim-entry.money",
                Material.SUNFLOWER,
                "{theme}" + GuiTitles.smallCaps("Claim Money"),
                lore,
                false,
                "amount", formatPrice(claim.getMoney()),
                "note", claim.getNote(),
                "created", FormatUtils.formatDateTime(claim.getCreatedAt())
        );
    }

    private void decorateFrame(Inventory inventory, Material paneMaterial) {
        decorateFrame(inventory, pane(paneMaterial));
    }

    private void decorateFrame(Inventory inventory, ItemStack border) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            boolean borderSlot = row == 0 || row == 5 || column == 0 || column == 8;
            if (borderSlot) {
                inventory.setItem(slot, border.clone());
            }
        }
    }

    private void fillRow(Inventory inventory, int row, ItemStack item) {
        int startSlot = row * 9;
        for (int slot = startSlot; slot < startSlot + 9; slot++) {
            inventory.setItem(slot, item.clone());
        }
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
        return cycleLore(viewer, fileName, "items.sort", selectedMode.name(), SortMode.cycleOptions());
    }

    private List<String> filterLore(Player viewer, String fileName, FilterMode selectedMode) {
        return cycleLore(viewer, fileName, "items.filter", selectedMode.name(), FilterMode.cycleOptions());
    }

    private List<String> cycleLore(
            Player viewer,
            String fileName,
            String path,
            String selectedValue,
            List<CycleOption> defaults
    ) {
        List<CycleOption> options = configuredCycleOptions(
                viewer,
                fileName,
                path + ".lore-options",
                defaults
        );
        List<String> optionLines = options.stream()
                .map(option -> guiConfig.text(
                        viewer,
                        fileName,
                        path + (option.value().equalsIgnoreCase(selectedValue)
                                ? ".option-selected"
                                : ".option-unselected"),
                        option.value().equalsIgnoreCase(selectedValue)
                                ? "&7&l | {theme}» {option}"
                                : "&7&l | {white}• {option}",
                        "option", option.label()
                ))
                .toList();
        List<String> template = guiConfig.textList(
                viewer,
                fileName,
                path + ".lore",
                List.of(
                        "&8ʙᴜᴛᴛᴏɴ",
                        " ",
                        "&eⓘ Information ↓",
                        "&7&l | &fOptions:",
                        "{options}",
                        " ",
                        "{good}→ Click to Cycle ←"
                )
        );
        return GuiTextTemplates.expandLine(template, "{options}", optionLines);
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
        return contentSlots("main.yml", MAIN_CONTENT_SLOTS, mainControls().values());
    }

    private int[] listingsContentSlots() {
        return contentSlots("listings.yml", AUCTION_ENTRY_SLOTS, listingsControls().values());
    }

    private int[] sellerContentSlots() {
        return contentSlots("seller-view.yml", CONTENT_SLOTS, sellerControls().values());
    }

    private int[] claimsContentSlots() {
        return contentSlots("claims.yml", AUCTION_ENTRY_SLOTS, claimsControls().values());
    }

    private int[] historyContentSlots() {
        return contentSlots("history.yml", HISTORY_CONTENT_SLOTS, historyControls().values());
    }

    private int[] contentSlots(String fileName, int[] fallback, Collection<Integer> reserved) {
        int[] requested = guiConfig.slots(fileName, "content-slots", fallback);
        int[] resolved = GuiLayoutResolver.contentSlots(requested, fallback, 54, reserved);
        if (resolved.length != requested.length) {
            guiConfig.warnContentSlotAdjustment(fileName);
        }
        return resolved;
    }

    private Map<String, Integer> mainControls() {
        return controls("main.yml", 54,
                new SlotDefault("items.previous.slot", PREVIOUS_SLOT),
                new SlotDefault("items.claims.slot", CLAIMS_SLOT),
                new SlotDefault("items.sort.slot", SORT_SLOT),
                new SlotDefault("items.filter.slot", FILTER_SLOT),
                new SlotDefault("items.refresh.slot", REFRESH_SLOT),
                new SlotDefault("items.manage.slot", MANAGE_ORDERS_SLOT),
                new SlotDefault("items.search.slot", SEARCH_SLOT),
                new SlotDefault("items.history-or-clear.slot", CLEAR_SEARCH_SLOT),
                new SlotDefault("items.next.slot", NEXT_SLOT));
    }

    private Map<String, Integer> listingsControls() {
        return controls("listings.yml", 54,
                new SlotDefault("items.previous.slot", PREVIOUS_SLOT),
                new SlotDefault("items.back.slot", GuiSlots.bottomMiddleSlot(6)),
                new SlotDefault("items.sort.slot", SORT_SLOT),
                new SlotDefault("items.refresh.slot", 48),
                new SlotDefault("items.next.slot", NEXT_SLOT));
    }

    private Map<String, Integer> sellerControls() {
        return controls("seller-view.yml", 54,
                new SlotDefault("items.previous.slot", PREVIOUS_SLOT),
                new SlotDefault("items.back.slot", GuiSlots.bottomMiddleSlot(6)),
                new SlotDefault("items.sort.slot", SORT_SLOT),
                new SlotDefault("items.refresh.slot", 48),
                new SlotDefault("items.next.slot", NEXT_SLOT));
    }

    private Map<String, Integer> claimsControls() {
        return controls("claims.yml", 54,
                new SlotDefault("items.previous.slot", PREVIOUS_SLOT),
                new SlotDefault("items.back.slot", GuiSlots.bottomMiddleSlot(6)),
                new SlotDefault("items.refresh.slot", 48),
                new SlotDefault("items.next.slot", NEXT_SLOT));
    }

    private Map<String, Integer> historyControls() {
        return controls("history.yml", 54,
                new SlotDefault("items.previous.slot", HISTORY_PREVIOUS_SLOT),
                new SlotDefault("items.back.slot", GuiSlots.bottomMiddleSlot(6)),
                new SlotDefault("items.next.slot", HISTORY_NEXT_SLOT));
    }

    private Map<String, Integer> previewControls() {
        return controls("preview.yml", 45,
                new SlotDefault("items.preview.slot", CONTAINER_PREVIEW_ITEM_SLOT),
                new SlotDefault("items.back.slot", GuiSlots.bottomMiddleSlot(5)));
    }

    private Map<String, Integer> confirmationControls() {
        return controls("confirmation.yml", 27,
                new SlotDefault("items.preview.slot", CONFIRMATION_ITEM_SLOT),
                new SlotDefault("items.cancel.slot", CONFIRMATION_CANCEL_SLOT),
                new SlotDefault("items.confirm.slot", CONFIRMATION_CONFIRM_SLOT));
    }

    private Map<String, Integer> adminControls() {
        return controls("admin-remove.yml", 27,
                new SlotDefault("items.return.slot", ADMIN_RETURN_TO_SELLER_SLOT),
                new SlotDefault("items.preview.slot", ADMIN_PREVIEW_SLOT),
                new SlotDefault("items.take.slot", ADMIN_TAKE_ITEM_SLOT),
                new SlotDefault("items.back.slot", ADMIN_BACK_SLOT));
    }

    private Map<String, Integer> controls(String fileName, int inventorySize, SlotDefault... defaults) {
        List<GuiLayoutResolver.Control> requested = new ArrayList<>(defaults.length);
        for (SlotDefault definition : defaults) {
            requested.add(new GuiLayoutResolver.Control(
                    definition.path(),
                    guiConfig.slot(fileName, definition.path(), definition.fallback(), inventorySize),
                    definition.fallback()
            ));
        }
        Map<String, Integer> resolved = GuiLayoutResolver.controls(requested);
        for (GuiLayoutResolver.Control control : requested) {
            int effective = resolved.get(control.path());
            if (effective != control.requested()) {
                guiConfig.warnLayoutConflict(fileName, control.path(), control.requested(), effective);
            }
        }
        return resolved;
    }

    private String title(Player viewer, String fileName, String path, String fallback) {
        return GuiTitles.format(DialogIcons.fallbackText(guiConfig.text(viewer, fileName, path, fallback)));
    }

    private String titleFormat(Player viewer, String fileName, String fallback, Object... placeholders) {
        return GuiTitles.format(DialogIcons.fallbackText(guiConfig.text(
                viewer,
                fileName,
                "title-format",
                fallback,
                placeholders
        )));
    }

    private ItemStack decoration(Player viewer, String fileName, String path, Material fallbackMaterial) {
        return guiConfig.item(viewer, fileName, path, fallbackMaterial, " ", List.of(), false);
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

    static ItemStack[] extractContainerContents(ItemStack item) {
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

    static boolean hasContainerItems(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPreviewContainerType(Material material) {
        return material == Material.BARREL
                || material == Material.BUNDLE
                || material.name().endsWith("SHULKER_BOX");
    }

    static String resolveContainerDisplayName(ItemStack item) {
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
        publicGuis.openSellConfirmation(player, item, price, listingFee);
    }

    public boolean openBuyConfirmationGui(Player player, UUID listingId, int returnPage) {
        return openBuyConfirmationGui(player, listingId, returnPage, null);
    }

    public boolean openBuyConfirmationGui(Player player, UUID listingId, int returnPage, UUID sellerUuid) {
        return publicGuis.openBuyConfirmation(player, listingId, returnPage, sellerUuid);
    }

    private String formatPrice(double value) {
        return FormatUtils.formatPrice(value, settings.isCompactPriceFormatEnabled());
    }

    @FunctionalInterface
    private interface SlotMapper {
        void map(int slot, UUID listingId);
    }

    private record SlotDefault(String path, int fallback) {
    }
}
