package com.kuank.qauth;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

public class QAuthMod implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("qauth");

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<String, UUID> codeMap = new HashMap<>();
    private final Map<UUID, Vec3d> lastPositions = new HashMap<>();

    private String serverId = "default";
    private Properties config;
    private Path configPath;
    private ChatWebSocketServer wsServer;
    private MinecraftServer minecraftServer;
    private boolean authEnabled;
    private boolean wsEnabled;
    private int wsPort;

    @Override
    public void onInitializeServer() {
        loadConfig();
        registerCommands();
        registerEvents();
        LOGGER.info("QAuth v1.5.0 (Fabric) loaded! Server ID: {}, auth enabled: {}", serverId, authEnabled);
    }

    private void loadConfig() {
        configPath = Paths.get("config", "qauth.properties");
        config = new Properties();

        try {
            Files.createDirectories(configPath.getParent());

            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    config.load(is);
                }
            } else {
                // Create default config
                config.setProperty("server-id", "default");
                config.setProperty("auth.enabled", "true");
                config.setProperty("websocket.enabled", "false");
                config.setProperty("websocket.port", "25580");
                config.setProperty("msg.not-bound", "§c您的账号未绑定QQ，已被限制移动！");
                config.setProperty("msg.use-link", "§a请输入指令 /link 获取验证码");
                config.setProperty("msg.code-generated", "§a验证码: §b{code} §7(请发给机器人: 绑定 {code})");
                config.setProperty("msg.already-verified", "§a无需重复验证。");
                config.setProperty("msg.verify-success", "§a【系统】验证成功/绑定信息已更新，限制解除！");

                try (OutputStream os = Files.newOutputStream(configPath)) {
                    config.store(os, "QAuth Configuration");
                }
            }

            serverId = config.getProperty("server-id", "default");
            authEnabled = Boolean.parseBoolean(config.getProperty("auth.enabled", "true"));
            wsEnabled = Boolean.parseBoolean(config.getProperty("websocket.enabled", "false"));
            wsPort = Integer.parseInt(config.getProperty("websocket.port", "25580"));

            if (serverId.equals("default")) {
                LOGGER.warn("========================================");
                LOGGER.warn("Warning: server-id not configured!");
                LOGGER.warn("Please set a unique server-id in config/qauth.properties");
                LOGGER.warn("========================================");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load config", e);
        }
    }

    private String getMessage(String key) {
        return config.getProperty("msg." + key, "");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /link command
            dispatcher.register(CommandManager.literal("link")
                .executes(context -> {
                    ServerCommandSource source = context.getSource();
                    ServerPlayerEntity player = source.getPlayer();

                    if (player == null) {
                        source.sendError(Text.literal("Only players can use this command"));
                        return 0;
                    }

                    if (!authEnabled) {
                        player.sendMessage(Text.literal("QAuth verification is disabled on this server."));
                        return 1;
                    }

                    if (isVerified(player)) {
                        player.sendMessage(Text.literal(getMessage("already-verified")));
                        return 1;
                    }

                    String code = serverId + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase();
                    codeMap.values().remove(player.getUuid());
                    codeMap.put(code, player.getUuid());

                    player.sendMessage(Text.literal(getMessage("code-generated").replace("{code}", code)));
                    return 1;
                }));

            // /qadmin command
            registerQAdminCommand(dispatcher);
        });
    }

    private void registerQAdminCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("qadmin")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.literal("verify")
                .then(CommandManager.argument("code", StringArgumentType.string())
                    .executes(context -> {
                        String code = StringArgumentType.getString(context, "code");
                        ServerCommandSource source = context.getSource();

                        if (!authEnabled) {
                            source.sendFeedback(() -> Text.literal("FAIL:AuthDisabled"), false);
                            return 1;
                        }

                        if (codeMap.containsKey(code)) {
                            UUID uuid = codeMap.get(code);
                            ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(uuid);

                            if (target != null) {
                                unlockPlayer(target);
                                codeMap.remove(code);
                                source.sendFeedback(() -> Text.literal("SUCCESS:" + target.getName().getString()), false);
                            } else {
                                source.sendFeedback(() -> Text.literal("FAIL:PlayerOffline"), false);
                            }
                        } else {
                            source.sendFeedback(() -> Text.literal("FAIL:InvalidCode"), false);
                        }
                        return 1;
                    })))
            .then(CommandManager.literal("unlock")
                .then(CommandManager.argument("player", StringArgumentType.string())
                    .executes(context -> {
                        String playerName = StringArgumentType.getString(context, "player");
                        ServerCommandSource source = context.getSource();
                        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(playerName);

                        if (target != null) {
                            unlockPlayer(target);
                            source.sendFeedback(() -> Text.literal("SUCCESS:Unlocked"), false);
                        } else {
                            source.sendFeedback(() -> Text.literal("FAIL:PlayerOffline"), false);
                        }
                        return 1;
                    }))));
    }

    private void registerEvents() {
        // Server start event - start WebSocket server
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            minecraftServer = server;
            if (wsEnabled) {
                startWebSocketServer();
            }
        });

        // Server stop event - stop WebSocket server
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            minecraftServer = null;
            if (wsServer != null) {
                try {
                    wsServer.stop(1000);
                    LOGGER.info("WebSocket server stopped");
                } catch (InterruptedException e) {
                    LOGGER.warn("Error stopping WebSocket server: {}", e.getMessage());
                }
            }
        });

        // Player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            if (!authEnabled) {
                return;
            }

            if (!isVerified(player)) {
                frozenPlayers.add(player.getUuid());
                lastPositions.put(player.getUuid(), player.getPos());
                player.sendMessage(Text.literal(getMessage("not-bound")));
                player.sendMessage(Text.literal(getMessage("use-link")));
            }
        });

        // Player disconnect event
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            frozenPlayers.remove(uuid);
            lastPositions.remove(uuid);
            codeMap.values().remove(uuid);
        });

        // Chat message event
        ServerMessageEvents.CHAT_MESSAGE.register((message, senderEntity, params) -> {
            if (!wsEnabled || wsServer == null) return;

            String content = message.getContent().getString();
            if (content.startsWith("#")) {
                String chatMsg = content.substring(1).trim();
                if (!chatMsg.isEmpty()) {
                    String json = String.format(
                        "{\"type\":\"chat\",\"server_id\":\"%s\",\"player\":\"%s\",\"message\":\"%s\"}",
                        serverId,
                        senderEntity.getName().getString(),
                        chatMsg.replace("\\", "\\\\").replace("\"", "\\\"")
                    );
                    wsServer.broadcast(json);
                    senderEntity.sendMessage(Text.literal("§7[QAuth] 消息已转发到QQ群"));
                }
            }
        });

        // Tick event for movement restriction
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!authEnabled) {
                    return;
                }
                UUID uuid = player.getUuid();
                if (frozenPlayers.contains(uuid)) {
                    Vec3d lastPos = lastPositions.get(uuid);
                    if (lastPos != null) {
                        Vec3d currentPos = player.getPos();
                        if (currentPos.x != lastPos.x || currentPos.z != lastPos.z) {
                            player.teleport(lastPos.x, lastPos.y, lastPos.z);
                        }
                    }
                    lastPositions.put(uuid, player.getPos());
                }
            }
        });
    }

    private boolean isVerified(ServerPlayerEntity player) {
        return player.getCommandTags().contains("verified");
    }

    private void unlockPlayer(ServerPlayerEntity player) {
        player.addCommandTag("verified");
        frozenPlayers.remove(player.getUuid());
        lastPositions.remove(player.getUuid());
        player.sendMessage(Text.literal(getMessage("verify-success")));
    }

    private void startWebSocketServer() {
        wsServer = new ChatWebSocketServer(new InetSocketAddress(wsPort));
        wsServer.start();
        LOGGER.info("WebSocket server started on port: {}", wsPort);
    }

    private void broadcastWsChat(String sender, String message) {
        MinecraftServer server = minecraftServer;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Text text = Text.literal("§b[QQ] §f" + sender + ": §7" + message);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.sendMessage(text);
            }
        });
    }

    private class ChatWebSocketServer extends WebSocketServer {
        public ChatWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            LOGGER.info("WebSocket client connected: {}", conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            LOGGER.info("WebSocket client disconnected: {}", conn.getRemoteSocketAddress());
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
                LOGGER.warn("Invalid WebSocket message: {}", e.getMessage());
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            LOGGER.warn("WebSocket error: {}", ex.getMessage());
        }

        @Override
        public void onStart() {
            LOGGER.info("WebSocket server started successfully");
        }
    }
}
