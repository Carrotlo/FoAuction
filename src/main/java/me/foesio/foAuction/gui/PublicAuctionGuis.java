package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.GuiLoadReport;
import me.foesio.core.gui.publicgui.GuiDiagnostic;
import me.foesio.core.gui.publicgui.PublicGuiClick;
import me.foesio.core.gui.publicgui.PublicGuiContract;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiItemRenderer;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import me.foesio.core.message.FoMessageService;
import me.foesio.foAuction.config.AuctionSettings;
import me.foesio.foAuction.model.AuctionListing;
import me.foesio.foAuction.model.ClaimEntry;
import me.foesio.foAuction.model.FilterMode;
import me.foesio.foAuction.model.SoldAuctionHistoryEntry;
import me.foesio.foAuction.model.SortMode;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.service.PlayerNameCache;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Core-owned public auction inventories. This class supplies domain values, not item presentation. */
public final class PublicAuctionGuis implements AutoCloseable {
    private static final String PREFIX = "auction.";
    private static final Set<String> PLACEHOLDERS = Set.of(
            "page", "max_page", "seller", "buyer", "price", "fee", "time_left", "sold",
            "created", "note", "amount", "current", "container", "target");
    private static final List<String> MENUS = List.of(
            "main", "listings", "seller-view", "claims", "history", "preview",
            "confirmation", "sell-confirmation");

    public record Session(String menu, int page, int maxPage, UUID sellerUuid, int returnPage,
                          UUID listingId, double price, double fee) {
    }

    private final JavaPlugin plugin;
    private final AuctionService auctions;
    private final AuctionSettings settings;
    private final PlayerNameCache names;
    private final FoMessageService messages;
    private PublicGuiService service;
    private Map<String, Set<String>> registeredActions = Map.of();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Consumer<PublicGuiClick> clickHandler;
    private boolean migrationReady = true;
    private DisplayFormats displayFormats;

    private record DisplayFormats(Locale locale, String numberPattern, String compactPattern,
                                  List<String> suffixes, DateTimeFormatter date,
                                  Map<String, List<String>> options) {
    }

