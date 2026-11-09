package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    // Per-world serverconfig migration (review comment 52602/52604): the decision core
    // (decidePerWorldOverrides) is Minecraft-free and directly testable here; the NeoForge-
    // dependent application onto the live ConfigValue/MinecraftServer is covered separately by
    // LegacyConfigMigrationGameTests, which a plain JUnit test cannot reach.

    @Test
    void perWorldOverrideIsReadWhenLegacyFileExistsInThatWorldOnly(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Files.writeString(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        // worldB has no legacy per-world override at all -- the "global fallback" case: nothing
        // world-specific to migrate, so NeoForge's own already-correct global-default copy stands.

        Map<String, Object> overridesA = LegacyConfigMigration.decidePerWorldOverrides(worldA);
        Map<String, Object> overridesB = LegacyConfigMigration.decidePerWorldOverrides(worldB);

        assertEquals(3, overridesA.get("noviceMaxRing"), "world A's own legacy override must be read");
        assertTrue(overridesB.isEmpty(), "world B has no legacy override -- global fallback, nothing to apply");
    }

    @Test
    void perWorldOverridesDoNotCrossContaminateBetweenTwoDistinctWorlds(@TempDir Path worldsRoot) throws IOException {
        Path worldA = Files.createDirectory(worldsRoot.resolve("world-a"));
        Path worldB = Files.createDirectory(worldsRoot.resolve("world-b"));
        Files.writeString(worldA.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");
        Files.writeString(worldB.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 9\n");

        Map<String, Object> overridesA = LegacyConfigMigration.decidePerWorldOverrides(worldA);
        Map<String, Object> overridesB = LegacyConfigMigration.decidePerWorldOverrides(worldB);

        assertEquals(3, overridesA.get("noviceMaxRing"), "world A must get its own value, not world B's");
        assertEquals(9, overridesB.get("noviceMaxRing"), "world B must get its own value, not world A's");
    }

    @Test
    void perWorldMigrationSettlesPermanentlyAfterFirstCall(@TempDir Path worldDir) throws IOException {
        Files.writeString(worldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 3\n");

        Map<String, Object> firstCall = LegacyConfigMigration.decidePerWorldOverrides(worldDir);
        assertEquals(3, firstCall.get("noviceMaxRing"));

        // Simulate an admin changing their mind through Courier's own per-world config after the
        // first server start, then the server restarting: the legacy file is still sitting there
        // with its old value, but must never be re-applied and clobber the admin's later choice.
        Map<String, Object> secondCall = LegacyConfigMigration.decidePerWorldOverrides(worldDir);
        assertTrue(secondCall.isEmpty(), "a world already settled on its first start must never be re-migrated");
    }

    @Test
    void perWorldMigrationSettlesEvenWithNoLegacyOverrideToMigrate(@TempDir Path worldDir) {
        Map<String, Object> overrides = LegacyConfigMigration.decidePerWorldOverrides(worldDir);

        assertTrue(overrides.isEmpty());
        assertTrue(Files.exists(worldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER)),
                "global-fallback worlds must still settle, so a later legacy file dropped in by hand is never retroactively applied");
    }
}
