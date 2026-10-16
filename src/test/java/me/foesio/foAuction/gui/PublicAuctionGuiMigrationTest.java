package me.foesio.foAuction.gui;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublicAuctionGuiMigrationTest {
    private static final List<String> LEGACY = List.of("main", "listings", "seller-view",
            "claims", "history", "preview", "confirmation");

    @TempDir Path temp;

    @Test
    void freshInstallCopiesCurrentResourcesWithoutLegacyBackup() throws Exception {
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertTrue(Files.isRegularFile(temp.resolve("guis/sell-confirmation.yml")));
        assertTrue(Files.isRegularFile(temp.resolve("messages.yml")));
        assertTrue(Files.isRegularFile(temp.resolve("sounds.yml")));
        assertFalse(Files.exists(temp.resolve(".public-gui-fresh-install-pending")));
        assertFalse(YamlConfiguration.loadConfiguration(temp.resolve("guis/sell-confirmation.yml").toFile())
                .contains("buttons.cancel", false));
        assertTrue(Files.isRegularFile(temp.resolve("dialogs/search.yml")));
        assertFalse(Files.exists(temp.resolve("guis/main.yml.gui-v0-backup")));
        assertEquals(1, YamlConfiguration.loadConfiguration(temp.resolve("config.yml").toFile())
                .getInt("public-gui-schema-version"));
    }

    @Test
    void failedFreshVersionWriteCanRetryWithoutTreatingDefaultConfigAsOwnerUpgrade() throws Exception {
        Path config = temp.resolve("config.yml");
        Files.createDirectory(config);
        assertFalse(PublicAuctionGuiMigration.install(plugin(temp), false));
        assertFalse(Files.exists(temp.resolve("guis/main.yml")));
        assertFalse(Files.exists(temp.resolve("messages.yml")));
        assertTrue(Files.isRegularFile(temp.resolve(".public-gui-fresh-install-pending")));

        Files.delete(config);
        Files.writeString(config, "file-logging: false\n", StandardCharsets.UTF_8);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp), true));
        assertTrue(Files.isRegularFile(temp.resolve("guis/main.yml")));
        assertFalse(Files.exists(temp.resolve("guis/main.yml.gui-v0-backup")));
        assertFalse(Files.exists(temp.resolve(".public-gui-fresh-install-pending")));
    }

    @Test
    void unexpectedOwnerSellConfirmationStopsUpgradeWithoutOverwritingIt() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path ownerSell = temp.resolve("guis/sell-confirmation.yml");
        byte[] custom = "title: 'My separate confirmation'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(ownerSell, custom);
        byte[] oldMain = Files.readAllBytes(temp.resolve("guis/main.yml"));

        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(custom, Files.readAllBytes(ownerSell));
        assertArrayEquals(oldMain, Files.readAllBytes(temp.resolve("guis/main.yml")));
        assertFalse(Files.exists(temp.resolve("guis/main.yml.gui-v3-backup")));
    }

    @Test
    void nonFileOlderSoundsStopVersionAdvanceWithoutChangingOwnerGuis() throws Exception {
        installLegacy(temp, "legacy-guis");
        Files.createDirectory(temp.resolve("sounds.yml"));
        byte[] oldMain = Files.readAllBytes(temp.resolve("guis/main.yml"));

        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(oldMain, Files.readAllBytes(temp.resolve("guis/main.yml")));
        assertFalse(Files.exists(temp.resolve("config.yml")));
    }

    @Test
    void interruptedMixedSchemaCannotBeRemigratedAsAnOlderOwnerGui() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path main = temp.resolve("guis/main.yml");
        byte[] prior = Files.readAllBytes(main);
        Files.write(temp.resolve("guis/main.yml.gui-v3-backup"), prior);
        byte[] partial = Files.readAllBytes(Path.of("src/main/resources/guis/main.yml"));
        Files.write(main, partial);

        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(partial, Files.readAllBytes(main));
        assertArrayEquals(prior, Files.readAllBytes(temp.resolve("guis/main.yml.gui-v3-backup")));
        assertFalse(Files.exists(temp.resolve("config.yml")));
    }

    @Test
    void existingOwnerConfigWithoutGuiFilesIsNotMistakenForFreshInstall() throws Exception {
        byte[] original = "listing-fee-percent: 7.5\n".getBytes(StandardCharsets.UTF_8);
        Files.write(temp.resolve("config.yml"), original);
        assertFalse(PublicAuctionGuiMigration.install(plugin(temp), true));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("config.yml")));
        assertFalse(Files.exists(temp.resolve("guis/main.yml")));
        assertFalse(Files.exists(temp.resolve("messages.yml")));
    }

    @Test
    void oldDefaultsUpgradeWithCustomTextAndExactBackups() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path mainPath = temp.resolve("guis/main.yml");
        YamlConfiguration owner = YamlConfiguration.loadConfiguration(mainPath.toFile());
        owner.set("title", "Välkommen till auktioner");
        owner.set("items.claims.slot", 44);
        owner.set("items.claims.material", "NOT_A_MATERIAL");
        owner.save(mainPath.toFile());
        byte[] original = Files.readAllBytes(mainPath);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("guis/main.yml.gui-v0-backup")));
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(mainPath.toFile());
        assertEquals("Välkommen till auktioner", migrated.getString("title"));
        assertEquals(44, migrated.getInt("buttons.claims.slot"));
        assertEquals("CHEST_MINECART", migrated.getString("buttons.claims.material"));
        assertFalse(migrated.contains("items", false));
        assertEquals(1, YamlConfiguration.loadConfiguration(temp.resolve("config.yml").toFile())
                .getInt("public-gui-schema-version"));
        assertTrue(Files.isRegularFile(temp.resolve("sounds.yml")));
        assertFalse(YamlConfiguration.loadConfiguration(temp.resolve("guis/sell-confirmation.yml").toFile())
                .contains("buttons.cancel", false));
    }

    @Test
    void malformedOlderMessagesRepairOnlyInvalidPublicFields() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path messagePath = temp.resolve("messages.yml");
        byte[] original = ("tokens:\n  prefix: ['bad type']\ngui:\n"
                + "  own-listing:\n    invalid: text\n"
                + "  claim-item: 'Kravet er hentet'\n"
                + "command:\n  invalid-price: ['first', 4]\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(messagePath, original);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("messages.yml.gui-v0-backup")));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(messagePath.toFile());
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/messages.yml").toFile());
        assertEquals(bundled.getString("tokens.prefix"), upgraded.getString("tokens.prefix"));
        assertEquals(bundled.getString("gui.own-listing"), upgraded.getString("gui.own-listing"));
        assertEquals(bundled.getString("command.invalid-price"), upgraded.getString("command.invalid-price"));
        assertEquals("Kravet er hentet", upgraded.getString("gui.claim-item"));
    }

    @Test
    void failedVersionWriteRestoresEveryActiveOwnerFile() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path mainPath = temp.resolve("guis/main.yml");
        Path messagesPath = temp.resolve("messages.yml");
        byte[] originalMain = Files.readAllBytes(mainPath);
        byte[] originalMessages = Files.readAllBytes(messagesPath);
        Files.createDirectory(temp.resolve("config.yml"));
        TestPlugin plugin = plugin(temp);
        assertFalse(PublicAuctionGuiMigration.install(plugin));
        assertArrayEquals(originalMain, Files.readAllBytes(mainPath));
        assertArrayEquals(originalMessages, Files.readAllBytes(messagesPath));
        assertFalse(Files.exists(temp.resolve("guis/sell-confirmation.yml")));
        assertFalse(plugin.getConfig().isSet("public-gui-schema-version"));
    }

    @Test
    void failedVersionWriteAlsoRestoresRepairedSoundBytes() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path soundsPath = temp.resolve("sounds.yml");
        byte[] original = "gui:\n  open:\n    enabled: false\n    pitch: 9.0\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(soundsPath, original);
        Files.createDirectory(temp.resolve("config.yml"));

        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(soundsPath));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("sounds.yml.gui-v3-backup")));
    }

    @Test
    void newerLegacyShapeKeepsTranslationsAndRemovedOptionalControl() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path mainPath = temp.resolve("guis/main.yml");
        Path claimsPath = temp.resolve("guis/claims.yml");
        YamlConfiguration main = YamlConfiguration.loadConfiguration(mainPath.toFile());
        main.set("items.sort.lore-options", List.of("Højest", "Lavest", "Nyeste", "Ældste"));
        main.save(mainPath.toFile());
        Files.writeString(temp.resolve("messages.yml"),
                "tokens:\n  prefix: '&7[Auction] '\ngui-options: broken\n",
                StandardCharsets.UTF_8);
        YamlConfiguration claims = YamlConfiguration.loadConfiguration(claimsPath.toFile());
        claims.set("items.refresh", null);
        claims.save(claimsPath.toFile());
        Files.createDirectories(temp.resolve("dialogs"));
        Files.copy(Path.of("src/main/resources/migration/legacy-dialogs/search.yml"),
                temp.resolve("dialogs/search.yml"));
        Path dialogPath = temp.resolve("dialogs/search.yml");
        YamlConfiguration dialog = YamlConfiguration.loadConfiguration(dialogPath.toFile());
        dialog.set("confirm-button", null);
        dialog.set("confirm-button.label", "Anvend søgning");
        dialog.set("confirm-button.width", 136);
        dialog.set("confirm-button.tooltip", "Egen hjælp");
        dialog.set("max-length", 42);
        dialog.save(dialogPath.toFile());
        byte[] oldDialogBytes = Files.readAllBytes(dialogPath);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration convertedClaims = YamlConfiguration.loadConfiguration(claimsPath.toFile());
        assertFalse(convertedClaims.contains("buttons.refresh", false));
        assertFalse(PublicAuctionGuis.actionsIn("claims", convertedClaims).contains("claims.refresh"));
        YamlConfiguration convertedMessages = YamlConfiguration.loadConfiguration(temp.resolve("messages.yml").toFile());
        assertEquals(List.of("Højest", "Lavest", "Nyeste", "Ældste"),
                convertedMessages.getStringList("gui-options.main.sort"));
        YamlConfiguration convertedDialog = YamlConfiguration.loadConfiguration(dialogPath.toFile());
        assertEquals(320, convertedDialog.getInt("body-width"));
        assertEquals("Anvend søgning", convertedDialog.getString("confirm-button.label"));
        assertEquals(136, convertedDialog.getInt("confirm-button.width"));
        assertEquals("Egen hjælp", convertedDialog.getString("confirm-button.tooltip"));
        assertEquals(42, convertedDialog.getInt("max-length"));
        assertArrayEquals(oldDialogBytes, Files.readAllBytes(
                temp.resolve("dialogs/search.yml.gui-v3-backup")));
        assertTrue(Files.isRegularFile(temp.resolve("guis/main.yml.gui-v3-backup")));
        byte[] converted = Files.readAllBytes(mainPath);
        convertedClaims.set("buttons.back.lore", List.of());
        convertedClaims.set("buttons.back.name", "Tilbage uden ikon");
        convertedClaims.save(claimsPath.toFile());
        byte[] editedCurrent = Files.readAllBytes(claimsPath);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(converted, Files.readAllBytes(mainPath));
        assertArrayEquals(editedCurrent, Files.readAllBytes(claimsPath));
    }

    @Test
    void missingKnownLegacyPreviewSlotKeepsUpgradePending() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path confirmationPath = temp.resolve("guis/confirmation.yml");
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(confirmationPath.toFile());
        legacy.set("items.preview.slot", null);
        legacy.save(confirmationPath.toFile());
        byte[] original = Files.readAllBytes(confirmationPath);

        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(confirmationPath));
        assertFalse(Files.exists(temp.resolve("guis/confirmation.yml.gui-v3-backup")));
        assertFalse(Files.exists(temp.resolve("config.yml")));
    }

    @Test
    void oldestHistoryItemCustomizationSurvivesUpgrade() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path mainPath = temp.resolve("guis/main.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(mainPath.toFile());
        old.set("items.history-or-clear.material", "MAP");
        old.set("items.history-or-clear.name", "Éigen geschiedenis");
        old.set("items.history-or-clear.lore", List.of("Åangepaste regel"));
        old.save(mainPath.toFile());
        byte[] original = Files.readAllBytes(mainPath);

        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(
                temp.resolve("guis/main.yml.gui-v0-backup")));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(mainPath.toFile());
        assertEquals("MAP", upgraded.getString("buttons.history-or-clear.material"));
        assertEquals("Éigen geschiedenis", upgraded.getString("buttons.history-or-clear.name"));
        assertEquals(List.of("Åangepaste regel"),
                upgraded.getStringList("buttons.history-or-clear.lore"));
    }

    @Test
    void removedOlderControlsStayRemoved() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path mainPath = temp.resolve("guis/main.yml");
        YamlConfiguration main = YamlConfiguration.loadConfiguration(mainPath.toFile());
        main.set("items.sort.slot", null);
        main.set("items.refresh.material", null);
        main.set("items.history", null);
        main.set("items.claims.name", "Öwner claims");
        main.save(mainPath.toFile());
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(mainPath.toFile());
        assertFalse(upgraded.contains("buttons.sort", false));
        assertFalse(upgraded.contains("buttons.refresh", false));
        assertFalse(upgraded.contains("buttons.history-or-clear", false));
        assertEquals("Öwner claims", upgraded.getString("buttons.claims.name"));
    }

    @Test
    void released33HistoryAndClearSearchTextRemainEditable() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path mainPath = temp.resolve("guis/main.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(mainPath.toFile());
        old.set("items.history.name", "Owner history");
        old.set("items.clear-search.lore", List.of("Owner clear lore"));
        old.save(mainPath.toFile());

        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(mainPath.toFile());
        assertEquals("Owner history", upgraded.getString("buttons.history-or-clear.name"));
        assertEquals(List.of("Owner clear lore"),
                upgraded.getStringList("buttons.history-or-clear.states.clear-search.lore"));
    }

    @Test
    void invalidLegacySectionsUseCurrentDefaultsBesideValidCustomFields() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path mainPath = temp.resolve("guis/main.yml");
        YamlConfiguration main = YamlConfiguration.loadConfiguration(mainPath.toFile());
        main.set("frame", "invalid section");
        main.set("items.sort", "invalid section");
        main.set("items.claims.name", "Öwner claims");
        main.save(mainPath.toFile());
        Path previewPath = temp.resolve("guis/preview.yml");
        YamlConfiguration preview = YamlConfiguration.loadConfiguration(previewPath.toFile());
        preview.set("border", "invalid section");
        preview.save(previewPath.toFile());

        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(mainPath.toFile());
        assertTrue(upgraded.getBoolean("filler.enabled"));
        assertEquals("GRAY_STAINED_GLASS_PANE", upgraded.getString("filler.material"));
        assertEquals("main.sort", upgraded.getString("buttons.sort.action"));
        assertEquals("Öwner claims", upgraded.getString("buttons.claims.name"));
        YamlConfiguration upgradedMessages = YamlConfiguration.loadConfiguration(
                temp.resolve("messages.yml").toFile());
        assertEquals(List.of("Highest Price", "Lowest Price", "Most Recent", "Least Recent"),
                upgradedMessages.getStringList("gui-options.main.sort"));
        YamlConfiguration upgradedPreview = YamlConfiguration.loadConfiguration(previewPath.toFile());
        assertEquals("LIME_STAINED_GLASS_PANE",
                upgradedPreview.getString("decorations.border.material"));
    }

    @Test
    void oldSoundFieldsRepairIndividuallyWithoutRestoringRemovedEvents() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path soundsPath = temp.resolve("sounds.yml");
        byte[] original = ("gui:\n  open:\n    enabled: false\n"
                + "    sound: 'bad namespace:???'\n    category: PLAYERS\n"
                + "    volume: 0.25\n    pitch: 9.0\n"
                + "auction:\n  listing-created:\n    enabled: false\n"
                + "    sound: BLOCK_NOTE_BLOCK_BELL\n    category: INVALID\n"
                + "    volume: -1\n    pitch: 1.2\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(soundsPath, original);

        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("sounds.yml.gui-v3-backup")));
        YamlConfiguration repaired = YamlConfiguration.loadConfiguration(soundsPath.toFile());
        assertFalse(repaired.getBoolean("gui.open.enabled"));
        assertEquals("PLAYERS", repaired.getString("gui.open.category"));
        assertEquals("BLOCK_VAULT_OPEN_SHUTTER", repaired.getString("gui.open.sound"));
        assertEquals(0.25, repaired.getDouble("gui.open.volume"));
        assertEquals(1.0, repaired.getDouble("gui.open.pitch"));
        assertFalse(repaired.contains("gui.click", false));
        assertFalse(repaired.getBoolean("auction.listing-created.enabled"));
        assertEquals("MASTER", repaired.getString("auction.listing-created.category"));
        assertEquals(0.7, repaired.getDouble("auction.listing-created.volume"));
        assertEquals(1.2, repaired.getDouble("auction.listing-created.pitch"));
    }

    @Test
    void legacySoundAliasesMoveOnceButCurrentGuiSoundRemovalSurvives() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path soundsPath = temp.resolve("sounds.yml");
        Files.writeString(soundsPath, "auction:\n  menu-open:\n    enabled: false\n"
                + "    sound: BLOCK_NOTE_BLOCK_BELL\n    category: PLAYERS\n"
                + "    volume: 0.2\n    pitch: 0.7\n"
                + "  page-turn:\n    enabled: true\n    sound: BLOCK_NOTE_BLOCK_BELL\n"
                + "    category: MASTER\n    volume: 0.3\n    pitch: 1.3\n",
                StandardCharsets.UTF_8);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(soundsPath.toFile());
        assertFalse(upgraded.getBoolean("gui.open.enabled"));
        assertEquals("PLAYERS", upgraded.getString("gui.open.category"));
        assertEquals(0.2, upgraded.getDouble("gui.open.volume"));
        assertEquals(1.3, upgraded.getDouble("gui.page-next.pitch"));
        assertEquals(1.3, upgraded.getDouble("gui.page-previous.pitch"));
        assertFalse(upgraded.contains("gui.click", false));
        assertTrue(upgraded.contains("gui.back", false));
        assertFalse(upgraded.contains("auction.menu-open", false));
        byte[] migrated = Files.readAllBytes(soundsPath);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(migrated, Files.readAllBytes(soundsPath));

        Path mixedRoot = temp.resolve("mixed");
        installLegacy(mixedRoot, "legacy-guis");
        Path mixedSounds = mixedRoot.resolve("sounds.yml");
        Files.writeString(mixedSounds,
                "gui:\n  open:\n    enabled: false\n    sound: BLOCK_NOTE_BLOCK_BELL\n"
                + "auction:\n  page-turn: BLOCK_NOTE_BLOCK_BELL\n", StandardCharsets.UTF_8);
        assertTrue(PublicAuctionGuiMigration.install(plugin(mixedRoot)));
        upgraded = YamlConfiguration.loadConfiguration(mixedSounds.toFile());
        assertFalse(upgraded.contains("gui.click", false));
        assertFalse(upgraded.contains("gui.page-next", false));
        assertTrue(upgraded.contains("auction.page-turn", false));
    }

    @Test
    void invalidOldSoundGroupGetsBundledGroupWithoutChangingValidSibling() throws Exception {
        installLegacy(temp, "legacy-guis");
        Path soundsPath = temp.resolve("sounds.yml");
        Files.writeString(soundsPath,
                "gui: broken\nauction:\n  listing-created:\n    enabled: false\n"
                        + "    sound: BLOCK_NOTE_BLOCK_BELL\n    category: PLAYERS\n"
                        + "    volume: 0.25\n    pitch: 0.8\n", StandardCharsets.UTF_8);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        YamlConfiguration repaired = YamlConfiguration.loadConfiguration(soundsPath.toFile());
        assertTrue(repaired.isConfigurationSection("gui.open"));
        assertFalse(repaired.getBoolean("auction.listing-created.enabled"));
        assertEquals("PLAYERS", repaired.getString("auction.listing-created.category"));
    }

    @Test
    void malformedDialogLeavesAuctionMenusAvailableAndCanRetry() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path dialog = temp.resolve("dialogs/search.yml");
        Files.createDirectories(dialog.getParent());
        byte[] malformed = "title: [unterminated".getBytes(StandardCharsets.UTF_8);
        Files.write(dialog, malformed);
        TestPlugin plugin = plugin(temp);
        assertTrue(PublicAuctionGuiMigration.install(plugin));
        assertEquals(1, plugin.getConfig().getInt("public-gui-schema-version"));
        assertEquals(0, plugin.getConfig().getInt("public-search-dialog-schema-version"));
        assertArrayEquals(malformed, Files.readAllBytes(dialog));
        assertTrue(Files.isRegularFile(temp.resolve("guis/sell-confirmation.yml")));
        Files.copy(Path.of("src/main/resources/migration/legacy-dialogs-v0/search.yml"), dialog,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(PublicAuctionGuiMigration.install(plugin(temp)));
        assertEquals(1, YamlConfiguration.loadConfiguration(temp.resolve("config.yml").toFile())
                .getInt("public-search-dialog-schema-version"));
        assertEquals(320, YamlConfiguration.loadConfiguration(dialog.toFile()).getInt("body-width"));
    }

    @Test
    void ambiguousUnversionedLegacyFilesStayUntouched() throws Exception {
        installLegacy(temp, "legacy-guis-v0");
        Path mainPath = temp.resolve("guis/main.yml");
        Path claimsPath = temp.resolve("guis/claims.yml");
        YamlConfiguration main = YamlConfiguration.loadConfiguration(mainPath.toFile());
        main.set("title", "Egen auktion");
        main.set("items.claims.name", "Egne krav");
        main.save(mainPath.toFile());
        YamlConfiguration claims = YamlConfiguration.loadConfiguration(claimsPath.toFile());
        claims.set("title", "Egne krav");
        claims.set("items.back.slot", 49);
        claims.set("content-slots", YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/migration/legacy-guis/claims.yml").toFile())
                .getIntegerList("content-slots"));
        claims.save(claimsPath.toFile());
        byte[] mainBytes = Files.readAllBytes(mainPath);
        assertFalse(PublicAuctionGuiMigration.install(plugin(temp)));
        assertArrayEquals(mainBytes, Files.readAllBytes(mainPath));
        assertFalse(Files.exists(temp.resolve("guis/main.yml.gui-v0-backup")));
    }

    private void installLegacy(Path root, String schema) throws IOException {
        Path guiRoot = root.resolve("guis");
        Files.createDirectories(guiRoot);
        for (String menu : LEGACY) {
            Files.copy(Path.of("src/main/resources/migration", schema, menu + ".yml"),
                    guiRoot.resolve(menu + ".yml"));
        }
        Files.writeString(root.resolve("messages.yml"),
                "tokens:\n  prefix: '&7[Auction] '\ngui:\n  own-listing: 'Owner note'\n",
                StandardCharsets.UTF_8);
    }

    private TestPlugin plugin(Path root) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        TestPlugin plugin = (TestPlugin) unsafe.allocateInstance(TestPlugin.class);
        Field dataFolder = JavaPlugin.class.getDeclaredField("dataFolder");
        dataFolder.setAccessible(true);
        dataFolder.set(plugin, root.toFile());
        plugin.config = Files.isRegularFile(root.resolve("config.yml"))
                ? YamlConfiguration.loadConfiguration(root.resolve("config.yml").toFile())
                : new YamlConfiguration();
        return plugin;
    }

    private static final class TestPlugin extends JavaPlugin {
        private static final Logger LOGGER = Logger.getLogger("FoAuctionMigrationTest");
        static { LOGGER.setUseParentHandlers(false); }
        private FileConfiguration config;

        @Override public FileConfiguration getConfig() { return config; }
        @Override public Logger getLogger() { return LOGGER; }
        @Override public InputStream getResource(String name) {
            try {
                return Files.newInputStream(Path.of("src/main/resources").resolve(name));
            } catch (IOException exception) {
                return null;
            }
        }
    }
}
