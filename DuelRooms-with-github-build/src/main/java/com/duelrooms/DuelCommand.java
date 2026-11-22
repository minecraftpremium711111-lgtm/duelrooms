package com.duelrooms;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DuelCommand implements CommandExecutor, TabCompleter {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final DuelRoomsPlugin plugin;

    public DuelCommand(DuelRoomsPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() { return plugin.messages(); }
    private RoomManager rm() { return plugin.rooms(); }
    private boolean isAdmin(CommandSender s) { return s.isOp() || s.hasPermission("duel.admin"); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "leave" -> leave(s);
            case "rooms" -> {
                if (!isAdmin(s)) {
                    m().send(s, "no-permission");
                    return true;
                }
                rooms(s, a);
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        m().sendList(s, "help");
        if (isAdmin(s)) m().sendList(s, "help-admin");
    }

    private void usage(CommandSender s, String usage) {
        m().send(s, "usage", "usage", usage);
    }

    private void leave(CommandSender s) {
        if (!(s instanceof Player p)) {
            m().send(s, "player-only");
            return;
        }
        if (rm().getRoom(p) == null) {
            m().send(p, "not-in-room");
            return;
        }
        if (rm().leave(p)) m().send(p, "left-room");
        else m().send(p, "cannot-leave");
    }

    private void rooms(CommandSender s, String[] a) {
        if (a.length < 2) {
            help(s);
            return;
        }
        String sub = a[1].toLowerCase();
        switch (sub) {
            case "list" -> list(s);
            case "reload" -> {
                plugin.reloadAll();
                m().send(s, "reloaded");
            }
            case "create" -> create(s, a);
            case "delete" -> {
                DuelRoom r = room(s, a, 2, "/duel rooms delete <name>");
                if (r == null) return;
                rm().deleteRoom(r);
                m().send(s, "room-deleted", "room", r.name);
            }
            case "setspawn1", "setspawn2", "setentrance", "setexit" -> setLoc(s, sub, a);
            case "enable", "disable" -> {
                DuelRoom r = room(s, a, 2, "/duel rooms " + sub + " <name>");
                if (r == null) return;
                boolean en = sub.equals("enable");
                rm().setEnabled(r, en);
                m().send(s, en ? "admin-room-enabled" : "admin-room-disabled", "room", r.name);
            }
            case "set" -> setting(s, a);
            case "hologram" -> hologram(s, a);
            default -> help(s);
        }
    }

    private DuelRoom room(CommandSender s, String[] a, int idx, String usage) {
        if (a.length <= idx) {
            usage(s, usage);
            return null;
        }
        DuelRoom r = rm().getByName(a[idx]);
        if (r == null) m().send(s, "room-not-found", "room", a[idx]);
        return r;
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/duel rooms create <name>");
            return;
        }
        if (!NAME.matcher(a[2]).matches()) {
            m().send(s, "invalid-name");
            return;
        }
        DuelRoom r = rm().createRoom(a[2]);
        if (r == null) {
            m().send(s, "room-exists", "room", a[2]);
            return;
        }
        m().send(s, "room-created", "room", r.name, "id", String.valueOf(r.id));
    }

    private void setLoc(CommandSender s, String sub, String[] a) {
        if (!(s instanceof Player p)) {
            m().send(s, "player-only");
            return;
        }
        DuelRoom r = room(s, a, 2, "/duel rooms " + sub + " <name>");
        if (r == null) return;
        if (r.status != RoomStatus.AVAILABLE && r.status != RoomStatus.DISABLED) {
            m().send(s, "room-busy");
            return;
        }
        Location l = p.getLocation();
        switch (sub) {
            case "setspawn1", "setspawn2" -> {
                Location other = sub.equals("setspawn1") ? r.spawn2 : r.spawn1;
                if (other != null && !other.getWorld().equals(l.getWorld())) {
                    m().send(s, "different-world");
                    return;
                }
                if (sub.equals("setspawn1")) r.spawn1 = l;
                else r.spawn2 = l;
            }
            case "setentrance" -> r.entrance = Util.blockCenter(l);
            default -> r.exit = l;
        }
        rm().save();
        m().send(s, "location-set", "type", sub.substring(3), "room", r.name);
    }

    private void list(CommandSender s) {
        if (rm().all().isEmpty()) {
            m().send(s, "room-list-empty");
            return;
        }
        m().send(s, "room-list-header");
        for (DuelRoom r : rm().all()) {
            List<String> missing = r.missing();
            String info = missing.isEmpty() ? "&aready" : "&cmissing: " + String.join(", ", missing);
            m().send(s, "room-list-entry", "id", String.valueOf(r.id), "name", r.name,
                    "status", r.status.color() + r.status.name(), "info", info);
        }
    }

    private void setting(CommandSender s, String[] a) {
        if (a.length < 5) {
            usage(s, "/duel rooms set <name> <countdown|looting> <seconds|reset>");
            return;
        }
        DuelRoom r = room(s, a, 2, "/duel rooms set <name> <countdown|looting> <seconds|reset>");
        if (r == null) return;
        String key = a[3].toLowerCase();
        if (!key.equals("countdown") && !key.equals("looting")) {
            usage(s, "/duel rooms set <name> <countdown|looting> <seconds|reset>");
            return;
        }
        Integer val = null;
        if (!a[4].equalsIgnoreCase("reset")) {
            try {
                val = Integer.parseInt(a[4]);
            } catch (NumberFormatException ex) {
                m().send(s, "invalid-number");
                return;
            }
            if (val < (key.equals("looting") ? 1 : 0) || val > 3600) {
                m().send(s, "invalid-number");
                return;
            }
        }
        if (key.equals("countdown")) r.countdownOverride = val;
        else r.lootingOverride = val;
        rm().save();
        m().send(s, "setting-updated", "setting", key, "room", r.name, "value", val == null ? "default" : String.valueOf(val));
    }

    private void hologram(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/duel rooms hologram <number|remove <number>|list|reload>");
            return;
        }
        String h = a[2].toLowerCase();
        switch (h) {
            case "list" -> {
                List<String> d = plugin.holograms().describe();
                if (d.isEmpty()) {
                    m().send(s, "hologram-list-empty");
                    return;
                }
                m().send(s, "hologram-list-header");
                for (String line : d) {
                    String[] parts = line.split("\\|", 2);
                    m().send(s, "hologram-list-entry", "id", parts[0], "location", parts[1]);
                }
            }
            case "reload" -> {
                plugin.holograms().reload();
                m().send(s, "hologram-reloaded");
            }
            case "remove" -> {
                if (a.length < 4) {
                    usage(s, "/duel rooms hologram remove <number>");
                    return;
                }
                Integer id = parseInt(a[3]);
                if (id == null) {
                    m().send(s, "invalid-number");
                    return;
                }
                if (plugin.holograms().remove(id)) m().send(s, "hologram-removed", "id", String.valueOf(id));
                else m().send(s, "hologram-not-found", "id", String.valueOf(id));
            }
            default -> {
                Integer id = parseInt(a[2]);
                if (id == null) {
                    usage(s, "/duel rooms hologram <number|remove <number>|list|reload>");
                    return;
                }
                if (!(s instanceof Player p)) {
                    m().send(s, "player-only");
                    return;
                }
                if (rm().getById(id) == null) {
                    m().send(s, "room-id-not-found", "id", String.valueOf(id));
                    return;
                }
                plugin.holograms().create(id, p.getLocation());
                m().send(s, "hologram-created", "id", String.valueOf(id));
            }
        }
    }

    private Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("help");
            out.add("leave");
            if (isAdmin(s)) out.add("rooms");
            return filter(out, a[0]);
        }
        if (!isAdmin(s) || !a[0].equalsIgnoreCase("rooms")) return List.of();
        if (a.length == 2) {
            out.addAll(List.of("create", "delete", "setspawn1", "setspawn2", "setentrance", "setexit",
                    "enable", "disable", "list", "reload", "set", "hologram"));
            return filter(out, a[1]);
        }
        String sub = a[1].toLowerCase();
        if (a.length == 3) {
            switch (sub) {
                case "delete", "setspawn1", "setspawn2", "setentrance", "setexit", "enable", "disable", "set" ->
                        rm().all().forEach(r -> out.add(r.name));
                case "hologram" -> {
                    out.addAll(List.of("list", "reload", "remove"));
                    rm().all().forEach(r -> out.add(String.valueOf(r.id)));
                }
                default -> { }
            }
            return filter(out, a[2]);
        }
        if (a.length == 4) {
            if (sub.equals("set")) out.addAll(List.of("countdown", "looting"));
            else if (sub.equals("hologram") && a[2].equalsIgnoreCase("remove"))
                rm().all().forEach(r -> out.add(String.valueOf(r.id)));
            return filter(out, a[3]);
        }
        if (a.length == 5 && sub.equals("set")) {
            out.addAll(List.of("reset", "5", "10", "35"));
            return filter(out, a[4]);
        }
        return List.of();
    }

    private List<String> filter(List<String> in, String prefix) {
        String p = prefix.toLowerCase();
        return in.stream().filter(x -> x.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
