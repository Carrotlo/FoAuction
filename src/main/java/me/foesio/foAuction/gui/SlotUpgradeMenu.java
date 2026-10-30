package me.foesio.foAuction.gui;

import me.foesio.core.FoCoreContext;
import me.foesio.core.dialog.MenuDialogRequest;
import me.foesio.core.gui.publicgui.PublicGuiInventoryHolder;
import me.foesio.core.message.FoMessageService;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.SlotPurchaseService;
import me.foesio.foAuction.utils.OwnerMessages;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Small two-step purchase menu; its text is configurable in messages.yml. */
public final class SlotUpgradeMenu implements Listener {
    private static final int INFO = 11;
    private static final int BUY = 13;
    private static final int BACK = 15;

    private final AuctionService auctions;
    private final SlotPurchaseService purchases;
    private final AuctionSettings settings;
    private final FoMessageService messages;
    private final AuctionGuiManager guiManager;
    private final JavaPlugin plugin;
    private final Supplier<FoCoreContext> coreProvider;
    private final SlotUpgradeDialogConfig dialogConfig;

    public SlotUpgradeMenu(AuctionService auctions, SlotPurchaseService purchases, AuctionSettings settings,
                           FoMessageService messages, AuctionGuiManager guiManager, JavaPlugin plugin,
                           Supplier<FoCoreContext> coreProvider, SlotUpgradeDialogConfig dialogConfig) {
        this.auctions = auctions;
        this.purchases = purchases;
        this.settings = settings;
        this.messages = messages;
        this.guiManager = guiManager;
        this.plugin = plugin;
        this.coreProvider = coreProvider;
        this.dialogConfig = dialogConfig;
    }

    public void open(Player player) {
        if (!settings.isGemSlotsEnabled()) {
            OwnerMessages.send(messages, player, "slots.disabled");
            return;
        }
        if (!purchases.playerPointsAvailable()) {
            OwnerMessages.send(messages, player, "slots.unavailable");
            return;
        }
        if (!openDialog(player, false)) show(player, false);
    }

    private boolean openDialog(Player player, boolean confirmation) {
        FoCoreContext core = coreProvider.get();
        if (core == null || !core.nativeDialogs().canUseNativeDialogs(player)) return false;
        int owned = purchases.purchased(player.getUniqueId());
        int total = auctions.resolveMaxSlots(player);
        int price = total == Integer.MAX_VALUE ? 0 : purchases.nextPrice(player.getUniqueId());
        int balance = purchases.balance(player.getUniqueId());
        boolean available = balance >= 0;
        boolean canBuy = price > 0 && available;
        if (confirmation && !canBuy) return false;
        String status = total == Integer.MAX_VALUE ? "Unlimited slots"
                : price == 0 ? "All upgrades owned"
                : !available ? "Purchases unavailable"
                : "Next slot: " + price + " " + settings.getGemCurrencyName();
        Map<String, String> values = Map.of(
                "total", total == Integer.MAX_VALUE ? "Unlimited" : Integer.toString(total),
                "base", total == Integer.MAX_VALUE ? "Unlimited" : Integer.toString(total - owned),
                "bought", Integer.toString(owned), "price", Integer.toString(price),
                "balance", available ? Integer.toString(balance) : "Unavailable",
                "currency", settings.getGemCurrencyName(), "status", status);
        MenuDialogRequest request = dialogConfig.request(player, messages, values, confirmation, canBuy);
        if (request == null) return false;
        Object currentHolder = player.getOpenInventory().getTopInventory().getHolder();
        boolean fromAuctionInventory = (currentHolder instanceof PublicGuiInventoryHolder publicHolder
                && publicHolder.frame().definition().menuId().startsWith("auction."))
                || currentHolder instanceof Holder
                || currentHolder instanceof me.foesio.foAuction.gui.holder.MyListingsHolder;
        if (fromAuctionInventory) {
            core.inventoryCloseSuppressor().suppressNextClose(player);
            player.closeInventory();
        }
        boolean opened = core.dialogService().openMenu(player, request, key -> {
            if (confirmation) {
                if ("confirm".equals(key)) {
                    if (owned != purchases.purchased(player.getUniqueId())
                            || price != purchases.nextPrice(player.getUniqueId())) {
                        OwnerMessages.send(messages, player, "slots.price-changed");
                        open(player);
                    } else {
                        completePurchase(player);
                    }
                } else {
                    open(player);
                }
            } else if ("buy".equals(key)) {
                if (!openDialog(player, true)) show(player, true);
            }
        }, () -> guiManager.openMyListingsGui(player, 0));
        if (fromAuctionInventory) {
            if (opened) core.inventoryCloseSuppressor().clearLater(plugin, player, 5L);
            else core.inventoryCloseSuppressor().clear(player);
        }
        return opened;
    }

