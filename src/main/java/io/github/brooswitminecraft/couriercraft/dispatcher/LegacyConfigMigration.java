package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.electronwill.nightconfig.core.file.FileConfig;

import io.github.brooswitminecraft.couriercraft.CourierCraftMod;

/**
 * One-time, first-boot migration of the Dispatcher's pre-split (MINECRAFT-493) Dynamic Vehicles
 * config into this mod's own files, so existing world/admin tuning is preserved instead of
 * silently reset to Courier Craft's defaults (review comment 52567, finding 2). A no-op whenever
 * this mod's own file already exists (already migrated, or a fresh install with nothing to
 * migrate) or the legacy file is absent (Dynamic Vehicles never installed on this instance, or
 * this value was never customized -- nothing to lose).
 *
 * <p>Must run before {@link CourierCraftMod}'s own {@code registerConfig} calls, which otherwise
 * create {@link #NEW_SERVER_FILE}/{@link #NEW_CLIENT_FILE} from the spec's defaults first.
 */
public final class LegacyConfigMigration {
    static final String LEGACY_SERVER_FILE = "dynamicvehicles-dispatcher-server.toml";
    static final String LEGACY_CLIENT_FILE = "dynamicvehicles-client.toml";
    static final String NEW_SERVER_FILE = "couriercraft-server.toml";
    static final String NEW_CLIENT_FILE = "couriercraft-client.toml";
    static final String LEGACY_CLIENT_KEY = "delivery.showLegacyContractHud";
    static final String NEW_CLIENT_KEY = "showLegacyContractHud";

    private LegacyConfigMigration() {}

    /**
     * The server spec's keys are byte-for-byte identical pre- and post-split (same names, same
     * comments, same bounds -- unchanged from the pre-split {@code DispatcherOfferConfig}), so
     * copying the legacy file's bytes wholesale is a complete, lossless migration.
     */
    public static void migrateServerConfig(Path configDir) {
        copyFileIfNeeded(configDir.resolve(LEGACY_SERVER_FILE), configDir.resolve(NEW_SERVER_FILE));
    }

    /**
     * Unlike the server config, the client schema moved {@code delivery.showLegacyContractHud} to
     * a root {@code showLegacyContractHud} key (Courier Craft has no other client setting to nest
     * it against) -- the only key migrated; every other setting in the legacy client file still
     * belongs to Dynamic Vehicles' own (untouched) remaining settings and must not be copied.
     */
    public static void migrateClientConfig(Path configDir) {
        Path legacy = configDir.resolve(LEGACY_CLIENT_FILE);
        Path target = configDir.resolve(NEW_CLIENT_FILE);
        if (Files.exists(target) || !Files.exists(legacy)) {
            return;
        }
        try (FileConfig legacyConfig = FileConfig.of(legacy)) {
            legacyConfig.load();
            if (!legacyConfig.contains(LEGACY_CLIENT_KEY)) {
                return;
            }
            boolean legacyValue = legacyConfig.get(LEGACY_CLIENT_KEY);
            Files.createDirectories(target.getParent());
            try (FileConfig newConfig = FileConfig.of(target)) {
                newConfig.set(NEW_CLIENT_KEY, legacyValue);
                newConfig.save();
            }
        } catch (Exception e) {
            CourierCraftMod.LOGGER.warn(
                    "Could not migrate legacy Dispatcher client config from {}; falling back to defaults", legacy, e);
        }
    }

    private static void copyFileIfNeeded(Path legacy, Path target) {
        if (Files.exists(target) || !Files.exists(legacy)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(legacy, target);
        } catch (IOException e) {
            CourierCraftMod.LOGGER.warn(
                    "Could not migrate legacy Dispatcher server config from {}; falling back to defaults", legacy, e);
        }
    }
}
