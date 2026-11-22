package com.duelrooms;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/** A YAML file in the plugin folder with the bundled copy used as defaults. */
public class YamlFile {
    private final JavaPlugin plugin;
    private final String name;
    private final File file;
    private YamlConfiguration cfg;

    public YamlFile(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
        this.file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        load();
    }

    public void load() {
        cfg = YamlConfiguration.loadConfiguration(file);
        InputStream in = plugin.getResource(name);
        if (in != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public YamlConfiguration get() {
        return cfg;
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + name, e);
        }
    }
}
