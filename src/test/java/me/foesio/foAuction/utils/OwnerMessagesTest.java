package me.foesio.foAuction.utils;

import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class OwnerMessagesTest {
    @TempDir Path temp;

    @Test
    void deletedAndEmptyMessagesStaySilentWhileScalarAndListMessagesSend() throws Exception {
        Path file = temp.resolve("messages.yml");
        Files.writeString(file, "command:\n  scalar: 'Áuction {item}'\n"
                + "  lines: ['Één {item}', 'Twee {item}']\n  empty: []\n"
                + "gui-values:\n  compact-suffixes: ['千', '万']\n");
        FoMessageService messages = FoMessageService.loadPreservingRemovals(
                plugin(), FoMessageMigrations.none());
        OwnerMessages.configure(file);
        List<String> sent = new ArrayList<>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null
                            && args.length == 1 && args[0] instanceof String line) sent.add(line);
                    return null;
                });
        OwnerMessages.send(messages, sender, "command.deleted");
        OwnerMessages.send(messages, sender, "command.empty");
        assertEquals(List.of(), sent);
        assertEquals(List.of("千", "万"), OwnerMessages.optionalList(
                messages, "gui-values.compact-suffixes"));
        assertEquals(List.of(), OwnerMessages.optionalList(messages, "command.empty"));
        OwnerMessages.send(messages, sender, "command.scalar", "item", "bok");
        OwnerMessages.send(messages, sender, "command.lines", "item", "boek");
        assertEquals(List.of("Áuction bok", "Één boek", "Twee boek"), sent);

        Files.writeString(file, "command:\n  lines: []\n"
                + "gui-values:\n  compact-suffixes: []\n");
        messages.reload();
        OwnerMessages.reload(file);
        sent.clear();
        OwnerMessages.send(messages, sender, "command.scalar", "item", "bok");
        OwnerMessages.send(messages, sender, "command.lines", "item", "boek");
        assertEquals(List.of(), sent);
        assertEquals(List.of(), OwnerMessages.optionalList(messages, "command.lines"));
        assertEquals(List.of(), OwnerMessages.optionalList(messages, "command.deleted"));
        assertEquals(List.of(), OwnerMessages.optionalList(messages, "gui-values.compact-suffixes"));
    }

    private TestPlugin plugin() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        TestPlugin plugin = (TestPlugin) unsafe.allocateInstance(TestPlugin.class);
        Field dataFolder = JavaPlugin.class.getDeclaredField("dataFolder");
        dataFolder.setAccessible(true);
        dataFolder.set(plugin, temp.toFile());
        Field description = JavaPlugin.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(plugin, new PluginDescriptionFile("FoAuction", "3.4", "test.TestPlugin"));
        Field pluginMeta = JavaPlugin.class.getDeclaredField("pluginMeta");
        pluginMeta.setAccessible(true);
        pluginMeta.set(plugin, Proxy.newProxyInstance(PluginMeta.class.getClassLoader(),
                new Class<?>[] {PluginMeta.class}, (proxy, method, args) ->
                        method.getName().equals("getName") ? "FoAuction" : null));
        return plugin;
    }

    private static final class TestPlugin extends JavaPlugin {
        @Override public Logger getLogger() { return Logger.getLogger("OwnerMessagesTest"); }
    }
}
