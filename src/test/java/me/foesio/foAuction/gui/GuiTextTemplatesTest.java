package me.foesio.foAuction.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiTextTemplatesTest {

    @Test
    void expandsConfiguredCycleOptionLinesInPlace() {
        assertEquals(
                List.of("header", "selected A", "plain B", "footer"),
                GuiTextTemplates.expandLine(
                        List.of("header", "{options}", "footer"),
                        "{options}",
                        List.of("selected A", "plain B")
                )
        );
    }

    @Test
    void resolvedCycleLoreWinsOverRawConfiguredPlaceholder() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("items.sort.lore", List.of("header", "{options}", "footer"));

        assertEquals(
                List.of("header", "selected A", "plain B", "footer"),
                GuiConfigService.resolveItemLore(
                        config,
                        "items.sort",
                        List.of("fallback"),
                        List.of("header", "selected A", "plain B", "footer")
                )
        );
    }
}
