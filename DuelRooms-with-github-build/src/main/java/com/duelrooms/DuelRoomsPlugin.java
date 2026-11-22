package com.duelrooms;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelRoomsPlugin extends JavaPlugin {
    private YamlFile messagesFile, roomsFile, hologramsFile;
    private Messages messages;
    private RoomManager rooms;
    private HologramManager holograms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messagesFile = new YamlFile(this, "messages.yml");
        roomsFile = new YamlFile(this, "rooms.yml");
        hologramsFile = new YamlFile(this, "holograms.yml");
        messages = new Messages(this);
        rooms = new RoomManager(this);
        holograms = new HologramManager(this);
        rooms.load();
        holograms.load();

        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        DuelCommand cmd = new DuelCommand(this);
        PluginCommand pc = getCommand("duel");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getLogger().info("DuelRooms enabled with " + rooms.all().size() + " room(s).");
    }

    @Override
    public void onDisable() {
        if (rooms != null) rooms.shutdown();
        if (holograms != null) holograms.shutdown();
    }

    /** Reloads config.yml, messages.yml, rooms.yml (idle rooms only) and holograms.yml. */
    public void reloadAll() {
        reloadConfig();
        messagesFile.load();
        roomsFile.load();
        hologramsFile.load();
        rooms.load();
        holograms.load();
    }

    public YamlFile messagesFile() { return messagesFile; }
    public YamlFile roomsFile() { return roomsFile; }
    public YamlFile holoFile() { return hologramsFile; }
    public Messages messages() { return messages; }
    public RoomManager rooms() { return rooms; }
    public HologramManager holograms() { return holograms; }
}
