package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

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
    /**
     * NeoForge gives SERVER-type configs (only) an OPT-IN per-world override directory (see
     * {@code ServerLifecycleHooks#getServerConfigPath} and {@code
     * ConfigTracker#resolveBasePath}, read directly from a locally-cached NeoForge artifact on
     * this host to confirm the real behavior rather than assume it): {@code
     * <world>/serverconfig/<file>} is used ONLY if it already exists at the moment this world's
     * config loads; NeoForge never auto-copies the global default into a per-world file -- if no
     * per-world file exists, the config simply binds to the GLOBAL file directly for that world,
     * for the rest of that session. A per-world legacy override is therefore a SEPARATE thing
     * from the global one {@link #migrateServerConfig} already handles, and must be migrated
     * independently per world (review comment 52602/52604, finding raised by John Winstead and
     * confirmed by self-review).
     */
    static final String PER_WORLD_MIGRATION_MARKER = ".couriercraft-legacy-migration-settled";

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
     * Fires on {@link ServerAboutToStartEvent} -- the same event, for the same reason, the
     * pre-existing {@code VehicleSpecOverrides} per-car config load uses in dynamic-vehicles: it
     * fires before any level loads, but (unlike that case) it ALSO fires strictly after NeoForge's
     * own {@code ServerLifecycleHooks.handleServerAboutToStart} has already synchronously decided,
     * via {@code ConfigTracker.resolveBasePath}, which file this world's SERVER config is bound
     * to for the rest of this session: this world's own per-world file if one already existed at
     * that moment, otherwise the shared GLOBAL file -- {@code ConfigValue#save} always persists to
     * THAT file. If this world had no per-world file yet, calling {@code save()} after applying a
     * legacy override would corrupt the shared global default for every other world, so this
     * never calls {@code save()} in that case: it writes the per-world file directly instead (so
     * the NEXT start of this same world binds to it correctly) and separately applies the value
     * to the live cache via {@code ConfigValue#set} alone, so THIS session's behavior is already
     * correct without waiting for a restart.
     */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        migratePerWorldServerConfig(event.getServer());
    }

    static void migratePerWorldServerConfig(MinecraftServer server) {
        migratePerWorldServerConfig(server.getWorldPath(new LevelResource("serverconfig")));
    }

    static void migratePerWorldServerConfig(Path perWorldDir) {
        Optional<Path> legacy = decidePerWorldLegacyFile(perWorldDir);
        if (legacy.isEmpty()) {
            return;
        }
        // Settle ONLY after a durable success (review comment 52641): committing the marker
        // before the file is actually read/converted/written would permanently abandon this
        // world's migration on a transient read/write/validation failure -- the marker's absence
        // is exactly what makes the next server start retry.
        if (applyPerWorldOverridesFrom(legacy.get(), perWorldDir.resolve(NEW_SERVER_FILE))) {
            settlePerWorldMigration(perWorldDir);
        }
    }

    /**
     * The Minecraft-free decision core: whether this specific world's {@code serverconfig}
     * directory has an unmigrated legacy override, isolated from the NeoForge-dependent {@code
     * ConfigValue}/{@code MinecraftServer} machinery so it is directly unit-testable (see {@code
     * LegacyConfigMigrationTest}) against multiple distinct per-world directories at once,
     * proving no cross-world contamination.
     *
     * <p>Does NOT settle (write {@link #PER_WORLD_MIGRATION_MARKER}) when there is something to
     * migrate -- that is the caller's responsibility, and only after a durable success (review
     * comment 52641; see {@link #migratePerWorldServerConfig(Path)}). When there is genuinely
     * nothing to migrate (no legacy file, or a per-world Courier file already exists), there is
     * nothing to retry, so this settles immediately: exactly like {@link #migrateServerConfig}'s
     * own global one-time semantics, so a later admin edit made directly through this world's own
     * {@code couriercraft-server.toml} is never subsequently clobbered by a stale legacy value on
     * a later server restart.
     *
     * <p><b>Coexistence/precedence (review comment 52630, finding 1):</b> since NeoForge's own
     * per-world override is opt-in (it is used only if it ALREADY exists when the world's config
     * loads -- never auto-created from the global default), a per-world {@code
     * couriercraft-server.toml} that already exists at all means NeoForge's own {@code
     * resolveBasePath} already bound this world to it this session: either a real admin
     * customization, or an earlier run of this migration (which always writes that exact file --
     * see {@link #applyPerWorldOverridesFrom}). Either way it is already this world's correct,
     * owned config and must never be touched; the legacy override applies ONLY when no per-world
     * Courier file exists yet at all.
     */
    static Optional<Path> decidePerWorldLegacyFile(Path perWorldDir) {
        Path marker = perWorldDir.resolve(PER_WORLD_MIGRATION_MARKER);
        if (Files.exists(marker)) {
            return Optional.empty();
        }
        Path legacy = perWorldDir.resolve(LEGACY_SERVER_FILE);
        if (Files.exists(legacy) && !Files.exists(perWorldDir.resolve(NEW_SERVER_FILE))) {
            return Optional.of(legacy);
        }
        settlePerWorldMigration(perWorldDir);
        return Optional.empty();
    }

    static void settlePerWorldMigration(Path perWorldDir) {
        Path marker = perWorldDir.resolve(PER_WORLD_MIGRATION_MARKER);
        try {
            Files.createDirectories(perWorldDir);
            Files.createFile(marker);
        } catch (IOException e) {
            CourierCraftMod.LOGGER.warn("Could not write per-world Dispatcher config migration marker at {}", marker, e);
        }
    }

    /**
     * Converts and validates each legacy entry, then writes a per-world {@code
     * couriercraft-server.toml} containing only the successfully-converted keys directly
     * (bypassing {@code ConfigValue#save}, which would target the wrong file at this point -- see
     * {@link #onServerAboutToStart}'s javadoc) -- NeoForge's own {@code ModConfigSpec#correct}
     * fills in any key this doesn't cover from the schema's defaults the next time this exact
     * file is actually loaded as a real per-world override, the same way a hand-written partial
     * override file would behave. ALSO applies each converted value to the live cache via {@code
     * ConfigValue#set} (never {@code #save}) so this session's behavior is correct immediately,
     * without waiting for a restart to pick up the newly-written file.
     *
     * @return true if the legacy file was durably read and (if it held any valid values) written,
     *     meaning the caller may now settle; false if reading the legacy file or writing the new
     *     per-world file failed, meaning the caller must NOT settle -- the next server start must
     *     retry from scratch (review comment 52641)
     */
    private static boolean applyPerWorldOverridesFrom(Path legacyFile, Path perWorldCourierFile) {
        try (FileConfig legacyConfig = FileConfig.of(legacyFile)) {
            legacyConfig.load();
            Config fresh = TomlFormat.newConfig();
            boolean any = false;
            for (Field field : DispatcherOfferConfig.class.getDeclaredFields()) {
                if (!ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    ModConfigSpec.ConfigValue<Object> value = (ModConfigSpec.ConfigValue<Object>) field.get(null);
                    java.util.List<String> path = value.getPath();
                    String key = String.join(".", path);
                    if (!legacyConfig.contains(key)) {
                        continue;
                    }
                    // getRaw performs the same conversion NeoForge itself uses when loading from
                    // disk (correctly turns a raw TOML string into an enum constant for
                    // EnumValue, for instance); ConfigValue#set performs no validation at all, so
                    // ValueSpec#test is checked explicitly before applying (review 52630 finding 2).
                    Object converted = value.getRaw(legacyConfig, path, value::getDefault);
                    if (!value.getSpec().test(converted)) {
                        CourierCraftMod.LOGGER.warn(
                                "Legacy per-world Dispatcher config value for {} ({}) failed validation; keeping the current value",
                                key, converted);
                        continue;
                    }
                    fresh.set(path, converted);
                    value.set(converted);
                    any = true;
                } catch (Exception e) {
                    CourierCraftMod.LOGGER.warn("Could not apply legacy per-world override for Dispatcher config field {}", field.getName(), e);
                }
            }
            if (any) {
                try (OutputStream out = Files.newOutputStream(perWorldCourierFile)) {
                    TomlFormat.instance().createWriter().write(fresh, out);
                }
            }
            return true;
        } catch (Exception e) {
            CourierCraftMod.LOGGER.warn(
                    "Could not read legacy per-world Dispatcher server config from {}; will retry on the next server start",
                    legacyFile, e);
            return false;
        }
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
            // Deliberately NOT FileConfig.of(target).set(...).save(): confirmed (standalone JVM
            // reproduction, since this sourceSet can't run NeoForge) that in night-config 3.8.3, a
            // FileConfig backed by a file that doesn't exist yet silently fails to flush its first
            // save() -- the in-memory mutation is real (entrySet() shows it) but nothing ever
            // reaches disk, even after an explicit load(). Writing through the format's own
            // writer directly has no such issue.
            Config fresh = TomlFormat.newConfig();
            fresh.set(NEW_CLIENT_KEY, legacyValue);
            try (OutputStream out = Files.newOutputStream(target)) {
                TomlFormat.instance().createWriter().write(fresh, out);
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
