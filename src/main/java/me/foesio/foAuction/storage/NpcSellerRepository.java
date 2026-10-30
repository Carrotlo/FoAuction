package me.foesio.foAuction.storage;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent identity registry for virtual auction sellers. */
public final class NpcSellerRepository {
    private final DatabaseManager database;
    private final Map<UUID, String> names;

    public NpcSellerRepository(DatabaseManager database) throws SQLException {
        this.database = database;
        database.executeStatement("CREATE TABLE IF NOT EXISTS npc_sellers (uuid TEXT PRIMARY KEY, name TEXT NOT NULL)");
        this.names = database.executeQuery("SELECT uuid, name FROM npc_sellers", rows -> {
            Map<UUID, String> loaded = new HashMap<>();
            while (rows.next()) {
                try {
                    loaded.put(UUID.fromString(rows.getString("uuid")), rows.getString("name"));
                } catch (IllegalArgumentException ignored) {
                    // Ignore corrupt identities rather than treating them as player sellers.
                }
            }
            return loaded;
        });
    }

    public synchronized UUID register(String name) throws SQLException {
        String normalized = validateName(name);
        UUID id = idForName(normalized);
        database.executeStatement("INSERT INTO npc_sellers(uuid, name) VALUES (?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name", id.toString(), normalized);
        names.put(id, normalized);
        return id;
    }

    public static UUID idForName(String name) {
        String normalized = validateName(name);
        return UUID.nameUUIDFromBytes(("foauction:npc:" + normalized.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }

    public synchronized boolean isNpc(UUID id) {
        return names.containsKey(id);
    }

    public synchronized Map<UUID, String> names() {
        return Map.copyOf(names);
    }

    public static String validateName(String name) {
        if (name == null) throw new IllegalArgumentException("NPC name is required");
        String normalized = name.trim();
        if (!normalized.matches("[A-Za-z0-9 _.-]{1,32}")) {
            throw new IllegalArgumentException("NPC name must be 1..32 letters, numbers, spaces, _, ., or -");
        }
        return normalized;
    }
}
