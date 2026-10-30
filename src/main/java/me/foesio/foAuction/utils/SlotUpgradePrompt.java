package me.foesio.foAuction.utils;

import me.foesio.core.message.FoMessageService;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.service.SlotPurchaseService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.Map;

/** Offers the existing slot shop when a listing fails because all slots are full. */
public final class SlotUpgradePrompt {
    private static final String PATH = "slots.no-slots-action";

    private SlotUpgradePrompt() {}

    public static void send(Player player, int currentLimit, SlotPurchaseService purchases,
                            AuctionSettings settings, FoMessageService messages) {
        if (currentLimit == Integer.MAX_VALUE || purchases.nextPrice(player.getUniqueId()) == 0
                || !purchases.available() || !OwnerMessages.isConfiguredScalar(PATH)) return;
        Map<String, String> values = Map.of(
                "price", Integer.toString(purchases.nextPrice(player.getUniqueId())),
                "currency", settings.getGemCurrencyName());
        if (messages.render(PATH, "", values).isBlank()) return;
        Component line = messages.renderComponent(player, PATH, "", values)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(ClickEvent.runCommand("/auction slots"))
                .hoverEvent(HoverEvent.showText(Component.text("Open auction slot upgrades")));
        player.sendMessage(line);
    }
}
