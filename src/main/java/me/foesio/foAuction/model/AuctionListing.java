package me.foesio.foAuction.model;

import me.foesio.foAuction.storage.DatabaseManager;
import me.foesio.core.item.FoItemStacks;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AuctionListing {
    private final UUID id;
    private final UUID sellerUuid;
    private final ItemStack item;
    private final double price;
    private final long createdAt;
    private final long expiresAt;

    public AuctionListing(
            UUID id,
            UUID sellerUuid,
            ItemStack item,
            double price,
            long createdAt,
            long expiresAt
    ) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.item = item.clone();
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public Material getItemType() {
        return item.getType();
    }

    public double getPrice() {
        return price;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAt <= nowMillis;
    }

    public AuctionListing withItem(ItemStack replacement) {
        if (item == null || replacement == null || replacement.getType().isAir()
                || replacement.getAmount() <= 0 || replacement.getAmount() != item.getAmount()) {
            throw new IllegalArgumentException("Replacement must preserve a positive item quantity");
        }
        return new AuctionListing(id, sellerUuid, replacement, price, createdAt, expiresAt);
    }

    public AuctionListing copy() {
        return new AuctionListing(id, sellerUuid, item, price, createdAt, expiresAt);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id.toString());
        map.put("item", item.clone());
        map.put("price", price);
        map.put("createdAt", createdAt);
        map.put("expiresAt", expiresAt);
        return map;
    }

    public static AuctionListing fromMap(Map<?, ?> map, UUID sellerUuid) {
        Object idObject = map.get("id");
        Object itemObject = map.get("item");
        Object priceObject = map.get("price");
        Object createdObject = firstPresent(map, "createdAt", "created_time", "createdTime", "created");
        Object expiresObject = firstPresent(map, "expiresAt", "expire_time", "expireTime", "expireAt", "expires");

        if (!(itemObject instanceof ItemStack itemStack)
                || !(priceObject instanceof Number priceNumber)
                || !(createdObject instanceof Number createdNumber)
                || !(expiresObject instanceof Number expiresNumber)) {
            return null;
        }

        try {
            UUID id = parseListingId(idObject);
            double price = priceNumber.doubleValue();
            long createdAt = createdNumber.longValue();
            long expiresAt = expiresNumber.longValue();
            if (itemStack.getType().isAir()
                    || itemStack.getAmount() <= 0
                    || !Double.isFinite(price)
                    || price <= 0D
                    || expiresAt <= 0L
                    || createdAt <= 0L) {
                return null;
            }
            return new AuctionListing(
                    id,
                    sellerUuid,
                    itemStack,
                    price,
                    createdAt,
                    expiresAt
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static UUID parseListingId(Object idObject) {
        if (idObject instanceof String idString && !idString.isBlank()) {
            try {
                return UUID.fromString(idString);
            } catch (IllegalArgumentException ignored) {
                // Older YAML data did not always store UUID listing ids.
            }
        }
        return UUID.randomUUID();
    }

    private static ItemStack copyValidItem(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }

    public void saveToDatabase(DatabaseManager dbManager, Connection connection, UUID playerUuid) throws SQLException {
        String itemData = serializeItemStack(item);
        if (itemData.isEmpty()) {
            throw new SQLException("Could not serialize auction item " + id);
        }
        dbManager.executeUpdate(
            connection,
            "INSERT OR REPLACE INTO auctions (id, player_uuid, item_data, price, created_time, expire_time) VALUES (?, ?, ?, ?, ?, ?)",
            id.toString(),
            playerUuid.toString(),
            itemData,
            price,
            createdAt,
            expiresAt
        );
    }

    public static AuctionListing fromDatabaseRow(ResultSet rs, UUID playerUuid) throws SQLException {
        try {
            String idString = rs.getString("id");
            String itemData = rs.getString("item_data");
            ItemStack item = deserializeItemStack(itemData);

            if (item == null) {
                return null;
            }

            double price = rs.getDouble("price");
            long createdAt = rs.getLong("created_time");
            long expiresAt = rs.getLong("expire_time");

            if (!Double.isFinite(price) || price <= 0D || expiresAt <= 0L || createdAt <= 0L) {
                return null;
            }

            UUID id = parseListingId(idString);
            return new AuctionListing(
                id,
                playerUuid,
                item,
                price,
                createdAt,
                expiresAt
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String serializeItemStack(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        try {
            return FoItemStacks.encode(item);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static ItemStack deserializeItemStack(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        ItemStack coreItem = FoItemStacks.decode(data).map(AuctionListing::copyValidItem).orElse(null);
        if (coreItem != null) {
            return coreItem;
        }
        return deserializeLegacyItemStack(data);
    }

    private static ItemStack deserializeLegacyItemStack(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                Object obj = bois.readObject();
                if (obj instanceof ItemStack) {
                    ItemStack item = (ItemStack) obj;
                    return copyValidItem(item);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
