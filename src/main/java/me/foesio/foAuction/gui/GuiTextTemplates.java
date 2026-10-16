package me.foesio.foAuction.gui;

import java.util.ArrayList;
import java.util.List;

/** Expands list-valued placeholders without forcing GUI style in Java. */
final class GuiTextTemplates {

    private GuiTextTemplates() {
    }

    static List<String> expandLine(List<String> template, String placeholder, List<String> values) {
        List<String> expanded = new ArrayList<>();
        for (String line : template) {
            if (line.equals(placeholder)) {
                expanded.addAll(values);
            } else {
                expanded.add(line);
            }
        }
        return List.copyOf(expanded);
    }
}
