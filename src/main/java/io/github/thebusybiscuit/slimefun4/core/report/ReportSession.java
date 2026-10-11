package io.github.thebusybiscuit.slimefun4.core.report;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class ReportSession {
    public enum Stage { DESCRIPTION, MCLOGS, DISCORD, ROLE, CONFIRM, DONE }

    private final UUID playerUuid;
    private final String playerName;
    private final ReportCategory category;
    private final Slimefun plugin;
    private Stage stage = Stage.DESCRIPTION;
    private String description;
    private String mclogsUrl;
    private String discordUsername;
    private String role;
    private final Instant startedAt = Instant.now();

    public ReportSession(UUID playerUuid, String playerName, ReportCategory category, Slimefun plugin) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.category = category;
        this.plugin = plugin;
    }

    public boolean handleInput(Player player, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage("§c❌ Report cancelled.");
            stage = Stage.DONE;
            return true; // consumed
        }

        switch (stage) {
            case DESCRIPTION -> {
                description = message;
                stage = Stage.MCLOGS;
                player.sendMessage("§eStep 2/5 §f— Upload your log to §bhttps://mclo.gs §fand paste the link here.");
                player.sendMessage("§7(Type 'skip' if you don't have one — not recommended for bugs.)");
            }
            case MCLOGS -> {
                if (message.equalsIgnoreCase("skip")) {
                    mclogsUrl = null;
                } else if (message.contains("mclo.gs/")) {
                    mclogsUrl = message.trim();
                } else {
                    player.sendMessage("§cThat doesn't look like a mclo.gs URL. Try again, or type 'skip'.");
                    return true;
                }
                stage = Stage.DISCORD;
                player.sendMessage("§eStep 3/5 §f— What is your §9Discord username§f? We use it to contact you about this report.");
                player.sendMessage("§7(Type 'skip' if you'd rather not share it.)");
            }
            case DISCORD -> {
                if (message.equalsIgnoreCase("skip")) {
                    discordUsername = null;
                } else {
                    discordUsername = message.trim();
                }
                stage = Stage.ROLE;
                player.sendMessage("§eStep 4/5 §f— Are you a §aplayer §for the §6server owner/staff§f?");
                player.sendMessage("§7Type: player | owner");
            }
            case ROLE -> {
                if (message.equalsIgnoreCase("player") || message.equalsIgnoreCase("owner")) {
                    role = message.toLowerCase();
                    stage = Stage.CONFIRM;
                    // Show summary
                    player.sendMessage("§eStep 5/5 §f— Reviewing your report:");
                    player.sendMessage("§7  • Category: §f" + category.getDisplayName());
                    player.sendMessage("§7  • Description: §f" + description);
                    player.sendMessage("§7  • Log: §f" + (mclogsUrl != null ? mclogsUrl : "N/A"));
                    player.sendMessage("§7  • Discord: §f" + (discordUsername != null ? discordUsername : "N/A"));
                    player.sendMessage("§7  • Role: §f" + role);
                    player.sendMessage("§aType 'confirm' to submit or 'cancel' to discard.");
                } else {
                    player.sendMessage("§cPlease type either 'player' or 'owner'.");
                }
            }
            case CONFIRM -> {
                if (message.equalsIgnoreCase("confirm")) {
                    stage = Stage.DONE;
                    return true; // caller should now build the ticket
                } else {
                    player.sendMessage("§cType 'confirm' to submit or 'cancel' to discard.");
                }
            }
            default -> { return false; }
        }
        return true;
    }

    public boolean isComplete() { return stage == Stage.DONE && description != null && role != null; }
    public boolean isCancelled() { return stage == Stage.DONE && (description == null || role == null); }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public ReportCategory getCategory() { return category; }
    public String getDescription() { return description; }
    public String getMclogsUrl() { return mclogsUrl; }
    public String getDiscordUsername() { return discordUsername; }
    public String getRole() { return role; }
    public Instant getStartedAt() { return startedAt; }
}
