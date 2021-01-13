package cn.apisium.nekopermissions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.annotation.command.Command;
import org.bukkit.plugin.java.annotation.command.Commands;
import org.bukkit.plugin.java.annotation.permission.Permission;
import org.bukkit.plugin.java.annotation.permission.Permissions;
import org.bukkit.plugin.java.annotation.plugin.*;
import org.bukkit.plugin.java.annotation.plugin.author.Author;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
@Plugin(name = "NekoPermissions", version = "1.0")
@Description("An permission plugin used in NekoCraft.")
@Author("Shirasawa")
@Website("https://apisium.cn")
@ApiVersion(ApiVersion.Target.v1_13)
@Permissions(@Permission(name = "neko.permission"))
@Commands(@Command(name = "permission", permission = "neko.permission", aliases = "perms", desc = "A NekoPermissions provided command."))
@Permission(name = "neko.permission")
public final class Main extends JavaPlugin implements Listener {
    private final File CONFIG_FILE = new File(getDataFolder(), "config.json");
    private final Path CONFIG_PATH = CONFIG_FILE.toPath();
    private final WeakHashMap<Player, PermissionAttachment> attachments = new WeakHashMap<>();
    private final HashMap<String, String> userToGroups = new HashMap<>();
    private final HashMap<String, HashMap<String, Boolean>> userToPerms = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            loadStorage(true);
        } catch (IOException e) {
            e.printStackTrace();
            setEnabled(false);
        }
        getServer().getPluginManager().registerEvents(this, this);
        final PluginCommand cmd = getServer().getPluginCommand("permission");
        assert cmd != null;
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        cmd.setUsage("§c命令用法错误!");
        cmd.setPermissionMessage("§c你没有足够的权限来执行命令!");
        syncPlayers();
    }

    private void saveStorage() throws IOException {
        JsonObject json = new JsonObject();
        userToPerms.forEach((id, map) -> {
            if (map.isEmpty()) return;
            final JsonObject obj = new JsonObject();
            map.forEach(obj::addProperty);
            final JsonObject perms = new JsonObject();
            perms.add("permissions", obj);
            json.add(id, perms);
        });
        userToGroups.forEach((id, g) -> {
            if (json.has(id)) json.getAsJsonObject(id).addProperty("group", g);
            else json.addProperty(id, g);
        });
        try (final Writer w = Files.newBufferedWriter(CONFIG_PATH); final JsonWriter writer = new JsonWriter(w)) {
            writer.setIndent("  ");
            writer.setLenient(true);
            Streams.write(json, writer);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void loadStorage(final boolean init) throws IOException {
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        if (!CONFIG_FILE.exists()) Files.write(CONFIG_PATH, "{}".getBytes());
        userToGroups.clear();
        userToPerms.clear();
        if (!init) getServer().reloadPermissions();
        new JsonParser().parse(Files.newBufferedReader(CONFIG_PATH)).getAsJsonObject().entrySet().forEach(it -> {
            final JsonElement v = it.getValue();
            if (v.isJsonPrimitive()) userToGroups.put(it.getKey(), v.getAsString());
            else if (v.isJsonObject()) {
                final JsonObject obj = v.getAsJsonObject();
                if (obj.has("group")) userToGroups.put(it.getKey(), obj.getAsJsonPrimitive("group").getAsString());
                if (obj.has("permissions")) {
                    final HashMap<String, Boolean> map = new HashMap<>();
                    final JsonObject p = obj.getAsJsonObject("permissions");
                    if (p.size() == 0) return;
                    p.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsBoolean()));
                    userToPerms.put(it.getKey(), map);
                }
            }
        });
        syncPlayers();
    }

    private String formatPermissions(final String node) {
        final StringBuilder sb = new StringBuilder();
        formatPermissions(node, sb, true, 0);
        return sb.toString();
    }
    private String formatPermissions(final Map<String, Boolean> map) {
        final StringBuilder sb = new StringBuilder();
        if (!map.isEmpty()) map.forEach((k, v) -> formatPermissions(k, sb, v, 0));
        return sb.toString();
    }
    private void formatPermissions(final String node, final StringBuilder sb, final boolean type, final int start) {
        final org.bukkit.permissions.Permission perm = getServer().getPluginManager().getPermission(node);
        if (perm == null) return;
        for (int i = 0; i < start; i++) sb.append(' ');
        sb.append(type ? "§a+§f" : "§c-§f").append(node);
        final Map<String, Boolean> map = perm.getChildren();
        if (!map.isEmpty()) {
            sb.append(":\n");
            final int j = start + 2;
            map.forEach((k, v) -> formatPermissions(k, sb, v, j));
        } else sb.append('\n');
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (args.length < 1) return false;
        switch (args[0]) {
            case "reload":
                try {
                    loadStorage(false);
                    sender.sendMessage("§a重载成功!");
                } catch (IOException e) {
                    e.printStackTrace();
                    sender.sendMessage("§c重载失败!");
                }
                break;
            case "list":
                if (args.length == 1) sender.sendMessage(getAllPerms().stream().sorted().map(it -> {
                    final TextComponent t = new TextComponent(it + "\n");
                    t.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/permission set WHO " + it + " true"));
                    return t;
                }).toArray(TextComponent[]::new));
                else {
                    final Player p = getServer().getPlayerExact(args[1]);
                    if (p == null) {
                        sender.sendMessage("§c找不到该玩家!");
                        return true;
                    }
                    final PermissionAttachment attachment = attachments.get(p);
                    if (attachment != null) sender.sendMessage(formatPermissions(attachment.getPermissions()));
                }
                break;
            case "group": {
                if (args.length < 2) return false;
                final OfflinePlayer p = getPlayer(args[1], sender);
                if (p == null) return true;
                final String id = p.getUniqueId().toString();
                if (args.length == 2) userToGroups.remove(id);
                else userToGroups.put(id, args[2]);
                if (p.isOnline()) syncPlayer(Objects.requireNonNull(p.getPlayer()));
                try {
                    saveStorage();
                    sender.sendMessage("§a保存成功!");
                } catch (IOException e) {
                    e.printStackTrace();
                    sender.sendMessage("§c保存失败!");
                }
                break;
            }
            case "set": { // set <player> <permission> [true/false]
                if (args.length < 3) return false;
                final OfflinePlayer p = getPlayer(args[1], sender);
                if (p == null) return true;
                final String id = p.getUniqueId().toString();
                HashMap<String, Boolean> map = userToPerms.get(id);
                if (args.length == 3) {
                    if (map != null) {
                        map.remove(args[2]);
                        if (map.isEmpty()) userToPerms.remove(id);
                    }
                } else {
                    if (map == null) {
                        map = new HashMap<>();
                        userToPerms.put(id, map);
                    }
                    map.put(args[2], Boolean.valueOf(args[3]));
                }
                if (p.isOnline()) syncPlayer(Objects.requireNonNull(p.getPlayer()));
                try {
                    saveStorage();
                    sender.sendMessage("§a保存成功!");
                } catch (IOException e) {
                    e.printStackTrace();
                    sender.sendMessage("§c保存失败!");
                }
                break;
            }
            default: return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer getPlayer(String name, CommandSender sender) {
        final OfflinePlayer p = getServer().getOfflinePlayer(name);
        if (!p.hasPlayedBefore()) {
            sender.sendMessage("§c该玩家不存在于本服务器!");
            return null;
        }
        return p;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        switch (args.length) {
            case 1:
                return Arrays.asList("list", "reload", "group", "set");
            case 2:
                return args[0].equals("group") || args[0].equals("set")
                        ? getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList())
                        : null;
            case 3:
                switch (args[0]) {
                    case "set":
                        return new ArrayList<>(getAllPerms());
                    case "group":
                        return getServer().getPluginManager().getPermissions().stream()
                                .map(org.bukkit.permissions.Permission::getName).collect(Collectors.toList());
                }
            case 4:
                return args[0].equals("set") ? Arrays.asList("true", "false") : null;
            default: return null;
        }
    }

    private HashSet<String> getAllPerms() {
        final HashSet<String> set = new HashSet<>();
        for (org.bukkit.plugin.Plugin pl : getServer().getPluginManager().getPlugins()) {
            pl.getDescription().getPermissions().forEach(it -> set.add(it.getName()));
        }
        getServer().getPluginManager().getDefaultPermissions(true).forEach(it -> set.add(it.getName()));
        return set;
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent e) {
        syncPlayer(e.getPlayer());
    }

    private void syncPlayers() {
        getServer().getOnlinePlayers().forEach(this::syncPlayer);
    }

    private void syncPlayer(final Player p) {
        final String id = p.getUniqueId().toString();
        PermissionAttachment attach = attachments.get(p);
        if (attach != null) {
            attach.remove();
            attachments.remove(p);
        }
        final boolean hasGroup = userToGroups.containsKey(id), hasPerms = userToPerms.containsKey(id);
        if (!hasGroup && !hasPerms) return;
        attach = p.addAttachment(this);
        attachments.put(p, attach);
        if (hasGroup) {
            final String group = userToGroups.get(id);
            final org.bukkit.permissions.Permission perm = getServer().getPluginManager().getPermission(group);
            if (perm == null) getLogger().warning("No such group: " + group);
                else attach.setPermission(perm, true);
        }
        if (hasPerms) userToPerms.get(id).forEach(attach::setPermission);
    }
}
