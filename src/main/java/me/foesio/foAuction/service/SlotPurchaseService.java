package me.foesio.foAuction.service;

import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.storage.SlotEntitlementRepository;
import org.black_ixx.playerpoints.PlayerPoints;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Spends the existing PlayerPoints balance for permanent extra auction slots. */
public final class SlotPurchaseService {
    public enum Status { SUCCESS, MAXED, INSUFFICIENT, UNAVAILABLE, ERROR }
    public record Result(Status status, int price, int purchased, int balance) {}

    private final JavaPlugin plugin;
    private final SlotEntitlementRepository repository;
    private final AuctionSettings settings;

    public SlotPurchaseService(JavaPlugin plugin, SlotEntitlementRepository repository, AuctionSettings settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
    }

    public boolean available() {
        return settings.isGemSlotsEnabled() && playerPointsAvailable();
    }

    public boolean playerPointsAvailable() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("PlayerPoints") && PlayerPoints.getInstance() != null;
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("PlayerPoints is unavailable: " + exception.getMessage());
            return false;
        }
    }

    public int purchased(UUID player) {
        return repository.purchased(player);
    }

    public int balance(UUID player) {
        // Pending charges still need reconciliation when new purchases are switched off.
        if (!playerPointsAvailable()) return -1;
        try {
            return PlayerPoints.getInstance().getAPI().look(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not read PlayerPoints balance for " + player + ": " + exception.getMessage());
            return -1;
        }
    }

    public int nextPrice(UUID player) {
        int purchased = purchased(player);
        List<Integer> prices = settings.getGemSlotPrices();
        return purchased >= prices.size() ? 0 : prices.get(purchased);
    }

    public SlotEntitlementRepository.RecoveryReport recoverPendingPurchases() throws SQLException {
        SlotEntitlementRepository.RecoveryReport report = repository.recoverPending(this::balance);
        for (SlotEntitlementRepository.PendingPurchase pending : report.unresolved()) {
            plugin.getLogger().severe("Auction slot purchase " + pending.id() + " for " + pending.player()
                    + " needs manual review: price=" + pending.price() + ", balance before="
                    + pending.balanceBefore() + ", balance now=" + balance(pending.player())
                    + ", slot tier before=" + pending.previous()
                    + "; no entitlement was granted. "
                    + (pending.manualReview() ? "Resolve this pending purchase manually in entitlements.db."
                    : "After correcting PlayerPoints, restart FoAuction to retry reconciliation."));
        }
        return report;
    }

    public synchronized Result buy(Player player) {
        UUID id = player.getUniqueId();
        int owned = purchased(id);
        int price = nextPrice(id);
        if (!available()) return new Result(Status.UNAVAILABLE, price, owned, -1);
        if (player.getEffectivePermissions().stream().anyMatch(permission -> permission.getValue()
                && (permission.getPermission().equalsIgnoreCase("foauction.slots.*")
                || permission.getPermission().equalsIgnoreCase("foauction.slots.2147483647")))) {
            return new Result(Status.MAXED, 0, owned, balance(id));
        }
        if (price == 0) return new Result(Status.MAXED, 0, owned, balance(id));
        int before = balance(id);
        if (before < 0) return new Result(Status.UNAVAILABLE, price, owned, before);
        if (before < price) return new Result(Status.INSUFFICIENT, price, owned, before);
        String purchaseId;
        try {
            purchaseId = repository.preparePurchase(id, owned, price, before);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().severe("Could not prepare auction slot purchase for " + id + ": " + exception.getMessage());
            return new Result(Status.ERROR, price, owned, before);
        }
        Boolean charged = null;
        try {
            charged = PlayerPoints.getInstance().getAPI().take(id, price);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("PlayerPoints take threw for " + id + "; checking actual balance: "
                    + exception.getMessage());
        }
        int after = balance(id);
        if (!Boolean.TRUE.equals(charged) || after < 0 || (long) before - after != price) {
            return resolveUnconfirmedCharge(id, purchaseId, price, owned, before, after, charged);
        }
        try {
            repository.finishPurchase(id, purchaseId);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().severe("Could not complete charged auction slot purchase " + purchaseId
                    + " for " + id + "; pending entitlement will be reconciled on restart: " + exception.getMessage());
            return new Result(Status.ERROR, price, owned, balance(id));
        }
        plugin.getLogger().info("Auction slot purchased: player=" + id + " tier=" + (owned + 1) + " cost=" + price);
        return new Result(Status.SUCCESS, price, owned + 1, after);
    }

    private Result resolveUnconfirmedCharge(UUID player, String purchaseId, int price, int owned,
                                            int before, int after, Boolean charged) {
        try {
            if (Boolean.FALSE.equals(charged) && after == before) {
                repository.cancelPurchase(player, purchaseId);
            } else {
                repository.requireManualReview(player, purchaseId);
                plugin.getLogger().severe("Ambiguous PlayerPoints charge for " + player + ": purchase="
                        + purchaseId + ", take returned=" + charged + ", balance before=" + before
                        + ", balance after=" + after + ", price=" + price
                        + ". No refund or slot was granted; review this pending purchase in entitlements.db.");
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().severe("Could not record unresolved auction slot purchase " + purchaseId
                    + " for " + player + ": " + exception.getMessage());
        }
        return new Result(Status.ERROR, price, owned, after);
    }
}
