package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rules that stand between an operator and a half migrated server.
 *
 * <p>The online player count arrives as an {@link IntSupplier}, so the rule the request cares
 * about most can be asserted without a running server.
 */
class TestMigrationPreconditions {

    private static final IntSupplier EMPTY_SERVER = () -> 0;
    private static final IntSupplier ONE_PLAYER = () -> 1;

    @AfterEach
    void releaseFlag() {
        MigrationPreconditions.finish();
    }

    @Test
    @DisplayName("A ready server with nobody online and a complete target is allowed")
    void aCleanSetupIsAllowed() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(EMPTY_SERVER, true, true, StorageProviderType.LEGACY, StorageProviderType.SQLITE,
                List.of(), false));

        assertTrue(decision.allowed(), decision.describe());
        assertTrue(decision.problems().isEmpty());
        assertEquals("All preconditions are met", decision.describe());
    }

    @Test
    @DisplayName("A real run is refused while a single player is online")
    void onlinePlayersRefuseARealRun() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(ONE_PLAYER, true, true, StorageProviderType.LEGACY, StorageProviderType.SQLITE,
                List.of(), false));

        assertFalse(decision.allowed());
        assertEquals(1, decision.problems().size());
        assertTrue(decision.describe().contains("empty server"), decision.describe());
    }

    @Test
    @DisplayName("A dry run is allowed while players are online, because it writes nothing")
    void onlinePlayersDoNotBlockADryRun() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(ONE_PLAYER, true, true, StorageProviderType.LEGACY, StorageProviderType.SQLITE,
                List.of(), true));

        assertTrue(decision.allowed(), decision.describe());
    }

    @Test
    @DisplayName("An unknown player count refuses rather than permits")
    void anUnknownPlayerCountRefuses() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(() -> Integer.MAX_VALUE, true, true,
                StorageProviderType.LEGACY, StorageProviderType.SQLITE, List.of(), false));

        assertFalse(decision.allowed(), "A count that could not be read must never pass the rule");
    }

    @Test
    @DisplayName("Migrating a provider onto itself is refused as a no-op")
    void sameProviderIsRefused() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(EMPTY_SERVER, true, true, StorageProviderType.LEGACY, StorageProviderType.LEGACY,
                List.of(), false));

        assertFalse(decision.allowed());
        assertTrue(decision.describe().contains("nothing to migrate"), decision.describe());
    }

    @Test
    @DisplayName("An incomplete target names the missing keys, by key name only")
    void incompleteTargetNamesItsMissingKeys() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(EMPTY_SERVER, true, true, StorageProviderType.LEGACY, StorageProviderType.MYSQL,
                List.of("host", "username", "password"), false));

        assertFalse(decision.allowed());
        assertTrue(decision.describe().contains("host"));
        assertTrue(decision.describe().contains("username"));
        assertTrue(decision.describe().contains("password"),
            "The key name is reported, and only ever the key name");
    }

    @Test
    @DisplayName("An engine that is still starting up, or a source that cannot be read, both refuse")
    void anUnusableEngineRefuses() {
        MigrationPreconditions.Decision notReady = MigrationPreconditions.evaluate(
            conditions(EMPTY_SERVER, false, true, StorageProviderType.LEGACY, StorageProviderType.SQLITE,
                List.of(), false));

        assertFalse(notReady.allowed());
        assertTrue(notReady.describe().contains("not ready"), notReady.describe());

        MigrationPreconditions.Decision noSource = MigrationPreconditions.evaluate(
            conditions(EMPTY_SERVER, true, false, StorageProviderType.LEGACY, StorageProviderType.SQLITE,
                List.of(), false));

        assertFalse(noSource.allowed());
        assertTrue(noSource.describe().contains("not available"), noSource.describe());
    }

    @Test
    @DisplayName("Every broken rule is reported at once, not one per attempt")
    void everyBrokenRuleIsReportedTogether() {
        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            conditions(ONE_PLAYER, false, false, StorageProviderType.MYSQL, StorageProviderType.MYSQL,
                List.of("host"), false));

        assertFalse(decision.allowed());
        assertEquals(5, decision.problems().size(), decision.describe());
    }

    @Test
    @DisplayName("A used destination is refused unless overwrite was passed")
    void aUsedDestinationNeedsOverwrite() {
        assertTrue(MigrationPreconditions.checkDestination(0, false).allowed());
        assertTrue(MigrationPreconditions.checkDestination(12, true).allowed());

        MigrationPreconditions.Decision refused = MigrationPreconditions.checkDestination(12, false);

        assertFalse(refused.allowed());
        assertTrue(refused.describe().contains("12 player entries"), refused.describe());
        assertTrue(refused.describe().contains("overwrite"));

        assertTrue(MigrationPreconditions.checkDestination(1, false).describe().contains("1 player entry"),
            "The message is written for a human, so it agrees in number");
    }

    @Test
    @DisplayName("A second migration cannot start while the first is still copying")
    void onlyOneMigrationRunsAtATime() {
        assertFalse(MigrationPreconditions.isRunning());
        assertTrue(MigrationPreconditions.tryBegin());
        assertTrue(MigrationPreconditions.isRunning());
        assertFalse(MigrationPreconditions.tryBegin(), "A concurrent run would read a half written destination");

        MigrationPreconditions.finish();

        assertFalse(MigrationPreconditions.isRunning());
        assertTrue(MigrationPreconditions.tryBegin(), "The flag must be reusable after a run finishes");
    }

    private MigrationPreconditions.Conditions conditions(
        IntSupplier onlinePlayers,
        boolean storageReady,
        boolean sourceAvailable,
        StorageProviderType source,
        StorageProviderType destination,
        List<String> missingKeys,
        boolean dryRun
    ) {
        return new MigrationPreconditions.Conditions(
            onlinePlayers, storageReady, sourceAvailable, source, destination, missingKeys, dryRun);
    }
}
