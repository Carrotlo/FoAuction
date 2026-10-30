package me.foesio.foAuction.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerNameCache {
    private final ConcurrentMap<UUID, String> namesByUuid;

    public PlayerNameCache() {
        this.namesByUuid = new ConcurrentHashMap<>();
    }

    public void remember(Player player) {
        if (player == null) {
            return;
        }
        remember(player.getUniqueId(), player.getName());
    }

    public void rememberAll(Iterable<? extends Player> players) {
        if (players == null) {
            return;
        }
        for (Player player : players) {
            remember(player);
        }
    }

    public void rememberVirtual(UUID uuid, String name) {
        remember(uuid, name);
    }

    public String resolve(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }

        String cached = namesByUuid.get(uuid);
        if (cached != null) {
            return cached;
        }

        if (Bukkit.isPrimaryThread()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                remember(online);
                return online.getName();
            }
        }

        return uuid.toString().substring(0, 8);
    }

    private void remember(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        namesByUuid.put(uuid, name);
    }
}
