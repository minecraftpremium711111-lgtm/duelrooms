package com.duelrooms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/** Entrance detection, freeze, escape/teleport/command protection, death, quit and join handling. */
public class DuelListener implements Listener {
    private final DuelRoomsPlugin plugin;
    private final RoomManager rm;

    public DuelListener(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
        this.rm = plugin.rooms();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        DuelRoom r = rm.getRoom(p);

        if (r != null) {
            if (r.isFrozen()) { // countdown: no movement, free head rotation
                if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                    Location nl = from.clone();
                    nl.setYaw(to.getYaw());
                    nl.setPitch(to.getPitch());
                    e.setTo(nl);
                }
                return;
            }
            boolean blockChanged = from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                    || from.getBlockZ() != to.getBlockZ();
            if (blockChanged && plugin.getConfig().getBoolean("settings.escape-prevention", true)
                    && !rm.canBypass(p) && !r.isInside(to, rm.padding())) {
                if (r.isInside(from, rm.padding())) e.setCancelled(true);
                else rm.teleport(p, r.spawnOf(p.getUniqueId()));
                rm.throttled(p, "escape-blocked");
            }
            return; // duelists never trigger entrances
        }

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        rm.tryEntrance(p, to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        rm.consumeSpawnPending(id);
        if (rm.isInternalTeleport(id)) return;
        if (rm.getRoom(p) == null || rm.canBypass(p)) return;
        e.setCancelled(true);
        rm.throttled(p, "teleport-blocked");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (rm.getRoom(p) != null && !rm.canBypass(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        DuelRoom r = rm.getRoom(p);
        if (r == null || rm.canBypass(p)) return;
        String label = rm.commandLabel(e.getMessage());
        if (rm.isSpawnCommand(label)) {
            boolean mayLeave = r.status == RoomStatus.WAITING
                    || (r.status == RoomStatus.LOOTING && p.getUniqueId().equals(r.winner));
            if (!mayLeave) {
                e.setCancelled(true);
                rm.throttled(p, "no-leave-during-duel");
            }
            return; // allowed: released in the MONITOR handler below
        }
        if (!plugin.getConfig().getBoolean("settings.block-commands", true)) return;
        if (rm.isAllowedCommand(label)) return;
        e.setCancelled(true);
        rm.throttled(p, "command-blocked");
    }

    /** Runs after every other plugin had its say: if /spawn really goes ahead, release the room at once. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandMonitor(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        DuelRoom r = rm.getRoom(p);
        if (r == null) return;
        if (!rm.isSpawnCommand(rm.commandLabel(e.getMessage()))) return;
        if (r.status == RoomStatus.WAITING || (r.status == RoomStatus.LOOTING && p.getUniqueId().equals(r.winner))) {
            rm.releaseViaSpawn(p);
        } else if (rm.canBypass(p)) {
            rm.forceClose(r); // admin leaving mid-duel ends the duel
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    @SuppressWarnings("deprecation")
    public void onDeath(PlayerDeathEvent e) {
        final Player v = e.getEntity();
        if (rm.getRoom(v) == null) return;
        if (plugin.getConfig().getBoolean("settings.suppress-death-messages", true)) e.setDeathMessage(null);
        rm.handleDeath(v);
        if (plugin.getConfig().getBoolean("settings.auto-respawn", true)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (v.isOnline() && v.isDead()) v.spigot().respawn();
            });
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!rm.isPendingExit(id)) return;
        Location exit = rm.exitFor(id);
        if (exit != null) e.setRespawnLocation(exit.clone());
        rm.clearPending(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        rm.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!rm.isPendingExit(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (p.isDead()) p.spigot().respawn(); // respawn handler sends them to the exit
            else rm.sendToPendingExit(p);
        }, 10L);
    }
}