    public PublicAuctionGuis(JavaPlugin plugin, AuctionService auctions, AuctionSettings settings,
                             PlayerNameCache names, FoMessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.auctions = Objects.requireNonNull(auctions, "auctions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.names = Objects.requireNonNull(names, "names");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    private PublicGuiService newService(Map<String, Set<String>> menuActions) {
        PublicGuiService candidate = new PublicGuiService(plugin);
        java.util.Set<String> registeredActions = new java.util.HashSet<>();
        for (String menu : MENUS) {
            candidate.menu(PREFIX + menu, installed(menu), contractFor(menu, menuActions.get(menu)));
            candidate.entryPoint("auction:" + menu, PREFIX + menu);
            for (String action : actions(menu)) {
                if (registeredActions.add(action)) {
                    candidate.action(action, this::dispatch);
                }
            }
        }
        candidate.requireEntry(PREFIX + "main", "listings", 0);
        candidate.requireEntry(PREFIX + "listings", "listings", 0);
        candidate.requireEntry(PREFIX + "seller-view", "listings", 0);
        candidate.requireEntry(PREFIX + "claims", "claims", 0);
        candidate.requireEntry(PREFIX + "history", "history", 0);
        candidate.requireEntry(PREFIX + "preview", "preview-item", 1);
        candidate.requireEntry(PREFIX + "preview", "contents", 0);
        candidate.requireEntry(PREFIX + "confirmation", "preview", 1);
        candidate.requireEntry(PREFIX + "sell-confirmation", "preview", 1);
        candidate.requireState(PREFIX + "claims", "money");
        candidate.requireState(PREFIX + "claims", "money-note");
        candidate.requireState(PREFIX + "claims", "invalid");
        for (String menu : List.of("main", "seller-view")) {
            for (String state : List.of("buy", "buy-admin", "container", "container-admin")) {
                candidate.requireEntryLoreState(PREFIX + menu, "listings", state);
            }
        }
        candidate.requireEntryLoreState(PREFIX + "listings", "listings", "owner");
        for (String state : List.of("item", "item-note", "money", "money-note", "invalid")) {
            candidate.requireEntryLoreState(PREFIX + "claims", "claims", state);
        }
        return candidate;
    }

    public void bind(Consumer<PublicGuiClick> handler) {
        if (clickHandler != null) {
            throw new IllegalStateException("Public GUI action handler already bound");
        }
        clickHandler = Objects.requireNonNull(handler, "handler");
    }

    public void migrationReady(boolean ready) {
        migrationReady = ready;
    }

    public GuiLoadReport start() {
        if (clickHandler == null) {
            throw new IllegalStateException("Bind public GUI actions before starting");
        }
        try {
            registeredActions = installedActionMap();
        } catch (IOException | InvalidConfigurationException exception) {
            // Core reports the actual missing or malformed installed file on its first load.
            registeredActions = allActions();
        }
        service = newService(registeredActions);
        service.start(this::validateConditionalButtons);
        GuiLoadReport report = service.reload();
        if (!report.successful()) return report;
        return reloadDisplayFormats();
    }

    public GuiLoadReport reload() {
        DisplayFormats candidate;
        try {
            candidate = parseDisplayFormats();
        } catch (IllegalArgumentException exception) {
            return formatFailure(exception);
        }
        Map<String, Set<String>> nextActions;
        try {
            nextActions = installedActionMap();
        } catch (IOException | InvalidConfigurationException exception) {
            // The current Core service keeps its prior snapshot when this candidate is invalid.
            return service.reload();
        }
        if (nextActions.equals(registeredActions)) {
            GuiLoadReport report = service.reload();
            if (report.successful()) displayFormats = candidate;
            return report;
        }
        PublicGuiService replacement = newService(nextActions);
        GuiLoadReport report;
        try {
            replacement.start(this::validateConditionalButtons);
            // A replacement service has no cache yet, but this is still a reload:
            // reject bad owner edits before its first-load fallback can run.
            report = replacement.validateInstalled();
            if (report.successful()) report = replacement.reload();
        } catch (RuntimeException exception) {
            replacement.close();
            plugin.getLogger().warning("Public GUI action reload failed: " + exception.getMessage());
            return GuiLoadReport.failure(service.snapshot() == null ? 0L : service.snapshot().revision(),
                    List.of(new GuiDiagnostic("guis/", "actions", exception.getMessage())));
        }
        if (!report.successful()) {
            replacement.close();
            return report;
        }
        PublicGuiService previous = service;
        service = replacement;
        registeredActions = nextActions;
        displayFormats = candidate;
        previous.close();
        return report;
    }

    /** Reject malformed owner messages before Core changes the live message cache. */
    public void validateInstalledMessages() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = StrictGuiYaml.load(plugin.getDataFolder().toPath().resolve("messages.yml"));
        for (String path : List.of("gui-values.number-pattern", "gui-values.compact-number-pattern")) {
            Object value = yaml.get(path);
            if (value != null) {
                if (!(value instanceof String pattern)) throw new IllegalArgumentException(path + " must be text");
                new DecimalFormat(pattern);
            }
        }
        Object date = yaml.get("gui-values.date-format");
        if (date != null) {
            if (!(date instanceof String pattern)) throw new IllegalArgumentException("gui-values.date-format must be text");
            DateTimeFormatter.ofPattern(pattern);
        }
        Object zone = yaml.get("gui-values.date-zone");
        if (zone != null) {
            if (!(zone instanceof String configured)) throw new IllegalArgumentException("gui-values.date-zone must be text");
            if (!configured.isBlank() && !configured.equalsIgnoreCase("system")) ZoneId.of(configured);
        }
        Object locale = yaml.get("gui-values.number-locale");
        if (locale != null) {
            if (!(locale instanceof String tag)) throw new IllegalArgumentException("gui-values.number-locale must be text");
            if (!tag.isBlank()) new Locale.Builder().setLanguageTag(tag).build();
        }
        Object suffixes = yaml.get("gui-values.compact-suffixes");
        if (suffixes != null && (!(suffixes instanceof List<?> list)
                || !list.stream().allMatch(String.class::isInstance))) {
            throw new IllegalArgumentException("gui-values.compact-suffixes must be a list of text");
        }
        for (String key : List.of("main.sort", "main.filter", "listings.sort", "seller-view.sort")) {
            Object labels = yaml.get("gui-options." + key);
            int expected = key.equals("main.filter") ? FilterMode.values().length : SortMode.values().length;
            if (labels != null && (!(labels instanceof List<?> list)
                    || !list.stream().allMatch(String.class::isInstance)
                    || (!list.isEmpty() && list.size() != expected))) {
                throw new IllegalArgumentException("gui-options." + key + " needs " + expected + " labels or an empty list");
            }
        }
    }

    private GuiLoadReport reloadDisplayFormats() {
        try {
            displayFormats = parseDisplayFormats();
            return GuiLoadReport.success(service.snapshot().revision());
        } catch (IllegalArgumentException exception) {
            return formatFailure(exception);
        }
    }

