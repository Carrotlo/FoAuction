package me.foesio.foAuction.gui;

import me.foesio.foAuction.FoAuction;
import me.foesio.foAuction.utils.ColorPalette;
import me.foesio.core.config.ResourceFiles;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.gui.GuiItemConfig;
import me.foesio.core.material.MaterialTypes;
import me.foesio.core.migration.FoMigrationStore;
import me.foesio.core.message.FoMessageService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GuiConfigService {
    private static final String[] GUI_FILES = {
            "main.yml",
            "listings.yml",
            "seller-view.yml",
            "claims.yml",
            "history.yml",
            "preview.yml",
            "confirmation.yml"
    };
    private static final String[] LEGACY_GUI_FILES = {
            "admin-remove.yml"
    };
    private static final Map<String, List<String>> GUI_SCHEMA_V3_PATHS = schemaV3Paths();

    private final JavaPlugin plugin;
    private final FoMessageService messages;
    private final FoMigrationStore migrations;
    private final Map<String, FileConfiguration> configs;
    private final Set<String> warnedLayoutConflicts;

    public GuiConfigService(JavaPlugin plugin, FoMessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.migrations = FoMigrationStore.create(plugin);
        this.configs = new HashMap<>();
        this.warnedLayoutConflicts = new HashSet<>();
        reload();
    }

    public void reload() {
        File folder = new File(plugin.getDataFolder(), "guis");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning(ColorPalette.log("Could not create guis folder: " + folder.getAbsolutePath()));
            FoAuction.fileLogger().warn("Could not create guis folder: " + folder.getAbsolutePath() + ".");
        }

        configs.clear();
        warnedLayoutConflicts.clear();
        for (String fileName : GUI_FILES) {
            File file = new File(folder, fileName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            configs.put(fileName, config);
        }
        for (String fileName : LEGACY_GUI_FILES) {
            File file = new File(folder, fileName);
            configs.put(fileName, file.isFile()
                    ? YamlConfiguration.loadConfiguration(file)
                    : new YamlConfiguration());
        }
    }

    public String text(Player viewer, String fileName, String path, String fallback, Object... placeholders) {
        FileConfiguration config = config(fileName);
        return messages.renderTemplateForViewer(viewer, config.getString(path, fallback), placeholderValues(placeholders));
    }

    public List<String> textList(Player viewer, String fileName, String path, List<String> fallback, Object... placeholders) {
        FileConfiguration config = config(fileName);
        List<String> values = config.isList(path) ? config.getStringList(path) : fallback;
        Map<String, String> placeholderValues = placeholderValues(placeholders);
        return values.stream().map(line -> messages.renderTemplateForViewer(viewer, line, placeholderValues)).toList();
    }

    public int slot(String fileName, String path, int fallback) {
        return slot(fileName, path, fallback, 54);
    }

    public int slot(String fileName, String path, int fallback, int inventorySize) {
        FileConfiguration config = config(fileName);
        int slot = config.getInt(path, fallback);
        if (slot < 0 || slot >= inventorySize) {
            plugin.getLogger().warning(ColorPalette.log("Invalid GUI slot " + slot + " at guis/" + fileName + ":" + path + ". Using " + fallback + "."));
            FoAuction.fileLogger().warn("Invalid GUI slot " + slot + " at guis/" + fileName + ":" + path + ". Using " + fallback + ".");
            return fallback;
        }
        return slot;
    }

    public int[] slots(String fileName, String path, int[] fallback) {
        FileConfiguration config = config(fileName);
        List<Integer> values = config.getIntegerList(path);
        if (values.isEmpty()) {
            return fallback;
        }

        int[] slots = values.stream()
                .filter(slot -> {
                    boolean valid = slot >= 0 && slot <= 53;
                    if (!valid) {
                        plugin.getLogger().warning(ColorPalette.log("Invalid GUI content slot " + slot + " at guis/" + fileName + ":" + path + "."));
                        FoAuction.fileLogger().warn("Invalid GUI content slot " + slot + " at guis/" + fileName + ":" + path + ".");
                    }
                    return valid;
                })
                .mapToInt(Integer::intValue)
                .toArray();
        return slots.length == 0 ? fallback : slots;
    }

    public void warnLayoutConflict(String fileName, String path, int requested, int resolved) {
        String key = fileName + ':' + path + ':' + requested + ':' + resolved;
        if (!warnedLayoutConflicts.add(key)) {
            return;
        }
        String warning = "GUI slot collision at guis/" + fileName + ':' + path
                + " (" + requested + "). Using " + resolved + " so rendering and clicks stay aligned.";
        plugin.getLogger().warning(ColorPalette.log(warning));
        FoAuction.fileLogger().warn(warning);
    }

    public void warnContentSlotAdjustment(String fileName) {
        String key = fileName + ":content-slots";
        if (!warnedLayoutConflicts.add(key)) {
            return;
        }
        String warning = "Ignored duplicate, out-of-range, or control-overlapping content slots in guis/"
                + fileName + " so rendered entries and click actions stay aligned.";
        plugin.getLogger().warning(ColorPalette.log(warning));
        FoAuction.fileLogger().warn(warning);
    }

    public ItemStack item(
            Player viewer,
            String fileName,
            String path,
            Material fallbackMaterial,
            String fallbackName,
            List<String> fallbackLore,
            boolean fallbackGlow,
            Object... placeholders
    ) {
        return item(viewer, fileName, path, fallbackMaterial, fallbackName, fallbackLore,
                null, fallbackGlow, placeholders);
    }

    /**
     * Builds a configured item while using lore that has already expanded a
     * list-valued placeholder such as {@code {options}}.
     */
    public ItemStack itemWithResolvedLore(
            Player viewer,
            String fileName,
            String path,
            Material fallbackMaterial,
            String fallbackName,
            List<String> resolvedLore,
            boolean fallbackGlow,
            Object... placeholders
    ) {
        return item(viewer, fileName, path, fallbackMaterial, fallbackName, resolvedLore,
                resolvedLore, fallbackGlow, placeholders);
    }

    private ItemStack item(
            Player viewer,
            String fileName,
            String path,
            Material fallbackMaterial,
            String fallbackName,
            List<String> fallbackLore,
            List<String> resolvedLore,
            boolean fallbackGlow,
            Object... placeholders
    ) {
        FileConfiguration config = config(fileName);
        Material material = material(fileName, path + ".material", fallbackMaterial);
        ItemStack item = new ItemStack(material, Math.max(1, config.getInt(path + ".amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = resolveItemLore(config, path, fallbackLore, resolvedLore);
            Map<String, String> placeholderValues = placeholderValues(placeholders);
            String name = messages.renderTemplateForViewer(viewer,
                    config.getString(path + ".name", fallbackName), placeholderValues);
            List<String> renderedLore = lore.stream()
                    .map(line -> messages.renderTemplateForViewer(viewer, line, placeholderValues))
                    .toList();
            applyViewerMeta(meta, viewer, name, renderedLore);
            if (config.isSet(path + ".custom-model-data")) {
                meta.setCustomModelData(GuiItemConfig.parseCustomModelData(
                        config.get(path + ".custom-model-data"), null));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (config.getBoolean(path + ".hide-enchants", true)) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            boolean glow = config.isSet(path + ".glow")
                    ? config.getBoolean(path + ".glow")
                    : fallbackGlow;
            if (glow) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    static List<String> resolveItemLore(
            FileConfiguration config,
            String path,
            List<String> fallbackLore,
            List<String> resolvedLore
    ) {
        if (resolvedLore != null) {
            return List.copyOf(resolvedLore);
        }
        return config.isList(path + ".lore")
                ? config.getStringList(path + ".lore")
                : fallbackLore;
    }

    /**
     * Applies only explicitly configured legacy item fields to a new core item.
     * This keeps core defaults for new installations while preserving old GUI
     * material, text, lore, amount, glow, and item-flag choices.
     */
    public ItemStack itemOrFallback(
            Player viewer,
            String fileName,
            String path,
            ItemStack fallback,
            Object... placeholders
    ) {
        FileConfiguration config = config(fileName);
        return itemOrFallback(viewer, fileName, config, path, fallback, placeholders);
    }

    private ItemStack itemOrFallback(
            Player viewer,
            String fileName,
            FileConfiguration config,
            String path,
            ItemStack fallback,
            Object... placeholders
    ) {
        if (!hasItemOverride(config, path)) {
            return fallback;
        }

        ItemStack item = fallback.clone();
        if (config.isSet(path + ".material")) {
            item.setType(material(fileName, path + ".material", item.getType()));
        }
        if (config.isSet(path + ".amount")) {
            item.setAmount(Math.max(1, config.getInt(path + ".amount", item.getAmount())));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        Map<String, String> placeholderValues = placeholderValues(placeholders);
        boolean hasNameOverride = config.isSet(path + ".name");
        boolean hasLoreOverride = config.isSet(path + ".lore");
        if (hasNameOverride && hasLoreOverride) {
            String name = messages.renderTemplateForViewer(viewer,
                    config.getString(path + ".name", ""), placeholderValues);
            List<String> lore = config.isList(path + ".lore") ? config.getStringList(path + ".lore") : List.of();
            List<String> renderedLore = lore.stream()
                    .map(line -> messages.renderTemplateForViewer(viewer, line, placeholderValues))
                    .toList();
            applyViewerMeta(meta, viewer, name, renderedLore);
        } else if (hasNameOverride) {
            String name = messages.renderTemplateForViewer(viewer,
                    config.getString(path + ".name", ""), placeholderValues);
            if (!DialogIcons.applyItemMeta(meta, viewer, DialogIcons.render(viewer, name), meta.lore())) {
                meta.setDisplayName(DialogIcons.fallbackText(name));
            }
        } else if (hasLoreOverride) {
            List<String> lore = config.isList(path + ".lore") ? config.getStringList(path + ".lore") : List.of();
            List<String> renderedLore = lore.stream()
                    .map(line -> messages.renderTemplateForViewer(viewer, line, placeholderValues))
                    .toList();
            List<net.kyori.adventure.text.Component> loreComponents = renderedLore.stream()
                    .map(line -> DialogIcons.render(viewer, line))
                    .toList();
            if (!DialogIcons.applyItemMeta(meta, viewer, meta.displayName(), loreComponents)) {
                meta.setLore(renderedLore.stream().map(DialogIcons::fallbackText).toList());
            }
        }
        if (config.isSet(path + ".hide-enchants")) {
            if (config.getBoolean(path + ".hide-enchants")) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        if (config.isSet(path + ".glow")) {
            if (config.getBoolean(path + ".glow")) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeEnchant(Enchantment.UNBREAKING);
            }
        }
        if (config.isSet(path + ".custom-model-data")) {
            meta.setCustomModelData(GuiItemConfig.parseCustomModelData(
                    config.get(path + ".custom-model-data"), null));
        }
        item.setItemMeta(meta);
        return item;
    }

    private void applyViewerMeta(ItemMeta meta, Player viewer, String name, List<String> lore) {
        String safeName = name == null ? "" : name;
        if (DialogIcons.applyItemMeta(meta, viewer, safeName, lore)) {
            return;
        }
        if (name != null) {
            meta.setDisplayName(DialogIcons.fallbackText(name));
        }
        meta.setLore(lore == null
                ? List.of()
                : lore.stream().map(DialogIcons::fallbackText).toList());
    }

    /**
     * Reads an optional legacy frame/filler material. Exact old light-gray
     * defaults are upgraded to the current gray default; custom materials win.
     */
    public Material fillerMaterial(String fileName, Material fallback) {
        FileConfiguration config = config(fileName);
        for (String path : List.of("filler.material", "frame.material", "items.filler.material")) {
            if (!config.isSet(path)) {
                continue;
            }
            Material selected = material(fileName, path, fallback);
            return selected == Material.LIGHT_GRAY_STAINED_GLASS_PANE ? fallback : selected;
        }
        return fallback;
    }

    /**
     * Reads an optional legacy empty-entry filler without changing the current
     * intentional light-gray default for empty auction/claim/history slots.
     */
    public Material contentFillerMaterial(String fileName, Material fallback) {
        FileConfiguration config = config(fileName);
        for (String path : List.of(
                "content-filler.material",
                "empty-slot-filler.material",
                "items.empty-slot.material"
        )) {
            if (config.isSet(path)) {
                return material(fileName, path, fallback);
            }
        }
        return fallback;
    }

    private Map<String, String> placeholderValues(Object... placeholders) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.put(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        return values;
    }

    private Material material(String fileName, String path, Material fallback) {
        String raw = config(fileName).getString(path, fallback.name());
        Material material = MaterialTypes.match(raw == null ? "" : raw);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning(ColorPalette.log("Invalid GUI material " + raw + " at guis/" + fileName + ":" + path + ". Using " + fallback.name() + "."));
            FoAuction.fileLogger().warn("Invalid GUI material " + raw + " at guis/" + fileName + ":" + path + ". Using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    private FileConfiguration config(String fileName) {
        FileConfiguration config = configs.get(fileName);
        if (config == null) {
            plugin.getLogger().warning(ColorPalette.log("Missing GUI config guis/" + fileName + ". Using built-in fallbacks."));
            FoAuction.fileLogger().warn("Missing GUI config guis/" + fileName + ". Using built-in fallbacks.");
            return new YamlConfiguration();
        }
        return config;
    }

    private boolean backfillMissingDefaults(String fileName, File file, YamlConfiguration config) {
        try (InputStream input = plugin.getResource("guis/" + fileName)) {
            if (input == null) {
                return true;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            );
            boolean changed = false;
            changed |= migrateLegacyMainHistoryButtons(fileName, config);
            changed |= migrateDefaultButtonStyles(fileName, config, defaults);
            for (String path : defaults.getKeys(true)) {
                Object value = defaults.get(path);
                if (value instanceof ConfigurationSection) {
                    continue;
                }
                if (!config.isSet(path)) {
                    config.set(path, value);
                    changed = true;
                }
            }
            changed |= migrateLegacyAuctionContentSlots(fileName, config);
            changed |= migrateLegacyHistoryLayout(fileName, config);
            changed |= migrateLegacyStandardSlots(fileName, config);
            changed |= migrateLegacyFillerDefaults(config);

            if (changed) {
                backupBeforeMigration(file);
                config.save(file);
                FoAuction.fileLogger().info("Backfilled missing GUI defaults in guis/" + fileName + ".");
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning(ColorPalette.log("Could not update GUI config guis/" + fileName + ": " + exception.getMessage()));
                FoAuction.fileLogger().warn("Could not update GUI config guis/" + fileName + ": " + exception.getMessage() + ".");
            return false;
        }
    }

    /**
     * GUI defaults and legacy presentation migrations are one-time release
     * work. Reload must only re-read the live files so a deliberate owner
     * removal is not silently restored.
     */
    private void migrateGuiFiles() {
        migrations.runToVersion(2, () -> {
            for (String fileName : GUI_FILES) {
                File file = new File(plugin.getDataFolder(), "guis" + File.separator + fileName);
                ResourceFiles.saveDefault(plugin, "guis/" + fileName);
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                if (!backfillMissingDefaults(fileName, file, config)) {
                    return false;
                }
            }
            return true;
        });
        if (migrations.version() < 2) {
            return;
        }
        migrations.runToVersion(3, this::migrateSchemaV3);
    }

    private boolean migrateSchemaV3() {
        Map<File, YamlConfiguration> changedConfigs = new LinkedHashMap<>();
        Map<File, byte[]> originalFiles = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<String>> entry : GUI_SCHEMA_V3_PATHS.entrySet()) {
                String fileName = entry.getKey();
                File file = new File(plugin.getDataFolder(), "guis" + File.separator + fileName);
                ResourceFiles.saveDefault(plugin, "guis/" + fileName);
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                try (InputStream input = plugin.getResource("guis/" + fileName)) {
                    if (input == null) {
                        continue;
                    }
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(input, StandardCharsets.UTF_8)
                    );
                    boolean changed = migrateLegacyDecorationPaths(config);
                    changed |= addMissingPaths(config, defaults, entry.getValue());
                    if (changed) {
                        changedConfigs.put(file, config);
                        originalFiles.put(file, Files.readAllBytes(file.toPath()));
                    }
                }
            }

            for (Map.Entry<File, YamlConfiguration> changed : changedConfigs.entrySet()) {
                backupBeforeMigration(changed.getKey());
                changed.getValue().save(changed.getKey());
            }
            for (File changed : changedConfigs.keySet()) {
                FoAuction.fileLogger().info("Added FoAuction 3.4 GUI settings to guis/" + changed.getName() + ".");
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            for (Map.Entry<File, byte[]> original : originalFiles.entrySet()) {
                try {
                    Files.write(original.getKey().toPath(), original.getValue());
                } catch (IOException rollbackFailure) {
                    plugin.getLogger().severe(ColorPalette.log("Could not roll back GUI config "
                            + original.getKey().getName() + ": " + rollbackFailure.getMessage()));
                }
            }
            plugin.getLogger().warning(ColorPalette.log("Could not apply FoAuction 3.4 GUI migration: " + exception.getMessage()));
            FoAuction.fileLogger().warn("Could not apply FoAuction 3.4 GUI migration: " + exception.getMessage() + ".");
            return false;
        }
    }

    static boolean migrateLegacyDecorationPaths(YamlConfiguration config) {
        boolean changed = false;
        changed |= copyFirstPresent(config, "frame.material", "filler.material", "items.filler.material");
        changed |= copyFirstPresent(config, "content-filler.material", "empty-slot-filler.material", "items.empty-slot.material");
        return changed;
    }

    private static boolean copyFirstPresent(YamlConfiguration config, String target, String... sources) {
        if (config.isSet(target)) {
            return false;
        }
        for (String source : sources) {
            if (!config.isSet(source)) {
                continue;
            }
            config.set(target, config.get(source));
            return true;
        }
        return false;
    }

    static boolean addMissingPaths(YamlConfiguration config, YamlConfiguration defaults, List<String> paths) {
        boolean changed = false;
        for (String path : paths) {
            if (config.isSet(path) || !defaults.isSet(path)) {
                continue;
            }
            config.set(path, defaults.get(path));
            changed = true;
        }
        return changed;
    }

    private static Map<String, List<String>> schemaV3Paths() {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        paths.put("main.yml", List.of(
                "frame.material", "frame.name", "frame.lore",
                "content-filler.material", "content-filler.name", "content-filler.lore",
                "items.previous.material", "items.sort.lore", "items.sort.option-selected", "items.sort.option-unselected",
                "items.filter.lore", "items.filter.option-selected", "items.filter.option-unselected",
                "items.search.material", "items.history.material", "items.clear-search.material", "items.next.material"
        ));
        paths.put("listings.yml", List.of(
                "title-format", "frame.material", "frame.name", "frame.lore",
                "content-filler.material", "content-filler.name", "content-filler.lore",
                "items.previous.material", "items.back.material", "items.sort.lore",
                "items.sort.option-selected", "items.sort.option-unselected", "items.next.material"
        ));
        paths.put("seller-view.yml", List.of(
                "title-format", "frame.material", "frame.name", "frame.lore",
                "items.previous.material", "items.back.material", "items.sort.lore",
                "items.sort.option-selected", "items.sort.option-unselected", "items.next.material"
        ));
        paths.put("claims.yml", List.of(
                "title-format", "frame.material", "frame.name", "frame.lore",
                "content-filler.material", "content-filler.name", "content-filler.lore",
                "items.previous.material", "items.back.material", "items.next.material", "claim-entry.money.material"
        ));
        paths.put("history.yml", List.of(
                "title-format", "frame.material", "frame.name", "frame.lore",
                "content-filler.material", "content-filler.name", "content-filler.lore",
                "items.previous.material", "items.back.material", "items.next.material"
        ));
        paths.put("preview.yml", List.of(
                "title-format", "border.material", "border.name", "border.lore", "items.back.material"
        ));
        return Map.copyOf(paths);
    }

    private boolean migrateDefaultButtonStyles(String fileName, YamlConfiguration config, YamlConfiguration defaults) {
        boolean changed = false;
        if ("main.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.claims.name", "{theme}ᴄʟᴀɪᴍꜱ");
            changed |= replaceExactDefault(config, defaults, "items.claims.lore", List.of("{white}Open your claim box"));
            changed |= replaceExactDefault(config, defaults, "items.sort.name", "{theme}ꜱᴏʀᴛ");
            changed |= replaceExactDefault(config, defaults, "items.filter.name", "{theme}ꜰɪʟᴛᴇʀ");
            changed |= replaceExactDefault(config, defaults, "items.refresh.name", "{theme}ʀᴇꜰʀᴇꜱʜ");
            changed |= replaceExactDefault(config, defaults, "items.refresh.lore", List.of("{white}Reload listings"));
            changed |= replaceExactDefault(config, defaults, "items.manage.name", "{theme}ᴍᴀɴᴀɢᴇ ᴀᴜᴄᴛɪᴏɴꜱ");
            changed |= replaceExactDefault(config, defaults, "items.manage.lore", List.of(
                    "{white}View all your active auctions",
                    "{white}Click any listing to cancel"
            ));
            changed |= replaceExactDefault(config, defaults, "items.search.name", "{theme}ꜱᴇᴀʀᴄʜ");
            changed |= replaceExactDefault(config, defaults, "items.search.lore", List.of("{white}Open search prompt"));
            changed |= replaceExactDefault(config, defaults, "items.history.name", "{theme}ʜɪꜱᴛᴏʀʏ");
            changed |= replaceExactDefault(config, defaults, "items.history.lore", List.of("{white}Click to see sold auctions"));
            changed |= replaceExactDefault(config, defaults, "items.clear-search.name", "{theme}ʜɪꜱᴛᴏʀʏ");
            changed |= replaceExactDefault(config, defaults, "items.clear-search.lore", List.of("{white}Click to see sold auctions"));
        } else if ("listings.yml".equals(fileName) || "seller-view.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.back.name", "{theme}ʙᴀᴄᴋ");
            changed |= replaceExactDefault(config, defaults, "items.back.lore", List.of("{white}Return to auction house"));
            changed |= replaceExactDefault(config, defaults, "items.sort.name", "{theme}ꜱᴏʀᴛ");
            changed |= replaceExactDefault(config, defaults, "items.refresh.name", "{theme}ʀᴇꜰʀᴇꜱʜ");
            String refreshLore = "listings.yml".equals(fileName)
                    ? "{white}Reload your listings"
                    : "{white}Reload seller listings";
            changed |= replaceExactDefault(config, defaults, "items.refresh.lore", List.of(refreshLore));
        } else if ("claims.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.back.name", "{theme}ʙᴀᴄᴋ");
            changed |= replaceExactDefault(config, defaults, "items.back.lore", List.of("{white}Return to auction house"));
            changed |= replaceExactDefault(config, defaults, "items.refresh.name", "{theme}ʀᴇꜰʀᴇꜱʜ");
            changed |= replaceExactDefault(config, defaults, "items.refresh.lore", List.of("{white}Reload claims"));
        } else if ("history.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.back.name", "{theme}ʙᴀᴄᴋ");
            changed |= replaceExactDefault(config, defaults, "items.back.lore", List.of("{white}Return to auction house"));
        } else if ("preview.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.back.name", "{theme}ʙᴀᴄᴋ");
            changed |= replaceExactDefault(config, defaults, "items.back.lore", List.of("{white}Return to auction house"));
        } else if ("confirmation.yml".equals(fileName)) {
            changed |= replaceExactDefault(config, defaults, "items.cancel.name", "{bad}ᴄᴀɴᴄᴇʟ");
            changed |= replaceExactDefault(config, defaults, "items.cancel.lore", List.of(
                    "{white}Price: {theme}{price}",
                    "{white}Fee: {theme}{fee}"
            ));
            changed |= replaceExactDefault(config, defaults, "items.confirm.name", "{good}ᴄᴏɴꜰɪʀᴍ");
            changed |= replaceExactDefault(config, defaults, "items.confirm.lore", List.of(
                    "{white}Price: {theme}{price}",
                    "{white}Fee: {theme}{fee}"
            ));
        }
        return changed;
    }

    private boolean replaceExactDefault(YamlConfiguration config, YamlConfiguration defaults, String path, Object oldValue) {
        if (!config.isSet(path) || !Objects.equals(config.get(path), oldValue) || !defaults.isSet(path)) {
            return false;
        }
        Object current = config.get(path);
        Object replacement = defaults.get(path);
        if (Objects.equals(current, replacement)) {
            return false;
        }
        config.set(path, replacement);
        return true;
    }

    private boolean migrateLegacyAuctionContentSlots(String fileName, YamlConfiguration config) {
        if (!"listings.yml".equals(fileName) && !"claims.yml".equals(fileName)) {
            return false;
        }

        List<Integer> legacySlots = List.of(
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        );
        if (!config.getIntegerList("content-slots").equals(legacySlots)) {
            return false;
        }

        config.set("content-slots", List.of(
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34
        ));
        return true;
    }

    private boolean migrateLegacyHistoryLayout(String fileName, YamlConfiguration config) {
        if (!"history.yml".equals(fileName)) {
            return false;
        }

        boolean changed = false;
        List<Integer> legacyContentSlots = List.of(
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25
        );
        if (config.getIntegerList("content-slots").equals(legacyContentSlots)) {
            config.set("content-slots", List.of(
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34
            ));
            changed = true;
        }

        if (config.getInt("items.previous.slot", -1) == 27) {
            config.set("items.previous.slot", 45);
            changed = true;
        }
        if (config.getInt("items.back.slot", -1) == 31) {
            config.set("items.back.slot", 49);
            changed = true;
        }
        if (config.getInt("items.next.slot", -1) == 35) {
            config.set("items.next.slot", 53);
            changed = true;
        }
        if (config.getInt("items.empty.slot", -1) == 13) {
            config.set("items.empty.slot", 4);
            changed = true;
        }
        return changed;
    }

    private boolean migrateLegacyStandardSlots(String fileName, YamlConfiguration config) {
        if (!"listings.yml".equals(fileName)
                && !"seller-view.yml".equals(fileName)
                && !"claims.yml".equals(fileName)) {
            return false;
        }

        boolean changed = false;
        changed |= migrateExactSlot(config, "items.back.slot", 46, 49);
        changed |= migrateExactSlot(config, "items.refresh.slot", 49, 48);
        return changed;
    }

    private boolean migrateExactSlot(YamlConfiguration config, String path, int oldDefault, int newDefault) {
        if (config.getInt(path, Integer.MIN_VALUE) != oldDefault) {
            return false;
        }
        config.set(path, newDefault);
        return true;
    }

    private boolean migrateLegacyFillerDefaults(YamlConfiguration config) {
        boolean changed = false;
        for (String path : List.of("filler.material", "frame.material", "items.filler.material")) {
            if (!config.isSet(path)) {
                continue;
            }
            Material configured = MaterialTypes.match(config.getString(path, ""));
            if (configured == Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                config.set(path, Material.GRAY_STAINED_GLASS_PANE.name());
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateLegacyMainHistoryButtons(String fileName, YamlConfiguration config) {
        if (!"main.yml".equals(fileName) || !hasItemOverride(config, "items.history-or-clear")) {
            return false;
        }

        boolean changed = false;
        for (String targetPath : List.of("items.history", "items.clear-search")) {
            if (hasItemOverride(config, targetPath)) {
                continue;
            }
            for (String field : List.of("material", "amount", "name", "lore", "hide-enchants", "glow", "custom-model-data")) {
                String source = "items.history-or-clear." + field;
                if (!config.isSet(source)) {
                    continue;
                }
                config.set(targetPath + "." + field, config.get(source));
                changed = true;
            }
        }
        return changed;
    }

    static boolean hasItemOverride(FileConfiguration config, String path) {
        return config.isSet(path + ".material")
                || config.isSet(path + ".amount")
                || config.isSet(path + ".name")
                || config.isSet(path + ".lore")
                || config.isSet(path + ".hide-enchants")
                || config.isSet(path + ".glow")
                || config.isSet(path + ".custom-model-data");
    }

    private void backupBeforeMigration(File file) throws IOException {
        if (!file.isFile()) {
            return;
        }
        File backup = new File(file.getParentFile(), file.getName() + ".pre-migration.bak");
        if (!backup.exists()) {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
}
