package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.UnknownDependencyException;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * Responsible for validating and loading managed addon jars.
 */
public final class AddonLoader {

    private final Slimefun plugin;
    private final ConfigManager configManager;

    public AddonLoader(@Nonnull Slimefun plugin, @Nonnull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public boolean validateAddonJar(@Nonnull Path jarPath, @Nonnull AddonDefinition definition) {
        Validate.notNull(jarPath, "Jar path must not be null");
        Validate.notNull(definition, "Addon definition must not be null");

        if (Files.notExists(jarPath)) {
            plugin.getLogger().warning("Managed addon jar does not exist: " + jarPath);
            return false;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            if (jarFile.getEntry("plugin.yml") == null) {
                plugin.getLogger().warning("Managed addon jar is missing plugin.yml: " + jarPath.getFileName());
                return false;
            }

            try (InputStream inputStream = jarFile.getInputStream(jarFile.getEntry("plugin.yml"))) {
                PluginDescriptionFile description = new PluginDescriptionFile(inputStream);

                if (!description.getDepend().contains(plugin.getName()) && !description.getSoftDepend().contains(plugin.getName())) {
                    plugin.getLogger().warning("Managed addon \"" + definition.key() + "\" does not declare a dependency on Slimefun.");
                }

                return true;
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Failed to validate managed addon jar " + jarPath.getFileName(), x);
            return false;
        }
    }

    public void loadManagedAddons(@Nonnull Collection<AddonDefinition> definitions) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        boolean enabledAny = false;

        for (AddonDefinition definition : definitions) {
            if (!definition.enabled() || !definition.download()) {
                continue;
            }

            Path addonPath = configManager.resolveAddonPath(definition);

            if (!validateAddonJar(addonPath, definition)) {
                continue;
            }

            try {
                PluginDescriptionFile description = readDescription(addonPath.toFile());

                if (pluginManager.getPlugin(description.getName()) != null) {
                    plugin.getLogger().info("Managed addon already present in the plugin manager, skipping duplicate load: " + description.getName());
                    continue;
                }

                Plugin managedPlugin = pluginManager.loadPlugin(addonPath.toFile());
                if (managedPlugin != null) {
                    pluginManager.enablePlugin(managedPlugin);
                    plugin.getLogger().info("Loaded managed Slimefun addon: " + managedPlugin.getName() + " v" + managedPlugin.getDescription().getVersion());
                    enabledAny = true;
                }
            } catch (UnknownDependencyException | InvalidPluginException | InvalidDescriptionException x) {
                plugin.getLogger().log(Level.WARNING, "Failed to load managed addon \"" + definition.name() + "\"", x);
            }
        }

        /*
         * An addon enabled at runtime registers its SlimefunItems/BlockMenuPresets/BlockTickers during
         * its own onEnable (which enablePlugin() has just run synchronously). Re-run the reconciliation
         * pass so any stored blocks/inventories that were unresolved until now get bound immediately,
         * instead of waiting for a server restart. Scheduled 1 tick later to stay on the main thread
         * and let any deferred addon registration settle.
         */
        if (enabledAny) {
            Slimefun.runSync(BlockStorage::reconcileAllWorlds, 1L);
        }
    }

    public boolean isLoaded(@Nonnull AddonDefinition definition) {
        Path addonPath = configManager.resolveAddonPath(definition);

        if (Files.exists(addonPath)) {
            try {
                PluginDescriptionFile description = readDescription(addonPath.toFile());
                Plugin pluginInstance = Bukkit.getPluginManager().getPlugin(description.getName());
                return pluginInstance != null && pluginInstance.isEnabled();
            } catch (InvalidDescriptionException x) {
                plugin.getLogger().log(Level.WARNING, "Failed to read plugin.yml for managed addon \"" + definition.name() + "\"", x);
                return false;
            }
        }

        Plugin pluginInstance = Bukkit.getPluginManager().getPlugin(definition.name());
        return pluginInstance != null && pluginInstance.isEnabled();
    }

    public @Nonnull Set<String> getLoadedManagedAddonNames(@Nonnull Collection<AddonDefinition> definitions) {
        Set<String> names = new LinkedHashSet<>();

        for (AddonDefinition definition : definitions) {
            if (isLoaded(definition)) {
                names.add(definition.name());
            }
        }

        return names;
    }

    private @Nonnull PluginDescriptionFile readDescription(@Nonnull File file) throws InvalidDescriptionException {
        try (JarFile jarFile = new JarFile(file);
             InputStream inputStream = jarFile.getInputStream(jarFile.getEntry("plugin.yml"))) {
            return new PluginDescriptionFile(inputStream);
        } catch (InvalidDescriptionException x) {
            throw x;
        } catch (Exception x) {
            throw new InvalidDescriptionException(x);
        }
    }
}