    private GuiLoadReport formatFailure(IllegalArgumentException exception) {
        plugin.getLogger().warning("Invalid public GUI messages.yml formatting: " + exception.getMessage());
        long revision = service.snapshot() == null ? 0L : service.snapshot().revision();
        return GuiLoadReport.failure(revision,
                List.of(new GuiDiagnostic("messages.yml", "gui-values/gui-options", exception.getMessage())));
    }

    private DisplayFormats parseDisplayFormats() {
        String localeTag = messages.render("gui-values.number-locale", "");
        Locale locale = localeTag.isBlank() ? Locale.ROOT
                : new Locale.Builder().setLanguageTag(localeTag).build();
        String numberPattern = messages.render("gui-values.number-pattern", "");
        String compactPattern = messages.render("gui-values.compact-number-pattern", "");
        new DecimalFormat(numberPattern, DecimalFormatSymbols.getInstance(locale));
        new DecimalFormat(compactPattern, DecimalFormatSymbols.getInstance(locale));
        String configuredZone = messages.render("gui-values.date-zone", "");
        ZoneId zone = configuredZone.isBlank() || configuredZone.equalsIgnoreCase("system")
                ? ZoneId.systemDefault() : ZoneId.of(configuredZone);
        DateTimeFormatter date = DateTimeFormatter.ofPattern(
                messages.render("gui-values.date-format", "")).withZone(zone);
        List<String> suffixes = me.foesio.foAuction.utils.OwnerMessages.optionalList(
                messages, "gui-values.compact-suffixes");
        Map<String, List<String>> options = new HashMap<>();
        for (String key : List.of("main.sort", "main.filter", "listings.sort", "seller-view.sort")) {
            List<String> labels = me.foesio.foAuction.utils.OwnerMessages.optionalList(
                    messages, "gui-options." + key);
            int expected = key.equals("main.filter") ? FilterMode.values().length : SortMode.values().length;
            if (!labels.isEmpty() && labels.size() != expected) {
                throw new IllegalArgumentException("gui-options." + key + " needs " + expected + " labels");
            }
            options.put(key, List.copyOf(labels));
        }
        return new DisplayFormats(locale, numberPattern, compactPattern,
                List.copyOf(suffixes), date, Map.copyOf(options));
    }

    public Session session(UUID playerId) {
        return sessions.get(playerId);
    }

    public void forget(UUID playerId) {
        sessions.remove(playerId);
    }

    public int listingPageSize() {
        return slots("main", "listings").size();
    }

    public void openMain(Player player, int requestedPage) {
        if (!ready(player)) return;
        PublicGuiDefinition.Button historyControl = definition("main").buttons().get("history-or-clear");
        if (!settings.isPlayerOwnHistoryViewAllowed() && historyControl != null
                && historyControl.enabled() && !historyControl.states().containsKey("clear-search")) {
            me.foesio.foAuction.utils.OwnerMessages.send(messages, player, "gui.unavailable");
            return;
        }
        List<Integer> slots = slots("main", "listings");
        AuctionService.MainGuiView view = auctions.getMainGuiView(player.getUniqueId(), requestedPage, slots.size());
        int page = view.page();
        int maxPage = maxPage(view.totalListings(), slots.size());
        Map<String, String> placeholders = pagePlaceholders(page, maxPage);
        placeholders.put("current", view.searchQuery().isBlank()
                ? messages.render("gui-values.no-search", "") : view.searchQuery());
        placeholders.put("target", messages.render("gui-values.search-target", ""));
        Map<String, String> states = !settings.isPlayerOwnHistoryViewAllowed()
                && enabledButton("main", "history-or-clear")
                ? Map.of("history-or-clear", "clear-search") : Map.of();
        Map<String, PublicGuiText.CycleSelection> cycles = new HashMap<>();
        if (enabledButton("main", "sort"))
            cycles.put("sort", cycle("main.sort", SortMode.values().length, view.sortMode().ordinal()));
        if (enabledButton("main", "filter"))
            cycles.put("filter", cycle("main.filter", FilterMode.values().length, view.filterMode().ordinal()));
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        entries.put("listings", listingEntries(player, view.listings(), slots, false));
        pageButtons(entries, "main", page, maxPage);
        open(player, "main", new Session("main", page, maxPage, null, 0, null, 0, 0),
                new PublicGuiRenderInput(placeholders, states, cycles, entries));
    }