    private void show(Player player, boolean confirmation) {
        int owned = purchases.purchased(player.getUniqueId());
        int total = auctions.resolveMaxSlots(player);
        int price = total == Integer.MAX_VALUE ? 0 : purchases.nextPrice(player.getUniqueId());
        int balance = purchases.balance(player.getUniqueId());
        boolean available = balance >= 0;
        int baseline = total == Integer.MAX_VALUE ? Integer.MAX_VALUE : total - owned;
        String currency = settings.getGemCurrencyName();
        Map<String, String> values = Map.of(
                "total", total == Integer.MAX_VALUE ? "Unlimited" : Integer.toString(total),
                "base", baseline == Integer.MAX_VALUE ? "Unlimited" : Integer.toString(baseline),
                "bought", Integer.toString(owned), "price", Integer.toString(price),
                "balance", available ? Integer.toString(balance) : "Unavailable",
                "currency", currency);
        Holder holder = new Holder(player.getUniqueId(), confirmation, owned, price,
                colored(messages.render("slots.menu-title", "&8Auction Slots")));
        holder.inventory.setItem(INFO, item(Material.BOOK,
                messages.render("slots.info-name", "&eYour listing slots"),
                messages.renderList("slots.info-lore", List.of("&7Base slots: &f{base}",
                        "&7Bought slots: &f{bought}/3", "&7Total slots: &f{total}"), values)));
        String name = total == Integer.MAX_VALUE ? messages.render("slots.unlimited-name", "&aUnlimited listing slots")
                : price == 0 ? messages.render("slots.max-name", "&aAll upgrades owned")
                : !available ? messages.render("slots.unavailable-name", "&cPurchases unavailable")
                : confirmation ? messages.render("slots.confirm-name", "&aConfirm purchase")
                : messages.render("slots.buy-name", "&aBuy +1 slot");
        List<String> lore = total == Integer.MAX_VALUE
                ? messages.renderList("slots.unlimited-lore", List.of("&7Your permission grants unlimited listing slots."), values)
                : price == 0 ? messages.renderList("slots.max-lore", List.of("&7You own all 3 extra slots."), values)
                : !available ? messages.renderList("slots.unavailable-lore", List.of("&7Please try again later."), values)
                : confirmation ? messages.renderList("slots.confirm-lore", List.of("&7Cost: &f{price} {currency}",
                        "&eClick to confirm this permanent purchase."), values)
                : messages.renderList("slots.buy-lore", List.of("&7Cost: &f{price} {currency}",
                        "&7Balance: &f{balance} {currency}", "&eClick to review purchase."), values);
        holder.inventory.setItem(BUY, item(price == 0 || !available ? Material.BARRIER : Material.EMERALD, name, lore));
        holder.inventory.setItem(BACK, item(Material.IRON_DOOR,
                messages.render("slots.back-name", "&cBack to listings"), List.of()));
        player.openInventory(holder.inventory);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(colored(name));
        meta.setLore(lore.stream().map(SlotUpgradeMenu::colored).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static String colored(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() == BACK) {
            guiManager.openMyListingsGui(player, 0);
            return;
        }
        if (event.getRawSlot() != BUY || holder.price == 0) return;
        if (purchases.balance(player.getUniqueId()) < 0) {
            OwnerMessages.send(messages, player, "slots.unavailable");
            return;
        }
        if (!holder.confirmation) {
            show(player, true);
            return;
        }
        if (holder.owned != purchases.purchased(player.getUniqueId())
                || holder.price != purchases.nextPrice(player.getUniqueId())) {
            OwnerMessages.send(messages, player, "slots.price-changed");
            show(player, false);
            return;
        }
        completePurchase(player);
    }

    private void completePurchase(Player player) {
        if (auctions.resolveMaxSlots(player) == Integer.MAX_VALUE) {
            OwnerMessages.send(messages, player, "slots.unlimited");
            open(player);
            return;
        }
        SlotPurchaseService.Result result = purchases.buy(player);
        switch (result.status()) {
            case SUCCESS -> OwnerMessages.send(messages, player, "slots.success",
                    "total", Integer.toString(auctions.resolveMaxSlots(player)),
                    "price", Integer.toString(result.price()), "currency", settings.getGemCurrencyName());
            case MAXED -> OwnerMessages.send(messages, player,
                    auctions.resolveMaxSlots(player) == Integer.MAX_VALUE ? "slots.unlimited" : "slots.maxed");
            case INSUFFICIENT -> OwnerMessages.send(messages, player, "slots.insufficient",
                    "price", Integer.toString(result.price()), "currency", settings.getGemCurrencyName());
            case UNAVAILABLE -> OwnerMessages.send(messages, player, "slots.unavailable");
            case ERROR -> OwnerMessages.send(messages, player, "slots.error");
        }
        open(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private static final class Holder implements InventoryHolder {
        private final UUID viewer;
        private final boolean confirmation;
        private final int owned;
        private final int price;
        private final Inventory inventory;

        private Holder(UUID viewer, boolean confirmation, int owned, int price, String title) {
            this.viewer = viewer;
            this.confirmation = confirmation;
            this.owned = owned;
            this.price = price;
            inventory = org.bukkit.Bukkit.createInventory(this, 27, title);
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
