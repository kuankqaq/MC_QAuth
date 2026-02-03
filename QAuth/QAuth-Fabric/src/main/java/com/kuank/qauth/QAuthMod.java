package com.kuank.qauth;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
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

    @Override
    public void onInitializeServer() {
        loadConfig();
        registerCommands();
        registerEvents();
        LOGGER.info("QAuth v1.3 (Fabric) loaded! Server ID: {}", serverId);
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
        // Player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

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

        // Tick event for movement restriction
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
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
}
