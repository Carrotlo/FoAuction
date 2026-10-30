package me.foesio.foAuction.storage;

import me.foesio.foAuction.service.AuctionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlotEntitlementRepositoryTest {
    @TempDir Path folder;

    @Test
    void purchasesSurviveAuctionUserdataResetAndStillAddToPermissionLimit() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        first.addPurchase(player, 0, 400);
        first.addPurchase(player, 1, 650);
        Files.writeString(folder.resolve("userdata.db"), "old auction listings");
        Files.delete(folder.resolve("userdata.db"));

        SlotEntitlementRepository reloaded = new SlotEntitlementRepository(folder);
        assertEquals(2, reloaded.purchased(player));
        assertEquals(5, AuctionService.addPurchasedSlots(3, reloaded.purchased(player)));
        assertEquals(12, AuctionService.addPurchasedSlots(10, reloaded.purchased(player)));
        assertTrue(Files.isRegularFile(folder.resolve("entitlements.db")));
    }

    @Test
    void rejectsStaleOrExcessPurchases() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository repository = new SlotEntitlementRepository(folder);
        repository.addPurchase(player, 0, 400);
        assertThrows(IllegalStateException.class, () -> repository.addPurchase(player, 0, 400));
        repository.addPurchase(player, 1, 650);
        repository.addPurchase(player, 2, 1000);
        assertThrows(IllegalStateException.class, () -> repository.addPurchase(player, 3, 1000));
        assertEquals(3, new SlotEntitlementRepository(folder).purchased(player));
    }

    @Test
    void confirmedChargeIsGrantedOnceAfterRestart() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        String purchase = first.preparePurchase(player, 0, 400, 500);

        SlotEntitlementRepository recovered = new SlotEntitlementRepository(folder);
        assertEquals(0, recovered.purchased(player));
        assertEquals(1, recovered.recoverPending(id -> 100).granted());
        assertEquals(1, recovered.purchased(player));
        assertEquals(1, new SlotEntitlementRepository(folder).purchased(player));
        assertThrows(IllegalStateException.class, () -> recovered.finishPurchase(player, purchase));
    }

    @Test
    void rejectedChargeIsNotGrantedAfterRestart() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        first.preparePurchase(player, 0, 400, 500);

        SlotEntitlementRepository recovered = new SlotEntitlementRepository(folder);
        assertEquals(1, recovered.recoverPending(id -> 500).cancelled());
        assertEquals(0, recovered.purchased(player));
        assertTrue(recovered.pendingPurchases().isEmpty());
    }

    @Test
    void changedOrUnavailableBalanceWaitsForManualReview() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        first.preparePurchase(player, 0, 400, 500);

        SlotEntitlementRepository recovered = new SlotEntitlementRepository(folder);
        assertEquals(1, recovered.recoverPending(id -> 300).unresolved().size());
        assertEquals(1, recovered.recoverPending(id -> -1).unresolved().size());
        assertEquals(1, recovered.recoverPending(id -> 100).unresolved().size());
        assertTrue(recovered.pendingPurchases().getFirst().manualReview());
        assertEquals(0, recovered.purchased(player));
        assertEquals(1, recovered.pendingPurchases().size());
    }

    @Test
    void ambiguousChargeNeverAutoGrantsOrCancelsAfterRestart() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        String purchase = first.preparePurchase(player, 0, 400, 500);
        first.requireManualReview(player, purchase);

        SlotEntitlementRepository restarted = new SlotEntitlementRepository(folder);
        assertTrue(restarted.pendingPurchases().getFirst().manualReview());
        assertEquals(1, restarted.recoverPending(id -> 100).unresolved().size());
        assertEquals(1, restarted.recoverPending(id -> 500).unresolved().size());
        assertEquals(0, restarted.purchased(player));
        assertEquals(1, restarted.pendingPurchases().size());

        restarted.finishPurchase(player, purchase);
        assertEquals(1, new SlotEntitlementRepository(folder).purchased(player));
        assertTrue(restarted.pendingPurchases().isEmpty());
    }

    @Test
    void pendingPurchaseBlocksAnotherCheckoutForSamePlayer() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository repository = new SlotEntitlementRepository(folder);
        repository.preparePurchase(player, 0, 400, 500);

        assertThrows(java.sql.SQLException.class, () -> repository.preparePurchase(player, 0, 400, 500));
        assertEquals(0, new SlotEntitlementRepository(folder).purchased(player));
    }

    @Test
    void staleRepositoryCannotOverwriteAnAlreadyPurchasedTier() throws Exception {
        UUID player = UUID.randomUUID();
        SlotEntitlementRepository first = new SlotEntitlementRepository(folder);
        SlotEntitlementRepository stale = new SlotEntitlementRepository(folder);
        first.addPurchase(player, 0, 400);

        String pending = stale.preparePurchase(player, 0, 400, 500);
        assertThrows(SQLException.class, () -> stale.finishPurchase(player, pending));
        SlotEntitlementRepository reloaded = new SlotEntitlementRepository(folder);
        assertEquals(1, reloaded.purchased(player));
        assertEquals(1, reloaded.recoverPending(id -> 100).unresolved().size());
    }

    @Test
    void legacyPendingRowsRequireReviewAfterSchemaUpgrade() throws Exception {
        UUID player = UUID.randomUUID();
        String url = "jdbc:sqlite:" + folder.resolve("entitlements.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pending_slot_purchases (player_uuid TEXT PRIMARY KEY, id TEXT NOT NULL, previous_slots INTEGER NOT NULL, price INTEGER NOT NULL)");
            statement.execute("INSERT INTO pending_slot_purchases VALUES ('" + player + "', '" + UUID.randomUUID() + "', 0, 400)");
        }

        SlotEntitlementRepository upgraded = new SlotEntitlementRepository(folder);
        assertEquals(1, upgraded.recoverPending(id -> -1).unresolved().size());
        assertEquals(0, new SlotEntitlementRepository(folder).purchased(player));
        assertTrue(new SlotEntitlementRepository(folder).pendingPurchases().getFirst().manualReview());
    }
}
