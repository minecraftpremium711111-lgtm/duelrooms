package com.duelrooms;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DuelRoom {
    public final int id;
    public final String name;

    // persistent
    public Location spawn1, spawn2, entrance, exit;
    public boolean enabled = true;
    public Integer countdownOverride, lootingOverride;

    // runtime
    public RoomStatus status = RoomStatus.AVAILABLE;
    public UUID p1, p2, winner;
    public boolean fightStarted;
    public int lootRemaining;
    public BukkitTask task, waitTask;
    public final Set<UUID> items = new HashSet<>();

    public DuelRoom(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int playerCount() {
        return (p1 != null ? 1 : 0) + (p2 != null ? 1 : 0);
    }

    public boolean isDuelist(UUID u) {
        return u != null && (u.equals(p1) || u.equals(p2));
    }

    /** Both players are inside and the countdown has not finished yet. */
    public boolean isFrozen() {
        return status == RoomStatus.ACTIVE && !fightStarted;
    }

    public boolean isConfigured() {
        return spawn1 != null && spawn2 != null && entrance != null && exit != null;
    }

    public List<String> missing() {
        List<String> m = new ArrayList<>();
        if (spawn1 == null) m.add("spawn1");
        if (spawn2 == null) m.add("spawn2");
        if (entrance == null) m.add("entrance");
        if (exit == null) m.add("exit");
        return m;
    }

    public Location spawnOf(UUID u) {
        return u != null && u.equals(p1) ? spawn1 : spawn2;
    }

    public void cancelTasks() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (waitTask != null) {
            waitTask.cancel();
            waitTask = null;
        }
    }

    public boolean isAtEntrance(Location l, double radius) {
        if (entrance == null || l.getWorld() == null || !l.getWorld().equals(entrance.getWorld())) return false;
        double dy = l.getY() - entrance.getY();
        return Math.abs(l.getX() - entrance.getX()) <= radius
                && Math.abs(l.getZ() - entrance.getZ()) <= radius
                && dy >= -1.0 && dy <= 2.0;
    }

    /** Room area = sphere around the midpoint of both spawns, grown by padding. */
    public boolean isInside(Location l, double padding) {
        if (spawn1 == null || spawn2 == null || l == null || l.getWorld() == null) return false;
        if (!l.getWorld().equals(spawn1.getWorld()) || !l.getWorld().equals(spawn2.getWorld())) return false;
        double r = spawn1.distance(spawn2) / 2.0 + padding;
        return centerDistanceSq(l) <= r * r;
    }

    public double centerDistanceSq(Location l) {
        double mx = (spawn1.getX() + spawn2.getX()) / 2.0;
        double my = (spawn1.getY() + spawn2.getY()) / 2.0;
        double mz = (spawn1.getZ() + spawn2.getZ()) / 2.0;
        double dx = l.getX() - mx, dy = l.getY() - my, dz = l.getZ() - mz;
        return dx * dx + dy * dy + dz * dz;
    }
}
