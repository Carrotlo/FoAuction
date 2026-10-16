package me.foesio.foAuction.config;

import me.foesio.core.config.ConfigValueLists;
import me.foesio.core.config.FoConfigDefaults;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.foAuction.FoAuction;
import me.foesio.foAuction.utils.InputValidationUtils;
import me.foesio.foAuction.utils.ColorPalette;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

public final class AuctionSettings {
    private static final double ABSOLUTE_MIN_PRICE = 0.01D;
    /** Auction prices use finite doubles throughout persistence and Vault. */
    private static final double ABSOLUTE_MAX_PRICE = Double.MAX_VALUE;
    private static final int FIXED_EXPIRE_DAYS = 7;

    private final JavaPlugin plugin;

    private int expireDays;
    private long expireMillis;
    private double minPrice;
    private double maxPrice;
    private double listingFeePercent;
    private int defaultMaxSlots;
    private long guiRefreshCooldownMillis;
    private boolean confirmationGuiEnabled;
    private boolean compactPriceFormatEnabled;
    private boolean fileLoggingEnabled;
    private boolean allowPlayerOwnHistoryView;
    private Set<Material> blacklistedMaterials;
    private List<String> blacklistedNameContains;
    private boolean discordWebhookEnabled;
    private String discordWebhookUrl;
    private boolean discordWebhookSellingEnabled;
    private boolean discordWebhookListingEnabled;
    private boolean discordWebhookAdminRemovingEnabled;
    private boolean discordWebhookClaimsEnabled;
    private boolean discordWebhookCancellingListingsEnabled;
    private boolean discordWebhookClaimingListingsEnabled;

    public AuctionSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FoConfigDefaults.ensureDefaultConfig(plugin);
        FileConfiguration config = plugin.getConfig();

        applyDefaults(config);
        config.options().copyDefaults(true);
        migrateLegacyListingFee(config);

        // Validate configuration values and apply bounds
        validateAndApplyConfigValues(config);

