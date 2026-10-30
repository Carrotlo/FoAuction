package me.foesio.foAuction.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionSettingsGemSwitchTest {
    @Test
    void addsDefaultOffToOlderConfigAndPreservesOwnerChoice() {
        YamlConfiguration oldConfig = new YamlConfiguration();
        oldConfig.set("auction.default-max-slots", 3);
        AuctionSettings.backfillGemSlotSwitch(oldConfig);
        assertTrue(oldConfig.isSet("auction.gem-slots.enabled"));
        assertFalse(oldConfig.getBoolean("auction.gem-slots.enabled"));

        oldConfig.set("auction.gem-slots.enabled", true);
        AuctionSettings.backfillGemSlotSwitch(oldConfig);
        assertTrue(oldConfig.getBoolean("auction.gem-slots.enabled"));
    }
}
