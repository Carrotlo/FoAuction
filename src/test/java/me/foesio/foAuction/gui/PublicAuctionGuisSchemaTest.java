package me.foesio.foAuction.gui;

import me.foesio.core.gui.publicgui.ClickKind;
import me.foesio.core.gui.publicgui.GuiSnapshot;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiPlanner;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiStartupFallback;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublicAuctionGuisSchemaTest {
    private static final List<String> MENUS = List.of("main", "listings", "seller-view", "claims",
            "history", "preview", "confirmation", "sell-confirmation");

    @Test
    void everyPublicGuiParsesWithItsRealCoreContract() throws Exception {
        for (String menu : MENUS) {
            PublicGuiDefinition definition = load(menu);
            assertEquals("auction." + menu, definition.menuId());
            assertTrue(definition.rows() >= 1 && definition.rows() <= 6);
        }
    }

    @Test
    void movedControlBindsOnlyTheNewSlot() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/guis/main.yml"));
        yaml.set("buttons.claims.slot", 44);
        PublicGuiDefinition definition = PublicGuiSchema.parse("auction.main", "guis/main.yml", yaml,
                PublicAuctionGuis.contractFor("main"));
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(2L, Map.of("auction.main", definition)),
                "auction.main", new PublicGuiRenderInput(
                        Map.of("current", "None", "page", "1", "max_page", "1"),
                        Map.of(),
                        Map.of("sort", new PublicGuiText.CycleSelection(List.of("High", "Low", "New", "Old"), 0),
                                "filter", new PublicGuiText.CycleSelection(
                                        List.of("All", "Weapons", "Armor", "Tools", "Blocks", "Food",
                                                "Farm", "Redstone", "Potions", "Misc"), 0)),
                        Map.of()));
        assertEquals("main.claims", frame.bindings().get(44).actionFor(ClickKind.LEFT));
        assertFalse(frame.bindings().containsKey(46));
    }

    @Test
    void removedOptionalButtonHasNoActionOrGhostClick() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/guis/claims.yml"));
        int formerSlot = yaml.getInt("buttons.refresh.slot");
        yaml.set("buttons.refresh", null);
        var actions = PublicAuctionGuis.actionsIn("claims", yaml);
        assertFalse(actions.contains("claims.refresh"));
        PublicGuiDefinition definition = PublicGuiSchema.parse("auction.claims", "guis/claims.yml",
                yaml, PublicAuctionGuis.contractFor("claims", actions));
        PublicGuiFrame frame = PublicGuiPlanner.plan(
                new GuiSnapshot<>(5L, Map.of("auction.claims", definition)), "auction.claims",
                new PublicGuiRenderInput(Map.of("page", "1", "max_page", "1"),
                        Map.of(), Map.of(), Map.of()));
        assertFalse(frame.bindings().containsKey(formerSlot));
    }

    @Test
    void invalidActionCanUseOnlyItsBundledStartupDefault() throws Exception {
        var bundled = StrictGuiYaml.load(Path.of("src/main/resources/guis/main.yml"));
        var owner = StrictGuiYaml.load(Path.of("src/main/resources/guis/main.yml"));
        owner.set("buttons.claims.action", "unknown.action");
        var actions = PublicAuctionGuis.runtimeActions("main", owner, bundled);
        assertTrue(actions.contains("main.claims"));
        PublicGuiDefinition definition = PublicGuiStartupFallback.parse(owner, bundled,
                yaml -> PublicGuiSchema.parse("auction.main", "guis/main.yml", yaml,
                        PublicAuctionGuis.contractFor("main", actions)), ignored -> { });
        assertEquals("main.claims", definition.buttons().get("claims").action());
        assertEquals("main.claims", owner.getString("buttons.claims.action"));
    }

    @Test
    void mixedClaimsHaveCoreLoreStatesAndStableActions() throws Exception {
        PublicGuiDefinition definition = load("claims");
        List<Integer> slots = definition.entries().get("claims").slots();
        assertEquals(21, slots.size());
        assertEquals(PublicGuiDefinition.EditMode.APPEND,
                definition.entries().get("claims").sourceItem().loreStates().get("item-note").mode());
        assertEquals(PublicGuiDefinition.EditMode.PRESERVE,
                definition.entries().get("claims").sourceItem().loreStates().get("money").mode());
        assertNotNull(definition.states().get("money"));
        assertNotNull(definition.states().get("invalid"));
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(3L, Map.of("auction.claims", definition)),
                "auction.claims", new PublicGuiRenderInput(Map.of("page", "1", "max_page", "1"),
                        Map.of(), Map.of(), Map.of()));
        assertFalse(frame.bindings().containsKey(slots.get(0)));
    }

    @Test
    void configuredTextAndRemovalsReachTheCoreFrame() throws Exception {
        var yaml = StrictGuiYaml.load(Path.of("src/main/resources/guis/main.yml"));
        yaml.set("title", "Übersicht {page}/{max_page}");
        yaml.set("buttons.claims.name", ":custom_sprite: {theme}Åben {page}");
        yaml.set("buttons.claims.lore", List.of("Én linje {page}"));
        yaml.set("buttons.refresh.lore", List.of());
        yaml.set("buttons.sort.enabled", false);
        PublicGuiDefinition definition = PublicGuiSchema.parse("auction.main", "guis/main.yml", yaml,
                PublicAuctionGuis.contractFor("main"));
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(4L, Map.of("auction.main", definition)),
                "auction.main", new PublicGuiRenderInput(
                        Map.of("page", "2", "max_page", "3", "current", "søgeord",
                                "theme", "#112233"), Map.of(),
                        Map.of("filter", new PublicGuiText.CycleSelection(List.of("Alle", "Våben", "Rustning",
                                "Værktøj", "Blokke", "Mad", "Landbrug", "Redstone", "Drikke", "Andet"), 1)),
                        Map.of()));
        assertEquals("Übersicht 2/3", frame.title());
        var claim = frame.visuals().get(definition.buttons().get("claims").slot());
        assertEquals(":custom_sprite: #112233Åben 2", PublicGuiText.scalar(
                claim.fixed().name(), claim.placeholders(), claim.path() + ".name"));
        assertEquals(List.of("Én linje 2"), PublicGuiText.lore(
                claim.fixed().lore(), claim.placeholders(), null, null, claim.path() + ".lore"));
        var refresh = frame.visuals().get(definition.buttons().get("refresh").slot());
        assertEquals(List.of(), refresh.fixed().lore());
        assertFalse(frame.bindings().containsKey(47));
    }

    @Test
    void sellConfirmationHasNoCloseControl() throws Exception {
        PublicGuiDefinition definition = load("sell-confirmation");
        assertFalse(definition.buttons().containsKey("cancel"));
        PublicGuiFrame frame = PublicGuiPlanner.plan(
                new GuiSnapshot<>(5L, Map.of("auction.sell-confirmation", definition)),
                "auction.sell-confirmation", new PublicGuiRenderInput(
                        Map.of("price", "1", "fee", "0"), Map.of(), Map.of(), Map.of()));
        assertFalse(frame.bindings().containsKey(11));
        assertEquals("confirmation.confirm", frame.bindings().get(15).actionFor(ClickKind.LEFT));
    }

    private PublicGuiDefinition load(String menu) throws Exception {
        return PublicGuiSchema.parse("auction." + menu, "guis/" + menu + ".yml",
                StrictGuiYaml.load(Path.of("src/main/resources/guis/" + menu + ".yml")),
                PublicAuctionGuis.contractFor(menu));
    }
}
