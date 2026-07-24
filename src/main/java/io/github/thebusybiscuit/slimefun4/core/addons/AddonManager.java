package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;

import io.github.bakedlibs.dough.common.ChatColors;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Bootstrap facade for the managed addon subsystem.
 */
public final class AddonManager {

    private static final String PREFIX = "&8[&b✦ AddonManager&8] ";

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final AddonLoader addonLoader;
    private final UpdateChecker updateChecker;
    private final DownloadService downloadService;
    private final AddonService addonService;
    private final GitHubSyncService gitHubSyncService;
    private final CommandManager commandManager;

    public AddonManager(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
        this.configManager = new ConfigManager(plugin);
        this.addonLoader = new AddonLoader(plugin, configManager);
        this.updateChecker = new UpdateChecker(plugin);
        this.downloadService = new DownloadService(plugin, configManager, addonLoader);
        this.addonService = new AddonService(plugin, configManager, updateChecker, downloadService, addonLoader);
        this.gitHubSyncService = new GitHubSyncService(plugin, configManager, addonService);
        this.commandManager = new CommandManager(plugin, addonService, gitHubSyncService);
    }

    public void start() {
        try {
            configManager.initialize();
            registerCommand();
            addonService.refreshDefinitions();
            addonService.syncConfiguredAddons();

            if (configManager.getSyncSettings().enabled()) {
                gitHubSyncService.start();
            }

            String versionTag = VersionTagResolver.resolveTag(Slimefun.getMinecraftVersion());
            int total = addonService.listAddons().size();
            int compatible = addonService.listCompatibleAddons().size();
            info("Addon system ready — detected server: &f" + versionTag);
            info("Managed addons: &f" + total + " &7| &aCompatible: &f" + compatible);
            info("Repository: &f" + configManager.getRepositoryDirectory());
            info("Cloud sync: &f" + configManager.getSyncSettings().url());
        } catch (IOException x) {
            error("Failed to initialize the managed addon system", x);
        }
    }

    public void shutdown() {
        gitHubSyncService.shutdown();
        addonService.shutdown();
        info("Addon system shut down gracefully.");
    }

    public @Nonnull AddonService getAddonService() {
        return addonService;
    }

    public @Nonnull List<ManagedAddonStatus> listStatuses() {
        return addonService.listAddons();
    }

    public @Nonnull GitHubSyncService getGitHubSyncService() {
        return gitHubSyncService;
    }

    public boolean handleCommand(@Nonnull CommandSender sender, @Nonnull String label, @Nonnull String[] args) {
        return commandManager.handleCommand(sender, label, args);
    }

    public @Nonnull List<String> tabComplete(@Nonnull String[] args) {
        return commandManager.tabComplete(args);
    }

    public void info(@Nonnull String message) {
        plugin.getServer().getConsoleSender().sendMessage(ChatColors.color(PREFIX + "&a" + message));
    }

    public void warn(@Nonnull String message) {
        plugin.getServer().getConsoleSender().sendMessage(ChatColors.color(PREFIX + "&e" + message));
    }

    public void error(@Nonnull String message, @Nonnull Throwable throwable) {
        plugin.getServer().getConsoleSender().sendMessage(ChatColors.color(PREFIX + "&c" + message + ": &4" + throwable.getMessage()));
        plugin.getLogger().warning(message + ": " + throwable.getMessage());
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand("addon");
        if (command != null) {
            command.setExecutor(commandManager);
            command.setTabCompleter(commandManager);
        } else {
            warn("The /addon command is missing from plugin.yml and could not be registered.");
        }
    }
}
