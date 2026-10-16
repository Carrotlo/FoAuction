package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.GuiDefinitionException;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiStartupFallback;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.SoundCategory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/** One-time conversion of installed pre-Core public GUI files. Never runs on reload. */
public final class PublicAuctionGuiMigration {
    private static final String VERSION_KEY = "public-gui-schema-version";
    private static final String FRESH_PENDING_FILE = ".public-gui-fresh-install-pending";
    private static final String FRESH_PENDING_CONTENT = "FoAuction public GUI fresh install v1\n";
    private static final String DIALOG_VERSION_KEY = "public-search-dialog-schema-version";
    private static final String DIALOG_LEGACY_KEY = "public-search-dialog-legacy-schema";
    private static final int VERSION = 1;
    private static final List<String> OLD_MENUS = List.of(
            "main", "listings", "seller-view", "claims", "history", "preview", "confirmation");
    private static final List<String> NEW_MENUS = List.of(
            "main", "listings", "seller-view", "claims", "history", "preview",
            "confirmation", "sell-confirmation");
    private static final List<String> ITEM_FIELDS = List.of(
            "material", "name", "lore", "amount", "custom-model-data", "glow");

    private final JavaPlugin plugin;
    private final Path root;
    private final Path guiRoot;
    private final boolean ownerDataExisted;

