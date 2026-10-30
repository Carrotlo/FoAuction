package me.foesio.foAuction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ApiEventMessageMigrationTest {
    @Test
    void backfillsOnceWithoutReplacingOwnerTextOrResurrectingDeletion() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("command.listing-blocked", "My custom block message");

        assertTrue(FoAuction.backfillApiEventMessages(messages));
        assertEquals("My custom block message", messages.getString("command.listing-blocked"));
        assertTrue(messages.contains("gui.purchase-blocked"));
        assertTrue(messages.contains("gui.admin-virtual-seller"));

        messages.set("gui.purchase-blocked", null);
        assertFalse(FoAuction.backfillApiEventMessages(messages));
        assertFalse(messages.contains("gui.purchase-blocked"));
    }
}
