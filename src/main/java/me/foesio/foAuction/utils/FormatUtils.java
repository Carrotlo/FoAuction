package me.foesio.foAuction.utils;

import me.foesio.core.number.NumberFormatters;
import me.foesio.core.message.FoMessageService;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class FormatUtils {
    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,##0.####");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private FormatUtils() {
    }

    private static volatile FoMessageService configuredMessages;

    public static void configure(FoMessageService messages) {
        configuredMessages = messages;
    }

    public static String formatPrice(double value) {
        FoMessageService messages = configuredMessages;
        if (messages == null) return PRICE_FORMAT.format(value);
        return number(messages, value, false);
    }

    public static String formatPrice(double value, boolean compact) {
        FoMessageService messages = configuredMessages;
        if (messages != null) return number(messages, value, compact);
        if (!compact || Math.abs(value) < 1_000D) {
            return formatPrice(value);
        }
        return NumberFormatters.compact(value);
    }

    private static String number(FoMessageService messages, double value, boolean compact) {
        String localeTag = messages.render("gui-values.number-locale", "");
        Locale locale = localeTag.isBlank() ? Locale.ROOT
                : new Locale.Builder().setLanguageTag(localeTag).build();
        double shown = value;
        String suffix = "";
        boolean abbreviated = false;
        if (compact && Math.abs(value) >= 1_000D) {
            java.util.List<String> suffixes = OwnerMessages.optionalList(
                    messages, "gui-values.compact-suffixes");
            if (!suffixes.isEmpty()) {
                abbreviated = true;
                int tier = 0;
                shown /= 1_000D;
                while (Math.abs(shown) >= 1_000D && tier + 1 < suffixes.size()) {
                    shown /= 1_000D;
                    tier++;
                }
                suffix = suffixes.get(tier);
            }
        }
        String pattern = messages.render(abbreviated
                ? "gui-values.compact-number-pattern" : "gui-values.number-pattern", "");
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).format(shown) + suffix;
    }

    public static String formatDuration(long millis) {
        FoMessageService messages = configuredMessages;
        if (messages != null) {
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
        if (millis <= 0) {
            return "Expired";
        }

        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        duration = duration.minusDays(days);
        long hours = duration.toHours();
        duration = duration.minusHours(hours);
        long minutes = duration.toMinutes();

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    public static String formatDateTime(long epochMillis) {
        FoMessageService messages = configuredMessages;
        if (messages != null) {
            String pattern = messages.render("gui-values.date-format", "");
            String configuredZone = messages.render("gui-values.date-zone", "");
            ZoneId zone = configuredZone.isBlank() || configuredZone.equalsIgnoreCase("system")
                    ? ZoneId.systemDefault() : ZoneId.of(configuredZone);
            return DateTimeFormatter.ofPattern(pattern).withZone(zone)
                    .format(Instant.ofEpochMilli(epochMillis));
        }
        return DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }
}