    public void openListings(Player player, int requestedPage) {
        if (!ready(player)) return;
        List<Integer> slots = slots("listings", "listings");
        AuctionService.ListingsView view = auctions.getPlayerListingsView(player.getUniqueId(), requestedPage, slots.size());
        int maxPage = maxPage(view.totalListings(), slots.size());
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        entries.put("listings", listingEntries(player, view.listings(), slots, true));
        pageButtons(entries, "listings", view.page(), maxPage);
        open(player, "listings", new Session("listings", view.page(), maxPage, null, 0, null, 0, 0),
                new PublicGuiRenderInput(pagePlaceholders(view.page(), maxPage), Map.of(),
                        enabledButton("listings", "sort")
                                ? Map.of("sort", cycle("listings.sort", SortMode.values().length,
                                        view.sortMode().ordinal())) : Map.of(), entries));
    }

    public void openSeller(Player player, UUID seller, int requestedPage) {
        if (!ready(player)) return;
        List<Integer> slots = slots("seller-view", "listings");
        AuctionService.ListingsView view = auctions.getSellerListingsView(
                player.getUniqueId(), seller, requestedPage, slots.size());
        int maxPage = maxPage(view.totalListings(), slots.size());
        Map<String, String> placeholders = pagePlaceholders(view.page(), maxPage);
        placeholders.put("seller", names.resolve(seller));
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        entries.put("listings", listingEntries(player, view.listings(), slots, false));
        pageButtons(entries, "seller-view", view.page(), maxPage);
        open(player, "seller-view", new Session("seller-view", view.page(), maxPage, seller, 0, null, 0, 0),
                new PublicGuiRenderInput(placeholders, Map.of(),
                        enabledButton("seller-view", "sort")
                                ? Map.of("sort", cycle("seller-view.sort", SortMode.values().length,
                                        view.sortMode().ordinal())) : Map.of(), entries));
    }

