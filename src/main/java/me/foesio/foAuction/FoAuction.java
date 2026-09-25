package me.foesio.foAuction;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminCommandContext;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.command.FoAdminSubcommand;
import me.foesio.core.command.FoAdminArguments;
import me.foesio.core.dialog.ConfiguredTextDialogs;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
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
import me.foesio.core.sound.FoSoundMigrations;
import me.foesio.core.update.UpdateNoticeService;

import me.foesio.foAuction.commands.AuctionCommand;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.gui.AuctionGuiManager;
import me.foesio.foAuction.gui.GuiConfigService;
import me.foesio.foAuction.gui.editor.AdminEditorManager;
import me.foesio.foAuction.listeners.AdminEditorListener;
import me.foesio.foAuction.listeners.GuiListener;
import me.foesio.foAuction.listeners.PlayerDataListener;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.DiscordWebhookService;
import me.foesio.foAuction.service.PlayerNameCache;
import me.foesio.foAuction.storage.DatabaseManager;
import me.foesio.foAuction.storage.IUserDataRepository;
import me.foesio.foAuction.storage.SqliteUserDataRepository;
import me.foesio.foAuction.tasks.ExpirationTask;
import me.foesio.foAuction.utils.ColorPalette;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FoAuction extends JavaPlugin {
    private static final String MODRINTH_PROJECT_ID = "foauction";
    private static final int BSTATS_PLUGIN_ID = 33181;

    private static FoFileLogger activeFileLogger;

    private AuctionSettings settings;
    private FoFileLogger fileLogger;
    private FoMetrics metrics;
    private FoMessageService messageService;
    private GuiConfigService guiConfigService;
    private ConfiguredTextDialogs dialogTexts;
    private VaultEconomyBridge economyService;
    private DatabaseManager databaseManager;
    private IUserDataRepository userDataRepository;
    private AuctionService auctionService;
    private DiscordWebhookService webhookService;
    private FoCoreContext core;
    private FoSoundService sounds;
    private FoAdminSounds adminSounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private UpdateNoticeService updateNotices;
    private PlayerNameCache playerNameCache;
    private AuctionGuiManager guiManager;
    private AdminEditorManager adminEditorManager;
    private ExpirationTask expirationTask;
    private volatile boolean periodicSaveCancelled;
    private volatile boolean periodicSaveScheduled;

    /** Available after successful enable; integrations must depend/softdepend on FoAuction. */
    public me.foesio.foAuction.api.StoredItemApi getStoredItemApi() {
        if (!isEnabled() || auctionService == null) throw new IllegalStateException("FoAuction is not ready");
        return auctionService;
    }

    public static FoFileLogger fileLogger() {
        return activeFileLogger;
    }

    @Override
    public void onEnable() {
        fileLogger = FoFileLogger.create(this);
        activeFileLogger = fileLogger;
        settings = new AuctionSettings(this);
        fileLogger.configure(settings.isFileLoggingEnabled(), true);
        messageService = FoMessageService.load(this, messageMigrations());
        refreshCoreContext();
        migrateSprites();
        guiConfigService = new GuiConfigService(this, messageService);
        dialogTexts = createDialogTexts();
        dialogTexts.load();
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
        userDataRepository = new SqliteUserDataRepository(this, databaseManager, core.scheduler());
        userDataRepository.loadAllFromDisk();
        fileLogger.info("SQLite userdata loaded from userdata.db.");

        webhookService = new DiscordWebhookService(this);
        updateNotices = createUpdateNotices().start();
        playerNameCache = new PlayerNameCache();
        playerNameCache.rememberAll(Bukkit.getOnlinePlayers());
        auctionService = new AuctionService(
                this,
                userDataRepository,
                economyService,
                  settings,
                webhookService,
                  playerNameCache,
                  messageService,
                  core.inventoryDeposits()
          );
          auctionService.rebuildIndex();

        guiManager = new AuctionGuiManager(auctionService, settings, messageService, playerNameCache, guiConfigService);
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
        }
        fileLogger.info("Plugin enable complete.");
    }

    @Override
    public void onDisable() {
        if (fileLogger != null) {
            fileLogger.info("Plugin disable started.");
        }
        if (expirationTask != null) {
            expirationTask.cancel();
            fileLogger.info("Expiration task stopped.");
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

    private void registerCommands() {
        AuctionCommand auctionCommandExecutor = new AuctionCommand(
                guiManager,
                auctionService,
                economyService,
                settings,
                messageService,
                adminSounds
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
                  new PlayerDataListener(userDataRepository, auctionService, playerNameCache, core.scheduler()),
                this
        );

        getServer().getPluginManager().registerEvents(
                  new GuiListener(this, guiManager, auctionService, () -> core, dialogTexts, messageService),
                this
        );

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
          .add("sounds", sounds::reload)
                  .addMessages(messageService)
                  .add("guis", guiConfigService::reload)
                  .add("dialogs", dialogTexts::reload)
                  .add("file-logging", () -> fileLogger.configure(settings.isFileLoggingEnabled(), false))
                  .reload();
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

    private void refreshCoreContext() {
        metrics = null;
        if (core != null) {
            core.close();
        }
        core = FoPluginCore.create(this);
        if (sounds == null) {
            sounds = core.createSounds(soundMigrations());
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

      private FoSoundMigrations soundMigrations() {
          return FoSoundMigrations.create()
                  .move("auction.menu-open", "gui.open")
                  .copy("auction.page-turn", "gui.page-next")
                  .copy("auction.page-turn", "gui.page-previous")
                  .remove("auction.page-turn")
                  .copy("auction.toggle", "gui.sort")
                  .copy("auction.toggle", "gui.filter")
                  .remove("auction.toggle")
                  .move("auction.refresh", "gui.click")
                  .move("auction.preview-open", "gui.open")
                  .move("auction.search-updated", "gui.search")
                  .move("auction.search-cleared", "gui.clear-search")
                  .move("auction.selling-confirm", "gui.confirm")
                  .move("auction.denied", "gui.error")
                  .build();
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
                .add(config -> backfillEditorMessageDefaults(config))
                .build();
      }

      private void migrateSprites() {
          messageService.migrateToVersion(core.migrations(), 1, config -> {
              boolean changed = false;
              changed |= FoMessageService.addMissingToken(config, "tokens.prefix", ":diamond:", null);
              changed |= FoMessageService.addMissingToken(config, "command.listed", ":emerald:");
              changed |= FoMessageService.addMissingToken(config, "command.invalid-price", ":redstone:");
              changed |= FoMessageService.addMissingToken(config, "admin.reload", ":emerald:");
              changed |= FoMessageService.addMissingToken(config, "admin.reload-failed", ":redstone:");
              changed |= FoMessageService.addMissingToken(config, "editor.opened", ":book:");
              changed |= FoMessageService.addMissingToken(config, "editor.saved", ":emerald:");
              changed |= FoMessageService.addMissingToken(config, "editor.save-failed", ":redstone:");
              changed |= FoMessageService.addMissingToken(config, "gui.purchase-inventory", ":emerald:");
              changed |= FoMessageService.addMissingToken(config, "gui.insufficient-funds", ":redstone:");
              return true;
          });
      }

      private boolean backfillEditorMessageDefaults(FileConfiguration config) {
          boolean changed = false;
          changed |= ensureMessage(config, "gui.item-updated", "{warn}Auction items were updated. Review the refreshed listing before buying.");
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

      private boolean ensureMessage(FileConfiguration config, String path, String fallback) {
          if (config.contains(path)) {
              return false;
          }
          config.set(path, fallback);
          return true;
      }

      private ConfiguredTextDialogs createDialogTexts() {
          TextDialogRequest searchFallback = new TextDialogRequest(
                  "Search",
                  java.util.List.of("Enter item text to search listings.", "Leave empty to clear the current search."),
                  "Search text",
                  "",
                  "Item name",
                  DialogButton.search("Search", "Apply this search."),
                  DialogButton.cancel("Cancel", "Return without changing search."),
                  320,
                  300,
                  100,
                  true,
                  true,
                  false
          );
          return ConfiguredTextDialogs.create(this)
                  .register("search", searchFallback);
      }
}
