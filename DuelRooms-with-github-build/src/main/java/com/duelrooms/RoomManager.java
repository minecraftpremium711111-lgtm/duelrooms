package com.duelrooms;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns all rooms and every state transition: AVAILABLE -> WAITING -> ACTIVE -> LOOTING -> AVAILABLE. */
public class RoomManager {
    private final DuelRoomsPlugin plugin;
    private final Map<String, DuelRoom> rooms = new LinkedHashMap<>();
    private final Map<UUID, DuelRoom> playerRooms = new HashMap<>();
    /** Players who must be sent to their room's exit next time we see them (offline, dead, crash recovery). */
    private final Map<UUID, String> pendingExit = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Set<UUID> spawnPending = new HashSet<>();
    private final Map<UUID, Long> msgCooldown = new HashMap<>();
    private int nextId = 1;

    public RoomManager(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        YamlConfiguration c = plugin.roomsFile().get();
        ConfigurationSection sec = c.getConfigurationSection("rooms");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                DuelRoom r = rooms.get(key.toLowerCase());
                if (r != null && r.status != RoomStatus.AVAILABLE && r.status != RoomStatus.DISABLED) continue; // busy: keep as is
                if (r == null) {
                    int id = s.getInt("id", 0);
                    if (id <= 0) id = nextId++;
                    else nextId = Math.max(nextId, id + 1);
                    r = new DuelRoom(id, key);
                    rooms.put(key.toLowerCase(), r);
                }
                r.spawn1 = Util.loadLoc(s, "spawn1");
                r.spawn2 = Util.loadLoc(s, "spawn2");
                r.entrance = Util.loadLoc(s, "entrance");
                r.exit = Util.loadLoc(s, "exit");
                r.enabled = s.getBoolean("enabled", true);
                r.countdownOverride = s.contains("countdown-seconds") ? s.getInt("countdown-seconds") : null;
                r.lootingOverride = s.contains("looting-seconds") ? s.getInt("looting-seconds") : null;
                r.status = r.enabled ? RoomStatus.AVAILABLE : RoomStatus.DISABLED;
            }
        }
        ConfigurationSection pe = c.getConfigurationSection("pending-exit");
        if (pe != null) {
            for (String k : pe.getKeys(false)) {
                try {
                    pendingExit.put(UUID.fromString(k), pe.getString(k, ""));
                } catch (IllegalArgumentException ignored) {
                    // bad uuid in file
                }
            }
        }
    }

    public void save() {
        YamlConfiguration c = plugin.roomsFile().get();
        c.set("rooms", null);
        for (DuelRoom r : rooms.values()) {
            String p = "rooms." + r.name;
            c.set(p + ".id", r.id);
            c.set(p + ".enabled", r.enabled);
            c.set(p + ".state", r.status.name());
            Util.saveLoc(c, p + ".spawn1", r.spawn1);
            Util.saveLoc(c, p + ".spawn2", r.spawn2);
            Util.saveLoc(c, p + ".entrance", r.entrance);
            Util.saveLoc(c, p + ".exit", r.exit);
            c.set(p + ".countdown-seconds", r.countdownOverride);
            c.set(p + ".looting-seconds", r.lootingOverride);
        }
        c.set("pending-exit", null);
        for (Map.Entry<UUID, String> e : pendingExit.entrySet()) {
            c.set("pending-exit." + e.getKey(), e.getValue());
        }
        plugin.roomsFile().save();
    }

    /** Called on plugin disable: nobody is left stuck in a room. */
    public void shutdown() {
        for (DuelRoom r : new ArrayList<>(rooms.values())) {
            if (r.status != RoomStatus.AVAILABLE && r.status != RoomStatus.DISABLED) forceClose(r);
        }
        save();
    }

    // ------------------------------------------------------------ lookups

    public Collection<DuelRoom> all() { return rooms.values(); }
    public DuelRoom getByName(String n) { return n == null ? null : rooms.get(n.toLowerCase()); }

    public DuelRoom getById(int id) {
        for (DuelRoom r : rooms.values()) if (r.id == id) return r;
        return null;
    }

    public DuelRoom getRoom(Player p) { return playerRooms.get(p.getUniqueId()); }

    public double padding() { return plugin.getConfig().getDouble("settings.room-padding", 12.0); }

    public boolean isProtected(Location l) {
        for (DuelRoom r : rooms.values()) if (r.isInside(l, padding())) return true;
        return false;
    }

    public boolean canBypass(Player p) {
        if (!p.hasPermission("duel.bypass")) return false;
        return getRoom(p) == null || plugin.getConfig().getBoolean("settings.bypass-while-in-duel", true);
    }

    public int countdownFor(DuelRoom r) {
        return r.countdownOverride != null ? r.countdownOverride : plugin.getConfig().getInt("settings.countdown-seconds", 5);
    }

    public int lootingFor(DuelRoom r) {
        return r.lootingOverride != null ? r.lootingOverride : plugin.getConfig().getInt("settings.looting-seconds", 35);
    }

    // ------------------------------------------------------------ admin operations

    public DuelRoom createRoom(String name) {
        if (rooms.containsKey(name.toLowerCase())) return null;
        DuelRoom r = new DuelRoom(nextId++, name);
        rooms.put(name.toLowerCase(), r);
        save();
        plugin.holograms().update(r);
        return r;
    }

    public void deleteRoom(DuelRoom r) {
        forceClose(r);
        rooms.remove(r.name.toLowerCase());
        plugin.holograms().remove(r.id);
        save();
    }

    public void setEnabled(DuelRoom r, boolean enabled) {
        r.enabled = enabled;
        if (!enabled) {
            forceClose(r);
            r.status = RoomStatus.DISABLED;
        } else if (r.status == RoomStatus.DISABLED) {
            r.status = RoomStatus.AVAILABLE;
        }
        plugin.holograms().update(r);
        save();
    }

    /** Ends whatever is happening: online duelists go to the exit, items are cleaned, room resets. */
    public void forceClose(DuelRoom r) {
        for (UUID id : new UUID[]{r.p1, r.p2}) {
            if (id == null) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                plugin.messages().send(p, "room-closed");
                teleport(p, r.exit);
                clearPending(id);
            }
        }
        resetRoom(r, true);
    }

    // ------------------------------------------------------------ entrance / matchmaking

    public void tryEntrance(Player p, Location to) {
        double radius = plugin.getConfig().getDouble("settings.entrance-radius", 1.0);
        for (DuelRoom r : rooms.values()) {
            if (r.isAtEntrance(to, radius)) {
                attemptJoin(p, r);
                return;
            }
        }
    }

    public void attemptJoin(Player p, DuelRoom r) {
        UUID id = p.getUniqueId();
        if (playerRooms.containsKey(id)) return;
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!p.hasPermission("duel.use")) {
            throttled(p, "no-permission");
            return;
        }
        if (!r.isConfigured()) {
            throttled(p, "room-not-configured", "room", r.name);
            return;
        }
        String rid = String.valueOf(r.id);
        switch (r.status) {
            case DISABLED -> throttled(p, "room-disabled", "id", rid, "room", r.name);
            case ACTIVE, LOOTING -> throttled(p, "room-occupied", "id", rid, "room", r.name);
            case WAITING -> joinSecond(p, r);
            case AVAILABLE -> joinFirst(p, r);
        }
    }

    private void assign(Player p, DuelRoom r) {
        UUID id = p.getUniqueId();
        boolean first = r.p1 == null;
        if (first) r.p1 = id;
        else r.p2 = id;
        playerRooms.put(id, r);
        pendingExit.put(id, r.name);
        save();
        teleport(p, first ? r.spawn1 : r.spawn2);
    }

    private void joinFirst(Player p, DuelRoom r) {
        assign(p, r);
        r.status = RoomStatus.WAITING;
        plugin.messages().send(p, "waiting");
        plugin.messages().title(p, "waiting-title", "waiting-subtitle");
        plugin.holograms().update(r);
        startWaitTimer(r);
    }

    private void joinSecond(Player p, DuelRoom r) {
        UUID otherId = r.p1 != null ? r.p1 : r.p2;
        Player other = otherId == null ? null : Bukkit.getPlayer(otherId);
        if (other == null || !other.isOnline()) { // stale state, recover
            resetRoom(r, false);
            joinFirst(p, r);
            return;
        }
        r.cancelTasks();
        assign(p, r);
        r.status = RoomStatus.ACTIVE; // room is now locked
        r.fightStarted = false;
        plugin.messages().send(p, "opponent-found", "opponent", other.getName());
        plugin.messages().send(other, "opponent-found", "opponent", p.getName());
        plugin.holograms().update(r);
        startCountdown(r);
    }

    private void startWaitTimer(DuelRoom r) {
        int secs = plugin.getConfig().getInt("settings.waiting-timeout-seconds", 0);
        if (secs <= 0) return;
        if (r.waitTask != null) r.waitTask.cancel();
        r.waitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            r.waitTask = null;
            if (r.status != RoomStatus.WAITING) return;
            UUID id = r.p1 != null ? r.p1 : r.p2;
            Player p = id == null ? null : Bukkit.getPlayer(id);
            if (p == null) {
                resetRoom(r, false);
                return;
            }
            leave(p);
            plugin.messages().send(p, "waiting-timeout");
        }, secs * 20L);
    }

    // ------------------------------------------------------------ duel flow

    @SuppressWarnings("deprecation")
    private void prepare(Player p) {
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
    }

    private void startCountdown(DuelRoom r) {
        r.cancelTasks();
        final Messages m = plugin.messages();
        if (plugin.getConfig().getBoolean("settings.heal-on-start", true)) {
            Player a = r.p1 == null ? null : Bukkit.getPlayer(r.p1);
            Player b = r.p2 == null ? null : Bukkit.getPlayer(r.p2);
            if (a != null) prepare(a);
            if (b != null) prepare(b);
        }
        final int secs = Math.max(0, countdownFor(r));
        r.task = new BukkitRunnable() {
            int remaining = secs;

            @Override
            public void run() {
                Player a = r.p1 == null ? null : Bukkit.getPlayer(r.p1);
                Player b = r.p2 == null ? null : Bukkit.getPlayer(r.p2);
                if (r.status != RoomStatus.ACTIVE || a == null || b == null) {
                    cancel();
                    return;
                }
                if (remaining > 0) {
                    String s = String.valueOf(remaining);
                    for (Player p : new Player[]{a, b}) {
                        m.send(p, "countdown", "seconds", s);
                        m.title(p, "countdown-title", "countdown-subtitle", "seconds", s);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                    }
                    remaining--;
                } else {
                    cancel();
                    r.task = null;
                    r.fightStarted = true; // PvP enabled, players unfrozen
                    for (Player p : new Player[]{a, b}) {
                        m.send(p, "fight-start");
                        m.title(p, "fight-title", "fight-subtitle");
                        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    }
                    plugin.holograms().update(r);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /** Called from PlayerDeathEvent (and from the quit handler) for a player who is in a room. */
    public void handleDeath(Player victim) {
        UUID vid = victim.getUniqueId();
        DuelRoom r = playerRooms.get(vid);
        if (r == null) return;
        playerRooms.remove(vid); // pendingExit entry stays: respawn handler sends them to the exit
        if (r.status == RoomStatus.ACTIVE) {
            UUID wid = vid.equals(r.p1) ? r.p2 : r.p1;
            Player winner = wid == null ? null : Bukkit.getPlayer(wid);
            if (winner == null || !winner.isOnline() || winner.isDead()) {
                resetRoom(r, false);
                return;
            }
            startLooting(r, winner, victim);
        } else {
            // died while waiting, or the winner died while looting: end the room, keep dropped items for normal despawn
            resetRoom(r, false);
        }
    }

    private void startLooting(DuelRoom r, Player winner, Player loser) {
        final UUID wid = winner.getUniqueId();
        final UUID lid = loser.getUniqueId();
        final Messages m = plugin.messages();
        r.cancelTasks();
        if (lid.equals(r.p1)) r.p1 = null;
        else if (lid.equals(r.p2)) r.p2 = null;
        r.status = RoomStatus.LOOTING;
        r.winner = wid;
        r.fightStarted = false;
        r.lootRemaining = Math.max(1, lootingFor(r));

        String time = String.valueOf(r.lootRemaining);
        m.send(winner, "winner-self", "time", time);
        m.title(winner, "winner-title", "winner-subtitle", "time", time);
        m.send(loser, "loser-self", "winner", winner.getName());
        if (plugin.getConfig().getBoolean("settings.broadcast-results", true)) {
            m.broadcast("winner-broadcast", "winner", winner.getName(), "loser", loser.getName(), "id", String.valueOf(r.id));
        }
        plugin.holograms().update(r);

        r.task = new BukkitRunnable() {
            @Override
            public void run() {
                Player w = Bukkit.getPlayer(wid);
                if (w == null || r.status != RoomStatus.LOOTING) {
                    cancel();
                    return;
                }
                if (r.lootRemaining <= 0) {
                    cancel();
                    r.task = null;
                    finishLooting(r, w);
                    return;
                }
                m.actionBar(w, "looting-actionbar", "time", String.valueOf(r.lootRemaining));
                plugin.holograms().update(r);
                r.lootRemaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void finishLooting(DuelRoom r, Player winner) {
        plugin.messages().send(winner, "looting-end");
        resetRoom(r, true);
        teleport(winner, r.exit);
        clearPending(winner.getUniqueId());
    }

    /** Resets a room to AVAILABLE (or DISABLED) and unlocks it. */
    public void resetRoom(DuelRoom r, boolean cleanItems) {
        r.cancelTasks();
        if (r.p1 != null) playerRooms.remove(r.p1, r);
        if (r.p2 != null) playerRooms.remove(r.p2, r);
        if (cleanItems) {
            for (UUID id : r.items) {
                Entity e = Bukkit.getEntity(id);
                if (e instanceof Item && e.isValid()) e.remove();
            }
        }
        r.items.clear();
        r.p1 = null;
        r.p2 = null;
        r.winner = null;
        r.fightStarted = false;
        r.lootRemaining = 0;
        r.status = r.enabled ? RoomStatus.AVAILABLE : RoomStatus.DISABLED;
        plugin.holograms().update(r);
    }

    // ------------------------------------------------------------ leaving

    /** Waiting player, or winner while looting, leaves voluntarily: teleported to the exit, room reset. */
    public boolean leave(Player p) {
        DuelRoom r = getRoom(p);
        if (r == null) return false;
        UUID id = p.getUniqueId();
        boolean ok = r.status == RoomStatus.WAITING || (r.status == RoomStatus.LOOTING && id.equals(r.winner));
        if (!ok) return false;
        resetRoom(r, true);
        teleport(p, r.exit);
        clearPending(id);
        return true;
    }

    /** Winner/waiting player ran /spawn (or an alias): release the room now and let the spawn plugin teleport them. */
    public void releaseViaSpawn(Player p) {
        DuelRoom r = getRoom(p);
        if (r == null) return;
        UUID id = p.getUniqueId();
        boolean ok = r.status == RoomStatus.WAITING || (r.status == RoomStatus.LOOTING && id.equals(r.winner));
        if (!ok) return;
        final Location exit = r.exit;
        spawnPending.add(id);
        long ticks = plugin.getConfig().getLong("settings.spawn-fallback-ticks", 100L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (spawnPending.remove(id)) { // spawn command never teleported them
                Player q = Bukkit.getPlayer(id);
                if (q != null && q.isOnline() && getRoom(q) == null) teleport(q, exit);
                clearPending(id);
            }
        }, ticks);
        resetRoom(r, true);
    }

    /** Called on every teleport; a teleport after /spawn means the player has left for good. */
    public void consumeSpawnPending(UUID id) {
        if (spawnPending.remove(id)) clearPending(id);
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        msgCooldown.remove(id);
        spawnPending.remove(id);
        DuelRoom r = playerRooms.get(id);
        if (r == null) return; // pendingExit (if any) stays: they are teleported out on next join

        if (r.status == RoomStatus.WAITING) {
            resetRoom(r, false);
        } else if (r.status == RoomStatus.ACTIVE) {
            if (r.fightStarted) {
                p.setHealth(0.0); // forfeit: loser drops items for the winner to loot
                if (playerRooms.get(id) == r) handleDeath(p); // safety net
            } else {
                // left during the countdown: remaining player goes back to WAITING
                playerRooms.remove(id, r);
                if (id.equals(r.p1)) r.p1 = null;
                else if (id.equals(r.p2)) r.p2 = null;
                r.cancelTasks();
                r.fightStarted = false;
                r.status = RoomStatus.WAITING;
                UUID oid = r.p1 != null ? r.p1 : r.p2;
                Player o = oid == null ? null : Bukkit.getPlayer(oid);
                if (o == null) {
                    resetRoom(r, false);
                } else {
                    plugin.messages().send(o, "opponent-left");
                    plugin.messages().send(o, "waiting");
                    plugin.holograms().update(r);
                    startWaitTimer(r);
                }
            }
        } else if (r.status == RoomStatus.LOOTING) {
            resetRoom(r, true);
        }
    }

    // ------------------------------------------------------------ items

    public void trackItem(Item item) {
        DuelRoom best = null;
        double bestD = Double.MAX_VALUE;
        Location l = item.getLocation();
        for (DuelRoom r : rooms.values()) {
            if (r.status != RoomStatus.ACTIVE && r.status != RoomStatus.LOOTING) continue;
            if (!r.isInside(l, padding())) continue;
            double d = r.centerDistanceSq(l);
            if (d < bestD) {
                best = r;
                bestD = d;
            }
        }
        if (best != null) best.items.add(item.getUniqueId());
    }

    public boolean mayPickUp(Player p, Item item) {
        UUID iid = item.getUniqueId();
        for (DuelRoom r : rooms.values()) {
            if (r.items.contains(iid)) return r.isDuelist(p.getUniqueId());
        }
        return true;
    }

    // ------------------------------------------------------------ teleport / pending helpers

    public boolean teleport(Player p, Location l) {
        if (l == null || l.getWorld() == null) return false;
        UUID id = p.getUniqueId();
        internalTeleports.add(id);
        try {
            p.leaveVehicle();
            p.eject();
            return p.teleport(l.clone());
        } finally {
            internalTeleports.remove(id);
        }
    }

    public boolean isInternalTeleport(UUID id) { return internalTeleports.contains(id); }
    public boolean isPendingExit(UUID id) { return pendingExit.containsKey(id); }

    public void clearPending(UUID id) {
        if (pendingExit.remove(id) != null) save();
    }

    public Location exitFor(UUID id) {
        String n = pendingExit.get(id);
        DuelRoom r = n == null ? null : rooms.get(n.toLowerCase());
        return r == null ? null : r.exit;
    }

    public void sendToPendingExit(Player p) {
        Location exit = exitFor(p.getUniqueId());
        if (exit != null) teleport(p, exit);
        clearPending(p.getUniqueId());
    }

    public void throttled(Player p, String key, String... kv) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("settings.message-cooldown-seconds", 3L) * 1000L;
        Long last = msgCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) return;
        msgCooldown.put(p.getUniqueId(), now);
        plugin.messages().send(p, key, kv);
    }

    // ------------------------------------------------------------ command helpers

    public String commandLabel(String message) {
        String m = message.startsWith("/") ? message.substring(1) : message;
        int sp = m.indexOf(' ');
        if (sp >= 0) m = m.substring(0, sp);
        return m.toLowerCase();
    }

    public boolean isSpawnCommand(String label) {
        return listContains(plugin.getConfig().getStringList("spawn-commands"), label);
    }

    public boolean isAllowedCommand(String label) {
        List<String> l = plugin.getConfig().getStringList("allowed-commands");
        if (listContains(l, label)) return true;
        int c = label.indexOf(':');
        return c >= 0 && listContains(l, label.substring(c + 1));
    }

    private boolean listContains(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }
}
