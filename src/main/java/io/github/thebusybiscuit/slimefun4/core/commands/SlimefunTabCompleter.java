package io.github.thebusybiscuit.slimefun4.core.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.researches.Research;
import io.github.thebusybiscuit.slimefun4.core.debug.TestCase;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.Checkpoint;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.WorldBackupService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.WorldSnapshot;

class SlimefunTabCompleter implements TabCompleter {

    private static final int MAX_SUGGESTIONS = 80;

    private final SlimefunCommand command;

    public SlimefunTabCompleter(@Nonnull SlimefunCommand command) {
        this.command = command;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            return createReturnList(command.getSubCommandNames(), args[0]);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("addon")) {
                return createReturnList(Slimefun.getAddonManager().tabComplete(new String[] { args[1] }), args[1]);
            } else if (args[0].equalsIgnoreCase("debug")) {
                return createReturnList(TestCase.VALUES_LIST, args[1]);
            } else if (args[0].equalsIgnoreCase("checkpoint")) {
                return createReturnList(Arrays.asList("create", "list", "info", "status", "restore", "worlds"), args[1]);
            } else if (args[0].equalsIgnoreCase("storage")) {
                return createReturnList(Arrays.asList(
                    "info", "stats", "status", "provider", "verify", "repair",
                    "cache", "backup", "migrate", "gui"), args[1]);
            } else {
                // Returning null will make it fallback to the default arguments (all online players)
                return null;
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("addon")) {
                return createReturnList(Slimefun.getAddonManager().tabComplete(new String[] { args[1], args[2] }), args[2]);
            } else if (args[0].equalsIgnoreCase("give")) {
                return createReturnList(getSlimefunItems(), args[2]);
            } else if (args[0].equalsIgnoreCase("research")) {
                List<Research> researches = Slimefun.getRegistry().getResearches();
                List<String> suggestions = new LinkedList<>();

                suggestions.add("all");
                suggestions.add("reset");

                for (Research research : researches) {
                    suggestions.add(research.getKey().toString().toLowerCase(Locale.ROOT));
                }

                return createReturnList(suggestions, args[2]);
            } else if (args[0].equalsIgnoreCase("checkpoint")) {
                if (args[1].equalsIgnoreCase("info")) {
                    return createReturnList(getCheckpointIds(), args[2]);
                } else if (args[1].equalsIgnoreCase("restore")) {
                    List<String> suggestions = new ArrayList<>(Arrays.asList("storage", "database"));
                    suggestions.addAll(getCheckpointIds());
                    return createReturnList(suggestions, args[2]);
                } else if (args[1].equalsIgnoreCase("worlds")) {
                    return createReturnList(Arrays.asList("create", "list", "info", "restore"), args[2]);
                } else {
                    return Collections.emptyList();
                }
            } else if (args[0].equalsIgnoreCase("storage")) {
                if (args[1].equalsIgnoreCase("backup")) {
                    return createReturnList(Arrays.asList("create", "list", "restore", "delete", "verify", "blocks"), args[2]);
                } else if (args[1].equalsIgnoreCase("cache")) {
                    return createReturnList(Arrays.asList("clear"), args[2]);
                } else if (args[1].equalsIgnoreCase("migrate")) {
                    return createReturnList(Arrays.asList("targets", "check", "start", "status", "apply"), args[2]);
                } else {
                    return Collections.emptyList();
                }
            } else {
                // Returning null will make it fallback to the default arguments (all online players)
                return null;
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("give")) {
                return createReturnList(Arrays.asList("1", "2", "4", "8", "16", "32", "64"), args[3]);
            } else if (args[0].equalsIgnoreCase("checkpoint")
                    && args[1].equalsIgnoreCase("restore")) {
                if (args[2].equalsIgnoreCase("storage") || args[2].equalsIgnoreCase("database")) {
                    return createReturnList(getCheckpointIds(), args[3]);
                }

                if (getCheckpointIds().stream().anyMatch(args[2]::equalsIgnoreCase)) {
                    return createReturnList(List.of("confirm"), args[3]);
                }

                return Collections.emptyList();
            } else if (args[0].equalsIgnoreCase("checkpoint")
                    && args[1].equalsIgnoreCase("worlds")
                    && (args[2].equalsIgnoreCase("info") || args[2].equalsIgnoreCase("restore"))) {
                return createReturnList(getWorldBackupIds(), args[3]);
            } else {
                return null;
            }
        } else if (args.length == 5) {
            if (args[0].equalsIgnoreCase("checkpoint")
                    && args[1].equalsIgnoreCase("worlds")
                    && args[2].equalsIgnoreCase("restore")
                    && getWorldBackupIds().stream().anyMatch(args[3]::equalsIgnoreCase)) {
                return createReturnList(List.of("confirm"), args[4]);
            }

            return Collections.emptyList();
        } else {
            // Returning null will make it fallback to the default arguments (all online players)
            return null;
        }
    }

    /***
     * Returns a sublist from a given list containing items that start with the given string if string is not empty
     * 
     * @param list
     *            The list to process
     * @param string
     *            The typed string
     * @return Sublist if string is not empty
     */
    @Nonnull
    private List<String> createReturnList(@Nonnull List<String> list, @Nonnull String string) {
        if (string.length() == 0) {
            return list;
        }

        String input = string.toLowerCase(Locale.ROOT);
        List<String> returnList = new LinkedList<>();

        for (String item : list) {
            if (item.toLowerCase(Locale.ROOT).contains(input)) {
                returnList.add(item);

                if (returnList.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            } else if (item.equalsIgnoreCase(input)) {
                return Collections.emptyList();
            }
        }

        return returnList;
    }

    @Nonnull
    private List<String> getSlimefunItems() {
        List<SlimefunItem> items = Slimefun.getRegistry().getEnabledSlimefunItems();
        List<String> list = new ArrayList<>(items.size());

        for (SlimefunItem item : items) {
            list.add(item.getId());
        }

        return list;
    }

    @Nonnull
    private List<String> getCheckpointIds() {
        Slimefun instance = Slimefun.instance();

        if (instance == null) {
            return Collections.emptyList();
        }

        StorageEngineService svc = instance.getStorageEngineService();

        if (svc == null) {
            return Collections.emptyList();
        }

        CheckpointService cpService = svc.getCheckpointService();

        if (cpService == null) {
            return Collections.emptyList();
        }

        List<Checkpoint> checkpoints = cpService.list();
        List<String> ids = new ArrayList<>(checkpoints.size());

        for (Checkpoint cp : checkpoints) {
            ids.add(cp.id());
        }

        return ids;
    }

    @Nonnull
    private List<String> getWorldBackupIds() {
        Slimefun instance = Slimefun.instance();

        if (instance == null) {
            return Collections.emptyList();
        }

        StorageEngineService svc = instance.getStorageEngineService();

        if (svc == null) {
            return Collections.emptyList();
        }

        WorldBackupService worldService = svc.getWorldBackupService();

        if (worldService == null) {
            return Collections.emptyList();
        }

        List<WorldSnapshot> snapshots = worldService.list();
        List<String> ids = new ArrayList<>(snapshots.size());

        for (WorldSnapshot snapshot : snapshots) {
            ids.add(snapshot.id());
        }

        return ids;
    }

}
