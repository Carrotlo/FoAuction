package me.foesio.foAuction.storage;

import me.foesio.foAuction.model.PlayerData;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Interface for user data storage implementations.
 * Provides methods for managing player data persistence.
 */
public interface IUserDataRepository {

    /**
     * Loads all user data from disk/database into cache.
     */
    void loadAllFromDisk();

    /**
     * Loads and caches user data for the specified UUID.
     * @param uuid The player's UUID
     * @return The loaded PlayerData
     */
    PlayerData loadAndCache(UUID uuid);

    /**
     * Loads and caches user data without blocking the caller thread.
     * The callback receives the cached or loaded data once available.
     * @param uuid The player's UUID
     * @param callback Completion callback
     */
    void loadAndCacheAsync(UUID uuid, Consumer<PlayerData> callback);

    /**
     * Gets or creates user data for the specified UUID.
     * @param uuid The player's UUID
     * @return The existing or new PlayerData
     */
    PlayerData getOrCreate(UUID uuid);

    /**
     * Gets all cached player data.
     * @return Collection of all cached PlayerData
     */
    Collection<PlayerData> getAllCached();

    /**
     * Saves user data asynchronously.
     * @param uuid The player's UUID to save
     */
    void saveAsync(UUID uuid);

    /**
     * Saves user data synchronously, waiting for completion.
     * @param uuid The player's UUID to save
     */
    void saveNow(UUID uuid);

    /**
     * Saves all cached user data asynchronously.
     */
    void saveAllAsync();

    /**
     * Shuts down the repository and flushes all pending saves.
     */
    void shutdownAndFlush();
}
