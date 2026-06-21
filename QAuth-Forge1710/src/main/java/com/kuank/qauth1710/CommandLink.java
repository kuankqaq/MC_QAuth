package com.kuank.qauth1710;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import java.util.UUID;

public class CommandLink extends CommandBase {

    private final QAuthMod mod;

    public CommandLink(QAuthMod mod) {
        this.mod = mod;
    }

    @Override
    public String getCommandName() {
        return "link";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/link";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayerMP;
    }

    @Override
    public int compareTo(Object other) {
        return getCommandName().compareTo(((ICommand) other).getCommandName());
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (!mod.isAuthEnabled()) {
            player.addChatMessage(new ChatComponentText("QAuth verification is disabled on this server."));
            return;
        }

        if (player.getEntityData().getBoolean("qauth_verified")) {
            player.addChatMessage(new ChatComponentText(mod.getMessage("already-verified")));
            return;
        }

        String code = mod.getServerId() + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase();
        mod.getCodeMap().values().remove(player.getUniqueID());
        mod.getCodeMap().put(code, player.getUniqueID());

        player.addChatMessage(new ChatComponentText(mod.getMessage("code-generated").replace("{code}", code)));
    }
}
