package com.kuank.qauth1710;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

@Mod(modid = QAuthMod.MODID, version = QAuthMod.VERSION, acceptableRemoteVersions = "*")
public class QAuthMod {

    public static final String MODID = "qauth";
    public static final String VERSION = "1.5.0";

    private static final Logger LOGGER = LogManager.getLogger("qauth");

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<String, UUID> codeMap = new HashMap<>();
    private final Map<UUID, double[]> lastPositions = new HashMap<>();

    private String serverId = "default";
    private Properties config;
    private ChatWebSocketServer wsServer;
    private boolean authEnabled;
    private boolean wsEnabled;
    private int wsPort;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        loadConfig(event.getModConfigurationDirectory());
    }

    @Mod.EventHandler
    public void onServerStart(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandLink(this));
        event.registerServerCommand(new CommandQAdmin(this));

        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);

        if (wsEnabled) {
            startWebSocketServer();
        }

        LOGGER.info("QAuth v{} (Forge 1.7.10) loaded! Server ID: {}", VERSION, serverId);
    }

    @Mod.EventHandler
    public void onServerStop(FMLServerStoppingEvent event) {
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
                LOGGER.info("WebSocket server stopped");
            } catch (InterruptedException e) {
                LOGGER.warn("Error stopping WebSocket server: {}", e.getMessage());
            }
        }
    }

    private void loadConfig(File configDir) {
        Path configPath = Paths.get(configDir.getPath(), "qauth.properties");
        config = new Properties();

        try {
            Files.createDirectories(configPath.getParent());

            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    config.load(is);
                }
            } else {
                config.setProperty("server-id", "default");
                config.setProperty("auth.enabled", "true");
                config.setProperty("websocket.enabled", "false");
                config.setProperty("websocket.port", "25580");
                config.setProperty("msg.not-bound", "\u00a7c\u60a8\u7684\u8d26\u53f7\u672a\u7ed1\u5b9aQQ\uff0c\u5df2\u88ab\u9650\u5236\u79fb\u52a8\uff01");
                config.setProperty("msg.use-link", "\u00a7a\u8bf7\u8f93\u5165\u6307\u4ee4 /link \u83b7\u53d6\u9a8c\u8bc1\u7801");
                config.setProperty("msg.code-generated", "\u00a7a\u9a8c\u8bc1\u7801: \u00a7b{code} \u00a77(\u8bf7\u53d1\u7ed9\u673a\u5668\u4eba: \u7ed1\u5b9a {code})");
                config.setProperty("msg.already-verified", "\u00a7a\u65e0\u9700\u91cd\u590d\u9a8c\u8bc1\u3002");
                config.setProperty("msg.verify-success", "\u00a7a\u3010\u7cfb\u7edf\u3011\u9a8c\u8bc1\u6210\u529f/\u7ed1\u5b9a\u4fe1\u606f\u5df2\u66f4\u65b0\uff0c\u9650\u5236\u89e3\u9664\uff01");

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

    private void startWebSocketServer() {
        wsServer = new ChatWebSocketServer(new InetSocketAddress(wsPort));
        wsServer.start();
        LOGGER.info("WebSocket server started on port: {}", wsPort);
    }

    private void broadcastWsChat(String sender, String message) {
        if (FMLCommonHandler.instance().getMinecraftServerInstance() == null) {
            return;
        }
        List players = FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList;
        ChatComponentText text = new ChatComponentText("\u00a7b[QQ] \u00a7f" + sender + ": \u00a77" + message);
        for (Object playerObj : players) {
            if (playerObj instanceof EntityPlayerMP) {
                ((EntityPlayerMP) playerObj).addChatMessage(text);
            }
        }
    }

    String getMessage(String key) {
        return config.getProperty("msg." + key, "");
    }

    // ========== Event Handlers ==========

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player.worldObj.isRemote) return;
        if (!authEnabled) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;

        if (!player.getEntityData().getBoolean("qauth_verified")) {
            frozenPlayers.add(player.getUniqueID());
            player.addChatMessage(new ChatComponentText(getMessage("not-bound")));
            player.addChatMessage(new ChatComponentText(getMessage("use-link")));
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.player.getUniqueID();
        frozenPlayers.remove(uuid);
        lastPositions.remove(uuid);
        codeMap.values().remove(uuid);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (event.player.worldObj.isRemote) return;
        if (!authEnabled) return;

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();

        if (frozenPlayers.contains(uuid)) {
            double[] lastPos = lastPositions.get(uuid);
            if (lastPos != null) {
                if (player.posX != lastPos[0] || player.posZ != lastPos[2]) {
                    player.playerNetServerHandler.setPlayerLocation(lastPos[0], player.posY, lastPos[2], player.rotationYaw, player.rotationPitch);
                }
            }
            lastPositions.put(uuid, new double[]{player.posX, player.posY, player.posZ});
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (!wsEnabled || wsServer == null) return;

        if (event.message.startsWith("#")) {
            String chatMsg = event.message.substring(1).trim();
            if (!chatMsg.isEmpty()) {
                String json = String.format(
                    "{\"type\":\"chat\",\"server_id\":\"%s\",\"player\":\"%s\",\"message\":\"%s\"}",
                    serverId,
                    event.player.getDisplayName(),
                    chatMsg.replace("\\", "\\\\").replace("\"", "\\\"")
                );
                wsServer.broadcast(json);
                event.player.addChatMessage(new ChatComponentText("\u00a77[QAuth] \u6d88\u606f\u5df2\u8f6c\u53d1\u5230QQ\u7fa4"));
            }
        }
    }

    // ========== Public API for commands ==========

    String getServerId() {
        return serverId;
    }

    Map<String, UUID> getCodeMap() {
        return codeMap;
    }

    Set<UUID> getFrozenPlayers() {
        return frozenPlayers;
    }

    boolean isAuthEnabled() {
        return authEnabled;
    }

    void unlockPlayer(EntityPlayerMP player) {
        player.getEntityData().setBoolean("qauth_verified", true);
        frozenPlayers.remove(player.getUniqueID());
        lastPositions.remove(player.getUniqueID());
        player.addChatMessage(new ChatComponentText(getMessage("verify-success")));
    }

    // ========== WebSocket Server ==========

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
