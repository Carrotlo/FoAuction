package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/** Adds the new control once, preserving owner text and later intentional deletions. */
public final class SlotButtonMigration {
    private static final String BUTTON = """

  buy-slots:
    slot: 50
    enabled: true
    material: "EMERALD"
    name: "{theme}&lMORE LISTING SLOTS"
    lore:
      - "&8ʙᴜᴛᴛᴏɴ"
      - " "
      - "&eⓘ Information ↓"
      - "&7&l | &fUnlock permanent extra listing slots."
      - " "
      - "{good}→ Click to View Upgrades ←"
    glow: true
    item-flags:
      - "HIDE_ATTRIBUTES"
    action: "listings.buy-slots"
    states:
      gem-disabled:
        visible: false
        clickable: false
""";

    private SlotButtonMigration() {}

    public static void install(JavaPlugin plugin) {
        install(plugin.getDataFolder().toPath().resolve("guis/listings.yml"), plugin.getLogger());
    }

    static void install(Path gui, Logger logger) {
        installButton(gui, logger);
        installHiddenState(gui, logger);
    }

    private static void installButton(Path gui, Logger logger) {
        Path marker = gui.resolveSibling(".slot-button-v1");
        if (Files.exists(marker) || !Files.isRegularFile(gui)) return;
        try {
            YamlConfiguration existing = StrictGuiYaml.load(gui);
            if (existing.isConfigurationSection("buttons.buy-slots")) {
                Files.writeString(marker, "installed\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            ConfigurationSection buttons = existing.getConfigurationSection("buttons");
            if (buttons == null || occupied(buttons, 50)) {
                logger.warning("Auction slot upgrade button could not use GUI slot 50; /auction slots remains available.");
                Files.writeString(marker, "slot occupied\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            // Insert immediately before the top-level entries section, leaving all owner text untouched.
            String original = Files.readString(gui, StandardCharsets.UTF_8);
            int entries = original.indexOf("\nentries:");
            if (entries < 0) throw new IllegalStateException("Missing top-level entries section");
            String changed = original.substring(0, entries) + BUTTON + original.substring(entries);
            Path backup = gui.resolveSibling("listings.yml.before-slot-button-v1.bak");
            Files.copy(gui, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.writeString(gui, changed, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                YamlConfiguration checked = StrictGuiYaml.load(gui);
                if (!checked.isConfigurationSection("buttons.buy-slots")) throw new IllegalStateException("Button not parsed");
                Files.writeString(marker, "installed\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (Exception exception) {
                Files.copy(backup, gui, StandardCopyOption.REPLACE_EXISTING);
                throw exception;
            }
        } catch (Exception exception) {
            logger.warning("Could not add auction slot upgrade button: " + exception.getMessage()
                    + "; /auction slots remains available.");
        }
    }

    private static void installHiddenState(Path gui, Logger logger) {
        Path marker = gui.resolveSibling(".slot-button-hidden-v1");
        if (Files.exists(marker) || !Files.isRegularFile(gui)) return;
        try {
            YamlConfiguration existing = StrictGuiYaml.load(gui);
            ConfigurationSection button = existing.getConfigurationSection("buttons.buy-slots");
            if (button == null || button.isConfigurationSection("states.gem-disabled")) {
                Files.writeString(marker, "installed\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            String original = Files.readString(gui, StandardCharsets.UTF_8);
            int buttonStart = original.indexOf("\n  buy-slots:");
            if (buttonStart < 0) throw new IllegalStateException("Could not locate buy-slots YAML block");
            int blockStart = buttonStart + 1;
            int blockEnd = original.length();
            for (int cursor = original.indexOf('\n', blockStart); cursor >= 0 && cursor + 1 < original.length();
                 cursor = original.indexOf('\n', cursor + 1)) {
                int line = cursor + 1;
                if (original.charAt(line) != ' ' && original.charAt(line) != '\n'
                        || original.startsWith("  ", line) && line + 2 < original.length()
                        && original.charAt(line + 2) != ' ' && original.charAt(line + 2) != '\n') {
                    blockEnd = line;
                    break;
                }
            }
            String block = original.substring(blockStart, blockEnd);
            int states = block.indexOf("\n    states:");
            String insertion;
            int position;
            if (states < 0) {
                position = blockEnd;
                insertion = (position > 0 && original.charAt(position - 1) != '\n' ? "\n" : "")
                        + "    states:\n      gem-disabled:\n        visible: false\n        clickable: false\n";
            } else {
                position = blockStart + states + "\n    states:".length();
                int endOfHeader = original.indexOf('\n', position);
                if (endOfHeader >= 0 && !original.substring(position, endOfHeader).isBlank()) {
                    throw new IllegalStateException("Owner states header has unsupported inline content");
                }
                insertion = "\n      gem-disabled:\n        visible: false\n        clickable: false";
            }
            String changed = original.substring(0, position) + insertion + original.substring(position);
            Path backup = gui.resolveSibling("listings.yml.before-slot-button-hidden-v1.bak");
            Files.copy(gui, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.writeString(gui, changed, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                YamlConfiguration checked = StrictGuiYaml.load(gui);
                if (checked.getBoolean("buttons.buy-slots.states.gem-disabled.visible", true)) {
                    throw new IllegalStateException("Hidden button state not parsed");
                }
                Files.writeString(marker, "installed\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (Exception exception) {
                Files.copy(backup, gui, StandardCopyOption.REPLACE_EXISTING);
                throw exception;
            }
        } catch (Exception exception) {
            logger.warning("Could not hide disabled auction slot button: " + exception.getMessage());
        }
    }

    private static boolean occupied(ConfigurationSection buttons, int slot) {
        for (String key : buttons.getKeys(false)) {
            if (buttons.getInt(key + ".slot", -1) == slot) return true;
        }
        return false;
    }
}
