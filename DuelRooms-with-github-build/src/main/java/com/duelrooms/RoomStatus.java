package com.duelrooms;

import org.bukkit.ChatColor;

public enum RoomStatus {
    AVAILABLE(ChatColor.GREEN),
    WAITING(ChatColor.YELLOW),
    ACTIVE(ChatColor.RED),
    LOOTING(ChatColor.LIGHT_PURPLE),
    DISABLED(ChatColor.DARK_RED);

    private final ChatColor color;

    RoomStatus(ChatColor color) {
        this.color = color;
    }

    public ChatColor color() {
        return color;
    }
}