    public void openClaims(Player player, int requestedPage) {
        if (!ready(player)) return;
        List<Integer> slots = slots("claims", "claims");
        List<ClaimEntry> claims = auctions.getClaims(player.getUniqueId());
        int maxPage = maxPage(claims.size(), slots.size());
        int page = clamp(requestedPage, maxPage);
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        Map<Integer, PublicGuiRenderInput.EntryValue> values = new LinkedHashMap<>();
        PublicGuiDefinition definition = definition("claims");
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < claims.size(); index++) {
            ClaimEntry claim = claims.get(start + index);
            Map<String, String> vars = new HashMap<>(messages.tokenValues());
            vars.put("created", date(claim.getCreatedAt()));
            vars.put("note", displayClaimNote(claim.getNote()));
            String state;
            ItemStack source;
            if (claim.getType() == ClaimEntry.Type.MONEY) {
                vars.put("amount", price(claim.getMoney()));
                state = claim.getNote().isBlank() ? "money" : "money-note";
                source = PublicGuiItemRenderer.fixed(player, definition.states().get(state),
                        vars, null, null, "states." + state);
            } else {
                source = claim.getItem();
                if (source == null || source.getType().isAir()) {
                    state = "invalid";
                    source = PublicGuiItemRenderer.fixed(player, definition.states().get(state),
                            vars, null, null, "states." + state);
                } else {
                    state = claim.getNote().isBlank() ? "item" : "item-note";
                }
            }
            values.put(slots.get(index), new PublicGuiRenderInput.EntryValue(
                    claim.getId().toString(), source, vars, state));
        }
        entries.put("claims", values);
        pageButtons(entries, "claims", page, maxPage);
        open(player, "claims", new Session("claims", page, maxPage, null, 0, null, 0, 0),
                new PublicGuiRenderInput(pagePlaceholders(page, maxPage), Map.of(), Map.of(), entries));
    }

    public void openHistory(Player player, UUID seller, int requestedPage, int returnPage) {
        if (!ready(player)) return;
        List<Integer> slots = slots("history", "history");
        List<SoldAuctionHistoryEntry> history = auctions.getSoldHistory(seller);
        int maxPage = maxPage(history.size(), slots.size());
        int page = clamp(requestedPage, maxPage);
        Map<String, String> placeholders = pagePlaceholders(page, maxPage);
        placeholders.put("seller", names.resolve(seller));
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        Map<Integer, PublicGuiRenderInput.EntryValue> values = new LinkedHashMap<>();
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < history.size(); index++) {
            SoldAuctionHistoryEntry entry = history.get(start + index);
            Map<String, String> vars = Map.of(
                    "seller", names.resolve(entry.getSellerUuid()),
                    "buyer", names.resolve(entry.getBuyerUuid()),
                    "price", price(entry.getPrice()),
                    "sold", date(entry.getSoldAt()));
            values.put(slots.get(index), new PublicGuiRenderInput.EntryValue(
                    Integer.toString(start + index), entry.getItem(), vars));
        }
        entries.put("history", values);
        if (history.isEmpty()) {
            List<Integer> emptySlots = optionalSlots("history", "empty");
            if (!emptySlots.isEmpty()) {
                entries.put("empty", Map.of(emptySlots.getFirst(),
                        new PublicGuiRenderInput.EntryValue("empty", null, Map.of())));
            }
        }
        pageButtons(entries, "history", page, maxPage);
        open(player, "history", new Session("history", page, maxPage, seller, returnPage, null, 0, 0),
                new PublicGuiRenderInput(placeholders, Map.of(), Map.of(), entries));
    }

    public boolean openPreview(Player player, UUID listingId, int returnPage, UUID seller) {
        if (!ready(player)) return false;
        AuctionListing listing = auctions.getListing(listingId);
        if (listing == null) {
            return false;
        }
        ItemStack item = listing.getItem();
        ItemStack[] contents = AuctionGuiManager.extractContainerContents(item);
        if (!AuctionGuiManager.hasContainerItems(contents)) {
            return false;
        }
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new HashMap<>();
        entries.put("preview-item", Map.of(slots("preview", "preview-item").getFirst(),
                new PublicGuiRenderInput.EntryValue(listingId.toString(), item, Map.of())));
        Map<Integer, PublicGuiRenderInput.EntryValue> contentValues = new LinkedHashMap<>();
        List<Integer> contentSlots = slots("preview", "contents");
        for (int index = 0; index < contentSlots.size() && index < contents.length; index++) {
            if (contents[index] != null && !contents[index].getType().isAir()) {
                contentValues.put(contentSlots.get(index), new PublicGuiRenderInput.EntryValue(
                        Integer.toString(index), contents[index], Map.of()));
            }
        }
        entries.put("contents", contentValues);
        String container = containerName(item);
        open(player, "preview", new Session("preview", 0, 0, seller, returnPage, listingId, 0, 0),
                new PublicGuiRenderInput(withTokens(Map.of("container", container)), Map.of(), Map.of(), entries));
        return true;
    }

    public void openSellConfirmation(Player player, ItemStack item, double price, double fee) {
        if (!ready(player)) return;
        Map<String, String> vars = Map.of("price", price(price), "fee", price(fee));
        Map<Integer, PublicGuiRenderInput.EntryValue> preview = Map.of(
                slots("sell-confirmation", "preview").getFirst(),
                new PublicGuiRenderInput.EntryValue("sell", item, Map.of()));
        open(player, "sell-confirmation", new Session("sell-confirmation", 0, 0, null, 0, null, price, fee),
                new PublicGuiRenderInput(withTokens(vars), Map.of(), Map.of(), Map.of("preview", preview)));
    }

    public boolean openBuyConfirmation(Player player, UUID listingId, int returnPage, UUID seller) {
        if (!ready(player)) return false;
        AuctionListing listing = auctions.getListing(listingId);
        if (listing == null) {
            return false;
        }
        Map<String, String> vars = Map.of("price", price(listing.getPrice()), "fee", "",
                "seller", names.resolve(listing.getSellerUuid()));
        Map<Integer, PublicGuiRenderInput.EntryValue> preview = Map.of(
                slots("confirmation", "preview").getFirst(),
                new PublicGuiRenderInput.EntryValue(listingId.toString(), listing.getItem(), Map.of()));
        open(player, "confirmation", new Session("confirmation", 0, 0, seller,
                        returnPage, listingId, listing.getPrice(), 0),
                new PublicGuiRenderInput(withTokens(vars), Map.of(), Map.of(), Map.of("preview", preview)));
        return true;
    }

    private void dispatch(PublicGuiClick click) {
        Consumer<PublicGuiClick> handler = clickHandler;
        if (handler != null) {
            handler.accept(click);
        }
    }

    private PublicGuiService.PreparedDependency validateConditionalButtons(
            Map<String, PublicGuiDefinition> definitions) {
        if (!settings.isPlayerOwnHistoryViewAllowed()) {
            PublicGuiDefinition main = definitions.get(PREFIX + "main");
            PublicGuiDefinition.Button control = main == null ? null
                    : main.buttons().get("history-or-clear");
            if (control != null && control.enabled() && !control.states().containsKey("clear-search")) {
                return new PublicGuiService.PreparedDependency(List.of(new GuiDiagnostic(
                        "guis/main.yml", "buttons.history-or-clear.states.clear-search",
                        "clear-search state is required while player history is disabled; disable or remove the button to hide it")),
                        () -> { });
            }
        }
        return PublicGuiService.PreparedDependency.ready(() -> { });
    }

    private boolean ready(Player player) {
        if (migrationReady && service.snapshot() != null && displayFormats != null) return true;
        me.foesio.foAuction.utils.OwnerMessages.send(messages, player, "gui.unavailable");
        return false;
    }

    private void open(Player player, String menu, Session session, PublicGuiRenderInput input) {
        Session previous = sessions.put(player.getUniqueId(), session);
        try {
            service.openEntryPoint(player, "auction:" + menu, input);
        } catch (RuntimeException exception) {
            if (previous == null) {
                sessions.remove(player.getUniqueId());
            } else {
                sessions.put(player.getUniqueId(), previous);
            }
            throw exception;
        }
    }

    private PublicGuiDefinition definition(String menu) {
        if (service.snapshot() == null) {
            throw new IllegalStateException("Public auction GUI " + menu + " is unavailable");
        }
        return service.snapshot().require(PREFIX + menu);
    }

    private List<Integer> slots(String menu, String entry) {
        PublicGuiDefinition.Entry region = definition(menu).entries().get(entry);
        if (region == null || region.slots().isEmpty()) {
            throw new IllegalStateException("Missing public GUI entry region " + menu + ":" + entry);
        }
        return region.slots();
    }

    private Map<Integer, PublicGuiRenderInput.EntryValue> listingEntries(
            Player viewer, List<AuctionListing> listings, List<Integer> slots, boolean ownerView) {
        Map<Integer, PublicGuiRenderInput.EntryValue> values = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < slots.size() && index < listings.size(); index++) {
            AuctionListing listing = listings.get(index);
            ItemStack source = listing.getItem();
            boolean container = AuctionGuiManager.hasContainerItems(
                    AuctionGuiManager.extractContainerContents(source));
            String state = ownerView ? "owner" : (container ? "container" : "buy");
            if (!ownerView && viewer.hasPermission("foauction.admin")) {
                state += "-admin";
            }
            Map<String, String> vars = Map.of(
                    "seller", names.resolve(listing.getSellerUuid()),
                    "price", price(listing.getPrice()),
                    "time_left", duration(listing.getExpiresAt() - now));
            values.put(slots.get(index), new PublicGuiRenderInput.EntryValue(
                    listing.getId().toString(), source, vars, state));
        }
        return values;
    }

    private void pageButtons(Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries,
                             String menu, int page, int maxPage) {
        List<Integer> previous = optionalSlots(menu, "previous");
        List<Integer> next = optionalSlots(menu, "next");
        if (page > 0 && !previous.isEmpty()) {
            entries.put("previous", Map.of(previous.getFirst(),
                    new PublicGuiRenderInput.EntryValue("previous", null, Map.of())));
        }
        if (page < maxPage && !next.isEmpty()) {
            entries.put("next", Map.of(next.getFirst(),
                    new PublicGuiRenderInput.EntryValue("next", null, Map.of())));
        }
    }

    private List<Integer> optionalSlots(String menu, String entry) {
        PublicGuiDefinition.Entry region = definition(menu).entries().get(entry);
        return region == null ? List.of() : region.slots();
    }

    private boolean enabledButton(String menu, String button) {
        PublicGuiDefinition.Button configured = definition(menu).buttons().get(button);
        return configured != null && configured.enabled();
    }

    private PublicGuiText.CycleSelection cycle(String key, int expected, int selected) {
        List<String> labels = displayFormats.options().getOrDefault(key, List.of());
        if (labels.isEmpty()) return new PublicGuiText.CycleSelection(List.of(), -1);
        return new PublicGuiText.CycleSelection(labels, selected);
    }

    private Map<String, String> pagePlaceholders(int page, int maxPage) {
        Map<String, String> values = new HashMap<>(messages.tokenValues());
        values.put("page", Integer.toString(page + 1));
        values.put("max_page", Integer.toString(maxPage + 1));
        return values;
    }

    private Map<String, String> withTokens(Map<String, String> domainValues) {
        Map<String, String> values = new HashMap<>(messages.tokenValues());
        values.putAll(domainValues);
        return values;
    }

    private int maxPage(int total, int pageSize) {
        return total <= 0 ? 0 : (total - 1) / pageSize;
    }

    private int clamp(int requestedPage, int maxPage) {
        return Math.max(0, Math.min(requestedPage, maxPage));
    }

    private String price(double value) {
        DisplayFormats formats = displayFormats;
        double displayed = value;
        String suffix = "";
        boolean abbreviated = false;
        if (settings.isCompactPriceFormatEnabled() && Math.abs(value) >= 1_000D) {
            List<String> suffixes = formats.suffixes();
            if (!suffixes.isEmpty()) {
                abbreviated = true;
                int tier = 0;
                displayed /= 1_000D;
                while (Math.abs(displayed) >= 1_000D && tier + 1 < suffixes.size()) {
                    displayed /= 1_000D;
                    tier++;
                }
                suffix = suffixes.get(tier);
            }
        }
        DecimalFormat format = new DecimalFormat(abbreviated
                ? formats.compactPattern() : formats.numberPattern(),
                DecimalFormatSymbols.getInstance(formats.locale()));
        return format.format(displayed) + suffix;
    }

    private String date(long epochMillis) {
        return displayFormats.date().format(Instant.ofEpochMilli(epochMillis));
    }

    private String duration(long millis) {
        if (millis <= 0) return messages.render("gui-values.time-expired", "");
        Duration remaining = Duration.ofMillis(millis);
        long days = remaining.toDays();
        long hours = remaining.minusDays(days).toHours();
        long minutes = remaining.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0) return messages.render("gui-values.time-days-hours", "",
                "days", Long.toString(days), "hours", Long.toString(hours));
        if (hours > 0) return messages.render("gui-values.time-hours-minutes", "",
                "hours", Long.toString(hours), "minutes", Long.toString(minutes));
        return messages.render("gui-values.time-minutes", "", "minutes", Long.toString(minutes));
    }

    private String containerName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String custom = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            if (custom != null && !custom.isBlank()) return custom;
        }
        String key = "gui-values.containers." + item.getType().name().toLowerCase(Locale.ROOT);
        return messages.render(key, "");
    }

    private String displayClaimNote(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String key = switch (raw) {
            case "@foauction:claim-delivery-fallback", "Claim delivery fallback" -> "claim-delivery-fallback";
            case "@foauction:admin-removed", "Admin removed listing" -> "admin-removed";
            case "@foauction:listing-expired", "Listing expired" -> "listing-expired";
            case "@foauction:inventory-full", "Inventory full fallback" -> "inventory-full";
            case "@foauction:player-offline", "Player offline" -> "player-offline";
            default -> null;
        };
        if (key != null) return messages.render("claim-notes." + key, "");
        String prefix = "@foauction:purchased-from:";
        if (raw.startsWith(prefix)) {
            try {
                return messages.render("claim-notes.purchased-from", "", "seller",
                        names.resolve(UUID.fromString(raw.substring(prefix.length()))));
            } catch (IllegalArgumentException ignored) {
                return raw;
            }
        }
        if (raw.startsWith("Purchased from ")) {
            return messages.render("claim-notes.purchased-from", "", "seller",
                    raw.substring("Purchased from ".length()));
        }
        return raw;
    }

    private Path installed(String menu) {
        return plugin.getDataFolder().toPath().resolve("guis").resolve(menu + ".yml");
    }

    private Map<String, Set<String>> installedActionMap()
            throws IOException, InvalidConfigurationException {
        Map<String, Set<String>> discovered = new LinkedHashMap<>();
        for (String menu : MENUS) {
            discovered.put(menu, runtimeActions(menu, StrictGuiYaml.load(installed(menu)),
                    bundledMenu(menu)));
        }
        return Map.copyOf(discovered);
    }

    private YamlConfiguration bundledMenu(String menu) throws IOException {
        try (InputStream input = plugin.getResource("guis/" + menu + ".yml")) {
            if (input == null) throw new IOException("Missing bundled GUI " + menu);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException exception) {
                throw new IOException("Invalid bundled GUI " + menu, exception);
            }
            return yaml;
        }
    }

    private static Map<String, Set<String>> allActions() {
        Map<String, Set<String>> defaults = new LinkedHashMap<>();
        for (String menu : MENUS) defaults.put(menu, actionsFor(menu));
        return Map.copyOf(defaults);
    }

    static Set<String> actionsIn(String menu, YamlConfiguration yaml) {
        Set<String> allowed = actionsFor(menu);
        java.util.Set<String> actual = new java.util.LinkedHashSet<>();
        for (String group : List.of("buttons", "entries")) {
            ConfigurationSection sections = yaml.getConfigurationSection(group);
            if (sections == null) continue;
            for (String id : sections.getKeys(false)) {
                ConfigurationSection section = sections.getConfigurationSection(id);
                if (section == null) continue;
                Object direct = section.get("action");
                if (direct instanceof String action && allowed.contains(action)) actual.add(action);
                ConfigurationSection clicks = section.getConfigurationSection("actions");
                if (clicks == null) continue;
                for (String click : clicks.getKeys(false)) {
                    Object value = clicks.get(click);
                    if (value instanceof String action && allowed.contains(action)) actual.add(action);
                }
            }
        }
        return Set.copyOf(actual);
    }

    static Set<String> runtimeActions(String menu, YamlConfiguration owner,
                                      YamlConfiguration bundled) {
        java.util.Set<String> allowed = actionsFor(menu);
        java.util.Set<String> result = new java.util.LinkedHashSet<>(actionsIn(menu, owner));
        for (String group : List.of("buttons", "entries")) {
            if (owner.contains(group, false) && !owner.isConfigurationSection(group)) {
                result.addAll(actionsIn(menu, bundled));
                continue;
            }
            ConfigurationSection sections = owner.getConfigurationSection(group);
            if (sections == null) continue;
            for (String id : sections.getKeys(false)) {
                String path = group + "." + id;
                ConfigurationSection section = owner.getConfigurationSection(path);
                ConfigurationSection defaultSection = bundled.getConfigurationSection(path);
                if (section == null) {
                    if (defaultSection != null) result.addAll(actionsForSection(defaultSection, allowed));
                    continue;
                }
                if (section.contains("action", false)
                        && !knownAction(section.get("action"), allowed)) {
                    addDefaultAction(result, defaultSection, "action", allowed);
                }
                if (section.contains("actions", false) && !section.isConfigurationSection("actions")) {
                    if (defaultSection != null) {
                        ConfigurationSection defaults = defaultSection.getConfigurationSection("actions");
                        if (defaults != null) {
                            for (String click : defaults.getKeys(false)) {
                                addDefaultAction(result, defaults, click, allowed);
                            }
                        }
                    }
                    continue;
                }
                ConfigurationSection clicks = section.getConfigurationSection("actions");
                ConfigurationSection defaultClicks = defaultSection == null ? null
                        : defaultSection.getConfigurationSection("actions");
                if (clicks == null) continue;
                for (String click : clicks.getKeys(false)) {
                    if (!knownAction(clicks.get(click), allowed)) {
                        addDefaultAction(result, defaultClicks, click, allowed);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private static boolean knownAction(Object value, Set<String> allowed) {
        return value instanceof String action && allowed.contains(action);
    }

    private static Set<String> actionsForSection(ConfigurationSection section, Set<String> allowed) {
        java.util.Set<String> actions = new java.util.LinkedHashSet<>();
        addDefaultAction(actions, section, "action", allowed);
        ConfigurationSection clicks = section.getConfigurationSection("actions");
        if (clicks != null) {
            for (String click : clicks.getKeys(false)) addDefaultAction(actions, clicks, click, allowed);
        }
        return actions;
    }

    private static void addDefaultAction(Set<String> result, ConfigurationSection section,
                                         String key, Set<String> allowed) {
        if (section == null) return;
        Object value = section.get(key);
        if (knownAction(value, allowed)) result.add((String) value);
    }

    static PublicGuiContract contractFor(String menu) {
        return contractFor(menu, actionsFor(menu));
    }

    static PublicGuiContract contractFor(String menu, Set<String> actions) {
        return new PublicGuiContract(actions, Map.of(), PLACEHOLDERS,
                PublicGuiContract.InteractionMode.CORE);
    }

    private Set<String> actions(String menu) {
        return actionsFor(menu);
    }

    private static Set<String> actionsFor(String menu) {
        return switch (menu) {
            case "main" -> Set.of("main.claims", "main.sort", "main.filter", "main.refresh",
                    "main.manage", "main.search", "main.history-or-clear", "main.previous", "main.next",
                    "listing.select", "listing.preview", "listing.admin");
            case "listings" -> Set.of("listings.back", "listings.sort", "listings.refresh",
                    "listings.previous", "listings.next", "listing.cancel");
            case "seller-view" -> Set.of("seller-view.back", "seller-view.sort", "seller-view.refresh",
                    "seller-view.previous", "seller-view.next", "listing.select", "listing.preview", "listing.admin");
            case "claims" -> Set.of("claims.back", "claims.refresh", "claims.previous", "claims.next",
                    "claims.claim");
            case "history" -> Set.of("history.back", "history.previous", "history.next");
            case "preview" -> Set.of("preview.back");
            case "confirmation" -> Set.of("confirmation.cancel", "confirmation.confirm");
            case "sell-confirmation" -> Set.of("confirmation.confirm");
            default -> throw new IllegalArgumentException("Unknown public GUI menu " + menu);
        };
    }

    @Override
    public void close() {
        if (service != null) service.close();
        sessions.clear();
    }
}
