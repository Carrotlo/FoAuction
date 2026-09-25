package me.foesio.foAuction.model;

import me.foesio.core.item.FoItemStacks;
import me.foesio.foAuction.storage.DatabaseManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ClaimEntry {
    private final UUID id;
    private final Type type;
    private final ItemStack item;
    private final double money;
    private final long createdAt;
    private final String note;

    private ClaimEntry(
            UUID id,
            Type type,
            ItemStack item,
            double money,
            long createdAt,
            String note
    ) {
        this.id = id;
        this.type = type;
        this.item = item == null ? null : item.clone();
        this.money = money;
        this.createdAt = createdAt;
        this.note = note == null ? "" : note;
    }

    public static ClaimEntry item(ItemStack item, String note) {
        return new ClaimEntry(UUID.randomUUID(), Type.ITEM, item, 0D, System.currentTimeMillis(), note);
    }

    public static ClaimEntry item(ItemStack item) {
        return item(item, "");
    }

    public static ClaimEntry money(double amount, String note) {
        return new ClaimEntry(UUID.randomUUID(), Type.MONEY, null, amount, System.currentTimeMillis(), note);
    }

    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public ItemStack getItem() {
        return item == null ? null : item.clone();
    }

    public double getMoney() {
        return money;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getNote() {
        return note;
    }

    public ClaimEntry withItem(ItemStack replacement) {
        if (item == null || replacement == null || replacement.getType().isAir()
                || replacement.getAmount() <= 0 || replacement.getAmount() != item.getAmount()) {
            throw new IllegalArgumentException("Replacement must preserve a positive item quantity");
        }
        return new ClaimEntry(id, type, replacement, money, createdAt, note);
    }

    public ClaimEntry copy() {
        return new ClaimEntry(id, type, item, money, createdAt, note);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id.toString());
        map.put("type", type.name());
        map.put("createdAt", createdAt);
        if (!note.isBlank()) {
            map.put("note", note);
        }
        if (type == Type.ITEM && item != null) {
            map.put("item", item.clone());
        }
        if (type == Type.MONEY) {
            map.put("money", money);
        }
        return map;
    }

    public static ClaimEntry fromMap(Map<?, ?> map) {
        Object idObject = map.get("id");
        Object typeObject = map.get("type");
        Object createdAtObject = firstPresent(map, "createdAt", "created_time", "createdTime", "created");
        Object noteObject = map.get("note");
        Type type = parseClaimType(typeObject, map);
        if (type == null) {
            return null;
        }

        try {
            UUID id = parseClaimId(idObject);
            String note = noteObject == null ? "" : String.valueOf(noteObject);
            long createdAt = createdAtObject instanceof Number createdAtNumber
                    ? createdAtNumber.longValue()
                    : System.currentTimeMillis();

            if (type == Type.ITEM) {
                Object itemObject = map.get("item");
                ItemStack itemStack = itemObject instanceof ItemStack legacyItem
                        ? legacyItem
                        : itemObject instanceof String encodedItem
                        ? FoItemStacks.decode(encodedItem).orElse(null)
                        : null;
                if (itemStack == null
                        || itemStack.getType().isAir()
                        || itemStack.getAmount() <= 0) {
                    return null;
                }
                return new ClaimEntry(id, Type.ITEM, itemStack, 0D, createdAt, note);
            }

            Object moneyObject = map.get("money");
            if (!(moneyObject instanceof Number moneyNumber)) {
                return null;
            }
            double money = moneyNumber.doubleValue();
            if (!Double.isFinite(money) || money < 0D) {
                return null;
            }
            return new ClaimEntry(id, Type.MONEY, null, money, createdAt, note);
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

    private static UUID parseClaimId(Object idObject) {
        if (idObject instanceof String idString && !idString.isBlank()) {
            try {
                return UUID.fromString(idString);
            } catch (IllegalArgumentException ignored) {
                // Older YAML data did not always store UUID claim ids.
            }
        }
        return UUID.randomUUID();
    }

    private static Type parseClaimType(Object typeObject, Map<?, ?> map) {
        if (typeObject instanceof String typeString && !typeString.isBlank()) {
            try {
                return Type.valueOf(typeString.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (map.get("item") instanceof ItemStack) {
            return Type.ITEM;
        }
        if (map.get("money") instanceof Number) {
            return Type.MONEY;
        }
        return null;
    }

    public void saveToDatabase(DatabaseManager dbManager, Connection connection, UUID playerUuid) throws SQLException {
        String claimData = serializeClaimEntry();
        if (claimData.isEmpty()) {
            throw new SQLException("Could not serialize claim " + id);
        }
        dbManager.executeUpdate(
            connection,
            "INSERT OR REPLACE INTO claims (player_uuid, claim_data, created_time) VALUES (?, ?, ?)",
            playerUuid.toString(),
            claimData,
            createdAt
        );
    }

    public static ClaimEntry fromDatabaseRow(ResultSet rs) throws SQLException {
        try {
            String claimData = rs.getString("claim_data");
            ClaimEntry entry = deserializeClaimEntry(claimData);
            if (entry == null) {
                return null;
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeClaimEntry() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id.toString());
            data.put("type", type.name());
            data.put("createdAt", createdAt);
            data.put("note", note);
            
            if (type == Type.ITEM && item != null) {
                data.put("item", FoItemStacks.encode(item));
            }
            if (type == Type.MONEY) {
                data.put("money", money);
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                boos.writeObject(data);
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    private static ClaimEntry deserializeClaimEntry(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                Object obj = bois.readObject();
                if (obj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) obj;
                    return fromMap(map);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public enum Type {
        ITEM,
        MONEY
    }
}
