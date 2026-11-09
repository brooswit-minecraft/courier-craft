package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<String, Object> overrides = decidePerWorldOverrides(perWorldDir);
        if (!overrides.isEmpty()) {
            applyPerWorldOverrides(overrides);
        }
    }

    /**
     * The Minecraft-free decision core: whether this specific world's {@code serverconfig}
     * directory has an unmigrated legacy override, and if so, what values it holds -- isolated
     * from the NeoForge-dependent {@code ConfigValue}/{@code MinecraftServer} machinery above so
     * it is directly unit-testable (see {@code LegacyConfigMigrationTest}) against multiple
     * distinct per-world directories at once, proving no cross-world contamination.
     *
     * <p>Settles permanently on the FIRST call for a given world (writes {@link
     * #PER_WORLD_MIGRATION_MARKER}), whether or not a legacy override existed, exactly like {@link
     * #migrateServerConfig}'s own global one-time semantics -- so a later admin edit made directly
     * through this world's own {@code couriercraft-server.toml} (via the serverconfig override, or
     * in-game config screens that write there) is never subsequently clobbered by a stale legacy
     * value on a later server restart.
     */
    static Map<String, Object> decidePerWorldOverrides(Path perWorldDir) {
        Path marker = perWorldDir.resolve(PER_WORLD_MIGRATION_MARKER);
        if (Files.exists(marker)) {
            return Map.of();
        }
        Map<String, Object> overrides = Map.of();
        Path legacy = perWorldDir.resolve(LEGACY_SERVER_FILE);
        if (Files.exists(legacy)) {
            try (FileConfig legacyConfig = FileConfig.of(legacy)) {
                legacyConfig.load();
                Map<String, Object> read = new LinkedHashMap<>();
                for (Config.Entry entry : legacyConfig.entrySet()) {
                    read.put(entry.getKey(), entry.getValue());
                }
                overrides = read;
            } catch (Exception e) {
                CourierCraftMod.LOGGER.warn(
                        "Could not read legacy per-world Dispatcher server config from {}; this world keeps its current/default settings",
                        legacy, e);
                overrides = Map.of();
            }
        }
        try {
            Files.createDirectories(perWorldDir);
            Files.createFile(marker);
        } catch (IOException e) {
            CourierCraftMod.LOGGER.warn("Could not write per-world Dispatcher config migration marker at {}", marker, e);
        }
        return overrides;
    }

    /** Applies decided per-world values onto the live, already-loaded spec via reflection (the
     * schema is identical pre-/post-split, so legacy keys match {@code ConfigValue} paths
     * one-to-one) and persists with a single save. Each field is independent: one incompatible
     * value (e.g. an enum field receiving a raw TOML string) is logged and skipped rather than
     * aborting the rest of the migration. */
    private static void applyPerWorldOverrides(Map<String, Object> overrides) {
        boolean changed = false;
        for (Field field : DispatcherOfferConfig.class.getDeclaredFields()) {
            if (!ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                ModConfigSpec.ConfigValue<?> value = (ModConfigSpec.ConfigValue<?>) field.get(null);
                String key = String.join(".", value.getPath());
                if (overrides.containsKey(key)) {
                    @SuppressWarnings("unchecked")
                    ModConfigSpec.ConfigValue<Object> typed = (ModConfigSpec.ConfigValue<Object>) value;
                    typed.set(overrides.get(key));
                    changed = true;
                }
            } catch (Exception e) {
                CourierCraftMod.LOGGER.warn("Could not apply legacy per-world override for Dispatcher config field {}", field.getName(), e);
            }
        }
        if (changed) {
            DispatcherOfferConfig.SPEC.save();
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
