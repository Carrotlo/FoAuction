package me.foesio.foAuction.storage;

import me.foesio.foAuction.model.AuctionListing;
import me.foesio.foAuction.model.ClaimEntry;
import me.foesio.foAuction.model.FilterMode;
import me.foesio.foAuction.model.PlayerData;
import me.foesio.foAuction.model.SoldAuctionHistoryEntry;
import me.foesio.foAuction.model.SortMode;
import me.foesio.foAuction.FoAuction;
import me.foesio.foAuction.utils.ColorPalette;
import me.foesio.core.scheduler.FoScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SqliteUserDataRepository implements IUserDataRepository {
    private record PlayerPreferences(SortMode sortMode, FilterMode filterMode) {}

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final FoScheduler scheduler;
    private final ConcurrentMap<UUID, PlayerData> cache;
    private final ExecutorService ioExecutor;
    private final ConcurrentMap<UUID, PlayerData> pendingSaves;
    private final AtomicBoolean saveDrainScheduled;
    private final AtomicBoolean shuttingDown;

    public SqliteUserDataRepository(JavaPlugin plugin, DatabaseManager databaseManager, FoScheduler scheduler) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.scheduler = scheduler;
        this.cache = new ConcurrentHashMap<>();
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FoAuction-SQLite");
            thread.setDaemon(true);
            return thread;
        });
        this.pendingSaves = new ConcurrentHashMap<>();
        this.saveDrainScheduled = new AtomicBoolean(false);
        this.shuttingDown = new AtomicBoolean(false);
    }

    public void loadAllFromDisk() {
        Set<UUID> playerUuids = collectAllKnownPlayerUuids();
        for (UUID uuid : playerUuids) {
            PlayerData playerData = loadPlayerData(uuid);
            cache.put(uuid, playerData);
        }
        importLegacyYamlData(playerUuids);
        FoAuction.fileLogger().info("Loaded userdata cache for " + cache.size() + " player(s).");
    }

    private Set<UUID> collectAllKnownPlayerUuids() {
        Set<UUID> playerUuids = new LinkedHashSet<>();
        collectPlayerUuids(playerUuids, "SELECT uuid FROM players");
        collectPlayerUuids(playerUuids, "SELECT DISTINCT player_uuid FROM auctions");
        collectPlayerUuids(playerUuids, "SELECT DISTINCT player_uuid FROM claims");
        collectPlayerUuids(playerUuids, "SELECT DISTINCT player_uuid FROM sold_history");
        collectPlayerUuids(playerUuids, "SELECT DISTINCT player_uuid FROM notifications");
        return playerUuids;
    }

    private void collectPlayerUuids(Set<UUID> playerUuids, String sql) {
        try {
            databaseManager.executeQuery(sql, resultSet -> {
                while (resultSet.next()) {
                    String rawUuid = resultSet.getString(1);
                    if (rawUuid == null || rawUuid.isBlank()) {
                        continue;
                    }

                    try {
                        playerUuids.add(UUID.fromString(rawUuid));
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning(ColorPalette.log("Invalid UUID found in database: " + rawUuid));
                        FoAuction.fileLogger().warn("Invalid UUID found in database: " + rawUuid + ".");
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, ColorPalette.log("Failed to load player UUIDs from database"), exception);
            FoAuction.fileLogger().error("Failed to load player UUIDs from database.", exception);
        }
    }

    public PlayerData loadAndCache(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadPlayerData);
    }

    public void loadAndCacheAsync(UUID uuid, Consumer<PlayerData> callback) {
        PlayerData cached = cache.get(uuid);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        try {
            ioExecutor.execute(() -> callback.accept(cache.computeIfAbsent(uuid, this::loadPlayerData)));
        } catch (RejectedExecutionException exception) {
            callback.accept(loadAndCache(uuid));
        }
    }

    public PlayerData getOrCreate(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadPlayerData);
    }

    @Override
    public PlayerData getCached(UUID owner) {
        return cache.get(owner);
    }

    public Collection<PlayerData> getAllCached() {
        return cache.values();
    }

    public void saveAsync(UUID uuid) {
        if (deferSnapshotToServerThread(() -> saveAsync(uuid))) {
            return;
        }
        queueSaveSnapshot(uuid);
    }

    private void queueSaveSnapshot(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) {
            return;
        }

        pendingSaves.put(uuid, data.snapshot());
        scheduleSaveDrain();
    }

    public void saveNow(UUID uuid) {
        queueSaveSnapshot(uuid);
        awaitPendingIo();
    }

    public void saveAllAsync() {
        if (deferSnapshotToServerThread(this::saveAllAsync)) {
            return;
        }
        for (UUID uuid : cache.keySet()) {
            queueSaveSnapshot(uuid);
        }
    }

    public void shutdownAndFlush() {
        shuttingDown.set(true);
        saveAllAsync();
        boolean executorDrained = awaitPendingIo();
        if (executorDrained) {
            flushPendingSavesOnCurrentThread();
        }
        ioExecutor.shutdown();

        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private PlayerData loadPlayerData(UUID uuid) {
        try {
            return databaseManager.withConnection(connection -> {
                ensurePlayerExists(connection, uuid);

                PlayerPreferences preferences = databaseManager.executeQuery(
                    connection,
                    "SELECT sort_mode, filter_mode FROM players WHERE uuid = ?",
                    resultSet -> {
                        if (resultSet.next()) {
                            return new PlayerPreferences(
                                SortMode.fromName(resultSet.getString("sort_mode")),
                                FilterMode.fromName(resultSet.getString("filter_mode"))
                            );
                        }
                        return new PlayerPreferences(SortMode.NEWEST, FilterMode.ALL);
                    },
                    uuid.toString()
                );

                List<AuctionListing> auctions = databaseManager.executeQuery(
                    connection,
                    "SELECT id, item_data, price, created_time, expire_time FROM auctions WHERE player_uuid = ?",
                    resultSet -> {
                        List<AuctionListing> loadedAuctions = new ArrayList<>();
                        while (resultSet.next()) {
                            AuctionListing listing = AuctionListing.fromDatabaseRow(resultSet, uuid);
                            if (listing != null) {
                                loadedAuctions.add(listing);
                            }
                        }
                        return loadedAuctions;
                    },
                    uuid.toString()
                );

                List<ClaimEntry> claims = databaseManager.executeQuery(
                    connection,
                    "SELECT claim_data, created_time FROM claims WHERE player_uuid = ?",
                    resultSet -> {
                        List<ClaimEntry> loadedClaims = new ArrayList<>();
                        while (resultSet.next()) {
                            ClaimEntry claim = ClaimEntry.fromDatabaseRow(resultSet);
                            if (claim != null) {
                                loadedClaims.add(claim);
                            }
                        }
                        return loadedClaims;
                    },
                    uuid.toString()
                );

                List<SoldAuctionHistoryEntry> soldHistory = databaseManager.executeQuery(
                    connection,
                    "SELECT item_data, price, buyer_name, sold_time FROM sold_history WHERE player_uuid = ?",
                    resultSet -> {
                        List<SoldAuctionHistoryEntry> loadedHistory = new ArrayList<>();
                        while (resultSet.next()) {
                            SoldAuctionHistoryEntry entry = SoldAuctionHistoryEntry.fromDatabaseRow(resultSet, uuid);
                            if (entry != null) {
                                loadedHistory.add(entry);
                            }
                        }
                        return loadedHistory;
                    },
                    uuid.toString()
                );

                List<String> notifications = databaseManager.executeQuery(
                    connection,
                    "SELECT message FROM notifications WHERE player_uuid = ? ORDER BY created_time",
                    resultSet -> {
                        List<String> loadedNotifications = new ArrayList<>();
                        while (resultSet.next()) {
                            loadedNotifications.add(resultSet.getString("message"));
                        }
                        return loadedNotifications;
                    },
                    uuid.toString()
                );

                return new PlayerData(
                    uuid,
                    auctions,
                    claims,
                    soldHistory,
                    notifications,
                    preferences.sortMode(),
                    preferences.filterMode()
                );
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, ColorPalette.log("Failed to load player data for " + uuid), e);
            FoAuction.fileLogger().error("Failed to load player data for " + uuid + ".", e);
            return new PlayerData(uuid);
        }
    }

    private void importLegacyYamlData(Set<UUID> sqlitePlayerUuids) {
        File legacyUserDataFolder = new File(plugin.getDataFolder(), "userdata");
        File[] legacyFiles = legacyUserDataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (legacyFiles == null || legacyFiles.length == 0) {
            return;
        }

        int importedPlayers = 0;
        for (File file : legacyFiles) {
            String fileName = file.getName();
            String uuidPart = fileName.substring(0, fileName.length() - 4);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidPart);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning(ColorPalette.log("Skipping invalid legacy userdata file: " + fileName));
                FoAuction.fileLogger().warn("Skipping invalid legacy userdata file: " + fileName + ".");
                continue;
            }

            if (hasStoredCollections(uuid)) {
                continue;
            }

            PlayerData legacyData = readLegacyUserFile(file, uuid);
            cache.put(uuid, legacyData);
            savePlayerData(legacyData);
            sqlitePlayerUuids.add(uuid);
            importedPlayers++;
        }

        if (importedPlayers > 0) {
            plugin.getLogger().info(ColorPalette.log("Imported " + importedPlayers + " legacy YAML userdata file(s) into SQLite."));
        FoAuction.fileLogger().info("Imported " + importedPlayers + " legacy YAML userdata file(s) into SQLite.");
        }
    }

    private PlayerData readLegacyUserFile(File file, UUID uuid) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        List<AuctionListing> auctions = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("auctions")) {
            AuctionListing listing = AuctionListing.fromMap(map, uuid);
            if (listing != null) {
                auctions.add(listing);
            }
        }

        List<ClaimEntry> claims = new ArrayList<>();
        List<?> rawClaims = yaml.getList("claims", List.of());
        for (Object claim : rawClaims) {
            if (claim instanceof ItemStack itemStack && itemStack.getType() != Material.AIR) {
                claims.add(ClaimEntry.item(itemStack));
                continue;
            }
            if (claim instanceof Map<?, ?> map) {
                ClaimEntry claimEntry = ClaimEntry.fromMap(map);
                if (claimEntry != null) {
                    claims.add(claimEntry);
                }
            }
        }

        List<SoldAuctionHistoryEntry> soldHistory = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("sold-history")) {
            SoldAuctionHistoryEntry entry = SoldAuctionHistoryEntry.fromMap(map, uuid);
            if (entry != null) {
                soldHistory.add(entry);
            }
        }

        List<String> notifications = new ArrayList<>(yaml.getStringList("notifications"));
        SortMode sortMode = SortMode.fromName(yaml.getString("sort-mode"));
        FilterMode filterMode = FilterMode.fromName(yaml.getString("filter-mode"));

        return new PlayerData(uuid, auctions, claims, soldHistory, notifications, sortMode, filterMode);
    }

    private boolean hasStoredCollections(UUID uuid) {
        String rawUuid = uuid.toString();
        try {
            return databaseManager.withConnection(connection ->
                hasRows(connection, "SELECT 1 FROM auctions WHERE player_uuid = ? LIMIT 1", rawUuid)
                    || hasRows(connection, "SELECT 1 FROM claims WHERE player_uuid = ? LIMIT 1", rawUuid)
                    || hasRows(connection, "SELECT 1 FROM sold_history WHERE player_uuid = ? LIMIT 1", rawUuid)
                    || hasRows(connection, "SELECT 1 FROM notifications WHERE player_uuid = ? LIMIT 1", rawUuid)
            );
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, ColorPalette.log("Failed to inspect SQLite data for " + uuid), exception);
            FoAuction.fileLogger().error("Failed to inspect SQLite data before legacy import for " + uuid + ".", exception);
            return true;
        }
    }

    private boolean hasRows(Connection connection, String sql, String rawUuid) throws SQLException {
        return databaseManager.executeQuery(connection, sql, ResultSet::next, rawUuid);
    }

    private void savePlayerData(PlayerData playerData) {
        try {
            databaseManager.runInTransaction(connection -> {
                databaseManager.executeUpdate(
                    connection,
                    """
                    INSERT INTO players (uuid, sort_mode, filter_mode) VALUES (?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        sort_mode = excluded.sort_mode,
                        filter_mode = excluded.filter_mode
                    """,
                    playerData.getUuid().toString(),
                    playerData.getSortMode().name(),
                    playerData.getFilterMode().name()
                );

                deleteSavedCollections(connection, playerData.getUuid());

                for (AuctionListing auction : playerData.getAuctions()) {
                    auction.saveToDatabase(databaseManager, connection, playerData.getUuid());
                }

                for (ClaimEntry claim : playerData.getClaims()) {
                    claim.saveToDatabase(databaseManager, connection, playerData.getUuid());
                }

                for (SoldAuctionHistoryEntry entry : playerData.getSoldHistory()) {
                    entry.saveToDatabase(databaseManager, connection, playerData.getUuid());
                }

                for (String notification : playerData.getPendingNotifications()) {
                    databaseManager.executeUpdate(
                        connection,
                        "INSERT INTO notifications (player_uuid, message, created_time) VALUES (?, ?, ?)",
                        playerData.getUuid().toString(),
                        notification,
                        System.currentTimeMillis()
                    );
                }
            });
        } catch (SQLException e) {
            Level level = DatabaseManager.isBusyException(e) ? Level.FINE : Level.SEVERE;
            plugin.getLogger().log(level, ColorPalette.log("Failed to save player data for " + playerData.getUuid()), e);
            FoAuction.fileLogger().error("Failed to save player data for " + playerData.getUuid() + ".", e);
        }
    }

    private void deleteSavedCollections(Connection connection, UUID uuid) throws SQLException {
        String rawUuid = uuid.toString();
        databaseManager.executeUpdate(connection, "DELETE FROM auctions WHERE player_uuid = ?", rawUuid);
        databaseManager.executeUpdate(connection, "DELETE FROM claims WHERE player_uuid = ?", rawUuid);
        databaseManager.executeUpdate(connection, "DELETE FROM sold_history WHERE player_uuid = ?", rawUuid);
        databaseManager.executeUpdate(connection, "DELETE FROM notifications WHERE player_uuid = ?", rawUuid);
    }

    private void ensurePlayerExists(Connection connection, UUID uuid) throws SQLException {
        boolean playerExists = databaseManager.executeQuery(
            connection,
            "SELECT uuid FROM players WHERE uuid = ?",
            ResultSet::next,
            uuid.toString()
        );

        if (!playerExists) {
            databaseManager.executeUpdate(
                connection,
                "INSERT INTO players (uuid, sort_mode, filter_mode) VALUES (?, ?, ?)",
                uuid.toString(),
                SortMode.NEWEST.name(),
                FilterMode.ALL.name()
            );
        }
    }

    private void scheduleSaveDrain() {
        if (!saveDrainScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            ioExecutor.execute(this::drainPendingSaves);
        } catch (RejectedExecutionException exception) {
            saveDrainScheduled.set(false);
            flushPendingSavesOnCurrentThread();
        }
    }

    private void drainPendingSaves() {
        try {
            PlayerData snapshot;
            while ((snapshot = pollNextPendingSave()) != null) {
                savePlayerData(snapshot);
            }
        } finally {
            saveDrainScheduled.set(false);
            if (!pendingSaves.isEmpty() && !shuttingDown.get()) {
                scheduleSaveDrain();
            }
        }
    }

    private PlayerData pollNextPendingSave() {
        for (UUID uuid : pendingSaves.keySet()) {
            PlayerData snapshot = pendingSaves.remove(uuid);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    private boolean awaitPendingIo() {
        try {
            ioExecutor.submit(() -> { }).get();
            return true;
        } catch (RejectedExecutionException exception) {
            return ioExecutor.isTerminated();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, ColorPalette.log("Failed to flush pending SQLite saves"), exception);
            FoAuction.fileLogger().error("Failed to flush pending SQLite saves.", exception);
            return true;
        }
    }

    private void flushPendingSavesOnCurrentThread() {
        PlayerData snapshot;
        while ((snapshot = pollNextPendingSave()) != null) {
            savePlayerData(snapshot);
        }
    }

    private boolean deferSnapshotToServerThread(Runnable action) {
        if (Bukkit.isPrimaryThread() || shuttingDown.get() || !plugin.isEnabled()) {
            return false;
        }

        try {
            scheduler.runGlobal(action);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

}
