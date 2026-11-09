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
     * NeoForge gives SERVER-type configs (only) a per-world override directory (see {@code
     * ServerLifecycleHooks#getServerConfigPath}): {@code <world>/serverconfig/<file>} takes
     * precedence over the global {@code config/<file>} for that world specifically, for as long
     * as that world exists. A per-world legacy override is therefore a SEPARATE thing from the
     * global one {@link #migrateServerConfig} already handles, and must be migrated independently
     * per world (review comment 52602/52604, finding raised by John Winstead and confirmed by
     * self-review).
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
     * own {@code ServerLifecycleHooks.handleServerAboutToStart} has already synchronously called
     * {@code ConfigTracker.loadConfigs(SERVER, ...)} for this world -- by the time this listener
     * runs, this mod's own per-world {@code couriercraft-server.toml} already exists (NeoForge
     * creates it from the global default the first time a world is started) and its values are
     * already loaded into the live {@link DispatcherOfferConfig#SPEC}. Overwriting the per-world
     * FILE from here would therefore NOT take effect for the live config this session -- the
     * override must instead go through {@link ModConfigSpec.ConfigValue#set} on the already-live
     * spec (the standard, officially-supported way to change a loaded config value at runtime),
     * followed by one {@code SPEC.save()}.
     */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        migratePerWorldServerConfig(event.getServer());
    }

    static void migratePerWorldServerConfig(MinecraftServer server) {
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path globalServerFile = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(NEW_SERVER_FILE);
        decidePerWorldLegacyFile(perWorldDir, globalServerFile).ifPresent(LegacyConfigMigration::applyPerWorldOverridesFrom);
    }

    /**
     * The Minecraft-free decision core: whether this specific world's {@code serverconfig}
     * directory has an unmigrated legacy override that is actually SAFE to apply, isolated from
     * the NeoForge-dependent {@code ConfigValue}/{@code MinecraftServer} machinery so it is
     * directly unit-testable (see {@code LegacyConfigMigrationTest}) against multiple distinct
     * per-world directories at once, proving no cross-world contamination.
     *
     * <p>Settles permanently on the FIRST call for a given world (writes {@link
     * #PER_WORLD_MIGRATION_MARKER}), exactly like {@link #migrateServerConfig}'s own global
     * one-time semantics -- so a later admin edit made directly through this world's own {@code
     * couriercraft-server.toml} is never subsequently clobbered by a stale legacy value on a
     * later server restart.
     *
     * <p><b>Coexistence/precedence (review comment 52630, finding 1):</b> by the time this runs,
     * NeoForge has already created this world's own {@code couriercraft-server.toml} from the
     * global default IF none existed. A per-world Courier file that still matches the current
     * global default byte-for-byte is therefore safe to assume was just freshly synthesized this
     * way, with no genuine customization yet, and the legacy override may apply. A per-world
     * Courier file that already DIFFERS from the global default means a real customization
     * already exists for this specific world (an admin's deliberate edit, or a previous run of an
     * earlier, marker-less version of this migration) -- that must never be overwritten by a
     * stale legacy value, so the legacy override is skipped even if a legacy file is present.
     */
    static Optional<Path> decidePerWorldLegacyFile(Path perWorldDir, Path globalServerFile) {
        Path marker = perWorldDir.resolve(PER_WORLD_MIGRATION_MARKER);
        if (Files.exists(marker)) {
            return Optional.empty();
        }
        Path legacy = perWorldDir.resolve(LEGACY_SERVER_FILE);
        Optional<Path> result = Optional.empty();
        if (Files.exists(legacy) && perWorldCourierSafeToOverride(perWorldDir.resolve(NEW_SERVER_FILE), globalServerFile)) {
            result = Optional.of(legacy);
        }
        try {
            Files.createDirectories(perWorldDir);
            Files.createFile(marker);
        } catch (IOException e) {
            CourierCraftMod.LOGGER.warn("Could not write per-world Dispatcher config migration marker at {}", marker, e);
        }
        return result;
    }

    private static boolean perWorldCourierSafeToOverride(Path perWorldCourierFile, Path globalServerFile) {
        if (!Files.exists(perWorldCourierFile)) {
            return true;
        }
        if (!Files.exists(globalServerFile)) {
            // Can't prove the per-world file is an unmodified copy of anything -- conservatively
            // treat it as a genuine customization and preserve it.
            return false;
        }
        try {
            return Files.mismatch(perWorldCourierFile, globalServerFile) == -1L;
        } catch (IOException e) {
            CourierCraftMod.LOGGER.warn(
                    "Could not compare per-world Dispatcher config {} against the global default; preserving the per-world file",
                    perWorldCourierFile, e);
            return false;
        }
    }

    /**
     * Applies a legacy per-world file's values onto the live, already-loaded spec. Each matching
     * {@code ConfigValue} converts the raw legacy entry through its OWN {@code getRaw} (the same
     * conversion NeoForge itself uses when loading from disk -- correctly turns a raw TOML string
     * into an enum constant for {@code EnumValue}, for instance, rather than caching the raw value
     * untyped) and is applied only if {@code ValueSpec#test} accepts it (range/type validation --
     * {@code ConfigValue#set} itself performs none). An out-of-range or otherwise invalid legacy
     * value is logged and skipped, leaving that one field at its current/default value, rather
     * than corrupting the live cache or aborting the rest of the migration (review comment 52630,
     * finding 2).
     */
    private static void applyPerWorldOverridesFrom(Path legacyFile) {
        try (FileConfig legacyConfig = FileConfig.of(legacyFile)) {
            legacyConfig.load();
            boolean changed = false;
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
                    Object converted = value.getRaw(legacyConfig, path, value::getDefault);
                    if (!value.getSpec().test(converted)) {
                        CourierCraftMod.LOGGER.warn(
                                "Legacy per-world Dispatcher config value for {} ({}) failed validation; keeping the current value",
                                key, converted);
                        continue;
                    }
                    value.set(converted);
                    changed = true;
                } catch (Exception e) {
                    CourierCraftMod.LOGGER.warn("Could not apply legacy per-world override for Dispatcher config field {}", field.getName(), e);
                }
            }
            if (changed) {
                DispatcherOfferConfig.SPEC.save();
            }
        } catch (Exception e) {
            CourierCraftMod.LOGGER.warn(
                    "Could not read legacy per-world Dispatcher server config from {}; this world keeps its current/default settings",
                    legacyFile, e);
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
