package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    // A dedicated logger, NOT CourierCraftMod.LOGGER: that field's own type is fine, but merely
    // loading the CourierCraftMod class to read it also links every other type CourierCraftMod's
    // own methods reference (ModContainer, IEventBus, registry events, ...), none of which exist
    // on the plain JUnit test classpath -- this is the same "Minecraft-light class must never
    // reference a Minecraft-heavy class" rule dynamic-vehicles' own VehicleSpecOverrides follows
    // for the identical reason. Confirmed the hard way: the forced-write-failure JUnit tests
    // below are the first ones in this class's history to actually execute a warn() call, and
    // doing so via CourierCraftMod.LOGGER threw NoClassDefFoundError on that classpath. LogUtils
    // itself is nothing more than LoggerFactory.getLogger with a fixed binding, so this is
    // identical logging, just without the link.
    private static final Logger LOGGER = LoggerFactory.getLogger("couriercraft/LegacyConfigMigration");
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
            LOGGER.warn("Could not write per-world Dispatcher config migration marker at {}", marker, e);
        }
    }

    /**
     * Converts and validates each legacy entry, then PUBLISHES a per-world {@code
     * couriercraft-server.toml} containing only the successfully-converted keys -- via a sibling
     * temp file plus an atomic move (review comment 52646: a direct write to the real target can
     * leave an empty or truncated file behind on a disk-full/short-write/I/O failure; since the
     * marker is absent but the target file now exists, {@link #decidePerWorldLegacyFile} would
     * mistake that partial file for an already-settled per-world config on the NEXT start and
     * never retry -- the target file must therefore always be either fully correct or entirely
     * absent, never partial). NeoForge's own {@code ModConfigSpec#correct} fills in any key this
     * doesn't cover from the schema's defaults the next time this exact file is actually loaded
     * as a real per-world override, the same way a hand-written partial override file would
     * behave. Live {@code ConfigValue}s are mutated (never {@code #save}d, which would target the
     * wrong file at this point -- see {@link #onServerAboutToStart}'s javadoc) only AFTER
     * publication succeeds, so a failed publish never leaves the live cache out of sync with
     * what's (or isn't) on disk either.
     *
     * @return true if the legacy file was durably read and (if it held any valid values) published,
     *     meaning the caller may now settle; false if reading the legacy file or publishing the
     *     new per-world file failed, meaning the caller must NOT settle -- the next server start
     *     must retry from scratch (review comments 52641, 52646)
     */
    private static boolean applyPerWorldOverridesFrom(Path legacyFile, Path perWorldCourierFile) {
        try (FileConfig legacyConfig = FileConfig.of(legacyFile)) {
            legacyConfig.load();
            Config fresh = TomlFormat.newConfig();
            java.util.List<Runnable> pendingLiveApplies = new java.util.ArrayList<>();
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
                        LOGGER.warn(
                                "Legacy per-world Dispatcher config value for {} ({}) failed validation; keeping the current value",
                                key, converted);
                        continue;
                    }
                    fresh.set(path, converted);
                    pendingLiveApplies.add(() -> value.set(converted));
                    any = true;
                } catch (Exception e) {
                    LOGGER.warn("Could not apply legacy per-world override for Dispatcher config field {}", field.getName(), e);
                }
            }
            if (any) {
                publishAtomically(fresh, perWorldCourierFile);
            }
            pendingLiveApplies.forEach(Runnable::run);
            return true;
        } catch (Exception e) {
            LOGGER.warn(
                    "Could not read or publish legacy per-world Dispatcher server config from {}; will retry on the next server start",
                    legacyFile, e);
            return false;
        }
    }

    /**
     * Writes to a sibling temp file, durably closes it, then atomically moves it into place --
     * same directory, so the move is a rename, never a cross-filesystem copy -- so {@code target}
     * always ends up either fully correct or entirely untouched, never partially written (review
     * comment 52646).
     */
    private static void publishAtomically(Config content, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                TomlFormat.instance().createWriter().write(content, out);
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
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
            // Published atomically (review comment 52663, the same class of defect already fixed
            // for the per-world path at 52646): a direct write to the real target can leave an
            // empty/truncated file on a disk-full or short write, which the "target already
            // exists" check above would then mistake for an already-migrated file forever,
            // silently losing the legacy value with no retry.
            publishAtomically(fresh, target);
        } catch (Exception e) {
            LOGGER.warn(
                    "Could not migrate legacy Dispatcher client config from {}; falling back to defaults", legacy, e);
        }
    }

    /**
     * Copies the legacy server config file wholesale via a sibling temp file plus an atomic move
     * (review comment 52663), for the same reason {@link #publishAtomically} exists: a direct
     * {@code Files.copy} straight to {@code target} can leave a truncated file behind on a
     * disk-full or short write, which the "target already exists" check above would then mistake
     * for an already-migrated file forever, silently losing the legacy values with no retry.
     */
    private static void copyFileIfNeeded(Path legacy, Path target) {
        if (Files.exists(target) || !Files.exists(legacy)) {
            return;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            Files.copy(legacy, tmp);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn(
                    "Could not migrate legacy Dispatcher server config from {}; falling back to defaults", legacy, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }
}
