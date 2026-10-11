package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.core.report.ReportCategory;
import io.github.thebusybiscuit.slimefun4.core.report.ReportSessionManager;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Handles the {@code /sf report <category>} command.
 * Starts an interactive chat-based report wizard for the player.
 */
class ReportCommand extends SubCommand {

    @ParametersAreNonnullByDefault
    ReportCommand(Slimefun plugin, SlimefunCommand cmd) {
        super(plugin, cmd, "report", false);
    }

    @Override
    public void onExecute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!(sender instanceof Player player)) {
            Slimefun.getLocalization().sendMessage(sender, "messages.only-players", true);
            return;
        }

        if (!sender.hasPermission("slimefun.report.use")) {
            Slimefun.getLocalization().sendMessage(sender, "messages.no-permission", true);
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§eUsage: §f/sf report <category>");
            player.sendMessage("§7Available categories: §fbug§7, §faddon-conflict§7, §fgriefing§7, §fperformance§7, §fother");
            return;
        }

        String categoryName = args[1].toUpperCase().replace("-", "_");
        ReportCategory category = ReportCategory.fromString(categoryName);

        if (category == null) {
            player.sendMessage("§cUnknown report category: §f" + args[1]);
            player.sendMessage("§7Available categories: §fbug§7, §faddon-conflict§7, §fgriefing§7, §fperformance§7, §fother");
            return;
        }

        ReportSessionManager sessionManager = Slimefun.getReportSessionManager();
        if (sessionManager == null) {
            player.sendMessage("§cThe report system is not available right now.");
            return;
        }

        sessionManager.startSession(player, category);
    }
}
