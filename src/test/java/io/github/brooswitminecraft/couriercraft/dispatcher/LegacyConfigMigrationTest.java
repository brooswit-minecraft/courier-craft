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

    // Per-world serverconfig migration (review comment 52602/52604/52630): the decision core
    // (decidePerWorldLegacyFile) is Minecraft-free and directly testable here; the NeoForge-
    // dependent conversion/validation/application onto the live ConfigValue/MinecraftServer is
    // covered separately by LegacyConfigMigrationGameTests, which a plain JUnit test cannot reach.

    @Test
    void perWorldOverrideIsReadWhenLegacyFileExistsInThatWorldOnly(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Path globalServerFile = worldsRoot.resolve("global-" + LegacyConfigMigration.NEW_SERVER_FILE);
        Files.writeString(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // worldB has no legacy per-world override at all -- the "global fallback" case: nothing
        // world-specific to migrate, so NeoForge's own already-correct global-default copy stands.

        Optional<Path> legacyA = LegacyConfigMigration.decidePerWorldLegacyFile(worldA, globalServerFile);
        Optional<Path> legacyB = LegacyConfigMigration.decidePerWorldLegacyFile(worldB, globalServerFile);

        assertEquals(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), legacyA.orElseThrow(),
                "world A's own legacy override must be decided");
        assertTrue(legacyB.isEmpty(), "world B has no legacy override -- global fallback, nothing to apply");
    }

    @Test
    void perWorldOverridesDoNotCrossContaminateBetweenTwoDistinctWorlds(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Path globalServerFile = worldsRoot.resolve("global-" + LegacyConfigMigration.NEW_SERVER_FILE);
        Path legacyA = worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path legacyB = worldB.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Files.writeString(legacyA, "noviceMaxRing = 3\n");
        Files.writeString(legacyB, "noviceMaxRing = 9\n");

        Optional<Path> decidedA = LegacyConfigMigration.decidePerWorldLegacyFile(worldA, globalServerFile);
        Optional<Path> decidedB = LegacyConfigMigration.decidePerWorldLegacyFile(worldB, globalServerFile);

        assertEquals(legacyA, decidedA.orElseThrow(), "world A must get its own legacy file, not world B's");
        assertEquals(legacyB, decidedB.orElseThrow(), "world B must get its own legacy file, not world A's");
    }

    @Test
    void perWorldMigrationSettlesPermanentlyAfterFirstCall(@TempDir Path worldsRoot) throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Path globalServerFile = worldsRoot.resolve("global-" + LegacyConfigMigration.NEW_SERVER_FILE);
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");

        Optional<Path> firstCall = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir, globalServerFile);
        assertTrue(firstCall.isPresent());

        // Simulate an admin changing their mind through Courier's own per-world config after the
        // first server start, then the server restarting: the legacy file is still sitting there
        // with its old value, but must never be re-applied and clobber the admin's later choice.
        Optional<Path> secondCall = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir, globalServerFile);
        assertTrue(secondCall.isEmpty(), "a world already settled on its first start must never be re-migrated");
    }

    @Test
    void perWorldMigrationSettlesEvenWithNoLegacyOverrideToMigrate(@TempDir Path worldsRoot) {
        Path worldDir = worldsRoot.resolve("world");
        Path globalServerFile = worldsRoot.resolve("global-" + LegacyConfigMigration.NEW_SERVER_FILE);

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir, globalServerFile);

        assertTrue(decided.isEmpty());
        assertTrue(Files.exists(worldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER)),
                "global-fallback worlds must still settle, so a later legacy file dropped in by hand is never retroactively applied");
    }

    // Coexistence/precedence (review comment 52630, finding 1): a per-world Courier config that
    // already diverges from the current global default means a genuine prior customization for
    // this specific world -- never silently overwritten by a legacy value, even if a legacy file
    // happens to also be sitting in that same per-world folder.

    @Test
    void perWorldLegacyOverrideIsSkippedWhenAGenuinePerWorldCourierCustomizationAlreadyExists(@TempDir Path worldsRoot)
            throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Path globalServerFile = worldsRoot.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Files.writeString(globalServerFile, "noviceMaxRing = 1\n");
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // This world's own Courier file already diverges from the global default -- a real,
        // pre-existing customization (an admin's deliberate edit, or an earlier marker-less run).
        Files.writeString(worldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE), "noviceMaxRing = 42\n");

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir, globalServerFile);

        assertTrue(decided.isEmpty(),
                "a genuine pre-existing per-world Courier customization must never be overwritten by a legacy value");
    }

    @Test
    void perWorldLegacyOverrideAppliesWhenThePerWorldCourierFileStillMatchesTheGlobalDefault(@TempDir Path worldsRoot)
            throws IOException {
        Path worldDir = Files.createDirectory(worldsRoot.resolve("world"));
        Path globalServerFile = worldsRoot.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Files.writeString(globalServerFile, "noviceMaxRing = 1\n");
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // This world's own Courier file is byte-identical to the current global default -- exactly
        // what NeoForge's own first-start auto-copy produces when nothing has customized it yet.
        Files.writeString(worldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE), "noviceMaxRing = 1\n");

        Optional<Path> decided = LegacyConfigMigration.decidePerWorldLegacyFile(worldDir, globalServerFile);

        assertTrue(decided.isPresent(),
                "a per-world Courier file indistinguishable from a fresh global-default copy is safe to override with the legacy value");
    }
}
