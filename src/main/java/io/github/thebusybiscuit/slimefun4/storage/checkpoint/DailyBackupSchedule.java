package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A set of fixed UTC clock times at which an automatic backup should run.
 *
 * <p>This is the "run at exact times" alternative to a fixed hour interval. An admin lists times
 * like {@code "00:00"}, {@code "06:00"}, {@code "12:00"}, {@code "18:00"} and a backup fires as each
 * one comes round, four times a day in that example. Times are always interpreted in <b>UTC</b> so a
 * server's local zone or daylight-saving changes never shift the schedule.
 *
 * <p>The scheduler polls {@link #shouldFire(Instant)} once a minute. Each configured minute fires at
 * most once, tracked by {@link #lastFired}, so a poll landing twice inside the same minute cannot
 * trigger two backups. When the configured list is empty the caller falls back to the hour interval.
 *
 * @author SlimefunCore Contributors
 */
public final class DailyBackupSchedule {

    private static final DateTimeFormatter[] FORMATS = {
        DateTimeFormatter.ofPattern("HH:mm"),
        DateTimeFormatter.ofPattern("H:mm")
    };

    private final List<LocalTime> times;
    private Instant lastCheck;

    private DailyBackupSchedule(@Nonnull List<LocalTime> times) {
        this.times = times;
    }

    /**
     * Parses a raw list of {@code HH:mm} UTC time strings into a schedule.
     *
     * <p>Blank and unparseable entries are ignored, seconds are dropped, and duplicates are removed,
     * so a malformed config never throws — it just contributes no times.
     *
     * @param raw The configured time strings, may be {@code null}
     * @return A schedule, never {@code null}, possibly {@link #isEmpty() empty}
     */
    @Nonnull
    public static DailyBackupSchedule parse(@Nullable List<String> raw) {
        List<LocalTime> parsed = new ArrayList<>();

        if (raw != null) {
            for (String entry : raw) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }

                LocalTime time = tryParse(entry.trim());

                if (time != null) {
                    LocalTime minute = time.withSecond(0).withNano(0);

                    if (!parsed.contains(minute)) {
                        parsed.add(minute);
                    }
                }
            }
        }

        return new DailyBackupSchedule(parsed);
    }

    @Nullable
    private static LocalTime tryParse(@Nonnull String value) {
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalTime.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next accepted format.
            }
        }

        return null;
    }

    /**
     * Whether any valid times were configured.
     *
     * @return {@code true} when the schedule holds no usable time
     */
    public boolean isEmpty() {
        return times.isEmpty();
    }

    /**
     * Returns the configured times, sorted, for display.
     *
     * @return An immutable copy of the configured UTC times
     */
    @Nonnull
    public List<LocalTime> times() {
        List<LocalTime> copy = new ArrayList<>(times);
        copy.sort(LocalTime::compareTo);
        return List.copyOf(copy);
    }

    /**
     * Whether a backup should fire for the given instant.
     *
     * <p>Fires when a configured UTC time was crossed since the previous call, i.e. an occurrence
     * falls in the half-open window {@code (lastCheck, now]}. This is deliberately window-based
     * rather than an exact-minute match: the poll task is tick-driven, so under server lag a poll can
     * arrive more than a minute after the last one, and an exact-minute match would silently skip any
     * scheduled time that no poll happened to land on. The first call only establishes the baseline
     * and never fires, so enabling the schedule cannot trigger an immediate backup.
     *
     * <p>If several configured times fall inside one (unusually long) window, this still returns
     * {@code true} only once; the service's own busy guard would reject a second concurrent run
     * anyway, and running two backups back-to-back is never wanted.
     *
     * @param now The current instant
     * @return {@code true} once for each configured time crossed since the previous call
     */
    public synchronized boolean shouldFire(@Nonnull Instant now) {
        if (times.isEmpty()) {
            return false;
        }

        Instant previous = lastCheck;
        lastCheck = now;

        if (previous == null || !now.isAfter(previous)) {
            // First poll (baseline) or a non-advancing/backwards clock: never fire.
            return false;
        }

        LocalDate nowDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        // Windows are normally ~1 minute, spanning one or two dates. Clamp against a large forward
        // clock jump so the day loop below can never iterate an unbounded number of times.
        LocalDate previousDate = previous.atZone(ZoneOffset.UTC).toLocalDate();

        if (previousDate.isBefore(nowDate.minusDays(1))) {
            previousDate = nowDate.minusDays(1);
        }

        for (LocalTime time : times) {
            for (LocalDate date = previousDate; !date.isAfter(nowDate); date = date.plusDays(1)) {
                Instant occurrence = ZonedDateTime.of(date, time, ZoneOffset.UTC).toInstant();

                if (occurrence.isAfter(previous) && !occurrence.isAfter(now)) {
                    return true;
                }
            }
        }

        return false;
    }
}