    private PublicAuctionGuiMigration(JavaPlugin plugin, boolean ownerDataExisted) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath();
        this.guiRoot = root.resolve("guis");
        this.ownerDataExisted = ownerDataExisted;
    }

    public static boolean install(JavaPlugin plugin) {
        String[] entries = plugin.getDataFolder().list();
        return install(plugin, entries == null ? plugin.getDataFolder().exists() : entries.length > 0);
    }

    public static boolean install(JavaPlugin plugin, boolean ownerDataExisted) {
        return new PublicAuctionGuiMigration(plugin, ownerDataExisted).run();
    }

    private boolean run() {
        if (plugin.getConfig().getInt(VERSION_KEY, 0) >= VERSION) {
            migratePendingDialog();
            return true;
        }
        try {
            Files.createDirectories(guiRoot);
            boolean hasOldGui = OLD_MENUS.stream().anyMatch(name -> Files.exists(guiRoot.resolve(name + ".yml")));
            if (!hasOldGui) {
                if (!verifiedFreshInstall()) {
                    throw new IOException("No old GUI files remain, but existing plugin data indicates an upgrade; "
                            + "preserving the possible owner deletion");
                }
                return installFresh();
            }
            for (String menu : OLD_MENUS) {
                if (!Files.isRegularFile(guiRoot.resolve(menu + ".yml"))) {
                    throw new IOException("Missing older owner GUI file guis/" + menu + ".yml");
                }
            }
            boolean upgraded = upgrade();
            if (upgraded) migratePendingDialog();
            return upgraded;
        } catch (IOException | InvalidConfigurationException | GuiDefinitionException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Public auction GUI migration failed; installed owner files were kept for retry", exception);
            return false;
        }
    }

    private boolean verifiedFreshInstall() throws IOException {
        Path pending = root.resolve(FRESH_PENDING_FILE);
        boolean unfinishedFresh = Files.isRegularFile(pending)
                && FRESH_PENDING_CONTENT.equals(Files.readString(pending, StandardCharsets.UTF_8));
        if ((ownerDataExisted && !unfinishedFresh) || plugin.getConfig().isSet("config-version")
                || Files.exists(root.resolve("userdata.db"))) {
            return false;
        }
        if (Files.exists(root.resolve("userdata")) || Files.exists(root.resolve("data"))) {
            return false;
        }
        // An existing message or sound file can carry public-interface choices even if GUI files were deleted.
        return !Files.exists(root.resolve("messages.yml")) && !Files.exists(root.resolve("sounds.yml"));
    }

    private boolean installFresh() throws IOException, InvalidConfigurationException, GuiDefinitionException {
        Map<Path, byte[]> created = new LinkedHashMap<>();
        Path pending = root.resolve(FRESH_PENDING_FILE);
        if (!Files.exists(pending)) {
            Files.writeString(pending, FRESH_PENDING_CONTENT, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        try {
            for (String menu : NEW_MENUS) {
                Path target = guiRoot.resolve(menu + ".yml");
                if (Files.exists(target)) {
                    throw new IOException("Fresh GUI target already exists: " + target);
                }
                byte[] bundled = bundledBytes("guis/" + menu + ".yml");
                Path staged = Files.createTempFile(guiRoot, ".fresh-gui-", ".yml");
                try {
                    Files.write(staged, bundled);
                    validate(menu, StrictGuiYaml.load(staged), bundledMenuYaml(menu));
                    replace(staged, target);
                } finally {
                    Files.deleteIfExists(staged);
                }
                created.put(target, bundled);
            }
            for (String resource : List.of("messages.yml", "sounds.yml", "dialogs/search.yml")) {
                Path target = root.resolve(resource);
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) throw new IOException("Fresh public-interface target already exists: " + target);
                byte[] bundled = bundledBytes(resource);
                Path staged = Files.createTempFile(root, ".fresh-interface-", ".yml");
                try {
                    Files.write(staged, bundled);
                    StrictGuiYaml.load(staged);
                    replace(staged, target);
                } finally {
                    Files.deleteIfExists(staged);
                }
                created.put(target, bundled);
            }
            writeVersion(-1);
            try {
                Files.deleteIfExists(pending);
            } catch (IOException exception) {
                plugin.getLogger().warning("Fresh GUI install completed, but pending marker cleanup failed: "
                        + exception.getMessage());
            }
            return true;
        } catch (IOException | InvalidConfigurationException | GuiDefinitionException exception) {
            for (Path target : created.keySet()) Files.deleteIfExists(target);
            throw exception;
        }
    }

    private boolean upgrade() throws IOException, InvalidConfigurationException, GuiDefinitionException {
        Map<String, YamlConfiguration> owner = new LinkedHashMap<>();
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        Path existingSellConfirmation = guiRoot.resolve("sell-confirmation.yml");
        if (Files.exists(existingSellConfirmation)) {
            if (!Files.isRegularFile(existingSellConfirmation)
                    || !Arrays.equals(Files.readAllBytes(existingSellConfirmation),
                    bundledBytes("guis/sell-confirmation.yml"))) {
                throw new IOException("Unexpected owner guis/sell-confirmation.yml before the GUI schema upgrade; "
                        + "preserving it because its older mapping is unknown");
            }
            originals.put(existingSellConfirmation, Files.readAllBytes(existingSellConfirmation));
        }
        for (String menu : OLD_MENUS) {
            Path path = guiRoot.resolve(menu + ".yml");
            originals.put(path, Files.readAllBytes(path));
            YamlConfiguration installed = StrictGuiYaml.load(path);
            if (installed.contains("rows", false) && installed.contains("entries", false)) {
                throw new IOException("guis/" + menu + ".yml already has the Core GUI shape while "
                        + VERSION_KEY + " is pending; preserving possible interrupted-upgrade owner edits");
            }
            owner.put(menu, installed);
        }
        // These released GUI shapes predate an explicit GUI schema marker. Identify them by
        // fields introduced together, never by the unrelated message/config version.
        boolean decoratedLegacy = owner.get("claims").contains("claim-entry", false)
                || owner.get("claims").contains("title-format", false)
                || owner.get("main").contains("frame", false)
                || owner.get("main").contains("items.sort.lore-options", false);
        if (!decoratedLegacy) {
            YamlConfiguration oldestMain = bundledResourceYaml("migration/legacy-guis-v0/main.yml");
            YamlConfiguration oldestClaims = bundledResourceYaml("migration/legacy-guis-v0/claims.yml");
            boolean identifiableOldest = Objects.equals(owner.get("main").get("title"), oldestMain.get("title"))
                    || Objects.equals(owner.get("claims").get("title"), oldestClaims.get("title"))
                    || Objects.equals(owner.get("claims").get("items.back.slot"), oldestClaims.get("items.back.slot"))
                    || Objects.equals(owner.get("main").get("items.claims.name"), oldestMain.get("items.claims.name"))
                    || Objects.equals(owner.get("claims").get("content-slots"), oldestClaims.get("content-slots"));
            if (!identifiableOldest) {
                throw new IOException("Installed GUI files have no known v0 or v3 schema marker; "
                        + "preserving the owner files because their legacy defaults are ambiguous");
            }
        }
        int originalVersion = decoratedLegacy ? 3 : 0;
        String defaultSet = decoratedLegacy ? "legacy-guis" : "legacy-guis-v0";
        plugin.getLogger().info("Detected installed public GUI schema v" + originalVersion);
        Map<String, YamlConfiguration> oldDefaults = new LinkedHashMap<>();
        for (String menu : OLD_MENUS) {
            oldDefaults.put(menu, bundledResourceYaml("migration/" + defaultSet + "/" + menu + ".yml"));
        }
        Path messagesPath = root.resolve("messages.yml");
        if (!Files.isRegularFile(messagesPath)) {
            throw new IOException("Older installation has no messages.yml; preserving the possible owner deletion");
        }
        originals.put(messagesPath, Files.readAllBytes(messagesPath));

        Map<Path, byte[]> proposed = new LinkedHashMap<>();
        for (String menu : NEW_MENUS) {
            String legacy = menu.equals("sell-confirmation") ? "confirmation" : menu;
            requireLegacyFields(menu, owner.get(legacy), oldDefaults.get(legacy));
            YamlConfiguration target = bundledMenuYaml(menu);
            mapMenu(menu, owner.get(legacy), oldDefaults.get(legacy), target);
            if (menu.equals("listings")) listingLore(owner.get("main"), oldDefaults.get("main"), target, true);
            if (menu.equals("seller-view")) listingLore(owner.get("main"), oldDefaults.get("main"), target, false);
            validate(menu, target, bundledMenuYaml(menu));
            proposed.put(guiRoot.resolve(menu + ".yml"), target.saveToString().getBytes(StandardCharsets.UTF_8));
        }
        proposed.put(messagesPath, migrateOptionLabels(owner, oldDefaults, messagesPath));
        Path soundsPath = root.resolve("sounds.yml");
        if (originalVersion == 0 && !Files.exists(soundsPath)) {
            // The v0 release had no sounds.yml; this is a genuinely new resource.
            proposed.put(soundsPath, bundledBytes("sounds.yml"));
        } else if (Files.exists(soundsPath) && !Files.isRegularFile(soundsPath)) {
            throw new IOException("Installed sounds.yml is not a regular file; keeping GUI upgrade pending");
        } else if (Files.isRegularFile(soundsPath)) {
            byte[] originalSounds = Files.readAllBytes(soundsPath);
            byte[] repairedSounds = repairInstalledSounds(soundsPath);
            if (!Arrays.equals(originalSounds, repairedSounds)) {
                originals.put(soundsPath, originalSounds);
                proposed.put(soundsPath, repairedSounds);
            }
        }

        Map<Path, Path> staged = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, byte[]> entry : proposed.entrySet()) {
                Files.createDirectories(entry.getKey().getParent());
                Path temp = Files.createTempFile(entry.getKey().getParent(), ".gui-upgrade-", ".staged");
                Files.write(temp, entry.getValue());
                staged.put(entry.getKey(), temp);
            }
            for (Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                Path backup = entry.getKey().resolveSibling(entry.getKey().getFileName()
                        + ".gui-v" + originalVersion + "-backup");
                if (!Files.exists(backup)) {
                    Path stagedBackup = Files.createTempFile(entry.getKey().getParent(),
                            ".gui-backup-", ".staged");
                    try {
                        Files.write(stagedBackup, entry.getValue());
                        try {
                            Files.move(stagedBackup, backup, StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                            Files.move(stagedBackup, backup);
                        }
                    } finally {
                        Files.deleteIfExists(stagedBackup);
                    }
                }
            }
            try {
                for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                    replace(entry.getValue(), entry.getKey());
                }
                writeVersion(originalVersion);
                return true;
            } catch (IOException | RuntimeException failure) {
                rollback(originals, proposed.keySet());
                throw failure;
            }
        } finally {
            for (Path temp : staged.values()) Files.deleteIfExists(temp);
        }
    }

    private void rollback(Map<Path, byte[]> originals, Set<Path> changed) throws IOException {
        IOException failure = null;
        for (Path target : changed) {
            try {
                byte[] old = originals.get(target);
                if (old == null) {
                    Files.deleteIfExists(target);
                } else {
                    Path temp = Files.createTempFile(target.getParent(), ".gui-rollback-", ".staged");
                    try {
                        Files.write(temp, old);
                        replace(temp, target);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            } catch (IOException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void writeVersion(int originalVersion) throws IOException {
        Object previous = plugin.getConfig().get(VERSION_KEY);
        Object previousDialog = plugin.getConfig().get(DIALOG_VERSION_KEY);
        Object previousLegacy = plugin.getConfig().get(DIALOG_LEGACY_KEY);
        plugin.getConfig().set(VERSION_KEY, VERSION);
        if (originalVersion < 0) plugin.getConfig().set(DIALOG_VERSION_KEY, VERSION);
        else plugin.getConfig().set(DIALOG_LEGACY_KEY, originalVersion);
        try {
            saveConfigVersionAtomically();
        } catch (IOException | RuntimeException exception) {
            plugin.getConfig().set(VERSION_KEY, previous);
            plugin.getConfig().set(DIALOG_VERSION_KEY, previousDialog);
            plugin.getConfig().set(DIALOG_LEGACY_KEY, previousLegacy);
            throw exception;
        }
    }

    private void saveConfigVersionAtomically() throws IOException {
        Path staged = Files.createTempFile(root, ".gui-version-", ".staged");
        try {
            Files.writeString(staged, plugin.getConfig().saveToString(), StandardCharsets.UTF_8);
            replace(staged, root.resolve("config.yml"));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void migratePendingDialog() {
        if (plugin.getConfig().getInt(DIALOG_VERSION_KEY, 0) >= VERSION) return;
        Path dialog = root.resolve("dialogs/search.yml");
        int legacy = plugin.getConfig().getInt(DIALOG_LEGACY_KEY, 0);
        byte[] original = null;
        Path staged = null;
        boolean changed = false;
        try {
            if (Files.exists(dialog)) {
                if (!Files.isRegularFile(dialog)) throw new IOException("Dialog path is not a file: " + dialog);
                original = Files.readAllBytes(dialog);
                boolean currentShape;
                try {
                    AuctionSearchDialog.validate(StrictGuiYaml.load(dialog));
                    currentShape = true;
                } catch (IllegalArgumentException invalidCurrentShape) {
                    currentShape = false;
                }
                if (!currentShape) {
                    if (!plugin.getConfig().isSet(DIALOG_LEGACY_KEY)) {
                        throw new IOException("Search dialog schema is ambiguous without a legacy marker; "
                                + "preserving the owner file");
                    }
                    byte[] migrated = migrateSearchDialog(dialog,
                            legacy >= 3 ? "legacy-guis" : "legacy-guis-v0");
                    staged = Files.createTempFile(dialog.getParent(), ".search-dialog-", ".staged");
                    Files.write(staged, migrated);
                    Path backup = dialog.resolveSibling(dialog.getFileName() + ".gui-v" + legacy + "-backup");
                    if (!Files.exists(backup)) Files.write(backup, original, java.nio.file.StandardOpenOption.CREATE_NEW);
                    replace(staged, dialog);
                    changed = true;
                }
            }
            Object previous = plugin.getConfig().get(DIALOG_VERSION_KEY);
            plugin.getConfig().set(DIALOG_VERSION_KEY, VERSION);
            try {
                saveConfigVersionAtomically();
            } catch (IOException | RuntimeException exception) {
                plugin.getConfig().set(DIALOG_VERSION_KEY, previous);
                throw exception;
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            if (changed && original != null) {
                try {
                    Path rollback = Files.createTempFile(dialog.getParent(), ".search-dialog-rollback-", ".staged");
                    try {
                        Files.write(rollback, original);
                        replace(rollback, dialog);
                    } finally {
                        Files.deleteIfExists(rollback);
                    }
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            plugin.getLogger().log(Level.WARNING,
                    "Search dialog migration is pending; chat search remains available", exception);
        } finally {
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            }
        }
    }

    private void validate(String menu, YamlConfiguration candidate, YamlConfiguration bundled)
            throws GuiDefinitionException {
        PublicGuiStartupFallback.parse(candidate, bundled,
                yaml -> {
                    PublicGuiDefinition definition = PublicGuiSchema.parse("auction." + menu,
                            "guis/" + menu + ".yml", yaml,
                            PublicAuctionGuis.contractFor(menu, PublicAuctionGuis.actionsIn(menu, yaml)));
                    validateRuntimeRequirements(menu, definition);
                    return definition;
                },
                replacement -> plugin.getLogger().warning("GUI upgrade guis/" + menu + ".yml:"
                        + replacement.path() + "=" + replacement.invalidValue() + " ("
                        + replacement.reason() + ") -> newest bundled default " + replacement.defaultValue()));
    }

    private byte[] repairInstalledSounds(Path soundsPath)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration owner = StrictGuiYaml.load(soundsPath);
        YamlConfiguration bundled = bundledResourceYaml("sounds.yml");
        boolean changed = migrateLegacySoundAliases(owner, bundled);
        for (String group : List.of("gui", "auction")) {
            ConfigurationSection defaults = bundled.getConfigurationSection(group);
            if (defaults == null) continue;
            if (owner.contains(group, false) && !owner.isConfigurationSection(group)) {
                changed |= replaceSoundField(owner, bundled, group, owner.get(group),
                        "public sound group must be a section");
            }
            for (String event : defaults.getKeys(false)) {
                String path = group + "." + event;
                if (!owner.contains(path, false)) continue; // Owner removed this sound.
                Object raw = owner.get(path);
                if (raw instanceof String sound) {
                    if (!sound.isBlank() && !validSoundKey(sound)) {
                        changed |= replaceSoundField(owner, bundled, path, raw,
                                "unknown sound key");
                    }
                    continue;
                }
                if (!owner.isConfigurationSection(path)) {
                    changed |= replaceSoundField(owner, bundled, path, raw,
                            "sound event must be text or a section");
                    continue;
                }
                for (String field : List.of("enabled", "sound", "key", "category", "volume", "pitch")) {
                    String fieldPath = path + "." + field;
                    if (!owner.contains(fieldPath, false)) continue;
                    Object value = owner.get(fieldPath);
                    if (validSoundField(field, value)) continue;
                    if (field.equals("key")) {
                        owner.set(fieldPath, null);
                        if (!owner.contains(path + ".sound", false)) {
                            owner.set(path + ".sound", bundled.get(path + ".sound"));
                        }
                        plugin.getLogger().warning("GUI upgrade sounds.yml:" + fieldPath + "=" + value
                                + " is invalid; using newest bundled sound "
                                + bundled.get(path + ".sound"));
                        changed = true;
                    } else {
                        changed |= replaceSoundField(owner, bundled, fieldPath, value,
                                "invalid " + field);
                    }
                }
            }
        }
        return changed ? owner.saveToString().getBytes(StandardCharsets.UTF_8)
                : Files.readAllBytes(soundsPath);
    }

    private boolean migrateLegacySoundAliases(YamlConfiguration owner, YamlConfiguration bundled) {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("auction.menu-open", List.of("gui.open"));
        aliases.put("auction.page-turn", List.of("gui.page-next", "gui.page-previous"));
        aliases.put("auction.toggle", List.of("gui.sort", "gui.filter"));
        aliases.put("auction.refresh", List.of("gui.click"));
        aliases.put("auction.preview-open", List.of("gui.open"));
        aliases.put("auction.search-updated", List.of("gui.search"));
        aliases.put("auction.search-cleared", List.of("gui.clear-search"));
        aliases.put("auction.selling-confirm", List.of("gui.confirm"));
        aliases.put("auction.denied", List.of("gui.error"));
        boolean hasLegacyAliases = aliases.keySet().stream().anyMatch(path -> owner.contains(path, false));
        if (!hasLegacyAliases || owner.contains("gui", false)) {
            // Mixed/current files may contain deliberately removed gui.* events.
            return false;
        }
        for (Map.Entry<String, List<String>> alias : aliases.entrySet()) {
            if (!owner.contains(alias.getKey(), false)) continue;
            for (String target : alias.getValue()) {
                if (!owner.contains(target, false)) copySoundEvent(owner, alias.getKey(), target);
            }
            owner.set(alias.getKey(), null);
        }
        for (String newEvent : List.of("gui.back", "gui.cancel")) {
            if (!owner.contains(newEvent, false)) copySoundEvent(bundled, newEvent, owner, newEvent);
        }
        plugin.getLogger().info("Migrated legacy auction sound aliases to gui.* with owner removals intact");
        return true;
    }

    private static void copySoundEvent(YamlConfiguration config, String source, String target) {
        copySoundEvent(config, source, config, target);
    }

    private static void copySoundEvent(YamlConfiguration source, String sourcePath,
                                       YamlConfiguration target, String targetPath) {
        ConfigurationSection section = source.getConfigurationSection(sourcePath);
        if (section == null) {
            target.set(targetPath, source.get(sourcePath));
            return;
        }
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                target.set(targetPath + "." + key, section.get(key));
            }
        }
    }

    private boolean replaceSoundField(YamlConfiguration owner, YamlConfiguration bundled,
                                      String path, Object invalid, String reason) {
        Object fallback = bundled.get(path);
        owner.set(path, null);
        if (fallback instanceof ConfigurationSection section) {
            for (String key : section.getKeys(true)) {
                if (!section.isConfigurationSection(key)) owner.set(path + "." + key, section.get(key));
            }
        } else {
            owner.set(path, fallback);
        }
        plugin.getLogger().warning("GUI upgrade sounds.yml:" + path + "=" + invalid
                + " (" + reason + ") -> newest bundled default " + fallback);
        return true;
    }

    private boolean validSoundField(String field, Object value) {
        return switch (field) {
            case "enabled" -> value instanceof Boolean;
            case "sound", "key" -> value instanceof String sound
                    && (sound.isBlank() || validSoundKey(sound));
            case "category" -> {
                if (!(value instanceof String category)) yield false;
                try {
                    SoundCategory.valueOf(category.trim().toUpperCase(java.util.Locale.ROOT));
                    yield true;
                } catch (IllegalArgumentException exception) {
                    yield false;
                }
            }
            case "volume" -> value instanceof Number number
                    && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0;
            case "pitch" -> value instanceof Number number
                    && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0.5 && number.doubleValue() <= 2.0;
            default -> false;
        };
    }

    private boolean validSoundKey(String sound) {
        if (sound.contains(":")) {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                    sound.toLowerCase(java.util.Locale.ROOT));
            if (key == null) return false;
            return !key.getNamespace().equals("minecraft") || org.bukkit.Bukkit.getServer() == null
                    || me.foesio.core.sound.SoundTypes.isValid(sound);
        }
        // The standalone test JVM has no Paper registry. On a running server,
        // Core's resolver checks the installed version's actual sound registry.
        return org.bukkit.Bukkit.getServer() == null
                || me.foesio.core.sound.SoundTypes.isValid(sound);
    }

    private void validateRuntimeRequirements(String menu, PublicGuiDefinition definition)
            throws GuiDefinitionException {
        switch (menu) {
            case "main", "listings", "seller-view" -> requireEntry(definition, "listings", 0);
            case "claims" -> requireEntry(definition, "claims", 0);
            case "history" -> requireEntry(definition, "history", 0);
            case "preview" -> {
                requireEntry(definition, "preview-item", 1);
                requireEntry(definition, "contents", 0);
            }
            case "confirmation", "sell-confirmation" -> requireEntry(definition, "preview", 1);
            default -> throw new IllegalArgumentException("Unknown public auction menu " + menu);
        }
        if (menu.equals("claims")) {
            for (String state : List.of("money", "money-note", "invalid")) {
                if (!definition.states().containsKey(state)) {
                    throw new GuiDefinitionException("states." + state,
                            "Auction claim renderer requires state " + state);
                }
            }
            for (String state : List.of("item", "item-note", "money", "money-note", "invalid")) {
                requireLoreState(definition, "claims", state);
            }
        }
        if (menu.equals("main") || menu.equals("seller-view")) {
            for (String state : List.of("buy", "buy-admin", "container", "container-admin")) {
                requireLoreState(definition, "listings", state);
            }
        }
        if (menu.equals("listings")) requireLoreState(definition, "listings", "owner");
        if (menu.equals("main") && !plugin.getConfig().getBoolean("history.allow-player-own-view", true)) {
            PublicGuiDefinition.Button button = definition.buttons().get("history-or-clear");
            if (button != null && button.enabled() && !button.states().containsKey("clear-search")) {
                throw new GuiDefinitionException("buttons.history-or-clear.states.clear-search",
                        "Clear-search state is required while player history is disabled");
            }
        }
    }

    private static void requireEntry(PublicGuiDefinition definition, String entry, int exactSlots)
            throws GuiDefinitionException {
        PublicGuiDefinition.Entry configured = definition.entries().get(entry);
        if (configured == null) {
            throw new GuiDefinitionException("entries." + entry,
                    "Auction renderer requires entry " + entry);
        }
        if (configured.slots().isEmpty() || exactSlots > 0 && configured.slots().size() != exactSlots) {
            throw new GuiDefinitionException("entries." + entry + ".slots",
                    "Auction renderer requires " + (exactSlots > 0 ? exactSlots : "at least one")
                            + " slot(s) for " + entry);
        }
    }

    private static void requireLoreState(PublicGuiDefinition definition, String entry, String state)
            throws GuiDefinitionException {
        PublicGuiDefinition.Entry configured = definition.entries().get(entry);
        if (configured == null || configured.sourceItem() == null
                || !configured.sourceItem().loreStates().containsKey(state)) {
            throw new GuiDefinitionException("entries." + entry + ".source-item.lore-states." + state,
                    "Auction renderer requires lore state " + state);
        }
    }

    private byte[] bundledBytes(String resource) throws IOException {
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) throw new IOException("Missing bundled resource " + resource);
            return input.readAllBytes();
        }
    }

    private YamlConfiguration bundledMenuYaml(String menu) throws IOException {
        return bundledResourceYaml("guis/" + menu + ".yml");
    }

    private YamlConfiguration bundledResourceYaml(String resource) throws IOException {
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) throw new IOException("Missing bundled resource " + resource);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException exception) {
                throw new IOException("Invalid bundled YAML " + resource, exception);
            }
            return yaml;
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void mapMenu(String menu, YamlConfiguration old, YamlConfiguration previous,
                         YamlConfiguration target) {
        transferTitle(old, previous, target);
        if (menu.equals("preview")) {
            if (removedLegacyItem(old, previous, "border")) target.set("decorations.border", null);
            else copyItem(old, previous, target, "border", "decorations.border");
            copySlot(old, previous, target, "items.preview.slot", "entries.preview-item.slots");
            copyControl(old, previous, target, "back", "buttons.back");
            return;
        }
        if (menu.equals("confirmation") || menu.equals("sell-confirmation")) {
            String kind = menu.equals("confirmation") ? "buy" : "sell";
            transfer(old, previous, target, kind + "-title", "title", false);
            copySlot(old, previous, target, "items.preview.slot", "entries.preview.slots");
            if (menu.equals("confirmation")) {
                copyControl(old, previous, target, "cancel", "buttons.cancel");
            }
            copyControl(old, previous, target, "confirm", "buttons.confirm");
            YamlConfiguration newest = bundledLegacyV3("confirmation");
            List<String> lore = new ArrayList<>(scalarLine(old, previous, newest, "preview-lore.separator"));
            lore.addAll(lines(old, previous, newest, "preview-lore." + kind));
            target.set("entries.preview.source-item.lore.lines", lore);
            return;
        }
        if (removedLegacyItem(old, previous, "frame")) {
            target.set("filler", null);
            target.createSection("filler").set("enabled", false);
        } else copyItem(old, previous, target, "frame", "filler");
        String region = menu.equals("claims") ? "claims" : menu.equals("history") ? "history" : "listings";
        copySlots(old, previous, target, "content-slots", "entries." + region + ".slots");
        if (removedLegacyItem(old, previous, "content-filler"))
            target.set("entries." + region + ".empty-item", null);
        else copyItem(old, previous, target, "content-filler", "entries." + region + ".empty-item");
        for (String control : controls(menu)) copyControl(old, previous, target, control, "buttons." + control);
        copyControl(old, previous, target, "previous", "entries.previous");
        copyControl(old, previous, target, "next", "entries.next");
        for (String kind : menu.equals("main") ? List.of("sort", "filter")
                : menu.equals("listings") || menu.equals("seller-view") ? List.of("sort") : List.<String>of()) {
            transfer(old, previous, target, "items." + kind + ".option-selected",
                    "buttons." + kind + ".cycle.selected-format", false);
            transfer(old, previous, target, "items." + kind + ".option-unselected",
                    "buttons." + kind + ".cycle.unselected-format", false);
        }
        switch (menu) {
            case "main" -> {
                // Released 3.3 had separate history/clear-search sections without
                // material fields. Their presence, not material, identifies the shape.
                boolean separateHistory = previous.isConfigurationSection("items.history");
                boolean removedHistory = separateHistory
                        ? !old.contains("items.history", false)
                                && !hasMalformedAncestor(old, "items.history")
                        : removedLegacyItem(old, previous, "items.history-or-clear");
                boolean removedClearSearch = previous.isConfigurationSection("items.clear-search")
                        && !old.contains("items.clear-search", false)
                        && !hasMalformedAncestor(old, "items.clear-search");
                if (hasMalformedAncestor(old, "items.history-or-clear.slot")) {
                    plugin.getLogger().warning("GUI upgrade items.history-or-clear has an invalid section; "
                            + "using the newest bundled history button");
                } else if (!old.contains("items.history-or-clear.slot", false)
                        || removedHistory || removedClearSearch) {
                    target.set("buttons.history-or-clear", null);
                } else {
                    copySlot(old, previous, target, "items.history-or-clear.slot", "buttons.history-or-clear.slot");
                    // The oldest released GUI kept the history item in history-or-clear itself.
                    String history = separateHistory ? "history" : "history-or-clear";
                    copyItem(old, previous, target, "items." + history, "buttons.history-or-clear");
                    if (previous.isConfigurationSection("items.clear-search")) {
                        copyItem(old, previous, target, "items.clear-search",
                                "buttons.history-or-clear.states.clear-search");
                    }
                }
                listingLore(old, previous, target, false);
            }
            case "listings", "seller-view" -> { }
            case "claims" -> claimLore(old, previous, target);
            case "history" -> historyLore(old, previous, target);
            default -> { }
        }
    }

    private List<String> controls(String menu) {
        return switch (menu) {
            case "main" -> List.of("claims", "sort", "filter", "refresh", "manage", "search");
            case "listings", "seller-view" -> List.of("back", "sort", "refresh");
            case "claims" -> List.of("back", "refresh");
            case "history" -> List.of("back");
            default -> List.of();
        };
    }

    private void transferTitle(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target) {
        if (old.contains("title-format", false)) {
            Object format = old.get("title-format");
            Object title = old.get("title");
            if (format instanceof String template && title instanceof String text
                    && (!Objects.equals(format, previous.get("title-format"))
                    || !Objects.equals(title, previous.get("title")))) {
                target.set("title", template.replace("{title}", text));
            }
        } else {
            transfer(old, previous, target, "title", "title", false);
        }
    }

    private void copyControl(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target,
                             String control, String destination) {
        String source = "items." + control;
        if (!old.isConfigurationSection(source) && previous.isConfigurationSection(source)) {
            if (old.contains(source, false) || hasMalformedAncestor(old, source)) {
                plugin.getLogger().warning("GUI upgrade " + source + " has an invalid section; "
                        + "using the newest bundled control");
            } else {
                target.set(destination, null);
            }
            return;
        }
        if (!old.contains(source + ".slot", false) && previous.contains(source + ".slot", false)) {
            target.set(destination, null);
            return;
        }
        if (!old.contains(source + ".material", false) && previous.contains(source + ".material", false)) {
            target.set(destination, null);
            return;
        }
        if (destination.startsWith("entries.")) {
            copySlot(old, previous, target, source + ".slot", destination + ".slots");
            copyItem(old, previous, target, source, destination + ".item");
        } else {
            copySlot(old, previous, target, source + ".slot", destination + ".slot");
            copyItem(old, previous, target, source, destination);
        }
    }

    private static boolean removedLegacyItem(YamlConfiguration old, YamlConfiguration previous, String item) {
        String material = item + ".material";
        return previous.contains(material, false) && !old.contains(material, false)
                && !hasMalformedAncestor(old, material);
    }

    private static boolean hasMalformedAncestor(YamlConfiguration old, String path) {
        for (int index = path.indexOf('.'); index > 0; index = path.indexOf('.', index + 1)) {
            String ancestor = path.substring(0, index);
            if (old.contains(ancestor, false) && !old.isConfigurationSection(ancestor)) return true;
        }
        return false;
    }

    private static void requireLegacyFields(String menu, YamlConfiguration old,
                                            YamlConfiguration previous) throws IOException {
        List<String> required = switch (menu) {
            case "main", "listings", "seller-view", "history" ->
                    List.of("title", "content-slots");
            case "claims" -> List.of("title", "content-slots", "claim-entry.money.material");
            case "preview" -> List.of("title", "items.preview.slot");
            case "confirmation" -> List.of("buy-title", "items.preview.slot");
            case "sell-confirmation" -> List.of("sell-title", "items.preview.slot");
            default -> throw new IllegalArgumentException("Unknown public auction menu " + menu);
        };
        for (String path : required) {
            if (previous.contains(path, false) && !old.contains(path, false)
                    && !hasMalformedAncestor(old, path)) {
                throw new IOException("guis/" + menu + ".yml: older owner field " + path
                        + " is missing; keeping the upgrade pending rather than restoring an intentional deletion");
            }
        }
    }

    private void copySlot(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target,
                          String source, String destination) {
        if (hasMalformedAncestor(old, source)) return;
        Object value = old.get(source);
        if (value == null) {
            if (previous.contains(source, false)) target.set(destination, null);
        } else if (!Objects.equals(value, previous.get(source))) {
            target.set(destination, destination.endsWith(".slots") && value instanceof Number
                    ? List.of(value) : value);
        }
    }

    private void copySlots(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target,
                           String source, String destination) {
        transfer(old, previous, target, source, destination, false);
    }

    private void copyItem(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target,
                          String source, String destination) {
        if ((old.contains(source, false) && !old.isConfigurationSection(source))
                || hasMalformedAncestor(old, source)) {
            plugin.getLogger().warning("GUI upgrade " + source + " has an invalid section; "
                    + "using the newest bundled item");
            return;
        }
        for (String field : ITEM_FIELDS) {
            transfer(old, previous, target, source + "." + field, destination + "." + field, true);
        }
        if (old.contains(source + ".hide-enchants", false)) {
            Object hidden = old.get(source + ".hide-enchants");
            if (Boolean.FALSE.equals(hidden)) {
                target.set(destination + ".item-flags", List.of("HIDE_ATTRIBUTES"));
            } else if (!(hidden instanceof Boolean)) {
                target.set(destination + ".item-flags", hidden);
            }
        }
    }

    private void transfer(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target,
                          String source, String destination, boolean optional) {
        if (hasMalformedAncestor(old, source)) return;
        if (!old.contains(source, false)) {
            if (optional && previous.contains(source, false)) target.set(destination, null);
            return;
        }
        Object value = old.get(source);
        if (!Objects.equals(value, previous.get(source))) {
            target.set(destination, value);
        }
    }

    private void listingLore(YamlConfiguration old, YamlConfiguration previous,
                             YamlConfiguration target, boolean owner) {
        YamlConfiguration source = bundledLegacyV3("main");
        List<String> details = lines(old, previous, source, "listing-lore.details");
        List<String> separator = scalarLine(old, previous, source, "listing-lore.action-separator");
        Map<String, List<String>> variants = new LinkedHashMap<>();
        if (owner) {
            variants.put("owner", lines(old, previous, source, "listing-lore.owner-actions"));
        } else {
            for (String kind : List.of("buy", "container")) {
                List<String> base = lines(old, previous, source, "listing-lore." + kind + "-actions");
                variants.put(kind, base);
                List<String> admin = new ArrayList<>(base);
                admin.addAll(lines(old, previous, source, "listing-lore.admin-actions"));
                variants.put(kind + "-admin", admin);
            }
        }
        for (Map.Entry<String, List<String>> entry : variants.entrySet()) {
            List<String> combined = new ArrayList<>(details);
            combined.addAll(separator);
            combined.addAll(entry.getValue());
            target.set("entries.listings.source-item.lore-states." + entry.getKey() + ".lines", combined);
        }
    }

    private void claimLore(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target) {
        YamlConfiguration source = bundledLegacyV3("claims");
        List<String> separator = scalarLine(old, previous, source, "claim-entry.separator");
        List<String> actionSeparator = scalarLine(old, previous, source, "claim-entry.action-separator");
        List<String> created = scalarLine(old, previous, source, "claim-entry.item.created");
        List<String> note = scalarLine(old, previous, source, "claim-entry.item.note");
        List<String> action = scalarLine(old, previous, source, "claim-entry.item.action");
        setLines(target, "entries.claims.source-item.lore-states.item.lines",
                separator, created, actionSeparator, action);
        setLines(target, "entries.claims.source-item.lore-states.item-note.lines",
                separator, note, created, actionSeparator, action);
        copyItem(old, previous, target, "claim-entry.money", "states.money");
        copyItem(old, previous, target, "claim-entry.money", "states.money-note");
        copyItem(old, previous, target, "claim-entry.invalid", "states.invalid");
        List<String> amount = scalarLine(old, previous, source, "claim-entry.money.amount");
        List<String> moneyNote = scalarLine(old, previous, source, "claim-entry.money.note");
        List<String> moneyCreated = scalarLine(old, previous, source, "claim-entry.money.created");
        List<String> moneyAction = scalarLine(old, previous, source, "claim-entry.money.action");
        setLines(target, "states.money.lore", amount, moneyCreated, actionSeparator, moneyAction);
        setLines(target, "states.money-note.lore", amount, moneyNote, moneyCreated, actionSeparator, moneyAction);
    }

    private void historyLore(YamlConfiguration old, YamlConfiguration previous, YamlConfiguration target) {
        YamlConfiguration source = bundledLegacyV3("history");
        List<String> lines = new ArrayList<>();
        for (String key : List.of("separator", "seller", "buyer", "price", "sold")) {
            lines.addAll(scalarLine(old, previous, source, "entry-lore." + key));
        }
        target.set("entries.history.source-item.lore.lines", lines);
        copyControl(old, previous, target, "empty", "entries.empty");
    }

    @SafeVarargs
    private final void setLines(YamlConfiguration yaml, String path, List<String>... components) {
        List<String> combined = new ArrayList<>();
        for (List<String> component : components) combined.addAll(component);
        yaml.set(path, combined);
    }

    private List<String> lines(YamlConfiguration old, YamlConfiguration previous,
                               YamlConfiguration newest, String path) {
        if (hasMalformedAncestor(old, path)) {
            plugin.getLogger().warning("GUI upgrade " + path + " has an invalid section; "
                    + "using newest bundled lore " + newest.get(path));
            return newest.getStringList(path);
        }
        if (!old.contains(path, false)) return previous.contains(path, false) ? List.of() : newest.getStringList(path);
        if (Objects.equals(old.get(path), previous.get(path))) return newest.getStringList(path);
        if (old.get(path) instanceof List<?> value && value.stream().allMatch(String.class::isInstance)) {
            return value.stream().map(String.class::cast).toList();
        }
        plugin.getLogger().warning("GUI upgrade " + path + " has invalid lore list " + old.get(path)
                + "; using newest bundled default " + newest.get(path));
        return newest.getStringList(path);
    }

    private List<String> scalarLine(YamlConfiguration old, YamlConfiguration previous,
                                    YamlConfiguration newest, String path) {
        if (hasMalformedAncestor(old, path)) {
            plugin.getLogger().warning("GUI upgrade " + path + " has an invalid section; "
                    + "using newest bundled text " + newest.get(path));
            return line(newest.get(path));
        }
        if (!old.contains(path, false)) return previous.contains(path, false) ? List.of() : line(newest.get(path));
        if (Objects.equals(old.get(path), previous.get(path))) return line(newest.get(path));
        if (old.get(path) instanceof String text) return List.of(text);
        plugin.getLogger().warning("GUI upgrade " + path + " has invalid text " + old.get(path)
                + "; using newest bundled default " + newest.get(path));
        return line(newest.get(path));
    }

    private List<String> line(Object value) {
        return value instanceof String text ? List.of(text) : List.of();
    }

    private YamlConfiguration bundledLegacyV3(String menu) {
        try {
            return bundledResourceYaml("migration/legacy-guis/" + menu + ".yml");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] migrateOptionLabels(Map<String, YamlConfiguration> owner,
                                       Map<String, YamlConfiguration> previous, Path messagesPath)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration messages = StrictGuiYaml.load(messagesPath);
        YamlConfiguration newest = bundledResourceYaml("messages.yml");
        repairPublicMessageSections(messages, newest);
        for (String path : List.of("gui-values.no-search", "gui-values.search-target",
                "gui-values.time-expired", "gui-values.time-days-hours",
                "gui-values.time-hours-minutes", "gui-values.time-minutes", "gui-values.date-format",
                "gui-values.date-zone",
                "gui-values.number-locale", "gui-values.number-pattern",
                "gui-values.compact-number-pattern", "gui-values.compact-suffixes",
                "gui-values.container", "gui-values.generic-item", "gui.unavailable", "gui.search-chat-prompt",
                "gui.search-chat-cancel-keyword", "gui.search-chat-clear-keyword",
                "claim-notes.claim-delivery-fallback", "claim-notes.purchased-from",
                "claim-notes.admin-removed", "claim-notes.listing-expired",
                "claim-notes.inventory-full", "claim-notes.player-offline",
                "validation-errors.price-empty", "validation-errors.price-too-low",
                "validation-errors.price-too-high", "validation-errors.price-format",
                "validation-errors.search-too-long", "validation-errors.player-empty",
                "validation-errors.player-format", "validation-errors.invalid-input")) {
            if (!messages.contains(path, false)) {
                messages.set(path, newest.get(path));
            } else if (!validNewMessage(path, messages.get(path))) {
                plugin.getLogger().warning("GUI upgrade messages.yml:" + path + "=" + messages.get(path)
                        + " is invalid; using newest bundled default " + newest.get(path));
                messages.set(path, newest.get(path));
            }
        }
        ConfigurationSection containerNames = newest.getConfigurationSection("gui-values.containers");
        if (containerNames != null) {
            for (String name : containerNames.getKeys(false)) {
                String path = "gui-values.containers." + name;
                if (!messages.contains(path, false)) messages.set(path, newest.get(path));
                else if (!(messages.get(path) instanceof String)) {
                    plugin.getLogger().warning("GUI upgrade messages.yml:" + path + "=" + messages.get(path)
                            + " is invalid; using newest bundled default " + newest.get(path));
                    messages.set(path, newest.get(path));
                }
            }
        }
        for (String menu : List.of("main", "listings", "seller-view")) {
            for (String kind : menu.equals("main") ? List.of("sort", "filter") : List.of("sort")) {
                String source = "items." + kind + ".lore-options";
                String destination = "gui-options." + menu + "." + kind;
                if (messages.contains(destination, false)) {
                    if (!validOptionLabels(messages.get(destination), newest.getStringList(destination).size())) {
                        plugin.getLogger().warning("GUI upgrade messages.yml:" + destination + "="
                                + messages.get(destination) + " is invalid; using newest bundled default "
                                + newest.get(destination));
                        messages.set(destination, newest.get(destination));
                    }
                    continue;
                }
                YamlConfiguration prior = owner.get(menu);
                if (!prior.contains(source, false)) {
                    if (hasMalformedAncestor(prior, source)) {
                        plugin.getLogger().warning("GUI upgrade " + menu + ":" + source
                                + " has an invalid section; using newest bundled " + destination);
                        messages.set(destination, newest.get(destination));
                    } else if (previous.get(menu).contains(source, false)) {
                        messages.set(destination, null);
                    }
                    continue;
                }
                Object value = prior.get(source);
                if (Objects.equals(value, previous.get(menu).get(source))) {
                    messages.set(destination, newest.get(destination));
                } else if (validOptionLabels(value, newest.getStringList(destination).size())) {
                    messages.set(destination, value);
                } else {
                    plugin.getLogger().warning("GUI upgrade " + menu + ":" + source
                            + " is invalid; using newest bundled " + destination);
                    messages.set(destination, newest.get(destination));
                }
            }
        }
        repairExistingPublicMessages(messages, newest);
        return messages.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    private void repairPublicMessageSections(YamlConfiguration messages, YamlConfiguration newest) {
        for (String root : List.of("tokens", "command", "gui", "auction", "dialog",
                "gui-values", "gui-options", "claim-notes", "validation-errors")) {
            if (!messages.contains(root, false) || messages.isConfigurationSection(root)) continue;
            Object invalid = messages.get(root);
            ConfigurationSection defaults = newest.getConfigurationSection(root);
            if (defaults == null) continue;
            plugin.getLogger().warning("GUI upgrade messages.yml:" + root + "=" + invalid
                    + (root.equals("gui-options")
                    ? " is invalid; rebuilding from legacy GUI option labels"
                    : " is invalid; using newest bundled section default"));
            messages.set(root, null);
            if (root.equals("gui-options")) continue; // legacy GUI option labels are mapped below
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key)) {
                    messages.set(root + "." + key, defaults.get(key));
                }
            }
        }
    }

    private void repairExistingPublicMessages(YamlConfiguration messages, YamlConfiguration newest) {
        for (String path : newest.getKeys(true)) {
            if (newest.isConfigurationSection(path) || !messages.contains(path, false)) continue;
            String root = path.substring(0, path.indexOf('.') > 0 ? path.indexOf('.') : path.length());
            if (!Set.of("tokens", "command", "gui", "auction", "dialog").contains(root)) continue;
            Object value = messages.get(path);
            boolean mayBeList = Set.of("command", "gui", "auction", "dialog").contains(root)
                    && !path.equals("gui.search-chat-cancel-keyword")
                    && !path.equals("gui.search-chat-clear-keyword");
            boolean valid = value instanceof String || mayBeList && value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance);
            if (!valid) {
                plugin.getLogger().warning("GUI upgrade messages.yml:" + path + "=" + value
                        + " is invalid; using newest bundled default " + newest.get(path));
                messages.set(path, newest.get(path));
            }
        }
    }

    private boolean validOptionLabels(Object value, int expected) {
        return value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)
                && (list.isEmpty() || list.size() == expected);
    }

    private boolean validNewMessage(String path, Object value) {
        try {
            if (path.equals("gui-values.compact-suffixes")) {
                return value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
            }
            if (path.equals("gui.unavailable") || path.equals("gui.search-chat-prompt")) {
                return value instanceof String || value instanceof List<?> list
                        && list.stream().allMatch(String.class::isInstance);
            }
            if (!(value instanceof String text)) return false;
            if (path.equals("gui-values.date-format")) java.time.format.DateTimeFormatter.ofPattern(text);
            if (path.equals("gui-values.date-zone") && !text.equalsIgnoreCase("system")) {
                java.time.ZoneId.of(text);
            }
            if (path.endsWith("number-pattern")) new java.text.DecimalFormat(text);
            if (path.equals("gui-values.number-locale") && !text.isBlank()) {
                new java.util.Locale.Builder().setLanguageTag(text).build();
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] migrateSearchDialog(Path installed, String defaultSet)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration old = StrictGuiYaml.load(installed);
        String legacyFolder = defaultSet.equals("legacy-guis") ? "legacy-dialogs" : "legacy-dialogs-v0";
        YamlConfiguration previous = bundledResourceYaml("migration/" + legacyFolder + "/search.yml");
        YamlConfiguration target = bundledResourceYaml("dialogs/search.yml");
        for (String path : List.of("title", "body", "field-label", "initial-value", "placeholder",
                "confirm-button", "cancel-button", "confirm-tooltip", "cancel-tooltip", "max-length")) {
            if (!old.contains(path, false)) {
                if (previous.contains(path, false)) target.set(path, null);
                continue;
            }
            Object value = old.get(path);
            if (Objects.equals(value, previous.get(path))) continue;
            if ((path.equals("confirm-button") || path.equals("cancel-button"))
                    && old.isConfigurationSection(path)) {
                ConfigurationSection source = old.getConfigurationSection(path);
                target.set(path, null);
                ConfigurationSection destination = target.createSection(path);
                String label = source.getString("label");
                if (label == null || label.isBlank()) {
                    plugin.getLogger().warning("GUI upgrade dialogs/search.yml:" + path
                            + ".label is invalid; using newest bundled default");
                    label = bundledResourceYaml("dialogs/search.yml").getString(path);
                }
                destination.set("label", label);
                for (String optional : List.of("tooltip", "icon")) {
                    if (!source.contains(optional)) continue;
                    if (source.get(optional) instanceof String) destination.set(optional, source.get(optional));
                    else plugin.getLogger().warning("GUI upgrade dialogs/search.yml:" + path + "." + optional
                            + "=" + source.get(optional) + " is invalid; leaving it absent");
                }
                Object width = source.get("width");
                int bundledWidth = target.getInt(path.startsWith("confirm") ? "confirm-width" : "cancel-width");
                destination.set("width", width instanceof Number number && number.intValue() > 0
                        && number.doubleValue() == number.intValue() ? width : bundledWidth);
                if (width != null && !Objects.equals(width, destination.get("width"))) {
                    plugin.getLogger().warning("GUI upgrade dialogs/search.yml:" + path + ".width=" + width
                            + " is invalid; using newest bundled default " + bundledWidth);
                }
                continue;
            }
            boolean valid = switch (path) {
                case "body" -> value instanceof String || value instanceof List<?> list
                        && list.stream().allMatch(String.class::isInstance);
                case "max-length" -> value instanceof Number number && number.intValue() > 0
                        && number.doubleValue() == number.intValue();
                case "confirm-button", "cancel-button" -> value instanceof String text && !text.isBlank();
                case "title", "field-label" -> value instanceof String text && !text.isBlank();
                default -> value instanceof String;
            };
            if (valid) target.set(path, value);
            else plugin.getLogger().warning("GUI upgrade dialogs/search.yml:" + path + "=" + value
                    + " is invalid; using newest bundled default " + target.get(path));
        }
        AuctionSearchDialog.validate(target);
        return target.saveToString().getBytes(StandardCharsets.UTF_8);
    }
}
