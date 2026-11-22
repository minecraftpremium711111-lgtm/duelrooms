package com.duelrooms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public final class Util {
    private Util() {}

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public static void saveLoc(ConfigurationSection root, String path, Location l) {
        if (l == null || l.getWorld() == null) {
            root.set(path, null);
            return;
        }
        root.set(path + ".world", l.getWorld().getName());
        root.set(path + ".x", l.getX());
        root.set(path + ".y", l.getY());
        root.set(path + ".z", l.getZ());
        root.set(path + ".yaw", (double) l.getYaw());
        root.set(path + ".pitch", (double) l.getPitch());
    }

    public static Location loadLoc(ConfigurationSection root, String path) {
        ConfigurationSection s = root.getConfigurationSection(path);
        if (s == null) return null;
        String worldName = s.getString("world", "");
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            Bukkit.getLogger().warning("[DuelRooms] World '" + worldName + "' is not loaded; location '" + s.getCurrentPath() + "' ignored.");
            return null;
        }
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public static Location blockCenter(Location l) {
        return new Location(l.getWorld(), l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
    }

    public static String fmt(Location l) {
        if (l == null || l.getWorld() == null) return "unset";
        return l.getWorld().getName() + " " + Math.round(l.getX() * 10) / 10.0 + " "
                + Math.round(l.getY() * 10) / 10.0 + " " + Math.round(l.getZ() * 10) / 10.0;
    }
}
