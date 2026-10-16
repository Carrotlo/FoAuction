package me.foesio.foAuction.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuiLayoutResolverTest {

    @Test
    void collidingControlOverrideFallsBackForRenderAndClick() {
        Map<String, Integer> resolved = GuiLayoutResolver.controls(List.of(
                new GuiLayoutResolver.Control("previous", 46, 45),
                new GuiLayoutResolver.Control("claims", 46, 46),
                new GuiLayoutResolver.Control("next", 53, 53)
        ));

        assertEquals(45, resolved.get("previous"));
        assertEquals(46, resolved.get("claims"));
        assertEquals(53, resolved.get("next"));
    }

    @Test
    void contentSlotsExcludeControlsDuplicatesAndOutOfBoundsSlots() {
        int[] resolved = GuiLayoutResolver.contentSlots(
                new int[]{10, 10, 45, -1, 54, 11},
                new int[]{10, 11, 12},
                54,
                List.of(45, 53)
        );

        assertArrayEquals(new int[]{10, 11}, resolved);
    }
}
