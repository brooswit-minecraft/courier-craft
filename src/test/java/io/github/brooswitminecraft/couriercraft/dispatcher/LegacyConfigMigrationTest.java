package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.file.FileConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-493 review (comment 52567, finding 2): proves existing world/admin tuning in the
 * pre-split Dynamic Vehicles config files actually survives into Courier Craft's own files on
 * first boot, instead of being silently reset to Courier Craft's defaults.
 */
class LegacyConfigMigrationTest {
    @Test
    void serverConfigIsCopiedWholesaleWhenLegacyFileExists(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE),
                "noviceMaxRing = 7\nrewardBase = 12.5\n");

        LegacyConfigMigration.migrateServerConfig(configDir);

        Path migrated = configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        assertTrue(Files.exists(migrated), "migrated server config file must exist");
        try (FileConfig config = FileConfig.of(migrated)) {
            config.load();
            assertEquals(7, config.getInt("noviceMaxRing"));
            assertEquals(12.5, ((Number) config.get("rewardBase")).doubleValue());
        }
    }

    @Test
    void serverConfigDoesNothingWhenNoLegacyFileExists(@TempDir Path configDir) {
        LegacyConfigMigration.migrateServerConfig(configDir);

        assertFalse(Files.exists(configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE)),
                "nothing to migrate must not fabricate a new config file");
    }

    @Test
    void serverConfigNeverOverwritesAnAlreadyMigratedFile(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 7\n");
        Path alreadyMigrated = configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Files.writeString(alreadyMigrated, "noviceMaxRing = 99\n");

        LegacyConfigMigration.migrateServerConfig(configDir);

        try (FileConfig config = FileConfig.of(alreadyMigrated)) {
            config.load();
            assertEquals(99, config.getInt("noviceMaxRing"), "a second run must not clobber the admin's own later edits");
        }
    }

    @Test
    void clientConfigMovesOnlyTheLegacyContractHudKey(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_CLIENT_FILE),
                "chaseCamera = true\n\n[delivery]\nshowLegacyContractHud = true\n\n[pathfinder]\nshowLegacyPathfinderHud = false\n");

        LegacyConfigMigration.migrateClientConfig(configDir);

        Path migrated = configDir.resolve(LegacyConfigMigration.NEW_CLIENT_FILE);
        assertTrue(Files.exists(migrated), "migrated client config file must exist");
        try (FileConfig config = FileConfig.of(migrated)) {
            config.load();
            assertEquals(true, (boolean) config.get(LegacyConfigMigration.NEW_CLIENT_KEY));
            // Only the one key belongs to Courier Craft; Dynamic Vehicles' own remaining settings
            // (chaseCamera, pathfinder.*) must not be copied into Courier Craft's own file.
            assertFalse(config.contains("chaseCamera"));
            assertFalse(config.contains("pathfinder"));
        }
    }

    @Test
    void clientConfigDoesNothingWhenLegacyKeyWasNeverSet(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_CLIENT_FILE), "chaseCamera = true\n");

        LegacyConfigMigration.migrateClientConfig(configDir);

        assertFalse(Files.exists(configDir.resolve(LegacyConfigMigration.NEW_CLIENT_FILE)),
                "a legacy file that never set this key has nothing to migrate");
    }

    // Atomic global publication (review comment 52663): the same partial-write defect already
    // fixed for the per-world path (52646) also applied to both global migration paths -- a
    // direct write/copy to the real target could leave a truncated file behind on a disk-full or
    // short write, which the "target already exists" check would then mistake for an
    // already-migrated file forever, silently losing the legacy values with no retry.

    @Test
    void serverConfigRetriesAfterATargetWriteFailure(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 7\n");
        Path target = configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);

        boolean madeReadOnly = configDir.toFile().setWritable(false);
        assertTrue(madeReadOnly, "test setup: must be able to make the config directory read-only on this platform");
        try {
            LegacyConfigMigration.migrateServerConfig(configDir);
            assertFalse(Files.exists(target), "a failed publish must leave no final file behind, partial or otherwise");
        } finally {
            configDir.toFile().setWritable(true);
        }

        // Simulate a later restart with the directory writable again.
        LegacyConfigMigration.migrateServerConfig(configDir);

        assertTrue(Files.exists(target), "a retry once writable again must succeed");
        try (FileConfig config = FileConfig.of(target)) {
            config.load();
            assertEquals(7, config.getInt("noviceMaxRing"), "the retried migration must still carry the original legacy value");
        }
    }

    @Test
    void clientConfigRetriesAfterATargetWriteFailure(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_CLIENT_FILE),
                "[delivery]\nshowLegacyContractHud = true\n");
        Path target = configDir.resolve(LegacyConfigMigration.NEW_CLIENT_FILE);

        boolean madeReadOnly = configDir.toFile().setWritable(false);
        assertTrue(madeReadOnly, "test setup: must be able to make the config directory read-only on this platform");
        try {
            LegacyConfigMigration.migrateClientConfig(configDir);
            assertFalse(Files.exists(target), "a failed publish must leave no final file behind, partial or otherwise");
        } finally {
            configDir.toFile().setWritable(true);
        }

        // Simulate a later restart with the directory writable again.
        LegacyConfigMigration.migrateClientConfig(configDir);

        assertTrue(Files.exists(target), "a retry once writable again must succeed");
        try (FileConfig config = FileConfig.of(target)) {
            config.load();
            assertEquals(true, (boolean) config.get(LegacyConfigMigration.NEW_CLIENT_KEY),
                    "the retried migration must still carry the original legacy value");
        }
    }

    // Per-world serverconfig migration (review comment 52602/52604/52630): the decision core
    // (decidePerWorldLegacyFile) is Minecraft-free and directly testable here; the NeoForge-
    // dependent conversion/validation/application onto the live ConfigValue/MinecraftServer is
    // covered separately by LegacyConfigMigrationGameTests, which a plain JUnit test cannot reach.
    //
    // NeoForge's own per-world override is opt-in (confirmed by reading ConfigTracker#
    // resolveBasePath directly): a per-world couriercraft-server.toml is used only if it ALREADY
    // exists when the world's config loads -- it is never auto-created from the global default.
    // So the precedence rule (review 52630 finding 1) is simply: any existing per-world Courier
    // file -- real admin customization or an earlier migration run, no way to tell apart and no
    // need to -- is already this world's correct, owned config and is never touched; the legacy
    // override applies only when no per-world Courier file exists at all yet.

    @Test
    void perWorldOverrideIsReadWhenLegacyFileExistsInThatWorldOnly(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Files.writeString(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // worldB has no legacy per-world override at all -- the "global fallback" case: nothing
        // world-specific to migrate, so this world keeps using the global file as usual.

        Optional<Path> legacyA = LegacyConfigMigration.decidePerWorldLegacyFile(worldA);
        Optional<Path> legacyB = LegacyConfigMigration.decidePerWorldLegacyFile(worldB);

        assertEquals(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), legacyA.orElseThrow(),
                "world A's own legacy override must be decided");
        assertTrue(legacyB.isEmpty(), "world B has no legacy override -- global fallback, nothing to apply");
    }

    @Test
    void perWorldOverridesDoNotCrossContaminateBetweenTwoDistinctWorlds(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Path legacyA = worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path legacyB = worldB.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Files.writeString(legacyA, "noviceMaxRing = 3\n");
        Files.writeString(legacyB, "noviceMaxRing = 9\n");

        Optional<Path> decidedA = LegacyConfigMigration.decidePerWorldLegacyFile(worldA);
        Optional<Path> decidedB = LegacyConfigMigration.decidePerWorldLegacyFile(worldB);

        assertEquals(legacyA, decidedA.orElseThrow(), "world A must get its own legacy file, not world B's");
        assertEquals(legacyB, decidedB.orElseThrow(), "world B must get its own legacy file, not world A's");
    }

    @Test
    void perWorldMigrationSettlesPermanentlyAfterFirstCall(@TempDir Path worldsRoot) throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");

        Optional<Path> firstCall = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);
        assertTrue(firstCall.isPresent());
        // Simulates the caller settling only after the migration actually durably succeeded
        // (review comment 52641) -- decidePerWorldLegacyFile alone never settles this case.
        LegacyConfigMigration.settlePerWorldMigration(worldDir);

        // Simulate an admin changing their mind through Courier's own per-world config after the
        // first server start, then the server restarting: the legacy file is still sitting there
        // with its old value, but must never be re-applied and clobber the admin's later choice.
        Optional<Path> secondCall = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);
        assertTrue(secondCall.isEmpty(), "a world already settled on its first start must never be re-migrated");
    }

    @Test
    void decidePerWorldLegacyFileDoesNotSettleWhenThereIsSomethingToMigrate(@TempDir Path worldsRoot) throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);

        assertTrue(decided.isPresent());
        // Review comment 52641: committing the marker before the migration actually durably
        // succeeds (read, converted, written) would permanently abandon this world's migration
        // on a transient failure. Only the caller may settle it, and only after success.
        assertFalse(Files.exists(worldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER)),
                "the marker must not be committed merely because there is something to migrate");
    }

    @Test
    void perWorldMigrationSettlesEvenWithNoLegacyOverrideToMigrate(@TempDir Path worldsRoot) {
        Path worldDir = worldsRoot.resolve("world");

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);

        assertTrue(decided.isEmpty());
        assertTrue(Files.exists(worldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER)),
                "global-fallback worlds must still settle, so a later legacy file dropped in by hand is never retroactively applied");
    }

    // Coexistence/precedence (review comment 52630, finding 1): a per-world Courier config that
    // already exists at all -- a real admin customization, or an earlier migration run -- must
    // never be overwritten by a legacy value, even if a legacy file is also sitting in that same
    // per-world folder.

    @Test
    void perWorldLegacyOverrideIsSkippedWhenAPerWorldCourierFileAlreadyExists(@TempDir Path worldsRoot) throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // This world already has its own Courier file -- NeoForge's own resolveBasePath has
        // already bound this world to it; a real, pre-existing customization (an admin's
        // deliberate per-world edit, or an earlier run of this migration).
        Files.writeString(worldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE), "noviceMaxRing = 42\n");

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);

        assertTrue(decided.isEmpty(),
                "a pre-existing per-world Courier config file must never be overwritten by a legacy value");
    }

    @Test
    void perWorldLegacyOverrideAppliesWhenNoPerWorldCourierFileExistsYet(@TempDir Path worldsRoot) throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // No per-world Courier file at all yet -- this world is still bound to the global file;
        // the legacy override is free to apply.

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir);

        assertTrue(decided.isPresent(),
                "a world with no per-world Courier file yet is safe to migrate from its legacy per-world override");
    }
}
