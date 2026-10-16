package me.foesio.foAuction.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiConfigServiceMigrationTest {

    @Test
    void newSchemaPathsAreAddedWithoutReplacingOwnerValuesAndAreIdempotent() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("title-format", "{title} | Page {page}");
        defaults.set("frame.material", "GRAY_STAINED_GLASS_PANE");
        defaults.set("items.sort.option-selected", "selected {option}");

        YamlConfiguration installed = new YamlConfiguration();
        installed.set("frame.material", "BLUE_STAINED_GLASS_PANE");

        List<String> newPaths = List.of(
                "title-format",
                "frame.material",
                "items.sort.option-selected"
        );
        assertTrue(GuiConfigService.addMissingPaths(installed, defaults, newPaths));
        assertEquals("BLUE_STAINED_GLASS_PANE", installed.getString("frame.material"));
        assertEquals("{title} | Page {page}", installed.getString("title-format"));
        assertEquals("selected {option}", installed.getString("items.sort.option-selected"));
        assertFalse(GuiConfigService.addMissingPaths(installed, defaults, newPaths));
    }

    @Test
    void legacyDecorationMaterialMovesOnlyWhenNewPathIsAbsent() {
        YamlConfiguration installed = new YamlConfiguration();
        installed.set("filler.material", "BLUE_STAINED_GLASS_PANE");
        installed.set("empty-slot-filler.material", "BLACK_STAINED_GLASS_PANE");

        assertTrue(GuiConfigService.migrateLegacyDecorationPaths(installed));
        assertEquals("BLUE_STAINED_GLASS_PANE", installed.getString("frame.material"));
        assertEquals("BLACK_STAINED_GLASS_PANE", installed.getString("content-filler.material"));
        assertFalse(GuiConfigService.migrateLegacyDecorationPaths(installed));

        installed.set("frame.material", "RED_STAINED_GLASS_PANE");
        assertFalse(GuiConfigService.migrateLegacyDecorationPaths(installed));
        assertEquals("RED_STAINED_GLASS_PANE", installed.getString("frame.material"));
    }

    @Test
    void customModelDataAloneCountsAsAnItemOverride() {
        YamlConfiguration installed = new YamlConfiguration();
        installed.set("items.back.custom-model-data", 42);

        assertTrue(GuiConfigService.hasItemOverride(installed, "items.back"));
    }
}
