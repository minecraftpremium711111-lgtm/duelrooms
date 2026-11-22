package com.duelrooms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One TextDisplay entity per non-blank line. Entities are only spawned/changed when the rendered text actually
 * changes (state change or looting second) - never every tick. Entities are non-persistent and tagged, so
 * nothing is left in the world after a restart or reload.
 */
public class HologramManager {
    public static final String TAG = "duelrooms_hologram";

    private static class Holo {
        final int roomId;
        Location base;
        Double height, spacing;
        final List<TextDisplay> entities = new ArrayList<>();
        List<String> last;

        Holo(int roomId, Location base) {
            this.roomId = roomId;
            this.base = base;
        }
    }

    private final DuelRoomsPlugin plugin;
    private final Map<Integer, Holo> holos = new HashMap<>();

    public HologramManager(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ lifecycle

    public void load() {
        purgeStray();
        holos.clear();
        ConfigurationSection sec = plugin.holoFile().get().getConfigurationSection("holograms");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                int id;
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                Location base = Util.loadLoc(sec, key);
                if (base == null) continue;
                Holo h = new Holo(id, base);
                ConfigurationSection hs = sec.getConfigurationSection(key);
                if (hs != null) {
                    if (hs.contains("height")) h.height = hs.getDouble("height");
                    if (hs.contains("line-spacing")) h.spacing = hs.getDouble("line-spacing");
                }
                holos.put(id, h);
            }
        }
        updateAll();
    }

    public void reload() {
        plugin.holoFile().load();
        load();
    }

    public void shutdown() {
        for (Holo h : holos.values()) despawn(h);
        holos.clear();
    }

    private void purgeStray() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay t : w.getEntitiesByClass(TextDisplay.class)) {
                if (t.getScoreboardTags().contains(TAG)) t.remove();
            }
        }
    }

    // ------------------------------------------------------------ commands

    public void create(int roomId, Location at) {
        YamlConfiguration c = plugin.holoFile().get();
        Location base = c.getBoolean("settings.center-on-block", true)
                ? new Location(at.getWorld(), at.getBlockX() + 0.5, at.getY(), at.getBlockZ() + 0.5)
                : at.clone();
        Holo old = holos.remove(roomId);
        Holo h = new Holo(roomId, base);
        if (old != null) {
            despawn(old);
            h.height = old.height;
            h.spacing = old.spacing;
        }
        holos.put(roomId, h);
        Util.saveLoc(c, "holograms." + roomId, base);
        plugin.holoFile().save();
        DuelRoom r = plugin.rooms().getById(roomId);
        if (r != null) update(r);
    }

    public boolean remove(int roomId) {
        Holo h = holos.remove(roomId);
        YamlConfiguration c = plugin.holoFile().get();
        boolean inFile = c.contains("holograms." + roomId);
        if (h == null && !inFile) return false;
        if (h != null) despawn(h);
        c.set("holograms." + roomId, null);
        plugin.holoFile().save();
        return true;
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Holo h : holos.values()) out.add(h.roomId + "|" + Util.fmt(h.base));
        return out;
    }

    // ------------------------------------------------------------ updating

    public void updateAll() {
        for (Holo h : holos.values()) {
            DuelRoom r = plugin.rooms().getById(h.roomId);
            if (r != null) update(r);
        }
    }

    public void onChunkLoad(Chunk chunk) {
        for (Holo h : holos.values()) {
            if (h.base.getWorld() == null || !h.base.getWorld().equals(chunk.getWorld())) continue;
            if ((h.base.getBlockX() >> 4) != chunk.getX() || (h.base.getBlockZ() >> 4) != chunk.getZ()) continue;
            final int id = h.roomId;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Holo hh = holos.get(id);
                DuelRoom r = plugin.rooms().getById(id);
                if (hh != null && r != null) {
                    hh.last = null;
                    update(r);
                }
            });
        }
    }

    public void update(DuelRoom room) {
        Holo h = holos.get(room.id);
        if (h == null || h.base.getWorld() == null) return;
        List<String> lines = render(room);
        if (intact(h, lines)) return; // nothing changed -> no work

        YamlConfiguration c = plugin.holoFile().get();
        double height = h.height != null ? h.height : c.getDouble("settings.default-height", 3.5);
        double spacing = h.spacing != null ? h.spacing : c.getDouble("settings.default-line-spacing", 0.27);
        double top = h.base.getY() + height;
        World w = h.base.getWorld();
        boolean loaded = w.isChunkLoaded(h.base.getBlockX() >> 4, h.base.getBlockZ() >> 4);

        while (h.entities.size() < lines.size()) h.entities.add(null);
        while (h.entities.size() > lines.size()) {
            TextDisplay t = h.entities.remove(h.entities.size() - 1);
            if (t != null) t.remove();
        }
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            TextDisplay t = h.entities.get(i);
            if (isBlank(text)) {
                if (t != null) {
                    t.remove();
                    h.entities.set(i, null);
                }
                continue;
            }
            Location loc = new Location(w, h.base.getX(), top - i * spacing, h.base.getZ());
            if (t == null || !t.isValid()) {
                if (!loaded) {
                    h.entities.set(i, null);
                    continue;
                }
                h.entities.set(i, spawn(loc, text));
            } else {
                if (Math.abs(t.getLocation().getY() - loc.getY()) > 0.001) t.teleport(loc);
                if (h.last == null || i >= h.last.size() || !h.last.get(i).equals(text)) t.setText(text);
            }
        }
        h.last = loaded ? lines : null;
    }

    private boolean intact(Holo h, List<String> lines) {
        if (h.last == null || !h.last.equals(lines) || h.entities.size() != lines.size()) return false;
        for (int i = 0; i < lines.size(); i++) {
            if (isBlank(lines.get(i))) continue;
            TextDisplay t = h.entities.get(i);
            if (t == null || !t.isValid()) return false;
        }
        return true;
    }

    private boolean isBlank(String s) {
        return ChatColor.stripColor(s).trim().isEmpty();
    }

    private List<String> render(DuelRoom r) {
        List<String> fmt = plugin.holoFile().get().getStringList("formats." + r.status.name());
        List<String> out = new ArrayList<>(fmt.size());
        for (String s : fmt) {
            out.add(Util.color(s
                    .replace("{id}", String.valueOf(r.id))
                    .replace("{name}", r.name)
                    .replace("{players}", String.valueOf(r.playerCount()))
                    .replace("{max}", "2")
                    .replace("{time}", String.valueOf(r.lootRemaining))));
        }
        return out;
    }

    private TextDisplay spawn(Location loc, String text) {
        final float range = (float) plugin.holoFile().get().getDouble("settings.view-range", 0.5);
        return loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setGravity(false);
            t.setInvulnerable(true);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setViewRange(range);
            t.addScoreboardTag(TAG);
            t.setText(text);
        });
    }

    private void despawn(Holo h) {
        for (TextDisplay t : h.entities) if (t != null) t.remove();
        h.entities.clear();
        h.last = null;
    }
}
