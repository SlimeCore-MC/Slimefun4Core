package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The immutable result of one migration attempt, successful or not.
 *
 * <p>One instance is published on {@code StorageEngineService} once the run finishes, and the
 * command output, the GUI and the log all read that same instance. Nothing here is mutable
 * after {@link Builder#build()}, so the async migration task and the main thread cannot race.
 *
 * <p>Per player digests are kept only for the players whose destination copy did not match
 * their source, because that is the actionable subset. A run over several thousand players
 * therefore holds a handful of strings rather than one per player.
 *
 * @author SlimefunCore Contributors
 */
public final class MigrationReport {

    /**
     * How a migration attempt ended.
     */
    public enum Outcome {

        /**
         * Every record was copied and verified.
         */
        SUCCESS("Success"),

        /**
         * Nothing was written, the run only reported what it found.
         */
        DRY_RUN("Dry run"),

        /**
         * A rule was broken, so nothing was written.
         */
        REFUSED("Refused"),

        /**
         * Something failed. The destination was rolled back or never written to.
         */
        FAILED("Failed");

        private final String label;

        Outcome(@Nonnull String label) {
            this.label = label;
        }

        /**
         * @return A human readable label
         */
        @Nonnull
        public String getLabel() {
            return label;
        }
    }

    private final Outcome outcome;
    private final StorageProviderType source;
    private final StorageProviderType destination;
    private final boolean overwrite;
    private final int players;
    private final int copiedEntries;
    private final Map<String, Integer> sourceCounts;
    private final Map<String, Integer> destinationCounts;
    private final Map<UUID, String> mismatches;
    private final List<String> problems;
    private final List<String> warnings;
    private final String detail;
    private final String playerBackupId;
    private final String blockBackupId;
    private final Duration duration;
    private final Instant timestamp;

    private MigrationReport(@Nonnull Builder builder) {
        this.outcome = builder.outcome;
        this.source = builder.source;
        this.destination = builder.destination;
        this.overwrite = builder.overwrite;
        this.players = builder.players;
        this.copiedEntries = builder.copiedEntries;
        this.sourceCounts = Map.copyOf(builder.sourceCounts);
        this.destinationCounts = Map.copyOf(builder.destinationCounts);
        this.mismatches = Map.copyOf(builder.mismatches);
        this.problems = List.copyOf(builder.problems);
        this.warnings = List.copyOf(builder.warnings);
        this.detail = builder.detail;
        this.playerBackupId = builder.playerBackupId;
        this.blockBackupId = builder.blockBackupId;
        this.duration = builder.duration;
        this.timestamp = builder.timestamp;
    }

    /**
     * @return How the attempt ended
     */
    @Nonnull
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * @return {@code true} only when every record was copied and verified
     */
    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    /**
     * @return The provider the data was read from
     */
    @Nonnull
    public StorageProviderType getSource() {
        return source;
    }

    /**
     * @return The provider the data was written to, or would have been written to
     */
    @Nonnull
    public StorageProviderType getDestination() {
        return destination;
    }

    /**
     * @return {@code true} when the run was allowed to write into a non-empty destination
     */
    public boolean isOverwrite() {
        return overwrite;
    }

    /**
     * @return How many players were found in the source
     */
    public int getPlayers() {
        return players;
    }

    /**
     * @return How many rows or entries were written to the destination
     */
    public int getCopiedEntries() {
        return copiedEntries;
    }

    /**
     * @return What the source held, by section
     */
    @Nonnull
    public Map<String, Integer> getSourceCounts() {
        return sourceCounts;
    }

    /**
     * @return What the destination holds after the copy, by section or table
     */
    @Nonnull
    public Map<String, Integer> getDestinationCounts() {
        return destinationCounts;
    }

    /**
     * @return Every player whose destination copy did not match their source
     */
    @Nonnull
    public Map<UUID, String> getMismatches() {
        return mismatches;
    }

    /**
     * @return Every broken rule and every element that could not be converted
     */
    @Nonnull
    public List<String> getProblems() {
        return problems;
    }

    /**
     * @return Everything worth knowing that did not stop the run
     */
    @Nonnull
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return A single line describing the outcome
     */
    @Nonnull
    public String getDetail() {
        return detail;
    }

    /**
     * @return The id of the pre-migration player data backup, or {@code null} if none was taken
     */
    @Nullable
    public String getPlayerBackupId() {
        return playerBackupId;
    }

    /**
     * @return The id of the pre-migration block archive, or {@code null} if none was taken
     */
    @Nullable
    public String getBlockBackupId() {
        return blockBackupId;
    }

    /**
     * @return How long the attempt took
     */
    @Nonnull
    public Duration getDuration() {
        return duration;
    }

    /**
     * @return When the attempt finished
     */
    @Nonnull
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return A one line summary suitable for a command reply or a log line
     */
    @Nonnull
    public String describe() {
        return outcome.getLabel() + ": " + name(source) + " to " + name(destination)
            + ", " + players + " player(s), " + copiedEntries + " entr" + (copiedEntries == 1 ? "y" : "ies")
            + " in " + duration.toMillis() + "ms - " + detail;
    }

    @Nonnull
    private static String name(@Nonnull StorageProviderType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Collects a report while a migration runs.
     *
     * <p>Package private on purpose: only {@link StorageMigrator} may produce a report, so a
     * report handed to a command or to the GUI always describes a run that actually happened.
     */
    static final class Builder {

        private final StorageProviderType source;
        private final StorageProviderType destination;
        private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        private final Map<String, Integer> destinationCounts = new LinkedHashMap<>();
        private final Map<UUID, String> mismatches = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final long startedAt = System.nanoTime();

        private Outcome outcome = Outcome.FAILED;
        private boolean overwrite;
        private int players;
        private int copiedEntries;
        private String detail = "No detail was recorded";
        private String playerBackupId;
        private String blockBackupId;
        private Duration duration = Duration.ZERO;
        private Instant timestamp = Instant.now();

        Builder(@Nonnull StorageProviderType source, @Nonnull StorageProviderType destination) {
            this.source = source;
            this.destination = destination;
        }

        @Nonnull
        Builder overwrite(boolean value) {
            this.overwrite = value;
            return this;
        }

        @Nonnull
        Builder players(int value) {
            this.players = value;
            return this;
        }

        @Nonnull
        Builder copiedEntries(int value) {
            this.copiedEntries = value;
            return this;
        }

        @Nonnull
        Builder sourceCount(@Nonnull String section, int value) {
            this.sourceCounts.put(section, value);
            return this;
        }

        @Nonnull
        Builder destinationCounts(@Nonnull Map<String, Integer> counts) {
            this.destinationCounts.putAll(counts);
            return this;
        }

        @Nonnull
        Builder destinationCount(@Nonnull String section, int value) {
            this.destinationCounts.put(section, value);
            return this;
        }

        @Nonnull
        Builder mismatch(@Nonnull UUID uuid, @Nonnull String description) {
            this.mismatches.put(uuid, description);
            return this;
        }

        @Nonnull
        Builder problem(@Nonnull String problem) {
            this.problems.add(problem);
            return this;
        }

        @Nonnull
        Builder problems(@Nonnull List<String> values) {
            this.problems.addAll(values);
            return this;
        }

        @Nonnull
        Builder warning(@Nonnull String warning) {
            this.warnings.add(warning);
            return this;
        }

        @Nonnull
        Builder playerBackupId(@Nullable String backupId) {
            this.playerBackupId = backupId;
            return this;
        }

        @Nonnull
        Builder blockBackupId(@Nullable String backupId) {
            this.blockBackupId = backupId;
            return this;
        }

        boolean hasProblems() {
            return !problems.isEmpty();
        }

        int problemCount() {
            return problems.size();
        }

        int mismatchCount() {
            return mismatches.size();
        }

        @Nonnull
        MigrationReport build(@Nonnull Outcome result, @Nonnull String summary) {
            this.outcome = result;
            this.detail = summary;
            this.duration = Duration.ofNanos(System.nanoTime() - startedAt);
            this.timestamp = Instant.now();

            return new MigrationReport(this);
        }
    }
}
