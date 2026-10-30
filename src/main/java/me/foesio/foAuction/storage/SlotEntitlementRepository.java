package me.foesio.foAuction.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Permanent purchases live outside userdata.db, which owners may reset with listings. */
public final class SlotEntitlementRepository {
    private final String url;
    private final Map<UUID, Integer> purchases = new HashMap<>();

    public SlotEntitlementRepository(Path dataFolder) throws Exception {
        Files.createDirectories(dataFolder);
        url = "jdbc:sqlite:" + dataFolder.resolve("entitlements.db").toAbsolutePath();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS slot_purchases (player_uuid TEXT PRIMARY KEY, purchased_slots INTEGER NOT NULL CHECK (purchased_slots BETWEEN 0 AND 3))");
            statement.execute("CREATE TABLE IF NOT EXISTS slot_purchase_log (id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, purchased_slots INTEGER NOT NULL, price INTEGER NOT NULL, purchased_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS pending_slot_purchases (player_uuid TEXT PRIMARY KEY, id TEXT NOT NULL, previous_slots INTEGER NOT NULL, price INTEGER NOT NULL, balance_before INTEGER, manual_review INTEGER NOT NULL DEFAULT 0)");
            boolean hasBalanceBefore = false;
            boolean hasManualReview = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(pending_slot_purchases)")) {
                while (columns.next()) {
                    if ("balance_before".equals(columns.getString("name"))) hasBalanceBefore = true;
                    if ("manual_review".equals(columns.getString("name"))) hasManualReview = true;
                }
            }
            if (!hasBalanceBefore) statement.execute("ALTER TABLE pending_slot_purchases ADD COLUMN balance_before INTEGER");
            if (!hasManualReview) statement.execute("ALTER TABLE pending_slot_purchases ADD COLUMN manual_review INTEGER NOT NULL DEFAULT 0");
            try (ResultSet rows = statement.executeQuery("SELECT player_uuid, purchased_slots FROM slot_purchases")) {
                while (rows.next()) {
                    purchases.put(UUID.fromString(rows.getString(1)), rows.getInt(2));
                }
            }
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 30000");
        }
        return connection;
    }

    public synchronized int purchased(UUID player) {
        return purchases.getOrDefault(player, 0);
    }

    public synchronized List<PendingPurchase> pendingPurchases() throws SQLException {
        List<PendingPurchase> pending = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT player_uuid, id, previous_slots, price, balance_before, manual_review FROM pending_slot_purchases")) {
            while (rows.next()) {
                int balanceBefore = rows.getInt(5);
                boolean legacy = rows.wasNull();
                pending.add(new PendingPurchase(UUID.fromString(rows.getString(1)), rows.getString(2),
                        rows.getInt(3), rows.getInt(4), legacy ? null : balanceBefore, rows.getInt(6) != 0));
            }
        }
        return pending;
    }

    /** Reconcile a stopped checkout against the currency balance seen after restart. */
    public synchronized RecoveryReport recoverPending(ToIntFunction<UUID> currentBalance) throws SQLException {
        int granted = 0;
        int cancelled = 0;
        List<PendingPurchase> unresolved = new ArrayList<>();
        for (PendingPurchase pending : pendingPurchases()) {
            if (pending.manualReview()) {
                unresolved.add(pending);
                continue;
            }
            if (purchased(pending.player()) != pending.previous()) {
                requireManualReview(pending.player(), pending.id());
                unresolved.add(new PendingPurchase(pending.player(), pending.id(), pending.previous(),
                        pending.price(), pending.balanceBefore(), true));
                continue;
            }
            if (pending.balanceBefore() == null) {
                // Legacy rows have no proof that PlayerPoints charged the player.
                requireManualReview(pending.player(), pending.id());
                unresolved.add(new PendingPurchase(pending.player(), pending.id(), pending.previous(),
                        pending.price(), null, true));
                continue;
            }
            int balance = currentBalance.applyAsInt(pending.player());
            if (balance >= 0 && (long) pending.balanceBefore() - balance == pending.price()) {
                finishPurchase(pending.player(), pending.id());
                granted++;
            } else if (balance == pending.balanceBefore()) {
                cancelPurchase(pending.player(), pending.id());
                cancelled++;
            } else {
                if (balance >= 0) {
                    requireManualReview(pending.player(), pending.id());
                    unresolved.add(new PendingPurchase(pending.player(), pending.id(), pending.previous(),
                            pending.price(), pending.balanceBefore(), true));
                } else {
                    unresolved.add(pending);
                }
            }
        }
        return new RecoveryReport(granted, cancelled, List.copyOf(unresolved));
    }

    synchronized void addPurchase(UUID player, int previous, int price) throws SQLException {
        String id = preparePurchase(player, previous, price, 0);
        finishPurchase(player, id);
    }

    public synchronized String preparePurchase(UUID player, int previous, int price, int balanceBefore) throws SQLException {
        if (previous < 0 || previous >= 3 || purchased(player) != previous) {
            throw new IllegalStateException("Slot purchase changed during checkout");
        }
        if (price <= 0 || balanceBefore < 0) throw new IllegalArgumentException("Invalid purchase balance or price");
        String id = UUID.randomUUID().toString();
        try (Connection connection = open(); PreparedStatement pending = connection.prepareStatement(
                "INSERT INTO pending_slot_purchases(player_uuid, id, previous_slots, price, balance_before) VALUES (?, ?, ?, ?, ?)")) {
            pending.setString(1, player.toString());
            pending.setString(2, id);
            pending.setInt(3, previous);
            pending.setInt(4, price);
            pending.setInt(5, balanceBefore);
            pending.executeUpdate();
        }
        return id;
    }

    public synchronized void cancelPurchase(UUID player, String id) throws SQLException {
        try (Connection connection = open(); PreparedStatement pending = connection.prepareStatement(
                "DELETE FROM pending_slot_purchases WHERE player_uuid = ? AND id = ?")) {
            pending.setString(1, player.toString());
            pending.setString(2, id);
            if (pending.executeUpdate() != 1) throw new IllegalStateException("Pending purchase missing");
        }
    }

    /** Keep an ambiguous checkout from being inferred from a later balance change. */
    public synchronized void requireManualReview(UUID player, String id) throws SQLException {
        try (Connection connection = open(); PreparedStatement pending = connection.prepareStatement(
                "UPDATE pending_slot_purchases SET manual_review = 1 WHERE player_uuid = ? AND id = ?")) {
            pending.setString(1, player.toString());
            pending.setString(2, id);
            if (pending.executeUpdate() != 1) throw new IllegalStateException("Pending purchase missing");
        }
    }

    public synchronized void finishPurchase(UUID player, String id) throws SQLException {
        boolean committed = false;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT previous_slots, price FROM pending_slot_purchases WHERE player_uuid = ? AND id = ?");
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO slot_purchases(player_uuid, purchased_slots) VALUES (?, ?)");
                 PreparedStatement update = connection.prepareStatement(
                    "UPDATE slot_purchases SET purchased_slots = ? WHERE player_uuid = ? AND purchased_slots = ?");
                 PreparedStatement log = connection.prepareStatement(
                    "INSERT INTO slot_purchase_log(id, player_uuid, purchased_slots, price, purchased_at) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement remove = connection.prepareStatement(
                    "DELETE FROM pending_slot_purchases WHERE player_uuid = ? AND id = ?")) {
                read.setString(1, player.toString());
                read.setString(2, id);
                int previous;
                int price;
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Pending purchase missing");
                    previous = row.getInt(1);
                    price = row.getInt(2);
                }
                if (purchased(player) != previous) throw new IllegalStateException("Slot purchase changed during checkout");
                if (previous == 0) {
                    insert.setString(1, player.toString());
                    insert.setInt(2, 1);
                    insert.executeUpdate();
                } else {
                    update.setInt(1, previous + 1);
                    update.setString(2, player.toString());
                    update.setInt(3, previous);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Slot purchase changed in entitlements.db");
                    }
                }
                log.setString(1, id);
                log.setString(2, player.toString());
                log.setInt(3, previous + 1);
                log.setInt(4, price);
                log.setLong(5, System.currentTimeMillis());
                log.executeUpdate();
                remove.setString(1, player.toString());
                remove.setString(2, id);
                if (remove.executeUpdate() != 1) throw new IllegalStateException("Pending purchase missing");
                connection.commit();
                purchases.put(player, previous + 1);
                committed = true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            // A close failure after a successful commit must not trigger recovery twice.
            if (!committed) throw exception;
        }
    }

    public record PendingPurchase(UUID player, String id, int previous, int price, Integer balanceBefore,
                                  boolean manualReview) {}
    public record RecoveryReport(int granted, int cancelled, List<PendingPurchase> unresolved) {}
}
