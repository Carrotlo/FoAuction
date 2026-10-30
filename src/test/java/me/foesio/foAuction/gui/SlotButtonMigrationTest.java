package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlotButtonMigrationTest {
    @TempDir Path folder;
    private final Logger logger = Logger.getLogger("slot-test");

    @Test
    void backfillsOnceWithoutChangingOwnerTextOrRestoringDeletedButton() throws Exception {
        Path gui = folder.resolve("listings.yml");
        String owner = "title: Custom Auctions\nrows: 6\nbuttons:\n  back:\n    slot: 49\n    name: Custom Back\nentries:\n  listings:\n    slots: [10]\n";
        Files.writeString(gui, owner);
        SlotButtonMigration.install(gui, logger);
        assertEquals("Custom Back", StrictGuiYaml.load(gui).getString("buttons.back.name"));
        assertEquals(50, StrictGuiYaml.load(gui).getInt("buttons.buy-slots.slot"));
        assertFalse(StrictGuiYaml.load(gui).getBoolean("buttons.buy-slots.states.gem-disabled.visible", true));
        assertEquals(owner, Files.readString(folder.resolve("listings.yml.before-slot-button-v1.bak")));

        Files.writeString(gui, owner);
        SlotButtonMigration.install(gui, logger);
        assertFalse(StrictGuiYaml.load(gui).contains("buttons.buy-slots"));
    }

    @Test
    void occupiedSlotLeavesOwnerGuiUntouched() throws Exception {
        Path gui = folder.resolve("listings.yml");
        String owner = "title: Custom Auctions\nrows: 6\nbuttons:\n  custom:\n    slot: 50\nentries:\n  listings:\n    slots: [10]\n";
        Files.writeString(gui, owner);
        SlotButtonMigration.install(gui, logger);
        assertEquals(owner, Files.readString(gui));
        assertTrue(Files.exists(folder.resolve(".slot-button-v1")));
    }

    @Test
    void retriesAfterOwnerRepairsMalformedGui() throws Exception {
        Path gui = folder.resolve("listings.yml");
        String malformed = "buttons: [broken\n";
        Files.writeString(gui, malformed);
        SlotButtonMigration.install(gui, logger);
        assertEquals(malformed, Files.readString(gui));
        assertFalse(Files.exists(folder.resolve(".slot-button-v1")));

        Files.writeString(gui, "title: Auctions\nrows: 6\nbuttons:\n  back:\n    slot: 49\nentries:\n  listings:\n    slots: [10]\n");
        SlotButtonMigration.install(gui, logger);
        assertEquals(50, StrictGuiYaml.load(gui).getInt("buttons.buy-slots.slot"));
        assertTrue(Files.exists(folder.resolve(".slot-button-v1")));
    }

    @Test
    void addsHiddenStateToAnExistingButtonWithoutChangingOwnerText() throws Exception {
        Path gui = folder.resolve("listings.yml");
        String owner = "title: Custom Auctions\nrows: 6\nbuttons:\n  buy-slots:\n    slot: 50\n"
                + "    action: listings.buy-slots\nentries:\n  listings:\n    slots: [10]\n";
        Files.writeString(gui, owner);

        SlotButtonMigration.install(gui, logger);
        assertEquals(owner, Files.readString(folder.resolve("listings.yml.before-slot-button-hidden-v1.bak")));
        assertFalse(StrictGuiYaml.load(gui).getBoolean("buttons.buy-slots.states.gem-disabled.visible", true));

        Files.writeString(gui, owner);
        SlotButtonMigration.install(gui, logger);
        assertFalse(StrictGuiYaml.load(gui).contains("buttons.buy-slots.states.gem-disabled"));
    }

    @Test
    void addsHiddenStateBesideAnOwnerButtonState() throws Exception {
        Path gui = folder.resolve("listings.yml");
        Files.writeString(gui, "title: Auctions\nrows: 6\nbuttons:\n  buy-slots:\n    slot: 50\n"
                + "    action: listings.buy-slots\n    states:\n      sold-out:\n        clickable: false\n"
                + "entries:\n  listings:\n    slots: [10]\n");

        SlotButtonMigration.install(gui, logger);
        var installed = StrictGuiYaml.load(gui);
        assertTrue(installed.isConfigurationSection("buttons.buy-slots.states.sold-out"));
        assertFalse(installed.getBoolean("buttons.buy-slots.states.gem-disabled.visible", true));
    }
}
