package me.foesio.foAuction.listeners;

import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.PlayerNameCache;
import me.foesio.foAuction.storage.IUserDataRepository;
import me.foesio.core.scheduler.FoScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.function.Consumer;

public final class PlayerDataListener implements Listener {
    private final IUserDataRepository userDataRepository;
    private final AuctionService auctionService;
    private final PlayerNameCache playerNameCache;
    private final FoScheduler scheduler;
    private final Consumer<Player> adminJoinNotice;

    public PlayerDataListener(
            IUserDataRepository userDataRepository,
            AuctionService auctionService,
            PlayerNameCache playerNameCache,
            FoScheduler scheduler,
            Consumer<Player> adminJoinNotice
    ) {
        this.userDataRepository = userDataRepository;
        this.auctionService = auctionService;
        this.playerNameCache = playerNameCache;
        this.scheduler = scheduler;
        this.adminJoinNotice = adminJoinNotice;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduler.runForPlayer(event.getPlayer(), () -> {
            if (event.getPlayer().isOnline()) adminJoinNotice.accept(event.getPlayer());
        });
        UUID playerUuid = event.getPlayer().getUniqueId();
        playerNameCache.remember(event.getPlayer());
        userDataRepository.loadAndCacheAsync(playerUuid, ignored -> scheduler.runForPlayer(event.getPlayer(), () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            playerNameCache.remember(player);
            auctionService.syncPlayerListings(playerUuid);
            auctionService.deliverPendingNotifications(player);
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerNameCache.remember(event.getPlayer());
        auctionService.clearSearchQuery(event.getPlayer().getUniqueId());
        userDataRepository.saveAsync(event.getPlayer().getUniqueId());
    }
}
