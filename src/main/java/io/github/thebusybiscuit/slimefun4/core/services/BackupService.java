package io.github.thebusybiscuit.slimefun4.core.services;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backup.ZipTreeWriter;

/**
 * This Service creates a Backup of your Slimefun world data on every server shutdown.
 * 
 * @author TheBusyBiscuit
 *
 */
public class BackupService implements Runnable {

    /**
     * The maximum amount of backups to maintain
     */
    private static final int MAX_BACKUPS = 20;

    /**
     * Our {@link DateTimeFormatter} for formatting file names.
     */
    private final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm", Locale.ROOT);

    /**
     * The directory in which to create the backups
     */
    private final File directory = new File("data-storage/Slimefun/block-backups");

    @Override
    public void run() {
        // Make sure that the directory exists.
        if (directory.exists()) {
            File[] existing = directory.listFiles();
            List<File> backups = existing == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(existing));

            if (backups.size() > MAX_BACKUPS) {
                try {
                    purgeBackups(backups);
                } catch (IOException e) {
                    Slimefun.logger().log(Level.WARNING, "Could not delete an old backup", e);
                }
            }

            File file = new File(directory, format.format(LocalDateTime.now()) + ".zip");

            if (!file.exists()) {
                try {
                    if (file.createNewFile()) {
                        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
                            createBackup(output);
                        }

                        Slimefun.logger().log(Level.INFO, "Backed up Slimefun data to: {0}", file.getName());
                    } else {
                        Slimefun.logger().log(Level.WARNING, "Could not create backup-file: {0}", file.getName());
                    }
                } catch (IOException x) {
                    Slimefun.logger().log(Level.SEVERE, x, () -> "An Exception occurred while creating a backup for Slimefun " + Slimefun.getVersion());
                }
            }
        }
    }

    private void createBackup(@Nonnull ZipOutputStream output) throws IOException {
        Validate.notNull(output, "The Output Stream cannot be null!");

        File[] worlds = new File("data-storage/Slimefun/stored-blocks/").listFiles();

        if (worlds != null) {
            for (File folder : worlds) {
                addDirectory(output, folder, "stored-blocks/" + folder.getName());
            }
        }

        addDirectory(output, new File("data-storage/Slimefun/universal-inventories/"), "universal-inventories");
        addDirectory(output, new File("data-storage/Slimefun/stored-inventories/"), "stored-inventories");

        ZipTreeWriter.addFile(output, new File("data-storage/Slimefun/stored-chunks/chunks.sfc"), "stored-chunks/chunks.sfc");
    }

    private void addDirectory(@Nonnull ZipOutputStream output, @Nonnull File directory, @Nonnull String zipPath) throws IOException {
        ZipTreeWriter.addDirectory(output, directory, zipPath);
    }

    /**
     * This method will delete old backups.
     * 
     * @param backups
     *            The {@link List} of all backups
     * 
     * @throws IOException
     *             An {@link IOException} is thrown if a {@link File} could not be deleted
     */
    private void purgeBackups(@Nonnull List<File> backups) throws IOException {
        Collections.sort(backups, (a, b) -> {
            LocalDateTime time1 = LocalDateTime.parse(a.getName().substring(0, a.getName().length() - 4), format);
            LocalDateTime time2 = LocalDateTime.parse(b.getName().substring(0, b.getName().length() - 4), format);

            return time2.compareTo(time1);
        });

        for (int i = backups.size() - MAX_BACKUPS; i > 0; i--) {
            Files.delete(backups.get(i).toPath());
        }
    }

}
