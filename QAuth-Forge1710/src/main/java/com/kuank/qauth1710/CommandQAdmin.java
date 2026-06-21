package com.kuank.qauth1710;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import java.util.UUID;

public class CommandQAdmin extends CommandBase {

    private final QAuthMod mod;

    public CommandQAdmin(QAuthMod mod) {
        this.mod = mod;
    }

    @Override
    public String getCommandName() {
        return "qadmin";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/qadmin <verify|unlock> <args>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public int compareTo(Object other) {
        return getCommandName().compareTo(((ICommand) other).getCommandName());
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) return;

        String subCmd = args[0].toLowerCase();
        String arg2 = args[1];

        if (subCmd.equals("verify")) {
            if (!mod.isAuthEnabled()) {
                sender.addChatMessage(new ChatComponentText("FAIL:AuthDisabled"));
                return;
            }

            if (mod.getCodeMap().containsKey(arg2)) {
                UUID uuid = mod.getCodeMap().get(arg2);
                EntityPlayerMP target = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.stream()
                    .filter(e -> e instanceof EntityPlayerMP && ((EntityPlayerMP) e).getUniqueID().equals(uuid))
                    .findFirst().orElse(null);

                if (target != null) {
                    mod.unlockPlayer(target);
                    mod.getCodeMap().remove(arg2);
                    sender.addChatMessage(new ChatComponentText("SUCCESS:" + target.getDisplayName()));
                } else {
                    sender.addChatMessage(new ChatComponentText("FAIL:PlayerOffline"));
                }
            } else {
                sender.addChatMessage(new ChatComponentText("FAIL:InvalidCode"));
            }
        }

        if (subCmd.equals("unlock")) {
            EntityPlayerMP target = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.stream()
                .filter(e -> e instanceof EntityPlayerMP && ((EntityPlayerMP) e).getDisplayName().equalsIgnoreCase(arg2))
                .findFirst().orElse(null);

            if (target != null) {
                mod.unlockPlayer(target);
                sender.addChatMessage(new ChatComponentText("SUCCESS:Unlocked"));
            } else {
                sender.addChatMessage(new ChatComponentText("FAIL:PlayerOffline"));
            }
        }
    }
}
