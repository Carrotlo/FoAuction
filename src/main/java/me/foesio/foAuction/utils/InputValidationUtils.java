package me.foesio.foAuction.utils;

import me.foesio.core.number.LargeNumberParser;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for validating user inputs to prevent security vulnerabilities
 * including injection attacks, overflow attacks, and malformed data.
 */
public final class InputValidationUtils {

    // Constants for validation limits
    private static final int MAX_STRING_LENGTH = 256;
    public static final int MAX_SEARCH_QUERY_LENGTH = 100;
    /**
     * Auction prices are stored and passed through Vault as finite doubles.
     * Keep validation aligned with that representation instead of the old
     * 999,999,999,999,999 ceiling, which rejected values such as 1000Td.
     */
    private static final BigDecimal MAX_SAFE_PRICE = BigDecimal.valueOf(Double.MAX_VALUE);
    private static final BigDecimal MIN_SAFE_PRICE = new BigDecimal("0.01");

    // Patterns for validation
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d{1,6})?)(k|m|b|t|qa|qi|sx|sp|oc|no|dc|ud|dd|td)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SAFE_STRING_PATTERN = Pattern.compile("^[\\w\\s\\-._]+$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");

    private InputValidationUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates and sanitizes a price input string
     * @param priceStr The price string to validate
     * @return The validated price as double
     * @throws InvalidInputException if the price is invalid
     */
    public static double validatePrice(String priceStr) throws InvalidInputException {
        if (priceStr == null || priceStr.trim().isEmpty()) {
            throw new InvalidInputException("Price cannot be empty", "price-empty");
        }

        String cleanPrice = priceStr.trim();

        try {
            BigDecimal price = parsePrice(cleanPrice);

            if (price.compareTo(MIN_SAFE_PRICE) < 0) {
                throw new InvalidInputException("Price too low (minimum: " + MIN_SAFE_PRICE + ")", "price-too-low");
            }

            if (price.compareTo(MAX_SAFE_PRICE) > 0) {
                throw new InvalidInputException("Price too high (maximum: " + MAX_SAFE_PRICE + ")", "price-too-high");
            }

            return price.doubleValue();
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Invalid price format", "price-format");
        }
    }

    private static BigDecimal parsePrice(String cleanPrice) throws InvalidInputException {
        Matcher matcher = PRICE_PATTERN.matcher(cleanPrice);
        if (!matcher.matches()) {
            throw new InvalidInputException("Invalid price format (examples: 500, 50K, 1.5M, 1Qa)", "price-format");
        }

        return LargeNumberParser.parse(cleanPrice)
                .orElseThrow(() -> new InvalidInputException("Invalid price format", "price-format"));
    }

    /**
     * Validates and sanitizes a search query
     * @param query The search query to validate
     * @return The sanitized search query
     * @throws InvalidInputException if the query is invalid
     */
    public static String validateSearchQuery(String query) throws InvalidInputException {
        if (query == null) {
            return "";
        }

        String trimmed = query.trim();

        if (trimmed.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new InvalidInputException("Search query too long (maximum: " + MAX_SEARCH_QUERY_LENGTH + " characters)", "search-too-long");
        }

        // Remove potentially dangerous characters
        String sanitized = trimmed.replaceAll("[<>\"'&]", "");

        return sanitized;
    }

    /**
     * Validates a player name
     * @param playerName The player name to validate
     * @return The validated player name
     * @throws InvalidInputException if the name is invalid
     */
    public static String validatePlayerName(String playerName) throws InvalidInputException {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new InvalidInputException("Player name cannot be empty", "player-empty");
        }

        String trimmed = playerName.trim();

        if (!PLAYER_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidInputException("Invalid player name format", "player-format");
        }

        return trimmed;
    }

    /**
     * Validates a UUID string
     * @param uuidStr The UUID string to validate
     * @return The validated UUID
     * @throws InvalidInputException if the UUID is invalid
     */
    public static UUID validateUUID(String uuidStr) throws InvalidInputException {
        if (uuidStr == null || uuidStr.trim().isEmpty()) {
            throw new InvalidInputException("UUID cannot be empty");
        }

        String trimmed = uuidStr.trim();

        if (!UUID_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidInputException("Invalid UUID format");
        }

        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Invalid UUID format");
        }
    }

    /**
     * Validates a general string input
     * @param input The string to validate
     * @param fieldName The name of the field for error messages
     * @return The validated string
     * @throws InvalidInputException if the string is invalid
     */
    public static String validateString(String input, String fieldName) throws InvalidInputException {
        if (input == null) {
            throw new InvalidInputException(fieldName + " cannot be null");
        }

        String trimmed = input.trim();

        if (trimmed.isEmpty()) {
            throw new InvalidInputException(fieldName + " cannot be empty");
        }

        if (trimmed.length() > MAX_STRING_LENGTH) {
            throw new InvalidInputException(fieldName + " too long (maximum: " + MAX_STRING_LENGTH + " characters)");
        }

        // Check for potentially dangerous characters
        if (!SAFE_STRING_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidInputException(fieldName + " contains invalid characters");
        }

        return trimmed;
    }

    /**
     * Validates that a string is safe for logging (removes sensitive data)
     * @param input The input string to sanitize for logging
     * @return A sanitized version safe for logging
     */
    public static String sanitizeForLogging(String input) {
        if (input == null) {
            return "[null]";
        }

        // Remove potential sensitive information and limit length
        String sanitized = input.replaceAll("[<>\"'&]", "?");
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 47) + "...";
        }

        return sanitized;
    }

    /**
     * Custom exception for invalid input
     */
    public static class InvalidInputException extends Exception {
        private final String reasonKey;

        public InvalidInputException(String message) {
            this(message, "invalid-input");
        }

        public InvalidInputException(String message, String reasonKey) {
            super(message);
            this.reasonKey = reasonKey;
        }

        public String reasonKey() {
            return reasonKey;
        }
    }
}
