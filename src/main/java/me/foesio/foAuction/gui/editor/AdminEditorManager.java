package me.foesio.foAuction.gui.editor;

import me.foesio.core.FoCoreContext;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.ConfigEditorButton;
import me.foesio.core.editor.ConfigEditorMenu;
import me.foesio.core.editor.ConfigEditorValueType;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.editor.EditorMenuHolder;
import me.foesio.core.editor.EditorSaveResult;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiButtons;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.gui.EntryBrowserClick;
import me.foesio.core.gui.EntryBrowserHolder;
import me.foesio.core.gui.EntryBrowserMenus;
import me.foesio.core.gui.EntryBrowserRequest;
import me.foesio.core.material.MaterialChooserActionType;
import me.foesio.core.material.MaterialChooserClick;
import me.foesio.core.material.MaterialChooserHolder;
import me.foesio.core.material.MaterialChooserMenus;
import me.foesio.core.material.MaterialChooserMode;
import me.foesio.core.material.MaterialChooserRequest;
import me.foesio.core.material.MaterialSelections;
import me.foesio.core.material.MaterialTypes;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.core.scheduler.FoScheduler;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.FoAuction;
import me.foesio.foAuction.utils.ColorPalette;
import me.foesio.foAuction.utils.FormatUtils;
import me.foesio.foAuction.utils.InputValidationUtils;
import me.foesio.core.message.FoMessageService;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class AdminEditorManager {
    private static final int AUCTION_MIN_PRICE_SLOT = 10;
    private static final int AUCTION_MAX_PRICE_SLOT = 11;
    private static final int AUCTION_FEE_SLOT = 12;
    private static final int AUCTION_SLOTS_SLOT = 13;
    private static final int AUCTION_EXPIRE_SLOT = 14;
    private static final int AUCTION_BACK_SLOT = 22;
    private static final int NATIVE_DIALOGS_SLOT = 10;
    private static final int GUI_REFRESH_COOLDOWN_SLOT = 11;
    private static final int NATIVE_DIALOG_WARN_SLOT = 12;
    private static final int FILE_LOGGING_SLOT = 14;
    private static final int GUI_CONFIRMATION_SLOT = 15;
    private static final int GUI_COMPACT_PRICE_SLOT = 16;
    private static final int HISTORY_OWN_VIEW_SLOT = 13;
    private static final int DISCORD_ENABLED_SLOT = 11;
    private static final int DISCORD_URL_SLOT = 13;
    private static final int DISCORD_EVENTS_SLOT = 15;
    private static final int DISCORD_BACK_SLOT = 22;
    private static final int MAIN_BLACKLIST_SLOT = 13;
    private static final int MAIN_HISTORY_TOGGLE_SLOT = 14;
    private static final int SMALL_BACK_SLOT = 22;
    private static final int CONFIRM_CANCEL_SLOT = 11;
    private static final int CONFIRM_DELETE_SLOT = 15;
    private static final String MATERIAL_LIST_PATH = "blacklisted-items.material";
    private static final String NAME_LIST_PATH = "blacklisted-items.name-contains";
    private static final List<CycleOption> BOOLEAN_OPTIONS = List.of(
            new CycleOption("true", "Enabled"),
            new CycleOption("false", "Disabled")
    );

    private final JavaPlugin plugin;
    private final AuctionSettings settings;
    private final FoMessageService messages;
    private final Supplier<FoCoreContext> coreProvider;
    private final FoEditorSounds sounds;
    private final Runnable afterSettingsReload;
    private final Map<UUID, String> materialSearches;
    private final Map<UUID, String> nameSearches;
    private final Set<UUID> ignoredConfirmationCloses;
    private final GuiButtonConfig buttons;
    private final FileConfiguration guiEditorDefaults;
    private final FileConfiguration discordEditorDefaults;

    public AdminEditorManager(
            JavaPlugin plugin,
            AuctionSettings settings,
            FoMessageService messages,
            Supplier<FoCoreContext> coreProvider,
            FoEditorSounds sounds,
            Runnable afterSettingsReload
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.coreProvider = coreProvider;
        this.sounds = sounds;
        this.afterSettingsReload = afterSettingsReload == null ? () -> { } : afterSettingsReload;
        this.materialSearches = new HashMap<>();
        this.nameSearches = new HashMap<>();
        this.ignoredConfirmationCloses = new HashSet<>();
        this.buttons = GuiButtonConfig.defaults();
        this.guiEditorDefaults = createGuiEditorDefaults();
        this.discordEditorDefaults = createDiscordEditorDefaults();
    }

    public void openMain(Player player) {
        AdminEditorHolder holder = holder(player, AdminEditorHolder.Page.MAIN, 27, "FoAuction Editor");
        Inventory inventory = holder.getInventory();
        fill(inventory);

        inventory.setItem(10, button(player, Material.SUNFLOWER, "Auction", List.of(
                white("Prices, slots, fees, expiry")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(12, button(player, Material.CHEST_MINECART, "GUI", List.of(
                white("Confirmation and refresh settings")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(MAIN_BLACKLIST_SLOT, button(player, Material.BARRIER, "Blacklists", List.of(
                white("Blocked materials and item names")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(MAIN_HISTORY_TOGGLE_SLOT, toggleButton(player, "Player Own History", settings.isPlayerOwnHistoryViewAllowed()));
        inventory.setItem(16, button(player, Material.REDSTONE, "Discord", List.of(
                white("Webhook and event toggles")
        ), ColorPalette.THEME_COLOR, true));

        player.openInventory(inventory);
    }

    public void openAuction(Player player) {
        AdminEditorHolder holder = holder(player, AdminEditorHolder.Page.AUCTION, 27, "Auction Settings");
        Inventory inventory = holder.getInventory();
        fill(inventory);

        inventory.setItem(AUCTION_MIN_PRICE_SLOT, valueButton(player, Material.SUNFLOWER, "Minimum Price", formatPrice(settings.getMinPrice()), "Money amount"));
        inventory.setItem(AUCTION_MAX_PRICE_SLOT, valueButton(player, Material.EMERALD, "Maximum Price", formatPrice(settings.getMaxPrice()), "Money amount"));
        inventory.setItem(AUCTION_FEE_SLOT, valueButton(player, Material.GOLD_INGOT, "Listing Fee", settings.getListingFeePercent() + "%", "Percent 0-100"));
        inventory.setItem(AUCTION_SLOTS_SLOT, valueButton(player, Material.CHEST_MINECART, "Default Slots", String.valueOf(settings.getDefaultMaxSlots()), "Whole number"));
        inventory.setItem(AUCTION_EXPIRE_SLOT, valueButton(player, Material.CLOCK, "Expire Days", String.valueOf(settings.getExpireDays()), "1-30 days"));
        inventory.setItem(AUCTION_BACK_SLOT, GuiButtons.back(player));

        player.openInventory(inventory);
    }

    public void openGui(Player player) {
        openConfigMenu(player, "foauction-gui-settings");
    }

    public void openHistory(Player player) {
        openConfigMenu(player, "foauction-history-settings");
    }

    public void openDiscord(Player player) {
        AdminEditorHolder holder = holder(player, AdminEditorHolder.Page.DISCORD, 27, "Discord Settings");
        Inventory inventory = holder.getInventory();
        fill(inventory);

        inventory.setItem(DISCORD_ENABLED_SLOT, EditorItemFactory.toggle(player, "Discord Webhook", settings.isDiscordWebhookEnabled()));
        inventory.setItem(DISCORD_URL_SLOT, valueButton(player,
                Material.OAK_SIGN,
                "Webhook URL",
                summarizeWebhook(settings.getDiscordWebhookUrl()),
                "https://discord.com/api/webhooks/..."
        ));
        inventory.setItem(DISCORD_EVENTS_SLOT, button(player, Material.REPEATER, "Event Toggles", List.of(
                white("Open webhook event settings")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(DISCORD_BACK_SLOT, GuiButtons.back(player));

        player.openInventory(inventory);
    }

    public void openDiscordEvents(Player player) {
        openConfigMenu(player, "foauction-discord-events");
    }

    public void openBlacklist(Player player) {
        AdminEditorHolder holder = holder(player, AdminEditorHolder.Page.BLACKLIST, 27, "Blacklists");
        Inventory inventory = holder.getInventory();
        fill(inventory);

        inventory.setItem(11, button(player, Material.BEDROCK, "Material Blacklist", List.of(
                white("Block item types from listings")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(15, button(player, Material.NAME_TAG, "Name Blacklist", List.of(
                white("Block display-name fragments")
        ), ColorPalette.THEME_COLOR, true));
        inventory.setItem(SMALL_BACK_SLOT, GuiButtons.back(player));

        player.openInventory(inventory);
    }

    public void openMaterials(Player player, int requestedPage) {
        String filter = materialSearches.getOrDefault(player.getUniqueId(), "");
        MaterialChooserRequest request = MaterialChooserRequest.builder()
                .title(GuiTitles.format("Material Blacklist"))
                .mode(MaterialChooserMode.MULTI_TOGGLE)
                .page(Math.max(0, requestedPage))
                .filter(filter)
                .availableMaterials(MaterialTypes.allItems())
                .selectedMaterials(MaterialSelections.fromKeys(configStringList(MATERIAL_LIST_PATH)))
                .showBack(true)
                .showSearch(true)
                .buttons(buttons)
                .extraLore(material -> List.of(white("Click to toggle blacklist")))
                .build();
        materialSearches.put(player.getUniqueId(), request.filter());
        openMaterialRequest(player, request);
    }

    public void openNames(Player player, int requestedPage) {
        List<String> entries = filteredList(configStringList(NAME_LIST_PATH), nameSearches.get(player.getUniqueId()));
        List<EntryBrowserRequest.Entry> browserEntries = entries.stream()
                .map(value -> EntryBrowserRequest.Entry.of(value, button(player, Material.PAPER, value, List.of(
                        white("Click to remove")
                ), ColorPalette.THEME_COLOR, true)))
                .toList();
        EntryBrowserMenus.open(player, EntryBrowserRequest.builder()
                .title("Name Blacklist")
                .entries(browserEntries)
                .page(requestedPage)
                .filter(nameSearches.getOrDefault(player.getUniqueId(), ""))
                .buttons(buttons)
                .showBack(true)
                .addButton(button(player, Material.ANVIL, "Add Name", List.of(
                        white("Type blocked name fragment")
                ), ColorPalette.GOOD_COLOR, true))
                .emptyItem(button(player, Material.PAPER, "No Names", List.of(
                        white("No entries match the current search")
                ), ColorPalette.LIGHT_GRAY_COLOR, false))
                .build());
    }

    public void openRemoveConfirm(Player player, AdminEditorHolder.Page page, String value, int returnPage) {
        AdminEditorHolder holder = holder(player, page, 27, "Confirm Delete", returnPage, value);
        Inventory inventory = holder.getInventory();
        fill(inventory);
        inventory.setItem(CONFIRM_CANCEL_SLOT, button(player, Material.RED_WOOL, "Cancel", List.of(
                white("Return to blacklist")
        ), ColorPalette.BAD_COLOR, false));
        inventory.setItem(CONFIRM_DELETE_SLOT, button(player, Material.LAVA_BUCKET, "Confirm Delete", List.of(
                white("Remove: ") + ColorPalette.THEME_COLOR + value
        ), ColorPalette.BAD_COLOR, true));
        player.openInventory(inventory);
    }

    public void clear(Player player) {
        UUID playerUuid = player.getUniqueId();
        FoCoreContext core = coreProvider.get();
        if (core != null) {
            core.dialogInputs().clear(player);
        }
        materialSearches.remove(playerUuid);
        nameSearches.remove(playerUuid);
        ignoredConfirmationCloses.remove(playerUuid);
    }

    public void handleClick(Player player, AdminEditorHolder holder, int slot, ItemStack cursor) {
        if (!holder.getViewerUuid().equals(player.getUniqueId())) {
            return;
        }

        if (!player.hasPermission("foauction.admin")) {
            messages.sendConfigured(player, "admin.no-permission");
            sounds.error(player);
            player.closeInventory();
            return;
        }

        switch (holder.getPage()) {
            case MAIN -> handleMainClick(player, slot);
            case AUCTION -> handleAuctionClick(player, slot);
            case DISCORD -> handleDiscordClick(player, slot);
            case BLACKLIST -> handleBlacklistClick(player, slot);
            case CONFIRM_REMOVE_NAME -> handleConfirmRemoveClick(player, holder, slot);
        }
    }

    public void handleConfigEditorClick(Player player, EditorMenuHolder holder, int slot) {
        if (!player.hasPermission("foauction.admin")) {
            messages.sendConfigured(player, "admin.no-permission");
            sounds.error(player);
            player.closeInventory();
            return;
        }

        ConfigEditorMenu menu = configMenu(holder.id());
        if (menu == null || !menu.matches(holder)) {
            return;
        }

        String action = holder.actionAt(slot);
        if (action == null) {
            return;
        }

        ConfigEditorButton button = menu.buttonAt(holder, slot).orElse(null);
        if (button == null) {
            return;
        }
        if (button.type() == ConfigEditorValueType.ACTION) {
            if ("back".equals(action)) {
                sounds.back(player);
                if ("foauction-discord-events".equals(holder.id())) {
                    openDiscord(player);
                } else {
                    openMain(player);
                }
            }
            return;
        }
        if (button.type() == ConfigEditorValueType.INTEGER) {
            if ("refresh-cooldown".equals(button.id())) {
                startPrompt(player, PromptType.REFRESH_COOLDOWN);
            }
            return;
        }

        EditorSaveResult result = menu.toggle(button);
        if (result.successful()) {
            messages.sendConfigured(player, "editor.saved",
                    "setting", settingName(result.path()),
                    "value", menu.displayValue(button));
            sounds.toggle(player, plugin.getConfig().getBoolean(result.path()));
            openConfigMenu(player, holder.id());
        } else {
            messages.sendConfigured(player, "editor.save-failed", "setting", settingName(result.path()));
            sounds.error(player);
        }
    }

    public void handleMaterialChooserClick(Player player, MaterialChooserHolder holder, int slot) {
        if (!player.hasPermission("foauction.admin")) {
            messages.sendConfigured(player, "admin.no-permission");
            sounds.error(player);
            player.closeInventory();
            return;
        }

        MaterialChooserClick click = MaterialChooserMenus.handleClick(slot, holder);
        MaterialChooserActionType action = click.action();
        switch (action) {
            case PREVIOUS_PAGE -> {
                sounds.previousPage(player);
                openMaterialRequest(player, click.nextRequest());
            }
            case NEXT_PAGE -> {
                sounds.nextPage(player);
                openMaterialRequest(player, click.nextRequest());
            }
            case SEARCH -> {
                sounds.search(player);
                materialSearches.put(player.getUniqueId(), click.filter());
                startPrompt(player, PromptType.SEARCH_MATERIAL, click.page());
            }
            case CLEAR_SEARCH -> {
                sounds.clearSearch(player);
                materialSearches.remove(player.getUniqueId());
                messages.sendConfigured(player, "editor.search-cleared");
                openMaterials(player, 0);
            }
            case BACK -> {
                sounds.back(player);
                openBlacklist(player);
            }
            case SELECT, TOGGLE -> {
                Material material = click.material();
                if (material == null) {
                    return;
                }
                MaterialChooserRequest nextRequest = holder.request().withSelectedMaterials(
                        MaterialSelections.toggled(holder.request().selectedMaterials(), material)
                );
                if (saveMaterialSelection(player, nextRequest)) {
                    sounds.toggle(player, nextRequest.isSelected(material));
                    openMaterialRequest(player, nextRequest);
                }
            }
            case NONE -> {
            }
            default -> {
            }
        }
    }

    public void handleEntryBrowserClick(Player player, EntryBrowserHolder holder, int slot,
                                        org.bukkit.event.inventory.ClickType clickType) {
        EntryBrowserClick click = EntryBrowserMenus.handleClick(slot, holder, clickType);
        String search = holder.request().filter();
        switch (click.action()) {
            case ENTRY -> {
                openRemoveConfirm(player, AdminEditorHolder.Page.CONFIRM_REMOVE_NAME,
                        click.entryId(), holder.request().page());
                sounds.open(player);
            }
            case ADD -> {
                sounds.add(player);
                startPrompt(player, PromptType.ADD_NAME, holder.request().page());
            }
            case SEARCH -> {
                sounds.search(player);
                startPrompt(player, PromptType.SEARCH_NAME, holder.request().page());
            }
            case CLEAR_SEARCH -> {
                sounds.clearSearch(player);
                nameSearches.remove(player.getUniqueId());
                messages.sendConfigured(player, "editor.search-cleared");
                openNames(player, 0);
            }
            case PREVIOUS_PAGE -> {
                sounds.previousPage(player);
                openNames(player, holder.request().page() - 1);
            }
            case NEXT_PAGE -> {
                sounds.nextPage(player);
                openNames(player, holder.request().page() + 1);
            }
            case BACK -> {
                sounds.back(player);
                openBlacklist(player);
            }
            case NONE -> {
            }
        }
    }

    private void openConfigMenu(Player player, String id) {
        ConfigEditorMenu menu = configMenu(id);
        if (menu == null) {
            return;
        }
        Inventory inventory = menu.open(player);
        int rows = inventory.getSize() / GuiSlots.ROW_SIZE;
        inventory.setItem(GuiSlots.bottomMiddleSlot(rows), GuiButtons.back(player));
        player.openInventory(inventory);
    }

    private ConfigEditorMenu configMenu(String id) {
        return switch (id) {
            case "foauction-gui-settings" -> ConfigEditorMenu.builder(plugin, messages, guiEditorDefaults)
                    .id(id)
                    .title("gui.editor.title", "GUI Settings")
                    .size("gui.editor.size", 27)
                    .filler("gui.editor.filler", true, Material.GRAY_STAINED_GLASS_PANE)
                    .booleanLabels("Enabled", "Disabled")
                    .reloadSettings(() -> {
                        settings.reload();
                        afterSettingsReload.run();
                    })
                    .button(ConfigEditorButton.booleanSetting("native-dialogs", "native-dialogs.enabled", "Native Dialogs", "gui.editor.native-dialogs", NATIVE_DIALOGS_SLOT,
                            () -> coreProvider.get().nativeDialogs().configEnabled()).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.integerSetting("refresh-cooldown", "gui.refresh-cooldown-millis", "Refresh Cooldown", "gui.editor.refresh-cooldown", GUI_REFRESH_COOLDOWN_SLOT,
                            () -> Math.toIntExact(settings.getGuiRefreshCooldownMillis())).fallbackMaterial(Material.MAP).build())
                    .button(ConfigEditorButton.booleanSetting("dialog-warning", "native-dialogs.warn-on-fallback", "Dialog Fallback Warning", "gui.editor.dialog-warning", NATIVE_DIALOG_WARN_SLOT,
                            () -> coreProvider.get().nativeDialogs().warnOnFallback()).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("file-logging", "file-logging", "File Logging", "gui.editor.file-logging", FILE_LOGGING_SLOT,
                            settings::isFileLoggingEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("confirmation-gui", "gui.confirmation-gui-enabled", "Confirmation GUI", "gui.editor.confirmation-gui", GUI_CONFIRMATION_SLOT,
                            settings::isConfirmationGuiEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("compact-prices", "gui.compact-price-format", "Compact Prices", "gui.editor.compact-prices", GUI_COMPACT_PRICE_SLOT,
                            settings::isCompactPriceFormatEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.action("back", "back", GuiSlots.bottomMiddleSlot(3)).fallbackMaterial(Material.ARROW).build())
                    .build();
            case "foauction-history-settings" -> ConfigEditorMenu.builder(plugin, messages, plugin.getConfig())
                    .id(id)
                    .title("history.editor.title", "History Settings")
                    .size("history.editor.size", 27)
                    .filler("history.editor.filler", true, Material.GRAY_STAINED_GLASS_PANE)
                    .booleanLabels("Enabled", "Disabled")
                    .reloadSettings(() -> {
                        settings.reload();
                        afterSettingsReload.run();
                    })
                    .button(ConfigEditorButton.booleanSetting("player-own-history", "history.allow-player-own-view", "Player Own History", "history.editor.player-own-history", HISTORY_OWN_VIEW_SLOT,
                            settings::isPlayerOwnHistoryViewAllowed).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.action("back", "back", GuiSlots.bottomMiddleSlot(3)).fallbackMaterial(Material.ARROW).build())
                    .build();
            case "foauction-discord-events" -> ConfigEditorMenu.builder(plugin, messages, discordEditorDefaults)
                    .id(id)
                    .title("discord.editor.events-title", "Discord Events")
                    .size("discord.editor.events-size", 27)
                    .filler("discord.editor.filler", true, Material.GRAY_STAINED_GLASS_PANE)
                    .booleanLabels("Enabled", "Disabled")
                    .reloadSettings(() -> {
                        settings.reload();
                        afterSettingsReload.run();
                    })
                    .button(ConfigEditorButton.booleanSetting("selling", "discord-webhook.selling", "Selling", "discord.editor.selling", 10,
                            settings::isDiscordWebhookSellingEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("listing", "discord-webhook.listing", "Listing", "discord.editor.listing", 11,
                            settings::isDiscordWebhookListingEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("admin-removing", "discord-webhook.admin-removing", "Admin Removing", "discord.editor.admin-removing", 12,
                            settings::isDiscordWebhookAdminRemovingEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("claims", "discord-webhook.claims", "Claims", "discord.editor.claims", 13,
                            settings::isDiscordWebhookClaimsEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("cancelling-listings", "discord-webhook.cancelling-listings", "Cancelling Listings", "discord.editor.cancelling-listings", 14,
                            settings::isDiscordWebhookCancellingListingsEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.booleanSetting("claiming-listings", "discord-webhook.claiming-listings", "Claiming Listings", "discord.editor.claiming-listings", 15,
                            settings::isDiscordWebhookClaimingListingsEnabled).fallbackMaterial(Material.LIME_DYE).build())
                    .button(ConfigEditorButton.action("back", "back", GuiSlots.bottomMiddleSlot(3)).fallbackMaterial(Material.ARROW).build())
                    .build();
            default -> null;
        };
    }

    private static FileConfiguration createGuiEditorDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gui.editor.title", "GUI Settings");
        config.set("gui.editor.size", 27);
        config.set("gui.editor.filler.enabled", true);
        config.set("gui.editor.filler.material", Material.GRAY_STAINED_GLASS_PANE.name());
        config.set("gui.editor.native-dialogs.lore", List.of(
                "{white}Use native Paper dialog screens when supported.",
                "{white}Current: {theme}{value}"
        ));
        config.set("gui.editor.refresh-cooldown.lore", List.of(
                "{white}Milliseconds between refresh clicks.",
                "{white}Set to {theme}0{white} to disable.",
                "{white}Current: {theme}{value}"
        ));
        config.set("gui.editor.dialog-warning.lore", List.of(
                "{white}Warn admins when dialogs use fallback input.",
                "{white}Current: {theme}{value}"
        ));
        config.set("gui.editor.file-logging.lore", List.of(
                "{white}Write plugin activity to logs/latest.log.",
                "{white}Current: {theme}{value}"
        ));
        config.set("gui.editor.confirmation-gui.lore", List.of(
                "{white}Require confirmation before buying or selling.",
                "{white}Current: {theme}{value}"
        ));
        config.set("gui.editor.compact-prices.lore", List.of(
                "{white}Show prices with compact suffixes like 1.5K or 1Qa.",
                "{white}Current: {theme}{value}"
        ));
        return config;
    }

    private static FileConfiguration createDiscordEditorDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("discord.editor.events-title", "Discord Events");
        config.set("discord.editor.events-size", 27);
        config.set("discord.editor.filler.enabled", true);
        config.set("discord.editor.filler.material", Material.GRAY_STAINED_GLASS_PANE.name());
        config.set("discord.editor.selling.lore", List.of(
                "{white}Send Discord notifications when players sell items.",
                "{white}Current: {theme}{value}"
        ));
        config.set("discord.editor.listing.lore", List.of(
                "{white}Send Discord notifications when players create listings.",
                "{white}Current: {theme}{value}"
        ));
        config.set("discord.editor.admin-removing.lore", List.of(
                "{white}Send Discord notifications when an admin removes a listing.",
                "{white}Current: {theme}{value}"
        ));
        config.set("discord.editor.claims.lore", List.of(
                "{white}Send Discord notifications for claim activity.",
                "{white}Current: {theme}{value}"
        ));
        config.set("discord.editor.cancelling-listings.lore", List.of(
                "{white}Send Discord notifications when listings are cancelled.",
                "{white}Current: {theme}{value}"
        ));
        config.set("discord.editor.claiming-listings.lore", List.of(
                "{white}Send Discord notifications when listings are claimed.",
                "{white}Current: {theme}{value}"
        ));
        return config;
    }

    private void openMaterialRequest(Player player, MaterialChooserRequest request) {
        if (request == null) {
            return;
        }
        materialSearches.put(player.getUniqueId(), request.filter());
        Inventory inventory = MaterialChooserMenus.createInventory(player, request);
        if (inventory.getHolder() instanceof MaterialChooserHolder holder) {
            for (Map.Entry<Integer, Material> entry : holder.materialsBySlot().entrySet()) {
                ItemStack item = inventory.getItem(entry.getKey());
                if (item == null) {
                    continue;
                }
                var meta = item.getItemMeta();
                if (meta == null || meta.getLore() == null) {
                    continue;
                }

                List<String> lore = new ArrayList<>(meta.getLore());
                for (int index = 0; index < lore.size(); index++) {
                    String plainLore = ChatColor.stripColor(lore.get(index));
                    if (plainLore == null || !plainLore.startsWith("State:")) {
                        continue;
                    }
                    String state = holder.request().isSelected(entry.getValue())
                            ? ColorPalette.BAD_COLOR + "Blacklisted"
                            : ColorPalette.GOOD_COLOR + "Allowed";
                    lore.set(index, ColorPalette.WHITE_COLOR + "State: " + state);
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                    break;
                }
            }
        }
        player.openInventory(inventory);
    }

    private boolean saveMaterialSelection(Player player, MaterialChooserRequest request) {
        Set<Material> selected = request.selectedMaterials();
        List<String> values = new ArrayList<>();
        for (String value : configStringList(MATERIAL_LIST_PATH)) {
            if (MaterialTypes.match(value) == null) {
                values.add(value);
            }
        }
        values.addAll(MaterialSelections.toKeys(selected));
        if (!saveAndReload(player, MATERIAL_LIST_PATH, values, false, false)) {
            return false;
        }
        messages.sendConfigured(player, "editor.saved",
                "setting", settingName(MATERIAL_LIST_PATH),
                "value", values.size() + " entries");
        return true;
    }

    public void handleConfirmationClose(Player player, AdminEditorHolder holder) {
        if (ignoredConfirmationCloses.remove(player.getUniqueId())) {
            return;
        }

        FoScheduler scheduler = coreProvider.get().scheduler();
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (holder.getPage() == AdminEditorHolder.Page.CONFIRM_REMOVE_NAME) {
                openNames(player, holder.getListPage());
            }
        });
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 10 -> {
                openAuction(player);
                sounds.open(player);
            }
            case 12 -> {
                openGui(player);
                sounds.open(player);
            }
            case MAIN_BLACKLIST_SLOT -> {
                openBlacklist(player);
                sounds.open(player);
            }
            case MAIN_HISTORY_TOGGLE_SLOT -> {
                boolean enabled = !settings.isPlayerOwnHistoryViewAllowed();
                if (saveAndReload(player, "history.allow-player-own-view", enabled, true, false)) {
                    sounds.toggle(player, enabled);
                    openMain(player);
                }
            }
            case 16 -> {
                openDiscord(player);
                sounds.open(player);
            }
            default -> {
            }
        }
    }

    private void handleAuctionClick(Player player, int slot) {
        switch (slot) {
            case AUCTION_MIN_PRICE_SLOT -> startPrompt(player, PromptType.MIN_PRICE);
            case AUCTION_MAX_PRICE_SLOT -> startPrompt(player, PromptType.MAX_PRICE);
            case AUCTION_FEE_SLOT -> startPrompt(player, PromptType.LISTING_FEE);
            case AUCTION_SLOTS_SLOT -> startPrompt(player, PromptType.DEFAULT_SLOTS);
            case AUCTION_EXPIRE_SLOT -> startPrompt(player, PromptType.EXPIRE_DAYS);
            case AUCTION_BACK_SLOT -> {
                sounds.back(player);
                openMain(player);
            }
            default -> {
            }
        }
    }

    private void handleDiscordClick(Player player, int slot) {
        switch (slot) {
            case DISCORD_ENABLED_SLOT -> {
                boolean enabled = !settings.isDiscordWebhookEnabled();
                if (saveAndReload(player, "discord-webhook.enabled", enabled, true, false)) {
                    sounds.toggle(player, enabled);
                    openDiscord(player);
                }
            }
            case DISCORD_URL_SLOT -> startPrompt(player, PromptType.WEBHOOK_URL);
            case DISCORD_EVENTS_SLOT -> {
                openDiscordEvents(player);
                sounds.open(player);
            }
            case DISCORD_BACK_SLOT -> {
                sounds.back(player);
                openMain(player);
            }
            default -> {
            }
        }
    }

    private void handleBlacklistClick(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                openMaterials(player, 0);
                sounds.open(player);
            }
            case 15 -> {
                openNames(player, 0);
                sounds.open(player);
            }
            case SMALL_BACK_SLOT -> {
                sounds.back(player);
                openMain(player);
            }
            default -> {
            }
        }
    }

    private void handleConfirmRemoveClick(Player player, AdminEditorHolder holder, int slot) {
        if (slot == CONFIRM_CANCEL_SLOT) {
            ignoredConfirmationCloses.add(player.getUniqueId());
            sounds.back(player);
            openNames(player, holder.getListPage());
            return;
        }

        if (slot != CONFIRM_DELETE_SLOT) {
            return;
        }

        ignoredConfirmationCloses.add(player.getUniqueId());
        if (removeListValue(player, NAME_LIST_PATH, holder.getValue())) {
            openNames(player, holder.getListPage());
        }
    }

    private void processPrompt(Player player, PendingPrompt prompt, String input) throws EditorInputException {
        switch (prompt.type()) {
            case MIN_PRICE -> {
                double value = parsePrice(input);
                if (value > settings.getMaxPrice()) {
                    throw new EditorInputException("Minimum price cannot exceed current maximum.");
                }
                if (saveAndReload(player, "auction.min-price", value)) {
                    openAuction(player);
                }
            }
            case MAX_PRICE -> {
                double value = parsePrice(input);
                if (value < settings.getMinPrice()) {
                    throw new EditorInputException("Maximum price cannot be below current minimum.");
                }
                if (saveAndReload(player, "auction.max-price", value)) {
                    openAuction(player);
                }
            }
            case LISTING_FEE -> {
                double value = parsePercent(input);
                if (saveAndReload(player, "auction.listing-fee-percent", value)) {
                    openAuction(player);
                }
            }
            case DEFAULT_SLOTS -> {
                int value = parseInt(input, 1, 1000, "Slots");
                if (saveAndReload(player, "auction.default-max-slots", value)) {
                    openAuction(player);
                }
            }
            case EXPIRE_DAYS -> {
                int value = parseInt(input, 1, 30, "Expire days");
                if (saveAndReload(player, "auction.expire-days", value)) {
                    openAuction(player);
                }
            }
            case REFRESH_COOLDOWN -> {
                long value = parseLong(input, 0L, 3_600_000L, "Refresh cooldown");
                if (saveAndReload(player, "gui.refresh-cooldown-millis", value)) {
                    openGui(player);
                }
            }
            case WEBHOOK_URL -> {
                String value = parseWebhookUrl(input);
                if (saveAndReload(player, "discord-webhook.webhook-url", value)) {
                    openDiscord(player);
                }
            }
            case ADD_NAME -> addNameFragment(player, input, prompt.returnPage());
            case SEARCH_MATERIAL -> {
                materialSearches.put(player.getUniqueId(), input.toLowerCase(Locale.ROOT));
                messages.sendConfigured(player, "editor.search-applied", "query", input);
                openMaterials(player, 0);
            }
            case SEARCH_NAME -> {
                nameSearches.put(player.getUniqueId(), input.toLowerCase(Locale.ROOT));
                messages.sendConfigured(player, "editor.search-applied", "query", input);
                openNames(player, 0);
            }
        }
    }

    private void addNameFragment(Player player, String rawValue, int returnPage) throws EditorInputException {
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 100) {
            throw new EditorInputException("Name fragment must be 1-100 characters.");
        }

        List<String> values = configStringList(NAME_LIST_PATH);
        if (containsIgnoreCase(values, value)) {
            messages.sendConfigured(player, "editor.already-exists", "value", value);
            sounds.error(player);
            FoAuction.fileLogger().warn("Editor name fragment add skipped for " + player.getName() + ": duplicate.");
            openNames(player, returnPage);
            return;
        }

        values.add(value);
        if (saveList(player, NAME_LIST_PATH, values, value, true)) {
            openNames(player, returnPage);
        }
    }

    private boolean removeListValue(Player player, String path, String value) {
        List<String> values = configStringList(path);
        boolean removed = values.removeIf(entry -> entry.equals(value));
        if (!removed) {
            messages.sendConfigured(player, "editor.not-found", "value", value);
            sounds.error(player);
            FoAuction.fileLogger().warn("Editor remove failed for " + player.getName() + " at " + path + ": value missing.");
            return false;
        }

        return saveList(player, path, values, value, false);
    }

    private boolean saveList(Player player, String path, List<String> values, String value, boolean added) {
        if (!saveAndReload(player, path, values, false, false)) {
            return false;
        }
        messages.sendConfigured(player, added ? "editor.added" : "editor.removed", "value", value);
        FoAuction.fileLogger().info("Editor " + (added ? "added" : "removed") + " value at " + path + " by " + player.getName() + ".");
        if (added) {
            sounds.add(player);
        } else {
            sounds.delete(player);
        }
        return true;
    }

    private boolean saveAndReload(Player player, String path, Object value) {
        return saveAndReload(player, path, value, true);
    }

    private boolean saveAndReload(Player player, String path, Object value, boolean sendFeedback) {
        return saveAndReload(player, path, value, sendFeedback, true);
    }

    private boolean saveAndReload(Player player, String path, Object value, boolean sendFeedback, boolean playSaveSound) {
        FileConfiguration config = plugin.getConfig();
        config.set(path, value);
        try {
            plugin.saveConfig();
            settings.reload();
            afterSettingsReload.run();
            if (sendFeedback) {
                messages.sendConfigured(player, "editor.saved", "setting", settingName(path), "value", displayValue(path, value));
            }
            FoAuction.fileLogger().info("Editor saved " + path + " by " + player.getName() + ".");
            if (playSaveSound) {
                sounds.save(player);
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.reloadConfig();
            settings.reload();
            afterSettingsReload.run();
            messages.sendConfigured(player, "editor.save-failed", "setting", settingName(path));
            plugin.getLogger().warning(ColorPalette.log("Failed to save editor setting " + path + ": " + exception.getMessage()));
            FoAuction.fileLogger().error("Editor failed to save " + path + " by " + player.getName() + ".", exception);
            sounds.error(player);
            return false;
        }
    }

    private void startPrompt(Player player, PromptType type) {
        startPrompt(player, type, 0);
    }

    private void startPrompt(Player player, PromptType type, int returnPage) {
        PendingPrompt prompt = new PendingPrompt(type, returnPage);
        FoCoreContext core = coreProvider.get();
        boolean openedNative = EditorDialogInputs.openTextFromInventory(
                plugin,
                core.inventoryCloseSuppressor(),
                core.dialogInputs().dialogs(),
                player,
                editorRequest(player, type),
                input -> handlePromptInput(player, prompt, input),
                () -> {
                    messages.sendConfigured(player, "editor.prompt-cancelled");
                    sounds.back(player);
                    reopenPromptPage(player, prompt);
                }
        );
        if (openedNative) {
            sounds.open(player);
        }
    }

    private TextDialogRequest editorRequest(Player player, PromptType type) {
        String current = currentPromptValue(player, type);
        List<String> body = List.of(
                ColorPalette.LIGHT_GRAY_COLOR + "Edit " + ColorPalette.THEME_COLOR + type.field(),
                ColorPalette.LIGHT_GRAY_COLOR + "Format: " + ColorPalette.THEME_COLOR + type.format(),
                ColorPalette.LIGHT_GRAY_COLOR + "Current value: " + ColorPalette.THEME_COLOR + current
        );

        if (type.isNumber()) {
            return TextDialogRequest.number(body, current, type.format());
        }
        if (type.maxLength() <= 256) {
            return TextDialogRequest.text(body, current, type.format());
        }
        return new TextDialogRequest(
                ColorPalette.THEME_COLOR + "Edit " + type.field(),
                body,
                ColorPalette.WHITE_COLOR + type.field(),
                current,
                type.format(),
                DialogButton.save("Save", "Submit this value."),
                DialogButton.cancel("Cancel", "Return without saving."),
                320,
                320,
                type.maxLength(),
                true,
                true,
                false
        );
    }

    private void handlePromptInput(Player player, PendingPrompt prompt, String input) {
        try {
            processPrompt(player, prompt, input.trim());
        } catch (EditorInputException exception) {
            messages.sendConfigured(player, "editor.invalid-input", "error", exception.getMessage());
            sounds.error(player);
            FoAuction.fileLogger().warn("Editor invalid input for " + prompt.type().field() + " by " + player.getName() + ": " + exception.getMessage() + ".");
            reopenPromptPage(player, prompt);
        }
    }

    private String currentPromptValue(Player player, PromptType type) {
        return switch (type) {
            case MIN_PRICE -> plainNumber(settings.getMinPrice());
            case MAX_PRICE -> plainNumber(settings.getMaxPrice());
            case LISTING_FEE -> plainNumber(settings.getListingFeePercent());
            case DEFAULT_SLOTS -> String.valueOf(settings.getDefaultMaxSlots());
            case EXPIRE_DAYS -> String.valueOf(settings.getExpireDays());
            case REFRESH_COOLDOWN -> String.valueOf(settings.getGuiRefreshCooldownMillis());
            case WEBHOOK_URL -> settings.getDiscordWebhookUrl();
            case ADD_NAME -> "";
            case SEARCH_MATERIAL -> materialSearches.getOrDefault(player.getUniqueId(), "");
            case SEARCH_NAME -> nameSearches.getOrDefault(player.getUniqueId(), "");
        };
    }

    private String plainNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void reopenPromptPage(Player player, PendingPrompt prompt) {
        FoScheduler scheduler = coreProvider.get().scheduler();
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            switch (prompt.type()) {
                case MIN_PRICE, MAX_PRICE, LISTING_FEE, DEFAULT_SLOTS, EXPIRE_DAYS -> openAuction(player);
                case REFRESH_COOLDOWN -> openGui(player);
                case WEBHOOK_URL -> openDiscord(player);
                case SEARCH_MATERIAL -> openMaterials(player, prompt.returnPage());
                case ADD_NAME, SEARCH_NAME -> openNames(player, prompt.returnPage());
            }
        });
    }

    private AdminEditorHolder holder(Player player, AdminEditorHolder.Page page, int size, String title) {
        return holder(player, page, size, title, 0, "");
    }

    private AdminEditorHolder holder(Player player, AdminEditorHolder.Page page, int size, String title, int listPage, String value) {
        return new AdminEditorHolder(
                player.getUniqueId(),
                page,
                size,
                ChatColor.DARK_GRAY + GuiTitles.smallCaps(title),
                listPage,
                value
        );
    }

    private ItemStack valueButton(Player player, Material material, String name, String value, String format) {
        return button(player, material, name, List.of(
                white("Current: ") + ColorPalette.THEME_COLOR + value,
                white("Format: ") + ColorPalette.THEME_COLOR + format,
                white("Click to edit")
        ), ColorPalette.THEME_COLOR, true);
    }

    private ItemStack toggleButton(Player player, String name, boolean enabled) {
        return EditorItemFactory.button(player,
                enabled ? Material.LIME_DYE : Material.RED_DYE,
                (enabled ? ColorPalette.GOOD_COLOR : ColorPalette.BAD_COLOR).toString(),
                name,
                CycleOptions.information(messages, Boolean.toString(enabled), BOOLEAN_OPTIONS),
                "toggle " + name.toLowerCase(Locale.ROOT)
        );
    }

    private ItemStack button(Player player, Material material, String label, List<String> lore, ChatColor nameColor, boolean glow) {
        return EditorItemFactory.button(player, material, nameColor.toString(), label, lore, "edit " + label.toLowerCase(Locale.ROOT));
    }

    private ItemStack rawNameButton(Player player, Material material, String label, List<String> lore, ChatColor nameColor, boolean glow) {
        return EditorItemFactory.button(player, material, nameColor.toString(), label, lore, "remove " + label.toLowerCase(Locale.ROOT));
    }

    private void fill(Inventory inventory) {
        ItemStack pane = EditorItemFactory.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane.clone());
        }
    }

    private List<String> configStringList(String path) {
        Object rawValue = plugin.getConfig().get(path);
        List<String> values = new ArrayList<>();
        if (rawValue instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    values.add(String.valueOf(value));
                }
            }
            return values;
        }
        if (rawValue instanceof String string && !string.isBlank()) {
            values.add(string);
            return values;
        }
        return values;
    }

    private List<String> filteredList(List<String> values, String search) {
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> normalizedSearch.isBlank() || value.toLowerCase(Locale.ROOT).contains(normalizedSearch))
                .sorted(Comparator.comparing(value -> value.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private double parsePrice(String input) throws EditorInputException {
        try {
            return InputValidationUtils.validatePrice(input);
        } catch (InputValidationUtils.InvalidInputException exception) {
            throw new EditorInputException(exception.getMessage());
        }
    }

    private double parsePercent(String input) throws EditorInputException {
        double value = parseDouble(input.endsWith("%") ? input.substring(0, input.length() - 1) : input, "Percent");
        if (value < 0D || value > 100D) {
            throw new EditorInputException("Percent must be 0-100.");
        }
        return value;
    }

    private int parseInt(String input, int min, int max, String label) throws EditorInputException {
        var parsed = LargeNumberParser.parse(input);
        if (parsed.isEmpty()) {
            throw new EditorInputException(label + " must be a whole number.");
        }
        try {
            int value = parsed.get().intValueExact();
            if (value < min || value > max) {
                throw new EditorInputException(label + " must be " + min + "-" + max + ".");
            }
            return value;
        } catch (ArithmeticException exception) {
            throw new EditorInputException(label + " must be a whole number.");
        }
    }

    private long parseLong(String input, long min, long max, String label) throws EditorInputException {
        var parsed = LargeNumberParser.parse(input);
        if (parsed.isEmpty()) {
            throw new EditorInputException(label + " must be a whole number.");
        }
        try {
            long value = parsed.get().longValueExact();
            if (value < min || value > max) {
                throw new EditorInputException(label + " must be " + min + "-" + max + ".");
            }
            return value;
        } catch (ArithmeticException exception) {
            throw new EditorInputException(label + " must be a whole number.");
        }
    }

    private double parseDouble(String input, String label) throws EditorInputException {
        var parsed = LargeNumberParser.parseDouble(input);
        if (parsed.isEmpty()) {
            throw new EditorInputException(label + " must be a number.");
        }
        return parsed.getAsDouble();
    }

    private String parseWebhookUrl(String input) throws EditorInputException {
        if (input.isBlank()
                || "none".equalsIgnoreCase(input)
                || "WEBHOOK_HERE".equalsIgnoreCase(input)
                || "WEBOOK_HERE".equalsIgnoreCase(input)) {
            return "WEBHOOK_HERE";
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            throw new EditorInputException("Webhook URL must start with http:// or https://.");
        }
        if (input.length() > 500) {
            throw new EditorInputException("Webhook URL too long.");
        }
        return input;
    }

    private boolean containsIgnoreCase(List<String> values, String value) {
        for (String entry : values) {
            if (entry.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String summarizeWebhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || "WEBHOOK_HERE".equalsIgnoreCase(webhookUrl)) {
            return "Not set";
        }
        return "Set";
    }

    private String settingName(String path) {
        return path.replace('-', ' ').replace('.', ' ');
    }

    private String displayValue(String path, Object value) {
        if ("discord-webhook.webhook-url".equals(path)) {
            return summarizeWebhook(String.valueOf(value));
        }
        if (value instanceof List<?> list) {
            return list.size() + " entries";
        }
        return String.valueOf(value);
    }

    private String formatPrice(double value) {
        return FormatUtils.formatPrice(value, settings.isCompactPriceFormatEnabled());
    }

    private String white(String value) {
        return ChatColor.WHITE + value;
    }

    private record PendingPrompt(PromptType type, int returnPage) {
    }

    private enum PromptType {
        MIN_PRICE("minimum price", "money amount, examples: 500, 50K, 1.5M, 1Qa", 32),
        MAX_PRICE("maximum price", "money amount, examples: 500, 50K, 1.5M, 1Qa", 32),
        LISTING_FEE("listing fee percent", "number 0-100, example: 2.5", 32),
        DEFAULT_SLOTS("default max slots", "whole number 1-1000", 16),
        EXPIRE_DAYS("expire days", "whole number 1-30", 16),
        REFRESH_COOLDOWN("refresh cooldown", "milliseconds 0-3600000", 16),
        WEBHOOK_URL("Discord webhook URL", "https://... or WEBHOOK_HERE", 500),
        ADD_NAME("name fragment", "text 1-100 characters", 100),
        SEARCH_MATERIAL("material search", "text", 100),
        SEARCH_NAME("name search", "text", 100);

        private final String field;
        private final String format;
        private final int maxLength;

        PromptType(String field, String format, int maxLength) {
            this.field = field;
            this.format = format;
            this.maxLength = maxLength;
        }

        private String field() {
            return field;
        }

        private String format() {
            return format;
        }

        private boolean isNumber() {
            return switch (this) {
                case MIN_PRICE, MAX_PRICE, LISTING_FEE, DEFAULT_SLOTS, EXPIRE_DAYS, REFRESH_COOLDOWN -> true;
                default -> false;
            };
        }

        private int maxLength() {
            return maxLength;
        }
    }

    private static final class EditorInputException extends Exception {
        private EditorInputException(String message) {
            super(message);
        }
    }
}
