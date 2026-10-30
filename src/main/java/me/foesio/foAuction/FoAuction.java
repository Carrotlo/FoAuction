package me.foesio.foAuction;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminCommandContext;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.command.FoAdminSubcommand;
import me.foesio.core.command.FoAdminArguments;
import me.foesio.core.logging.FoFileLogger;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.metrics.FoMetrics;
import me.foesio.core.number.TickDuration;
import me.foesio.core.placeholder.FoPlaceholders;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.core.update.UpdateNoticeService;

import me.foesio.foAuction.commands.AuctionCommand;
import me.foesio.foAuction.api.AuctionApi;
import me.foesio.foAuction.api.AuctionListingSnapshot;
import me.foesio.foAuction.api.CreateListingResult;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.gui.AuctionGuiManager;
import me.foesio.foAuction.gui.AuctionSearchDialog;
import me.foesio.foAuction.gui.GuiConfigService;
import me.foesio.foAuction.gui.PublicAuctionGuiMigration;
import me.foesio.foAuction.gui.PublicAuctionGuis;
import me.foesio.foAuction.gui.SlotUpgradeMenu;
import me.foesio.foAuction.gui.SlotButtonMigration;
import me.foesio.foAuction.gui.SlotUpgradeDialogConfig;
import me.foesio.foAuction.gui.editor.AdminEditorManager;
import me.foesio.foAuction.listeners.AdminEditorListener;
import me.foesio.foAuction.listeners.GuiListener;
import me.foesio.foAuction.listeners.PlayerDataListener;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.DiscordWebhookService;
import me.foesio.foAuction.service.PlayerNameCache;
import me.foesio.foAuction.service.SlotPurchaseService;
import me.foesio.foAuction.storage.DatabaseManager;
import me.foesio.foAuction.storage.IUserDataRepository;
import me.foesio.foAuction.storage.NpcSellerRepository;
import me.foesio.foAuction.storage.SqliteUserDataRepository;
import me.foesio.foAuction.storage.SlotEntitlementRepository;
import me.foesio.foAuction.tasks.ExpirationTask;
import me.foesio.foAuction.utils.ColorPalette;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class FoAuction extends JavaPlugin {
    private static final String MODRINTH_PROJECT_ID = "foauction";
    private static final int BSTATS_PLUGIN_ID = 33181;

    private static FoFileLogger activeFileLogger;

    private AuctionSettings settings;
    private FoFileLogger fileLogger;
    private FoMetrics metrics;
    private FoMessageService messageService;
    private GuiConfigService guiConfigService;
    private AuctionSearchDialog dialogTexts;
    private VaultEconomyBridge economyService;
    private DatabaseManager databaseManager;
    private IUserDataRepository userDataRepository;
    private NpcSellerRepository npcSellers;
    private AuctionService auctionService;
    private SlotPurchaseService slotPurchases;
    private SlotUpgradeMenu slotUpgradeMenu;
    private SlotUpgradeDialogConfig slotDialogConfig;
    private DiscordWebhookService webhookService;
    private FoCoreContext core;
    private FoSoundService sounds;
    private FoAdminSounds adminSounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private UpdateNoticeService updateNotices;
    private PlayerNameCache playerNameCache;
    private AuctionGuiManager guiManager;
    private PublicAuctionGuis publicGuis;
    private GuiListener guiListener;
    private AdminEditorManager adminEditorManager;
    private ExpirationTask expirationTask;
    private volatile boolean periodicSaveCancelled;
    private volatile boolean periodicSaveScheduled;

    public static FoFileLogger fileLogger() {
        return activeFileLogger;
    }

    @Override
    public void onEnable() {
        String[] priorDataEntries = getDataFolder().list();
        boolean ownerDataExisted = priorDataEntries == null
                ? getDataFolder().exists() : priorDataEntries.length > 0;
        fileLogger = FoFileLogger.create(this);
        activeFileLogger = fileLogger;
        settings = new AuctionSettings(this);
        fileLogger.configure(settings.isFileLoggingEnabled(), true);
        boolean publicGuiMigrationReady = PublicAuctionGuiMigration.install(this, ownerDataExisted);
        if (publicGuiMigrationReady) SlotButtonMigration.install(this);
        messageService = FoMessageService.loadPreservingRemovals(this, messageMigrations());
        me.foesio.foAuction.utils.OwnerMessages.configure(
                getDataFolder().toPath().resolve("messages.yml"));
        me.foesio.foAuction.utils.FormatUtils.configure(messageService);
        refreshCoreContext();
        guiConfigService = new GuiConfigService(this, messageService);
        dialogTexts = createDialogTexts();
        dialogTexts.load();
        slotDialogConfig = new SlotUpgradeDialogConfig(this);
        slotDialogConfig.installOnce();
        slotDialogConfig.load();
        fileLogger.info("Config, messages, GUI files, and public dialogs loaded.");

        economyService = core.createVaultEconomy();
        if (!economyService.isAvailable()) {
            getLogger().severe(ColorPalette.log("Vault with a valid economy provider is required."));
            fileLogger.error("Vault with a valid economy provider is required.", null);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        fileLogger.info("Vault economy provider hooked.");

        databaseManager = new DatabaseManager(this);
        databaseManager.initializeTables();
        try {
            npcSellers = new NpcSellerRepository(databaseManager);
        } catch (java.sql.SQLException exception) {
            getLogger().severe("Could not load virtual seller identities; auction is stopping: "
                    + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        userDataRepository = new SqliteUserDataRepository(this, databaseManager, core.scheduler());
        userDataRepository.loadAllFromDisk();
        fileLogger.info("SQLite userdata loaded from userdata.db.");

        try {
            SlotEntitlementRepository entitlements = new SlotEntitlementRepository(getDataFolder().toPath());
            slotPurchases = new SlotPurchaseService(this, entitlements, settings);
            SlotEntitlementRepository.RecoveryReport recovered = slotPurchases.recoverPendingPurchases();
            if (recovered.granted() > 0 || recovered.cancelled() > 0 || !recovered.unresolved().isEmpty()) {
                getLogger().warning("Auction slot checkout recovery: granted=" + recovered.granted()
                        + ", cancelled=" + recovered.cancelled()
                        + ", needs manual review=" + recovered.unresolved().size());
            }
        } catch (Exception exception) {
            getLogger().severe("Could not load entitlements.db; auction is stopping to protect purchased slots: "
                    + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        warnServerIfGemSlotsUnavailable();

        webhookService = new DiscordWebhookService(this);
        updateNotices = createUpdateNotices().start();
        playerNameCache = new PlayerNameCache();
        npcSellers.names().forEach(playerNameCache::rememberVirtual);
        playerNameCache.rememberAll(Bukkit.getOnlinePlayers());
        auctionService = new AuctionService(
                this,
                userDataRepository,
                economyService,
                settings,
                webhookService,
                playerNameCache,
                messageService,
                core.inventoryDeposits(),
                slotPurchases,
                npcSellers
        );
        auctionService.rebuildIndex();
        getServer().getServicesManager().register(AuctionApi.class,
                new AuctionApiAdapter(), this, ServicePriority.Normal);

        guiManager = new AuctionGuiManager(auctionService, settings, playerNameCache, guiConfigService);
        guiListener = new GuiListener(this, guiManager, auctionService, () -> core, dialogTexts, messageService, settings);
        publicGuis = new PublicAuctionGuis(this, auctionService, settings, playerNameCache,
                messageService, slotPurchases);
        publicGuis.migrationReady(publicGuiMigrationReady);
        publicGuis.bind(guiListener::handlePublicClick);
        guiManager.usePublicGuis(publicGuis);
        slotUpgradeMenu = new SlotUpgradeMenu(auctionService, slotPurchases, settings, messageService,
                guiManager, this, () -> core, slotDialogConfig);
        guiListener.slotUpgradeMenu(slotUpgradeMenu, slotPurchases);
        me.foesio.core.gui.publicgui.GuiLoadReport publicGuiLoad = publicGuis.start();
        if (!publicGuiMigrationReady || !publicGuiLoad.successful()) {
            getLogger().severe("Public auction inventories are unavailable until the GUI files are repaired.");
        }
        adminEditorManager = new AdminEditorManager(
                this,
                settings,
                messageService,
                () -> core,
                editorSounds,
                this::afterEditorSettingsReload
        );

        registerCommands();
        registerListeners();
          registerTasks();
          registerPeriodicSaveTask();
          registerPlaceholdersIfAvailable();

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            playerNameCache.remember(onlinePlayer);
            auctionService.syncPlayerListings(onlinePlayer.getUniqueId());
            auctionService.deliverPendingNotifications(onlinePlayer);
            warnAdminIfGemSlotsUnavailable(onlinePlayer);
        }
        fileLogger.info("Plugin enable complete.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (fileLogger != null) {
            fileLogger.info("Plugin disable started.");
        }
        if (expirationTask != null) {
            expirationTask.cancel();
            fileLogger.info("Expiration task stopped.");
        }
        if (publicGuis != null) {
            publicGuis.close();
            publicGuis = null;
        }
        periodicSaveCancelled = true;
        if (periodicSaveScheduled) {
            periodicSaveScheduled = false;
            fileLogger.info("Periodic save task stopped.");
        }

        if (webhookService != null) {
            webhookService.close();
        }

        if (metrics != null) {
            metrics.close();
            metrics = null;
        }
        if (core != null) {
            core.close();
            core = null;
        }
        updateNotices = null;

        if (userDataRepository != null) {
            userDataRepository.shutdownAndFlush();
            fileLogger.info("Userdata flushed.");
        }

        if (databaseManager != null) {
            databaseManager.closeConnection();
        }

        saveConfig();
        FoFileLogger logger = fileLogger;
        if (logger != null) {
            logger.info("Plugin disable complete.");
            logger.shutdown();
            fileLogger = null;
            if (activeFileLogger == logger) {
                activeFileLogger = null;
            }
        }
    }

    private final class AuctionApiAdapter implements AuctionApi {
        @Override
        public Optional<AuctionListingSnapshot> findActiveListing(UUID listingId) {
            if (!isEnabled()) throw new IllegalStateException("FoAuction is disabled");
            Objects.requireNonNull(listingId, "listingId");
            me.foesio.foAuction.model.AuctionListing listing = auctionService.getListing(listingId);
            return listing == null || listing.isExpired(System.currentTimeMillis())
                    ? Optional.empty() : Optional.of(auctionService.toSnapshot(listing));
        }

        @Override
        public List<AuctionListingSnapshot> getActiveListings(int offset, int limit) {
            if (!isEnabled()) throw new IllegalStateException("FoAuction is disabled");
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("offset must be nonnegative and limit must be 1..100");
            }
            return auctionService.getActiveListingsPage(offset, limit).stream()
                    .map(auctionService::toSnapshot).toList();
        }

        @Override
        public CreateListingResult createListingFromHand(Player seller, double price) {
            if (!isEnabled()) return CreateListingResult.failure(CreateListingResult.Status.UNAVAILABLE);
            Objects.requireNonNull(seller, "seller");
            if (!seller.isOnline()) return CreateListingResult.failure(CreateListingResult.Status.SELLER_OFFLINE);
            if (!seller.hasPermission("foauction.use")) {
                return CreateListingResult.failure(CreateListingResult.Status.NO_PERMISSION);
            }
            return mapCreateResult(auctionService.createListing(seller, price));
        }

        @Override
        public CreateListingResult createListingForPlayer(Player seller, ItemStack item, double price) {
            if (!isEnabled()) return CreateListingResult.failure(CreateListingResult.Status.UNAVAILABLE);
            Objects.requireNonNull(seller, "seller");
            if (!seller.isOnline()) return CreateListingResult.failure(CreateListingResult.Status.SELLER_OFFLINE);
            if (!seller.hasPermission("foauction.use")) {
                return CreateListingResult.failure(CreateListingResult.Status.NO_PERMISSION);
            }
            return mapCreateResult(auctionService.createListingForPlayer(seller, item, price));
        }

        @Override
        public CreateListingResult createListingForNpc(String npcName, ItemStack item, double price) {
            if (!isEnabled()) return CreateListingResult.failure(CreateListingResult.Status.UNAVAILABLE);
            return mapCreateResult(auctionService.createListingForNpc(npcName, item, price));
        }

        private CreateListingResult mapCreateResult(AuctionService.SellResult result) {
            CreateListingResult.Status status = switch (result.type()) {
                case SUCCESS -> CreateListingResult.Status.SUCCESS;
                case NO_ITEM -> CreateListingResult.Status.INVALID_ITEM;
                case PRICE_OUT_OF_RANGE -> CreateListingResult.Status.INVALID_PRICE;
                case INSUFFICIENT_FUNDS -> CreateListingResult.Status.INSUFFICIENT_FUNDS;
                case NO_SLOTS -> CreateListingResult.Status.NO_SLOTS;
                case BLACKLISTED -> CreateListingResult.Status.BLACKLISTED;
                case ECONOMY_ERROR -> CreateListingResult.Status.ECONOMY_ERROR;
                case CANCELLED -> CreateListingResult.Status.CANCELLED;
                case INVALID_NPC_NAME -> CreateListingResult.Status.INVALID_NPC_NAME;
                case STORAGE_ERROR -> CreateListingResult.Status.STORAGE_ERROR;
            };
            return new CreateListingResult(status,
                    result.listing() == null ? null : auctionService.toSnapshot(result.listing()),
                    result.maxSlots());
        }
    }

    private void registerCommands() {
        AuctionCommand auctionCommandExecutor = new AuctionCommand(
                guiManager,
                auctionService,
                economyService,
                settings,
                messageService,
                adminSounds,
                slotUpgradeMenu,
                slotPurchases
        );
        PluginCommand auctionCommand = Objects.requireNonNull(getCommand("auction"), "auction command missing");
        auctionCommand.setExecutor(auctionCommandExecutor);
        auctionCommand.setTabCompleter(auctionCommandExecutor);

        PluginCommand auctionAdminCommand = Objects.requireNonNull(getCommand("foauctionadmin"), "foauctionadmin command missing");
        FoAdminCommand adminCommand = createAdminCommand();
        auctionAdminCommand.setExecutor(adminCommand);
        auctionAdminCommand.setTabCompleter(adminCommand);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                  new PlayerDataListener(userDataRepository, auctionService, playerNameCache,
                          core.scheduler(), this::warnAdminIfGemSlotsUnavailable),
                this
        );

        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(slotUpgradeMenu, this);

        getServer().getPluginManager().registerEvents(
                new AdminEditorListener(adminEditorManager, () -> core),
                this
        );
    }

    private void registerTasks() {
        long fiveMinutesTicks = TickDuration.ofSeconds(5L * TickDuration.SECONDS_PER_MINUTE).ticks();
        expirationTask = new ExpirationTask(this, auctionService, core.scheduler(), fiveMinutesTicks);
        expirationTask.start();
        fileLogger.info("Expiration task scheduled every 5 minutes.");
    }

    private UpdateNoticeService createUpdateNotices() {
        return core.createUpdateNotices(messageService, MODRINTH_PROJECT_ID, adminSounds);
    }

    private void registerPeriodicSaveTask() {
        // Snapshot cached player data on the server thread, then persist it on the repository IO thread.
        long thirtyMinutesTicks = TickDuration.ofSeconds(30L * TickDuration.SECONDS_PER_MINUTE).ticks();
        periodicSaveCancelled = false;
        periodicSaveScheduled = true;
        schedulePeriodicSave(thirtyMinutesTicks);
        fileLogger.info("Periodic save task scheduled every 30 minutes.");
    }

    private void schedulePeriodicSave(long intervalTicks) {
        if (periodicSaveCancelled) {
            return;
        }

        core.scheduler().runGlobalLater(() -> {
            if (periodicSaveCancelled || !isEnabled()) {
                return;
            }
            try {
                if (userDataRepository != null) {
                    userDataRepository.saveAllAsync();
                }
            } finally {
                schedulePeriodicSave(intervalTicks);
            }
        }, intervalTicks);
    }

    private void registerPlaceholdersIfAvailable() {
        FoPlaceholders placeholders = core.placeholders("foauctions")
                .author(String.join(", ", getDescription().getAuthors()))
                .persist(true)
                .offline("sell_limit", player -> player == null ? "" : String.valueOf(auctionService.getSellLimit(player)))
                .offline("sell_count", player -> player == null ? "" : String.valueOf(auctionService.getSellCount(player.getUniqueId())))
                .offline("expired_count", player -> player == null ? "" : String.valueOf(auctionService.getExpiredCount(player.getUniqueId())))
                .offline("active_count", player -> player == null ? "" : String.valueOf(auctionService.getActiveCount(player.getUniqueId())))
                .offline("sort_name", player -> player == null ? "" : auctionService.getSortModeName(player.getUniqueId()))
                .offline("filter_name", player -> player == null ? "" : auctionService.getFilterModeName(player.getUniqueId()))
                .offline("total_sold", player -> player == null ? "" : String.valueOf(auctionService.getTotalSoldCount(player.getUniqueId())))
                .offlineFormattedRaw(
                        "total_earned",
                        player -> player == null ? "" : auctionService.formatDisplayPrice(auctionService.getTotalMoneyEarned(player.getUniqueId())),
                        player -> player == null ? "" : String.valueOf(auctionService.getTotalMoneyEarned(player.getUniqueId()))
                )
                .global("min_price", () -> auctionService.formatDisplayPrice(auctionService.getMinPrice()))
                .global("min_price_raw", () -> String.valueOf(auctionService.getMinPrice()))
                .global("max_price", () -> auctionService.formatDisplayPrice(auctionService.getMaxPrice()))
                .global("max_price_raw", () -> String.valueOf(auctionService.getMaxPrice()))
                .global("listing_fee", () -> me.foesio.foAuction.utils.FormatUtils.formatPrice(auctionService.getListingFeePercent()) + "%")
                .global("listing_fee_raw", () -> String.valueOf(auctionService.getListingFeePercent()))
                .global("total_listings", () -> String.valueOf(auctionService.getTotalListings()));
        if (!placeholders.registerIfAvailable()) {
            fileLogger.info("PlaceholderAPI unavailable; placeholders skipped.");
        }
    }

      private boolean reloadPluginConfig() {
          fileLogger.info("Reload started.");
          FoReloadResult result = FoReloadRegistry.create()
                  .add("config", settings::reload)
          .add("core", this::refreshCoreContext)
                  .add("economy", () -> {
                      if (!economyService.reload()) {
                          throw new IllegalStateException("Vault economy provider not found");
                      }
                  })
          .add("sounds", () -> {
              if (!sounds.reloadChecked()) throw new IllegalStateException("Invalid sounds.yml; previous sound cache remains active");
          })
          .add("messages", () -> {
              publicGuis.validateInstalledMessages();
              messageService.reload();
              me.foesio.foAuction.utils.OwnerMessages.reload(
                      getDataFolder().toPath().resolve("messages.yml"));
          })
                  .add("guis", () -> {
                      if (getConfig().getInt("public-gui-schema-version", 0) >= 1) {
                          SlotButtonMigration.install(this);
                      }
                      me.foesio.core.gui.publicgui.GuiLoadReport report = publicGuis.reload();
                      if (!report.successful()) {
                          throw new IllegalStateException("Public GUI reload failed with "
                                  + report.diagnostics().size() + " error(s); previous GUI cache remains active");
                      }
                      guiConfigService.reload();
                  })
                  .add("dialogs", () -> {
                      dialogTexts.reload();
                      slotDialogConfig.load();
                  })
                  .add("file-logging", () -> fileLogger.configure(settings.isFileLoggingEnabled(), false))
                  .reload();
          warnServerIfGemSlotsUnavailable();
          if (!result.successful()) {
              getLogger().warning(ColorPalette.log(
                      "Reload failed at " + result.failedStep() + ": " + result.errorMessage()
              ));
              fileLogger.error("Reload failed at " + result.failedStep() + ".", result.error());
              return false;
          }

          getLogger().info(ColorPalette.log("Configuration, messages, GUI files, and public dialogs reloaded."));
          fileLogger.info("Configuration, messages, GUI files, public dialogs, and file logging reloaded.");
          return true;
      }

      private void warnServerIfGemSlotsUnavailable() {
          if (settings.isGemSlotsEnabled() && slotPurchases != null && !slotPurchases.playerPointsAvailable()) {
              getLogger().warning("auction.gem-slots.enabled is true, but PlayerPoints is disabled or unavailable; "
                      + "new auction slot purchases are disabled until PlayerPoints is enabled.");
          }
      }

      private void warnAdminIfGemSlotsUnavailable(CommandSender sender) {
          if (sender.hasPermission("foauction.admin") && settings.isGemSlotsEnabled()
                  && slotPurchases != null && !slotPurchases.playerPointsAvailable()) {
              messageService.send(sender, "slots.admin-missing-playerpoints",
                      "{prefix}{bad}Auction slot purchases are disabled: PlayerPoints is unavailable. "
                              + "Enable PlayerPoints, then reload FoAuction.");
          }
      }

    private void refreshCoreContext() {
        metrics = null;
        if (core != null) {
            core.close();
        }
        core = FoPluginCore.create(this);
        if (sounds == null) {
            sounds = FoSoundService.loadPreservingRemovals(this);
            adminSounds = FoAdminSounds.create(sounds);
            editorSounds = FoEditorSounds.create(sounds);
            guiSounds = FoGuiSounds.create(sounds);
            me.foesio.foAuction.utils.SoundFeedback.configure(sounds);
            me.foesio.foAuction.utils.SoundFeedback.configureGui(guiSounds);
        }
        metrics = core.metrics(BSTATS_PLUGIN_ID)
                .togglePie("file_logging", settings::isFileLoggingEnabled)
                .togglePie("discord_webhook", settings::isDiscordWebhookEnabled)
                .singleLine("active_listings", () -> auctionService == null ? 0 : auctionService.getTotalListings());
        core.dialogInputs();
          if (core.nativeDialogs().warnOnFallback()) {
              core.warnIfNativeDialogsUnavailable();
          }
      }

      private void afterEditorSettingsReload() {
          refreshCoreContext();
          if (fileLogger != null) {
              fileLogger.configure(settings.isFileLoggingEnabled(), false);
          }
      }

      private FoAdminCommand createAdminCommand() {
          FoAdminMessages adminMessages = FoAdminMessages.builder()
                  .generalNoPermission("admin.no-permission", "{prefix}{bad}You do not have permission.")
                  .generalPlayerOnly("editor.only-players", "{prefix}{bad}Only players can open the editor.")
                  .usage("admin.usage", "{prefix}Usage: {theme}/{label} <version|reload|history <player>|editor>")
                  .reloadSuccess("admin.reload", "{prefix}{good}Configuration, messages, GUI files, and dialogs reloaded.")
                  .reloadFailed("admin.reload-failed", "{prefix}{bad}Reload failed. Check the server log.")
                  .editorOpened("editor.opened", "{prefix}Opening admin editor.")
                  .build();

          return FoAdminCommand.builder(this, messageService)
                  .commandName("foauctionadmin")
                  .permission("foauction.admin")
                  .adminMessages(adminMessages)
                  .adminSounds(adminSounds)
                  .updates(updateNotices)
                  .addSubcommand(FoAdminSubcommand.builder("reload", context -> {
                      fileLogger.info("Admin command reload used by " + context.sender().getName() + ".");
                      boolean successful = reloadPluginConfig();
                      if (successful) {
                          messageService.send(
                                  context.sender(),
                                  "admin.reload",
                                  "{prefix}{good}Configuration, messages, GUI files, and dialogs reloaded."
                          );
                      } else {
                          messageService.send(
                                  context.sender(),
                                  "admin.reload-failed",
                                  "{prefix}{bad}Reload failed. Check the server log."
                          );
                      }
                      warnAdminIfGemSlotsUnavailable(context.sender());
                      if (context.playerOrNull() != null) {
                          if (successful) {
                              adminSounds.reload(context.playerOrNull());
                          } else {
                              adminSounds.reloadError(context.playerOrNull());
                          }
                      }
                      return true;
                  }).usage("reload").build())
                  .addSubcommand(FoAdminSubcommand.builder("history", this::executeAdminHistory)
                          .usage("history <player>")
                          .tabCompleter(this::completeAdminHistory)
                          .build())
                  .addSubcommand(FoAdminSubcommand.builder("editor", context -> {
                      if (context.playerOrNull() == null) {
                          messageService.sendConfigured(context.sender(), "editor.only-players");
                          fileLogger.warn("Admin editor command rejected for console/non-player sender.");
                          return true;
                      }
                      if (context.args().length > 1) {
                          messageService.sendConfigured(context.sender(), "admin.usage", "label", context.label());
                          adminSounds.updateError(context.sender());
                          fileLogger.warn("Admin editor command invalid syntax from " + context.sender().getName() + ".");
                          return true;
                      }
                      Player player = context.playerOrNull();
                      fileLogger.info("Admin editor opened by " + player.getName() + ".");
                      adminEditorManager.openMain(player);
                      messageService.sendConfigured(player, "editor.opened");
                      editorSounds.open(player);
                      return true;
                  }).usage("editor").build())
                  .build();
      }

      private boolean executeAdminHistory(FoAdminCommandContext context) {
          Player player = context.playerOrNull();
          if (player == null) {
              messageService.sendConfigured(context.sender(), "admin.only-players-history");
              fileLogger.warn("Admin history command rejected for console/non-player sender.");
              return true;
          }
          if (context.args().length < 2) {
              messageService.sendConfigured(context.sender(), "admin.usage-history", "label", context.label());
              adminSounds.updateError(context.sender());
              fileLogger.warn("Admin history command missing target from " + context.sender().getName() + ".");
              return true;
          }

          String requestedName = context.subArg(0);
          org.bukkit.OfflinePlayer target;
          try {
              String validatedName = me.foesio.foAuction.utils.InputValidationUtils.validatePlayerName(requestedName);
              target = findOfflinePlayerByName(validatedName);
              if (target == null) {
                  messageService.sendConfigured(context.sender(), "command.player-not-found", "player", validatedName);
                  adminSounds.updateError(context.sender());
                  fileLogger.warn("Admin history target not found: " + validatedName + ".");
                  return true;
              }
          } catch (me.foesio.foAuction.utils.InputValidationUtils.InvalidInputException exception) {
              messageService.sendConfigured(context.sender(), "command.invalid-player-name", "error", exception.getMessage());
              adminSounds.updateError(context.sender());
              fileLogger.warn("Admin history invalid target from " + context.sender().getName() + ": " + exception.getMessage() + ".");
              return true;
          }

          String targetName = target.getName() == null ? requestedName : target.getName();
          fileLogger.info("Admin history opened by " + player.getName() + " for " + targetName + ".");
          guiManager.openHistoryGui(player, target.getUniqueId(), 0, 0);
          messageService.sendConfigured(player, "admin.opening-history", "player", targetName);
          me.foesio.foAuction.utils.SoundFeedback.menuOpen(player);
          return true;
      }

      private List<String> completeAdminHistory(FoAdminCommandContext context) {
          if (context.playerOrNull() == null || context.args().length < 2) {
              return List.of();
          }
          List<String> names = new ArrayList<>();
          for (Player online : Bukkit.getOnlinePlayers()) {
              names.add(online.getName());
          }
          return FoAdminArguments.completeOptions(names, context.subArg(0));
      }

      private org.bukkit.OfflinePlayer findOfflinePlayerByName(String name) {
          Player online = Bukkit.getPlayerExact(name);
          if (online != null) {
              return online;
          }
          for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
              if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(name)) {
                  return offlinePlayer;
              }
          }
          return null;
      }

      private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
                .replaceExact(
                        "admin.reload",
                        "{prefix}{good}Configuration, messages, and GUI files reloaded.",
                        "{prefix}{good}Configuration, messages, GUI files, and dialogs reloaded."
                )
                .replaceExact(
                        "command.usage",
                        "{prefix}Usage: {theme}/{label} [sell <price>|claims|search <text>|view [player]]",
                        "{prefix}Usage: {theme}/{label} [sell <price>|claims|search <text>|view [player]|slots]"
                )
                .add(config -> backfillEditorMessageDefaults(config))
                .add(FoAuction::backfillSlotMessages)
                .add(FoAuction::backfillApiEventMessages)
                .build();
      }

      static boolean backfillApiEventMessages(FileConfiguration config) {
          if (config.getInt("api-events.version", 0) >= 2) return false;
          ensureMessage(config, "command.listing-blocked",
                  "{prefix}{bad}Another plugin blocked this listing.");
          ensureMessage(config, "gui.purchase-blocked",
                  "{prefix}{bad}Another plugin blocked this purchase.");
          ensureMessage(config, "gui.admin-virtual-seller",
                  "{prefix}{bad}Virtual sellers have no claims. Use Take to recover this item.");
          config.set("api-events.version", 2);
          return true;
      }

      static boolean backfillSlotMessages(FileConfiguration config) {
          int version = config.getInt("slots.version", 0);
          if (version >= 4) return false;
          if (version < 1) {
          ensureMessage(config, "slots.menu-title", "&8Auction Slots");
          ensureMessage(config, "slots.info-name", "&eYour listing slots");
          ensureMessage(config, "slots.buy-name", "&aBuy +1 slot");
          ensureMessage(config, "slots.confirm-name", "&aConfirm purchase");
          ensureMessage(config, "slots.max-name", "&aAll upgrades owned");
          ensureMessage(config, "slots.unavailable-name", "&cPurchases unavailable");
          ensureMessage(config, "slots.back-name", "&cBack to listings");
          ensureMessage(config, "slots.success", "{prefix}{good}Bought one permanent auction slot for {price} {currency}. Total: {total}.");
          ensureMessage(config, "slots.maxed", "{prefix}{bad}You already own all 3 extra auction slots.");
          ensureMessage(config, "slots.insufficient", "{prefix}{bad}You need {price} {currency} for the next slot.");
          ensureMessage(config, "slots.unavailable", "{prefix}{bad}Slot purchases are temporarily unavailable.");
          ensureMessage(config, "slots.error", "{prefix}{bad}Purchase failed. Check your balance; contact staff if you were charged.");
          ensureMessage(config, "slots.price-changed", "{prefix}{bad}The slot price changed. Please review it again.");
          if (!config.contains("slots.info-lore")) config.set("slots.info-lore", List.of(
                  "&7Base slots: &f{base}", "&7Bought slots: &f{bought}/3", "&7Total slots: &f{total}"));
          if (!config.contains("slots.buy-lore")) config.set("slots.buy-lore", List.of(
                  "&7Cost: &f{price} {currency}", "&7Balance: &f{balance} {currency}", "&eClick to review purchase."));
          if (!config.contains("slots.confirm-lore")) config.set("slots.confirm-lore", List.of(
                  "&7Cost: &f{price} {currency}", "&eClick to confirm this permanent purchase."));
          if (!config.contains("slots.max-lore")) config.set("slots.max-lore", List.of("&7You own all 3 extra slots."));
          if (!config.contains("slots.unavailable-lore")) config.set("slots.unavailable-lore", List.of("&7Please try again later."));
          }
          if (version < 2) ensureMessage(config, "slots.no-slots-action",
                  "{good}➜ Click here to buy another auction slot ({price} {currency}).");
          if (version < 3) {
              ensureMessage(config, "slots.unlimited-name", "&aUnlimited listing slots");
              if (!config.contains("slots.unlimited-lore")) config.set("slots.unlimited-lore",
                      List.of("&7Your permission grants unlimited listing slots."));
              ensureMessage(config, "slots.unlimited", "{prefix}{good}You already have unlimited auction slots.");
          }
          ensureMessage(config, "slots.disabled", "{prefix}{bad}Auction slot purchases are disabled.");
          ensureMessage(config, "slots.admin-missing-playerpoints",
                  "{prefix}{bad}Auction slot purchases are disabled: PlayerPoints is unavailable. "
                          + "Enable PlayerPoints, then reload FoAuction.");
          config.set("slots.version", 4);
          return true;
      }

      private boolean backfillEditorMessageDefaults(FileConfiguration config) {
          boolean changed = false;
          changed |= ensureMessage(config, "editor.opened", "{prefix}Opening admin editor.");
          changed |= ensureMessage(config, "editor.only-players", "{prefix}{bad}Only players can open the editor.");
          changed |= ensureMessage(config, "editor.prompt", "{prefix}Type {theme}{field}{muted}. Format: {theme}{format}{muted}. Type {bad}cancel{muted} to cancel.");
          changed |= ensureMessage(config, "editor.prompt-cancelled", "{prefix}{bad}Editor input cancelled.");
          changed |= ensureMessage(config, "editor.saved", "{prefix}{good}Saved {theme}{setting}{muted}: {theme}{value}");
          changed |= ensureMessage(config, "editor.save-failed", "{prefix}{bad}Could not save {theme}{setting}{bad}.");
          changed |= ensureMessage(config, "editor.invalid-input", "{prefix}{bad}Invalid input: {muted}{error}");
          changed |= ensureMessage(config, "editor.cursor-empty", "{prefix}{bad}Put an item on your cursor first.");
          changed |= ensureMessage(config, "editor.already-exists", "{prefix}{bad}{theme}{value}{bad} already exists.");
          changed |= ensureMessage(config, "editor.added", "{prefix}{good}Added {theme}{value}{muted}.");
          changed |= ensureMessage(config, "editor.removed", "{prefix}{good}Removed {theme}{value}{muted}.");
          changed |= ensureMessage(config, "editor.not-found", "{prefix}{bad}{theme}{value}{bad} no longer exists.");
          changed |= ensureMessage(config, "editor.search-applied", "{prefix}Search applied: {theme}{query}");
          changed |= ensureMessage(config, "editor.search-cleared", "{prefix}{good}Search cleared.");
          return changed;
      }

      private static boolean ensureMessage(FileConfiguration config, String path, String fallback) {
          if (config.contains(path)) {
              return false;
          }
          config.set(path, fallback);
          return true;
      }

      private AuctionSearchDialog createDialogTexts() {
          return new AuctionSearchDialog(this);
      }
}
