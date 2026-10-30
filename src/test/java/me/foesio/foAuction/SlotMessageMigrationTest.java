package me.foesio.foAuction;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlotMessageMigrationTest {
    @Test
    void versionTwoPreservesAnIntentionallyRemovedChatLink() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("slots.version", 2);
        messages.set("slots.max-name", "Owner text");

        assertTrue(FoAuction.backfillSlotMessages(messages));
        assertEquals(4, messages.getInt("slots.version"));
        assertEquals("Owner text", messages.getString("slots.max-name"));
        assertFalse(messages.contains("slots.no-slots-action"));
        assertTrue(messages.isString("slots.unlimited-name"));
        assertTrue(messages.isString("slots.admin-missing-playerpoints"));
    }

    @Test
    void versionThreeReceivesOnlyTheNewWarnings() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("slots.version", 3);
        messages.set("slots.unavailable", "Owner text");

        assertTrue(FoAuction.backfillSlotMessages(messages));
        assertEquals(4, messages.getInt("slots.version"));
        assertEquals("Owner text", messages.getString("slots.unavailable"));
        assertTrue(messages.isString("slots.disabled"));
        assertTrue(messages.isString("slots.admin-missing-playerpoints"));
        assertFalse(messages.contains("slots.no-slots-action"));
    }

    @Test
    void olderInstallReceivesChatLinkOnlyOnce() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("slots.version", 1);

        assertTrue(FoAuction.backfillSlotMessages(messages));
        assertTrue(messages.isString("slots.no-slots-action"));
        messages.set("slots.no-slots-action", null);
        assertFalse(FoAuction.backfillSlotMessages(messages));
        assertFalse(messages.contains("slots.no-slots-action"));
    }
}
