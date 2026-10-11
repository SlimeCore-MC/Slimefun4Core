package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * The rules a migration must satisfy before anything is read or written.
 *
 * <p>Nothing here touches Bukkit or the plugin. The number of online players arrives as an
 * {@link IntSupplier} and every other input is a plain value, so the whole rule set can be
 * exercised without a server.
 *
 * <p>Two passes exist because one of the rules costs a connection. {@link #evaluate} covers
 * everything that can be answered from configuration alone and runs first, so a migration
 * that is going to be refused never opens a destination. {@link #checkDestination} covers
 * the remaining rule once the destination has been opened and counted.
 *
 * <p>The in-flight flag lives here as well. A second migration started while the first is
 * still copying would read a half-written destination, so the flag is claimed for the whole
 * run and released in a {@code finally} block.
 *
 * @author SlimefunCore Contributors
 */
public final class MigrationPreconditions {

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private MigrationPreconditions() {}

    /**
     * The outcome of one precondition pass.
     *
     * @param allowed  {@code true} when every rule was satisfied
     * @param problems Every rule that was broken, in evaluation order
     */
    public record Decision(boolean allowed, @Nonnull List<String> problems) {

        /**
         * @return A single line naming every broken rule
         */
        @Nonnull
        public String describe() {
            return problems.isEmpty() ? "All preconditions are met" : String.join("; ", problems);
        }
    }

    /**
     * Everything the configuration level rules read.
     *
     * @param onlinePlayers          How many players are on the server right now
     * @param storageReady           Whether the storage engine finished starting up
     * @param sourceAvailable        Whether the current backend can be read from
     * @param source                 The provider being migrated away from
     * @param destination            The provider being migrated to
     * @param missingDestinationKeys The {@code database.yml} keys the destination still needs,
     *                               by key name only, never by value
     * @param dryRun                 Whether the run will write nothing, which is the only case
     *                               in which online players are tolerated
     */
    public record Conditions(
        @Nonnull IntSupplier onlinePlayers,
        boolean storageReady,
        boolean sourceAvailable,
        @Nonnull StorageProviderType source,
        @Nonnull StorageProviderType destination,
        @Nonnull List<String> missingDestinationKeys,
        boolean dryRun
    ) {}

    /**
     * Applies every rule that can be answered without opening the destination.
     *
     * <p>A dry run is allowed while players are online because it opens no destination and
     * writes nothing. Its counts are then a live reading rather than a settled one, which
     * {@link StorageMigrator} records as a warning on the report.
     *
     * @param conditions The inputs to check
     * @return A {@link Decision} naming every broken rule
     */
    @Nonnull
    public static Decision evaluate(@Nonnull Conditions conditions) {
        List<String> problems = new ArrayList<>();
        int online = Math.max(0, conditions.onlinePlayers().getAsInt());

        if (online > 0 && !conditions.dryRun()) {
            problems.add(online + " player(s) are online, a migration may only run on an empty server");
        }

        if (!conditions.storageReady()) {
            problems.add("The storage engine is not ready yet");
        }

        if (!conditions.sourceAvailable()) {
            problems.add("The current backend " + name(conditions.source()) + " is not available to read from");
        }

        if (conditions.source() == conditions.destination()) {
            problems.add("The destination " + name(conditions.destination())
                + " is already the active provider, there is nothing to migrate");
        }

        if (!conditions.missingDestinationKeys().isEmpty()) {
            problems.add("database.yml is incomplete for " + name(conditions.destination())
                + ", missing key(s): " + String.join(", ", conditions.missingDestinationKeys()));
        }

        return new Decision(problems.isEmpty(), List.copyOf(problems));
    }

    /**
     * Applies the one rule that needs the destination to have been opened and counted.
     *
     * @param destinationEntries How many player rows or player files the destination already holds
     * @param overwrite          Whether the operator explicitly allowed writing into a used destination
     * @return A {@link Decision} naming the broken rule, if any
     */
    @Nonnull
    public static Decision checkDestination(int destinationEntries, boolean overwrite) {
        if (destinationEntries > 0 && !overwrite) {
            return new Decision(false, List.of("The destination already holds " + destinationEntries
                + " player entr" + (destinationEntries == 1 ? "y" : "ies")
                + ", pass overwrite to replace them"));
        }

        return new Decision(true, List.of());
    }

    /**
     * Claims the right to run a migration.
     *
     * @return {@code true} if no other migration is in flight, in which case the caller owns
     *         the flag and must release it with {@link #finish()}
     */
    public static boolean tryBegin() {
        return IN_FLIGHT.compareAndSet(false, true);
    }

    /**
     * Releases the in-flight flag. Safe to call even when the flag was never claimed.
     */
    public static void finish() {
        IN_FLIGHT.set(false);
    }

    /**
     * @return {@code true} while a migration is running
     */
    public static boolean isRunning() {
        return IN_FLIGHT.get();
    }

    @Nonnull
    private static String name(@Nonnull StorageProviderType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
