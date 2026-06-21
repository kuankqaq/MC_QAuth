package com.kuank;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class QAuth extends JavaPlugin implements Listener {

    private static final String VERSION = "1.5.0";
    private static final int BSTATS_PLUGIN_ID = 29266;

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<String, UUID> codeMap = new HashMap<>();

    private String serverId;
    private ChatWebSocketServer wsServer;
    private boolean authEnabled;
    private boolean wsEnabled;
    private int wsPort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverId = getConfig().getString("server-id", "default");
        authEnabled = getConfig().getBoolean("auth.enabled", true);
        wsEnabled = getConfig().getBoolean("websocket.enabled", false);
        wsPort = getConfig().getInt("websocket.port", 25580);

        if ("default".equals(serverId)) {
            getLogger().warning("========================================");
            getLogger().warning("Warning: server-id is not configured.");
            getLogger().warning("Please set a unique server-id in plugins/QAuth/config.yml");
            getLogger().warning("========================================");
        }

        if (wsEnabled) {
            startWebSocketServer();
        }

        new Metrics(this, BSTATS_PLUGIN_ID);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("QAuth v" + VERSION + " loaded. Server ID: " + serverId + ", auth enabled: " + authEnabled);
    }

    @Override
    public void onDisable() {
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
                getLogger().info("WebSocket server stopped");
            } catch (InterruptedException e) {
                getLogger().warning("Error stopping WebSocket server: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startWebSocketServer() {
        wsServer = new ChatWebSocketServer(new InetSocketAddress(wsPort));
        wsServer.start();
        getLogger().info("WebSocket server started on port: " + wsPort);
    }

    private String getMessage(String key) {
        String msg = getConfig().getString("messages." + key, "");
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!authEnabled) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.getScoreboardTags().contains("verified")) {
            frozenPlayers.add(player.getUniqueId());
            player.sendMessage(getMessage("not-bound"));
            player.sendMessage(getMessage("use-link"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        frozenPlayers.remove(event.getPlayer().getUniqueId());
        codeMap.values().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!authEnabled || !frozenPlayers.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) {
            return;
        }

        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) {
                return true;
            }
            if (!authEnabled) {
                sender.sendMessage(ChatColor.YELLOW + "QAuth verification is disabled on this server.");
                return true;
            }

            Player player = (Player) sender;
            if (player.getScoreboardTags().contains("verified")) {
                player.sendMessage(getMessage("already-verified"));
                return true;
            }

            String code = serverId + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase();
            codeMap.values().remove(player.getUniqueId());
            codeMap.put(code, player.getUniqueId());
            player.sendMessage(getMessage("code-generated").replace("{code}", code));
            return true;
        }

        if (label.equalsIgnoreCase("qadmin")) {
            if (sender instanceof Player && !sender.isOp()) {
                return true;
            }
            if (args.length < 2) {
                return false;
            }

            String subCmd = args[0].toLowerCase();
            String arg2 = args[1];

            if (subCmd.equals("verify")) {
                if (!authEnabled) {
                    sender.sendMessage("FAIL:AuthDisabled");
                    return true;
                }

                UUID uuid = codeMap.get(arg2);
                if (uuid == null) {
                    sender.sendMessage("FAIL:InvalidCode");
                    return true;
                }

                Player target = Bukkit.getPlayer(uuid);
                if (target != null) {
                    unlockPlayer(target);
                    codeMap.remove(arg2);
                    sender.sendMessage("SUCCESS:" + target.getName());
                } else {
                    sender.sendMessage("FAIL:PlayerOffline");
                }
                return true;
            }

            if (subCmd.equals("unlock")) {
                Player target = Bukkit.getPlayer(arg2);
                if (target != null) {
                    unlockPlayer(target);
                    sender.sendMessage("SUCCESS:Unlocked");
                } else {
                    sender.sendMessage("FAIL:PlayerOffline");
                }
                return true;
            }
        }
        return false;
    }

    private void unlockPlayer(Player player) {
        player.addScoreboardTag("verified");
        frozenPlayers.remove(player.getUniqueId());
        player.sendMessage(getMessage("verify-success"));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!wsEnabled || wsServer == null) {
            return;
        }

        String message = event.getMessage();
        if (message.startsWith("#")) {
            String chatMsg = message.substring(1).trim();
            if (!chatMsg.isEmpty()) {
                JsonObject json = new JsonObject();
                json.addProperty("type", "chat");
                json.addProperty("server_id", serverId);
                json.addProperty("player", event.getPlayer().getName());
                json.addProperty("message", chatMsg);
                wsServer.broadcast(json.toString());
                event.getPlayer().sendMessage(ChatColor.GRAY + "[QAuth] Message forwarded to QQ group");
            }
        }
    }

    private void broadcastWsChat(String sender, String message) {
        Bukkit.getScheduler().runTask(this, () ->
            Bukkit.broadcastMessage(ChatColor.AQUA + "[QQ] " + ChatColor.WHITE + sender + ": " + ChatColor.GRAY + message)
        );
    }

    private class ChatWebSocketServer extends WebSocketServer {
        ChatWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            getLogger().info("WebSocket client connected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            getLogger().info("WebSocket client disconnected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            try {
                JsonObject json = new JsonParser().parse(message).getAsJsonObject();
                if (!"chat".equals(json.has("type") ? json.get("type").getAsString() : "")) {
                    return;
                }
                String sender = json.has("sender") ? json.get("sender").getAsString() : "QQ";
                String chat = json.has("message") ? json.get("message").getAsString() : "";
                if (!chat.trim().isEmpty()) {
                    broadcastWsChat(sender, chat);
                }
            } catch (Exception e) {
                getLogger().warning("Invalid WebSocket message: " + e.getMessage());
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            getLogger().warning("WebSocket error: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            getLogger().info("WebSocket server started successfully");
        }
    }
}
