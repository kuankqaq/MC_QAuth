package com.kuank;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class QAuth extends JavaPlugin implements Listener {

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<String, UUID> codeMap = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("QAuth v1.2 by kuank 已加载！");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.getScoreboardTags().contains("verified")) {
            frozenPlayers.add(player.getUniqueId());
            player.sendMessage("§c您的账号未绑定QQ，已被限制移动！");
            player.sendMessage("§a请输入指令 /link 获取验证码");
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
                player.sendMessage("§a无需重复验证。");
                return true;
            }
            String code = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
            codeMap.values().remove(player.getUniqueId());
            codeMap.put(code, player.getUniqueId());
            player.sendMessage("§a验证码: §b" + code + " §7(请发给机器人: 绑定 " + code + ")");
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
        p.sendMessage("§a【系统】验证成功/绑定信息已更新，限制解除！");
    }
}