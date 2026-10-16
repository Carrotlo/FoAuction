package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuctionSearchDialogTest {
    @Test
    void ownerTextAndRemovedDecorationsReachTheDialogRequest() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/dialogs/search.yml"));
        yaml.set("title", "Søgning Å {current}");
        yaml.set("body", List.of("Én linje {current}"));
        yaml.set("field-label", "Vælg søgning");
        yaml.set("confirm-button", "Søg nu");
        yaml.set("confirm-tooltip", "");
        yaml.set("confirm-icon", null);
        yaml.set("cancel-icon", null);
        var request = AuctionSearchDialog.build(yaml,
                text -> text.replace("{current}", "søgeord"));
        assertEquals("Søgning Å søgeord", request.title());
        assertEquals(List.of("Én linje søgeord"), request.body());
        assertEquals("Vælg søgning", request.fieldLabel());
        assertEquals("Søg nu", request.submitButton().label());
        assertEquals("", request.submitButton().tooltip());
        assertEquals("", request.submitButton().icon());
        assertEquals("", request.cancelButton().icon());
    }

    @Test
    void missingRequiredDialogTextIsRejected() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/dialogs/search.yml"));
        yaml.set("title", null);
        assertThrows(IllegalArgumentException.class, () -> AuctionSearchDialog.build(yaml, text -> text));
    }
}
