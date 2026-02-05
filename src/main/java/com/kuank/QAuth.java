package com.kuank;

import org.bukkit.Bukkit;
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
import java.util.*;

public final class QAuth extends JavaPlugin implements Listener {

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<String, UUID> codeMap = new HashMap<>();
    private String serverId;
    private static final int BSTATS_PLUGIN_ID = 29266;
    private ChatWebSocketServer wsServer;
    private boolean wsEnabled;
    private int wsPort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverId = getConfig().getString("server-id", "default");

        if (serverId.equals("default")) {
            getLogger().warning("========================================");
            getLogger().warning("警告: server-id 未配置！");
            getLogger().warning("多服务器环境下请务必设置唯一的 server-id");
            getLogger().warning("编辑 plugins/QAuth/config.yml 进行配置");
            getLogger().warning("========================================");
        }

        // WebSocket 配置
        wsEnabled = getConfig().getBoolean("websocket.enabled", false);
        wsPort = getConfig().getInt("websocket.port", 25580);

        if (wsEnabled) {
            startWebSocketServer();
        }

        new Metrics(this, BSTATS_PLUGIN_ID);

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("QAuth v1.3 已加载！服务器ID: " + serverId);
    }

    @Override
    public void onDisable() {
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
                getLogger().info("WebSocket 服务端已关闭");
            } catch (InterruptedException e) {
                getLogger().warning("关闭 WebSocket 服务端时出错: " + e.getMessage());
            }
        }
    }

    private void startWebSocketServer() {
        wsServer = new ChatWebSocketServer(new InetSocketAddress(wsPort));
        wsServer.start();
        getLogger().info("WebSocket 服务端已启动，端口: " + wsPort);
    }

    private String getMessage(String key) {
        String msg = getConfig().getString("messages." + key, "");
        return msg.replace("&", "§");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
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
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // 1. 玩家指令: /link
        if (label.equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) return true;
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

        // 2. 内部/管理指令: /qadmin <unlock/verify> <args>
        if (label.equalsIgnoreCase("qadmin")) {
            // 只有控制台或OP能用
            if (sender instanceof Player && !sender.isOp()) return true;

            if (args.length >= 2) {
                String subCmd = args[0].toLowerCase();
                String arg2 = args[1];

                //机器人验证码回调 (verify <code>)
                if (subCmd.equals("verify")) {
                    if (codeMap.containsKey(arg2)) {
                        UUID uuid = codeMap.get(arg2);
                        Player target = Bukkit.getPlayer(uuid);
                        if (target != null) {
                            unlockPlayer(target); // 解锁
                            codeMap.remove(arg2);
                            sender.sendMessage("SUCCESS:" + target.getName());
                        } else {
                            sender.sendMessage("FAIL:PlayerOffline");
                        }
                    } else {
                        sender.sendMessage("FAIL:InvalidCode");
                    }
                    return true;
                }

                //管理员强制解锁 (unlock <name>)
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
        }
        return false;
    }

    private void unlockPlayer(Player p) {
        p.addScoreboardTag("verified");
        frozenPlayers.remove(p.getUniqueId());
        p.sendMessage(getMessage("verify-success"));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!wsEnabled || wsServer == null) return;

        String message = event.getMessage();
        if (message.startsWith("#")) {
            String chatMsg = message.substring(1).trim();
            if (!chatMsg.isEmpty()) {
                String json = String.format(
                    "{\"type\":\"chat\",\"server_id\":\"%s\",\"player\":\"%s\",\"message\":\"%s\"}",
                    serverId,
                    event.getPlayer().getName(),
                    chatMsg.replace("\\", "\\\\").replace("\"", "\\\"")
                );
                wsServer.broadcast(json);
                event.getPlayer().sendMessage("§7[QAuth] 消息已转发到QQ群");
            }
        }
    }

    private class ChatWebSocketServer extends WebSocketServer {
        public ChatWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            getLogger().info("WebSocket 客户端已连接: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            getLogger().info("WebSocket 客户端已断开: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 暂不处理客户端消息
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            getLogger().warning("WebSocket 错误: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            getLogger().info("WebSocket 服务端启动完成");
        }
    }
}