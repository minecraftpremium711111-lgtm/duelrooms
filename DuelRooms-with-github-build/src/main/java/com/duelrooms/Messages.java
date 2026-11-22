package com.duelrooms;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class Messages {
    private final DuelRoomsPlugin plugin;

    public Messages(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
    }

    public String format(String key, String... kv) {
        String s = plugin.messagesFile().get().getString(key);
        if (s == null) s = "&cMissing message: " + key;
        s = s.replace("{prefix}", plugin.messagesFile().get().getString("prefix", ""));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return Util.color(s);
    }

    public void send(CommandSender to, String key, String... kv) {
        String m = format(key, kv);
        if (!m.isEmpty()) to.sendMessage(m);
    }

    public void sendList(CommandSender to, String key) {
        List<String> lines = plugin.messagesFile().get().getStringList(key);
        for (String l : lines) to.sendMessage(Util.color(l));
    }

    public void broadcast(String key, String... kv) {
        String m = format(key, kv);
        if (!m.isEmpty()) Bukkit.broadcastMessage(m);
    }

    public void actionBar(Player p, String key, String... kv) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(format(key, kv)));
    }

    public void title(Player p, String titleKey, String subKey, String... kv) {
        p.sendTitle(format(titleKey, kv), subKey == null ? "" : format(subKey, kv), 0, 25, 5);
    }
}
