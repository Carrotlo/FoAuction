package me.foesio.foAuction.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure layout rules shared by GUI rendering and click dispatch. */
final class GuiLayoutResolver {

    private GuiLayoutResolver() {
    }

    static Map<String, Integer> controls(List<Control> controls) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (Control control : controls) {
            resolved.put(control.path(), control.requested());
        }

        boolean changed;
        do {
            changed = false;
            Map<Integer, Integer> occurrences = new HashMap<>();
            for (int slot : resolved.values()) {
                occurrences.merge(slot, 1, Integer::sum);
            }
            for (Control control : controls) {
                int current = resolved.get(control.path());
                if (occurrences.getOrDefault(current, 0) <= 1 || current == control.fallback()) {
                    continue;
                }
                resolved.put(control.path(), control.fallback());
                changed = true;
            }
        } while (changed);

        // Defaults are expected to be unique. This final guard makes a bad
        // future schema deterministic instead of leaving render/click drift.
        Set<Integer> used = new LinkedHashSet<>();
        for (Control control : controls) {
            int slot = resolved.get(control.path());
            if (used.add(slot)) {
                continue;
            }
            int fallback = control.fallback();
            if (used.add(fallback)) {
                resolved.put(control.path(), fallback);
            }
        }
        return Map.copyOf(resolved);
    }

    static int[] contentSlots(int[] requested, int[] fallback, int inventorySize, Collection<Integer> reserved) {
        int[] filtered = filter(requested, inventorySize, reserved);
        return filtered.length == 0 ? filter(fallback, inventorySize, reserved) : filtered;
    }

    private static int[] filter(int[] values, int inventorySize, Collection<Integer> reserved) {
        Set<Integer> seen = new LinkedHashSet<>();
        List<Integer> accepted = new ArrayList<>();
        for (int slot : values) {
            if (slot < 0 || slot >= inventorySize || reserved.contains(slot) || !seen.add(slot)) {
                continue;
            }
            accepted.add(slot);
        }
        return accepted.stream().mapToInt(Integer::intValue).toArray();
    }

    record Control(String path, int requested, int fallback) {
    }
}
