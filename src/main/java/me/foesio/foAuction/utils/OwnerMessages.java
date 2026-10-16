package me.foesio.foAuction.utils;

import me.foesio.core.message.FoMessageService;
import me.foesio.core.gui.publicgui.StrictGuiYaml;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends only owner-configured public feedback, including multiline messages. */
public final class OwnerMessages {
    private static volatile YamlConfiguration ownerConfig = new YamlConfiguration();

    private OwnerMessages() {
    }

    public static void configure(Path file) {
        try {
            reload(file);
        } catch (IOException | InvalidConfigurationException exception) {
            ownerConfig = new YamlConfiguration();
        }
    }

    public static void reload(Path file) throws IOException, InvalidConfigurationException {
        ownerConfig = StrictGuiYaml.load(file);
    }

    public static void send(FoMessageService messages, CommandSender recipient,
                            String path, String... placeholders) {
        YamlConfiguration config = ownerConfig;
        if (!config.isString(path) && !config.isList(path)) return;
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            values.put(placeholders[index], placeholders[index + 1]);
        }
        if (config.isList(path)) {
            if (optionalList(messages, path, values).stream().noneMatch(line -> !line.isBlank())) return;
        } else if (messages.render(path, "", placeholders).isBlank()) {
            return;
        }
        messages.send(recipient, path, "", placeholders);
    }

    public static List<String> optionalList(FoMessageService messages, String path) {
        return optionalList(messages, path, Map.of());
    }

    public static List<String> optionalList(FoMessageService messages, String path,
                                             Map<String, String> placeholders) {
        if (!ownerConfig.isList(path) || ownerConfig.getStringList(path).isEmpty()) return List.of();
        return messages.renderList(path, List.of(""), placeholders);
    }

    public static boolean isConfiguredList(String path) {
        return ownerConfig.isList(path);
    }

    public static boolean isConfiguredScalar(String path) {
        return ownerConfig.isString(path);
    }
}
