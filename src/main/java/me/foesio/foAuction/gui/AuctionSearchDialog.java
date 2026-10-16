package me.foesio.foAuction.gui;

import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import me.foesio.core.message.FoMessageService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Owner-owned search dialog. Missing optional fields stay absent on reload. */
public final class AuctionSearchDialog {
    private final JavaPlugin plugin;
    private final Path file;
    private YamlConfiguration active;

    public AuctionSearchDialog(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = plugin.getDataFolder().toPath().resolve("dialogs/search.yml");
    }

    public void load() {
        if (!Files.exists(file)) {
            active = null;
            return;
        }
        try {
            YamlConfiguration candidate = StrictGuiYaml.load(file);
            validate(candidate);
            active = candidate;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid dialogs/search.yml; "
                    + (active == null ? "chat search remains available: " : "previous dialog remains active: ")
                    + exception.getMessage());
        }
    }

    public void reload() {
        load();
    }

    public TextDialogRequest request(Player viewer, FoMessageService messages, String current) {
        YamlConfiguration config = active;
        if (config == null) return null;
        Map<String, String> placeholders = Map.of("current", current == null ? "" : current);
        return build(config, value -> messages.renderTemplateForViewer(viewer, value, placeholders));
    }

    static TextDialogRequest build(YamlConfiguration config, UnaryOperator<String> render) {
        validate(config);
        return new TextDialogRequest(
                render.apply(required(config, "title")),
                body(config).stream().map(render).toList(),
                render.apply(required(config, "field-label")),
                render.apply(optional(config, "initial-value")),
                render.apply(optional(config, "placeholder")),
                button(config, "confirm", render),
                button(config, "cancel", render),
                positive(config, "body-width"), positive(config, "input-width"),
                positive(config, "max-length"),
                config.getBoolean("label-visible"), config.getBoolean("can-close-with-escape"),
                config.getBoolean("pause"));
    }

    private static DialogButton button(YamlConfiguration config, String kind, UnaryOperator<String> render) {
        String path = kind + "-button";
        String label = config.isConfigurationSection(path) ? required(config, path + ".label")
                : required(config, path);
        String tooltip = config.isConfigurationSection(path) ? optional(config, path + ".tooltip")
                : optional(config, kind + "-tooltip");
        String icon = config.isConfigurationSection(path) ? optional(config, path + ".icon")
                : optional(config, kind + "-icon");
        int width = config.isConfigurationSection(path) ? positive(config, path + ".width")
                : positive(config, kind + "-width");
        return new DialogButton(render.apply(label), render.apply(tooltip), width, render.apply(icon));
    }

    static void validate(YamlConfiguration config) {
        required(config, "title");
        required(config, "field-label");
        for (String kind : List.of("confirm", "cancel")) {
            String path = kind + "-button";
            if (config.isConfigurationSection(path)) {
                required(config, path + ".label");
                positive(config, path + ".width");
                optional(config, path + ".tooltip");
                optional(config, path + ".icon");
            } else {
                required(config, path);
                positive(config, kind + "-width");
            }
        }
        for (String path : List.of("body-width", "input-width", "max-length")) positive(config, path);
        for (String path : List.of("label-visible", "can-close-with-escape", "pause")) {
            if (!(config.get(path) instanceof Boolean)) throw new IllegalArgumentException(path + " must be boolean");
        }
        body(config);
        for (String path : List.of("initial-value", "placeholder", "confirm-tooltip", "cancel-tooltip",
                "confirm-icon", "cancel-icon")) {
            if (config.contains(path, false) && !(config.get(path) instanceof String)) {
                throw new IllegalArgumentException(path + " must be text");
            }
        }
    }

    private static List<String> body(YamlConfiguration config) {
        if (!config.contains("body", false)) return List.of();
        Object raw = config.get("body");
        if (raw instanceof String text) return text.isEmpty() ? List.of() : List.of(text.split("\\R", -1));
        if (raw instanceof List<?> lines && lines.stream().allMatch(String.class::isInstance)) {
            return lines.stream().map(String.class::cast).toList();
        }
        throw new IllegalArgumentException("body must be text or a list of text");
    }

    private static String required(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(path + " must be nonempty text");
        }
        return text;
    }

    private static String optional(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (value == null) return "";
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(path + " must be text");
    }

    private static int positive(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Number number) || number.intValue() <= 0 || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(path + " must be a positive whole number");
        }
        return number.intValue();
    }
}
