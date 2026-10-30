package me.foesio.foAuction.gui;

import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.MenuDialogRequest;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import me.foesio.core.message.FoMessageService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Owner-editable action dialog; missing or invalid files use the inventory menu. */
public final class SlotUpgradeDialogConfig {
    private final JavaPlugin plugin;
    private final Path file;
    private YamlConfiguration active;

    public SlotUpgradeDialogConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("dialogs/slot-upgrades.yml");
    }

    public void installOnce() {
        Path marker = plugin.getDataFolder().toPath().resolve(".slot-upgrades-dialog-v1");
        if (Files.exists(marker)) return;
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) plugin.saveResource("dialogs/slot-upgrades.yml", false);
            Files.writeString(marker, "installed\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not install dialogs/slot-upgrades.yml: " + exception.getMessage());
        }
    }

    public void load() {
        if (!Files.isRegularFile(file)) {
            active = null;
            return;
        }
        try {
            YamlConfiguration candidate = StrictGuiYaml.load(file);
            build(candidate, false, true, UnaryOperator.identity());
            build(candidate, true, true, UnaryOperator.identity());
            active = candidate;
        } catch (Exception exception) {
            plugin.getLogger().warning("Invalid dialogs/slot-upgrades.yml; using inventory fallback: "
                    + exception.getMessage());
            active = null;
        }
    }

    public MenuDialogRequest request(Player player, FoMessageService messages,
                                     Map<String, String> values, boolean confirmation, boolean canBuy) {
        if (active == null) return null;
        try {
            return build(active, confirmation, canBuy,
                    text -> messages.renderTemplateForViewer(player, text, values));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not render dialogs/slot-upgrades.yml; using inventory fallback: "
                    + exception.getMessage());
            active = null;
            return null;
        }
    }

    static MenuDialogRequest build(YamlConfiguration config, boolean confirmation, boolean canBuy,
                                   UnaryOperator<String> render) {
        String prefix = confirmation ? "confirm." : "menu.";
        List<MenuDialogRequest.Button> buttons = new ArrayList<>();
        if (confirmation) {
            buttons.add(new MenuDialogRequest.Button("confirm", "emerald",
                    render.apply(required(config, prefix + "buy-label")),
                    render.apply(required(config, prefix + "buy-tooltip"))));
            buttons.add(new MenuDialogRequest.Button("cancel", "barrier",
                    render.apply(required(config, prefix + "cancel-label")), ""));
        } else {
            if (canBuy) buttons.add(new MenuDialogRequest.Button("buy", "emerald",
                    render.apply(required(config, prefix + "buy-label")),
                    render.apply(required(config, prefix + "buy-tooltip"))));
            buttons.add(new MenuDialogRequest.Button("done", "iron_door",
                    render.apply(required(config, prefix + "done-label")), ""));
        }
        return MenuDialogRequest.builder()
                .title(render.apply(required(config, prefix + "title")))
                .body(requiredLines(config, prefix + "body").stream().map(render).toList())
                .buttons(buttons)
                .columns(1)
                .bodyWidth(320)
                .buttonWidth(200)
                .showBack(!confirmation)
                .backButton(DialogButton.icon("iron_door", render.apply(required(config, "menu.back-label"))))
                .canCloseWithEscape(true)
                .pause(false)
                .build();
    }

    private static String required(YamlConfiguration config, String path) {
        String text = config.getString(path, "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Missing " + path);
        return text;
    }

    private static List<String> requiredLines(YamlConfiguration config, String path) {
        if (!config.isList(path)) throw new IllegalArgumentException("Missing list " + path);
        List<String> lines = config.getStringList(path);
        if (lines.isEmpty() || lines.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Empty or invalid " + path);
        }
        return lines;
    }
}
