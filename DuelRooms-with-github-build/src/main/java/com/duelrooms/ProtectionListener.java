package com.duelrooms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.Location;

/** Anti-exploit: building, PvP interference, item pickup, explosions, hologram chunk refresh. */
public class ProtectionListener implements Listener {
    private final DuelRoomsPlugin plugin;
    private final RoomManager rm;

    public ProtectionListener(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
        this.rm = plugin.rooms();
    }

    private boolean blocked(Player p, Location l) {
        if (!plugin.getConfig().getBoolean("settings.protect-blocks", true)) return false;
        if (rm.canBypass(p)) return false;
        return rm.getRoom(p) != null || rm.isProtected(l);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            rm.throttled(e.getPlayer(), "build-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            rm.throttled(e.getPlayer(), "build-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.getConfig().getBoolean("settings.protect-blocks", true)) return;
        e.blockList().removeIf(b -> rm.isProtected(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.getConfig().getBoolean("settings.protect-blocks", true)) return;
        e.blockList().removeIf(b -> rm.isProtected(b.getLocation()));
    }

    private Player attackerOf(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    /**
     * PvP is only legal between the two duelists of the same room once the fight has started.
     * During the countdown every damage source is cancelled.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        DuelRoom vr = rm.getRoom(victim);
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attackerOf(byEntity.getDamager());
            if (attacker != null) {
                if (attacker.equals(victim)) return;
                DuelRoom ar = rm.getRoom(attacker);
                if (vr == null && ar == null) return; // normal world PvP is not our business
                if (rm.canBypass(attacker)) return;
                if (vr != null && vr == ar && vr.status == RoomStatus.ACTIVE && vr.fightStarted) return;
                e.setCancelled(true);
                return;
            }
        }
        if (vr != null && vr.isFrozen()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        rm.trackItem(e.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (rm.canBypass(p)) return;
        if (!rm.mayPickUp(p, e.getItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        plugin.holograms().onChunkLoad(e.getChunk());
    }
}
