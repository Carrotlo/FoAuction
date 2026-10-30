package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SlotUpgradeDialogConfigTest {
    @Test
    void bundledDialogHasPurchaseReviewAndConfirmationActions() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/dialogs/slot-upgrades.yml"));
        var menu = SlotUpgradeDialogConfig.build(yaml, false, true, UnaryOperator.identity());
        var confirmation = SlotUpgradeDialogConfig.build(yaml, true, true, UnaryOperator.identity());
        assertEquals("buy", menu.buttons().get(0).key());
        assertEquals("done", menu.buttons().get(1).key());
        assertEquals("confirm", confirmation.buttons().get(0).key());
        assertEquals("cancel", confirmation.buttons().get(1).key());
        assertFalse(menu.body().isEmpty());
        assertFalse(confirmation.body().isEmpty());
    }

    @Test
    void malformedBodyIsRejectedForInventoryFallback() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/dialogs/slot-upgrades.yml"));
        yaml.set("menu.body", "wrong type");
        assertThrows(IllegalArgumentException.class,
                () -> SlotUpgradeDialogConfig.build(yaml, false, true, UnaryOperator.identity()));
    }
}