        plugin.saveConfig();
    }

    public int getExpireDays() {
        return expireDays;
    }

    public int getExpireHours() {
        return (int) Math.max(1L, expireMillis / (60L * 60L * 1000L));
    }

    public long getExpireMillis() {
        return expireMillis;
    }

    public double getMinPrice() {
        return minPrice;
    }

    public double getMaxPrice() {
        return maxPrice;
    }

    public double getListingFeePercent() {
        return listingFeePercent;
    }


    public int getDefaultMaxSlots() {
        return defaultMaxSlots;
    }

    public long getGuiRefreshCooldownMillis() {
        return guiRefreshCooldownMillis;
    }

    public boolean isConfirmationGuiEnabled() {
        return confirmationGuiEnabled;
    }

    public boolean isCompactPriceFormatEnabled() {
        return compactPriceFormatEnabled;
    }

    public boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    public boolean isPlayerOwnHistoryViewAllowed() {
        return allowPlayerOwnHistoryView;
    }

    public Set<Material> getBlacklistedMaterials() {
        return blacklistedMaterials;
    }

    public List<String> getBlacklistedNameContains() {
        return blacklistedNameContains;
    }

    public boolean isDiscordWebhookEnabled() {
        return discordWebhookEnabled;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public boolean isDiscordWebhookSellingEnabled() {
        return discordWebhookSellingEnabled;
    }

    public boolean isDiscordWebhookListingEnabled() {
        return discordWebhookListingEnabled;
    }

    public boolean isDiscordWebhookAdminRemovingEnabled() {
        return discordWebhookAdminRemovingEnabled;
    }

    public boolean isDiscordWebhookClaimsEnabled() {
        return discordWebhookClaimsEnabled;
    }

    public boolean isDiscordWebhookCancellingListingsEnabled() {
        return discordWebhookCancellingListingsEnabled;
    }

    public boolean isDiscordWebhookClaimingListingsEnabled() {
        return discordWebhookClaimingListingsEnabled;
    }

    private double clampPercentage(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(100D, value));
    }

    private double sanitizeFinite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private double getFiniteDouble(FileConfiguration config, String path, double fallback) {
        Object rawValue = config.get(path);
        if (rawValue instanceof Number number) {
            return sanitizeFinite(number.doubleValue(), fallback);
        }
        if (rawValue instanceof String string) {
            return LargeNumberParser.parseDouble(string.trim())
                    .stream()
                    .findFirst()
                    .orElse(fallback);
        }
        return fallback;
    }

    private double getFinitePrice(FileConfiguration config, String path, double fallback) {
        Object rawValue = config.get(path);
        if (rawValue instanceof Number number) {
            return sanitizeFinite(number.doubleValue(), fallback);
        }
        if (rawValue instanceof String string) {
            try {
                return sanitizeFinite(InputValidationUtils.validatePrice(string), fallback);
            } catch (InputValidationUtils.InvalidInputException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void applyDefaults(FileConfiguration config) {
        config.addDefault("auction.min-price", ABSOLUTE_MIN_PRICE);
        config.addDefault("auction.max-price", ABSOLUTE_MAX_PRICE);
        config.addDefault("auction.listing-fee-percent", 0D);
        config.addDefault("auction.default-max-slots", 3);
        config.addDefault("auction.expire-days", FIXED_EXPIRE_DAYS);
        config.addDefault("gui.refresh-cooldown-millis", 500L);
        config.addDefault("gui.confirmation-gui-enabled", true);
        config.addDefault("gui.compact-price-format", true);
        config.addDefault("file-logging", false);

        config.addDefault("history.allow-player-own-view", true);
        config.addDefault("blacklisted-items.material", List.of("BEDROCK"));
        config.addDefault("blacklisted-items.name-contains", List.of("banned"));
        String normalizedWebhookUrl = normalizeWebhookUrl(config);
        config.set("discord-webhook.webhook-url", normalizedWebhookUrl);
        config.set("discord-webhook.webhook", null);
        config.set("discord-webhook.url", null);

        config.addDefault("discord-webhook.enabled", false);
        config.addDefault("discord-webhook.webhook-url", "WEBHOOK_HERE");
        config.addDefault("discord-webhook.selling", true);
        config.addDefault("discord-webhook.listing", true);
        config.addDefault("discord-webhook.admin-removing", true);
        config.addDefault("discord-webhook.claims", true);
        config.addDefault("discord-webhook.cancelling-listings", true);
        config.addDefault("discord-webhook.claiming-listings", false);
    }

    private void migrateLegacyListingFee(FileConfiguration config) {
        if (config.contains("auction.listing-fee-percent")) {
            return;
        }
        if (!config.contains("auction.listing-fee")) {
            return;
        }
        config.set("auction.listing-fee-percent", clampPercentage(config.getDouble("auction.listing-fee", 0D)));
    }

    private String normalizeWebhookUrl(FileConfiguration config) {
        String webhookUrl = trimToEmpty(config.getString("discord-webhook.webhook-url", ""));
        if (!webhookUrl.isBlank()) {
            return normalizeWebhookPlaceholder(webhookUrl);
        }

        String legacyWebhook = trimToEmpty(config.getString("discord-webhook.webhook", ""));
        if (!legacyWebhook.isBlank()) {
            return normalizeWebhookPlaceholder(legacyWebhook);
        }

        String legacyUrl = trimToEmpty(config.getString("discord-webhook.url", ""));
        if (!legacyUrl.isBlank()) {
            return normalizeWebhookPlaceholder(legacyUrl);
        }

        return "WEBHOOK_HERE";
    }

    private String normalizeWebhookPlaceholder(String webhookUrl) {
        return "WEBOOK_HERE".equalsIgnoreCase(webhookUrl) ? "WEBHOOK_HERE" : webhookUrl;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Set<Material> parseBlacklistedMaterials(FileConfiguration config) {
        return ConfigValueLists.materials(
                plugin,
                config,
                "blacklisted-items.material",
                material -> material.isItem() && !material.isAir(),
                "blacklisted material"
        );
    }

    private void validateAndApplyConfigValues(FileConfiguration config) {
        // Expiry settings validation
        int configuredExpireDays = Math.max(1, Math.min(30, config.getInt("auction.expire-days", FIXED_EXPIRE_DAYS)));
        expireDays = configuredExpireDays;
        expireMillis = configuredExpireDays * 24L * 60L * 60L * 1000L;

        // Price settings validation with bounds
        double configuredMinPrice = getFinitePrice(config, "auction.min-price", ABSOLUTE_MIN_PRICE);
        minPrice = Math.max(ABSOLUTE_MIN_PRICE, Math.min(configuredMinPrice, ABSOLUTE_MAX_PRICE));

        double configuredMaxPrice = getFinitePrice(config, "auction.max-price", ABSOLUTE_MAX_PRICE);
        maxPrice = Math.max(minPrice, Math.min(configuredMaxPrice, ABSOLUTE_MAX_PRICE));

        // Ensure min price is less than max price
        if (minPrice >= maxPrice) {
            plugin.getLogger().warning(ColorPalette.log("Min price (" + minPrice + ") is greater than or equal to max price (" + maxPrice + "). Using defaults."));
            FoAuction.fileLogger().warn("Invalid auction price range in config; using defaults.");
            minPrice = ABSOLUTE_MIN_PRICE;
            maxPrice = ABSOLUTE_MAX_PRICE;
        }
        config.set("auction.min-price", minPrice);
        config.set("auction.max-price", maxPrice);

        // Fee percentage validation
        if (config.contains("auction.listing-fee-percent")) {
            listingFeePercent = clampPercentage(getFiniteDouble(config, "auction.listing-fee-percent", 0D));
        } else {
            listingFeePercent = clampPercentage(getFiniteDouble(config, "auction.listing-fee", 0D));
        }
        config.set("auction.listing-fee-percent", listingFeePercent);

        // Slots validation
        defaultMaxSlots = Math.max(1, config.getInt("auction.default-max-slots", 3));
        config.set("auction.default-max-slots", defaultMaxSlots);

        // Cooldown validation
        guiRefreshCooldownMillis = Math.max(0L, config.getLong("gui.refresh-cooldown-millis", 500L));
        config.set("gui.refresh-cooldown-millis", guiRefreshCooldownMillis);

        // Boolean settings
        confirmationGuiEnabled = config.getBoolean("gui.confirmation-gui-enabled", true);
        compactPriceFormatEnabled = config.getBoolean("gui.compact-price-format", true);
        fileLoggingEnabled = config.getBoolean("file-logging", false);
        allowPlayerOwnHistoryView = config.getBoolean("history.allow-player-own-view", true);

        // Legacy config.yml prefix moved to messages.yml tokens.
        config.set("feedback", null);

        // Blacklist validation
        blacklistedMaterials = parseBlacklistedMaterials(config);
        blacklistedNameContains = config.getStringList("blacklisted-items.name-contains").stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> s.length() <= 100) // Limit blacklist entries
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .toList();

        // Discord webhook validation
        discordWebhookEnabled = config.getBoolean("discord-webhook.enabled", false);
        String webhookUrl = config.getString("discord-webhook.webhook-url", "WEBHOOK_HERE");
        String trimmedWebhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (!discordWebhookEnabled) {
            discordWebhookUrl = trimmedWebhookUrl;
        } else if (!trimmedWebhookUrl.isEmpty()) {
            // Basic URL validation
            if (trimmedWebhookUrl.startsWith("http://") || trimmedWebhookUrl.startsWith("https://")) {
                discordWebhookUrl = trimmedWebhookUrl;
            } else {
                plugin.getLogger().warning(ColorPalette.log("Invalid Discord webhook URL format. Using empty URL."));
                FoAuction.fileLogger().warn("Invalid Discord webhook URL format in config; webhook disabled until fixed.");
                discordWebhookUrl = "";
            }
        } else {
            discordWebhookUrl = "";
        }

        discordWebhookSellingEnabled = config.getBoolean("discord-webhook.selling", true);
        discordWebhookListingEnabled = config.getBoolean("discord-webhook.listing", true);
        discordWebhookAdminRemovingEnabled = config.getBoolean("discord-webhook.admin-removing", true);
        discordWebhookClaimsEnabled = config.getBoolean("discord-webhook.claims", true);
        discordWebhookCancellingListingsEnabled = config.getBoolean("discord-webhook.cancelling-listings", true);
        discordWebhookClaimingListingsEnabled = config.getBoolean("discord-webhook.claiming-listings", false);
    }
}
