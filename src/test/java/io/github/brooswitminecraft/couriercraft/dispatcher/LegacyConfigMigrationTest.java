package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
