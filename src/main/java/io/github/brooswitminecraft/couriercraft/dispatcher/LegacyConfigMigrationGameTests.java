package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.electronwill.nightconfig.core.file.FileConfig;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-493 review (comment 52602/52604/52630): {@code LegacyConfigMigrationTest} covers the
 * Minecraft-free decision core ({@code LegacyConfigMigration#decidePerWorldLegacyFile}) directly,
 * including two-distinct-worlds, global-fallback, and coexistence/precedence cases. What it
 * CANNOT reach is whether applying a decided override through {@code ModConfigSpec.ConfigValue
 * #getRaw}/{@code #set} and the direct per-world file write actually takes effect -- with correct
 * type conversion and validation -- on a real, already-loaded NeoForge config; that needs a
 * genuine running server, which only a GameTest provides.
 *
 * <p>This GameTest server's own world has NO per-world {@code couriercraft-server.toml} when it
 * first starts (confirmed: NeoForge's own per-world override is opt-in, never auto-created --
 * see {@code LegacyConfigMigration}'s own javadoc), so its SERVER config is bound to the GLOBAL
 * file for the whole run. That is deliberately exactly the "no per-world Courier file yet" case
 * these tests exercise.
 */
@GameTestHolder(io.github.brooswitminecraft.couriercraft.CourierCraftMod.MODID)
@PrefixGameTestTemplate(false)
public final class LegacyConfigMigrationGameTests {
    private LegacyConfigMigrationGameTests() {}

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldLegacyOverrideAppliesTypedAndValidatedValues")
    public static void perWorldLegacyOverrideAppliesTypedAndValidatedValues(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path perWorldCourier = perWorldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int originalMaxRing = DispatcherOfferConfig.NOVICE_MAX_RING.get();
        DangerRollMode originalRollMode = DispatcherOfferConfig.DANGER_ROLL_MODE.get();
        // Distinct from both the schema default and whatever this GameTestServer's own world
        // might already carry, so a false pass can't hide behind a coincidental match.
        int migratedMaxRing = originalMaxRing == 17 ? 23 : 17;
        DangerRollMode migratedRollMode = originalRollMode == DangerRollMode.PER_OFFER
                ? DangerRollMode.PER_REGION : DangerRollMode.PER_OFFER;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(perWorldCourier);
            // A realistic full legacy file: a normal in-range int, a raw TOML string for the enum
            // field (proving typed conversion, not a raw-string cache corruption -- review 52630
            // finding 2), and one deliberately out-of-range value (journeymanMaxRing's bound is
            // [1,1000]) proving invalid legacy data is rejected rather than corrupting the cache.
            Files.writeString(legacy,
                    "noviceMaxRing = " + migratedMaxRing + "\n"
                            + "dangerRollMode = \"" + migratedRollMode.name() + "\"\n"
                            + "journeymanMaxRing = -7\n");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == migratedMaxRing,
                    "an in-range legacy int override must take effect on the live, already-loaded config");
            helper.assertTrue(DispatcherOfferConfig.DANGER_ROLL_MODE.get() == migratedRollMode,
                    "a legacy enum override (raw TOML string) must convert to the real enum constant, not a raw string");
            helper.assertTrue(DispatcherOfferConfig.JOURNEYMAN_MAX_RING.get() >= 1,
                    "an out-of-range legacy value must be rejected by validation, never applied");
            helper.assertTrue(Files.exists(marker), "this world must be marked settled after its first migration");
            helper.assertTrue(Files.exists(perWorldCourier),
                    "a per-world Courier file must now exist so the NEXT start of this world binds to it directly");

            // Simulate re-entering this same world a second time: the legacy file is still sitting
            // there, but this world is now settled (marker exists) -- re-running migration must
            // be a no-op, never touching the per-world file or live value it already applied.
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            LegacyConfigMigration.migratePerWorldServerConfig(server);
            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == originalMaxRing,
                    "a settled world must never be re-migrated and clobber a later change");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            DispatcherOfferConfig.DANGER_ROLL_MODE.set(originalRollMode);
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(perWorldCourier);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldLegacyOverrideIsSkippedWhenPerWorldCourierAlreadyExists")
    public static void perWorldLegacyOverrideIsSkippedWhenPerWorldCourierAlreadyExists(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path perWorldCourier = perWorldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int legacyValue = 17;
        int alreadyCustomizedValue = 5;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.writeString(legacy, "noviceMaxRing = " + legacyValue + "\n");
            // Simulate a genuine pre-existing per-world Courier customization: the file already
            // exists with its own value, written directly (not through this migration) --
            // exactly what an admin's own per-world edit, or an earlier migration run, leaves
            // behind. NeoForge's own resolveBasePath only cares whether the file exists, not who
            // wrote it or when.
            Files.writeString(perWorldCourier, "noviceMaxRing = " + alreadyCustomizedValue + "\n");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            try (FileConfig config = FileConfig.of(perWorldCourier)) {
                config.load();
                helper.assertTrue(config.getInt("noviceMaxRing") == alreadyCustomizedValue,
                        "a pre-existing per-world Courier config file must never be overwritten by a legacy value");
            }

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(perWorldCourier);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldMigrationRetriesAfterATransientFailure")
    public static void perWorldMigrationRetriesAfterATransientFailure(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path perWorldCourier = perWorldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int originalMaxRing = DispatcherOfferConfig.NOVICE_MAX_RING.get();
        int migratedValue = originalMaxRing == 17 ? 23 : 17;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(perWorldCourier);
            // Malformed TOML forces a real read failure inside the migration -- review comment
            // 52641's exact concern: a transient failure mid-migration must not permanently
            // abandon this world's migration by settling the marker anyway.
            Files.writeString(legacy, "noviceMaxRing = [unterminated\n");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(!Files.exists(marker),
                    "a failed migration attempt must not settle -- it must remain retryable on the next server start");
            helper.assertTrue(!Files.exists(perWorldCourier),
                    "a failed attempt must not leave behind a partially-written per-world file either");
            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == originalMaxRing,
                    "a failed attempt must not have mutated the live config either");

            // Simulate the next server start on the same world: the legacy file is now fixed.
            Files.writeString(legacy, "noviceMaxRing = " + migratedValue + "\n");
            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == migratedValue,
                    "a retried migration after the legacy file is fixed must succeed and apply");
            helper.assertTrue(Files.exists(marker), "a successful retry must settle");
            helper.assertTrue(Files.exists(perWorldCourier), "a successful retry must write the per-world file");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(perWorldCourier);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldMigrationRetriesAfterATargetWriteFailure")
    public static void perWorldMigrationRetriesAfterATargetWriteFailure(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path perWorldCourier = perWorldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int originalMaxRing = DispatcherOfferConfig.NOVICE_MAX_RING.get();
        int migratedValue = originalMaxRing == 17 ? 23 : 17;
        boolean madeReadOnly = false;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(perWorldCourier);
            Files.writeString(legacy, "noviceMaxRing = " + migratedValue + "\n");

            // Force the target write itself to fail (review comment 52646's exact concern,
            // distinct from a legacy-read failure): make the per-world directory read-only so
            // creating the sibling temp file throws, before any bytes of a final file could ever
            // be written -- proving the publish step itself is all-or-nothing, not just the read.
            madeReadOnly = perWorldDir.toFile().setWritable(false);
            helper.assertTrue(madeReadOnly, "test setup: must be able to make the per-world directory read-only on this platform");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(!Files.exists(marker), "a failed target write must not settle");
            helper.assertTrue(!Files.exists(perWorldCourier),
                    "a failed target write must leave no final file behind at all -- partial or otherwise");
            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == originalMaxRing,
                    "a failed target write must not have mutated the live config either -- live values are only applied after a successful publish");

            perWorldDir.toFile().setWritable(true);
            madeReadOnly = false;

            // Simulate the next server start on the same world: the directory is writable again.
            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == migratedValue,
                    "a retried migration once the target is writable again must succeed and apply");
            helper.assertTrue(Files.exists(marker), "a successful retry must settle");
            helper.assertTrue(Files.exists(perWorldCourier), "a successful retry must write the per-world file");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (madeReadOnly) {
                perWorldDir.toFile().setWritable(true);
            }
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(perWorldCourier);
                Files.deleteIfExists(marker);
                try (var stray = Files.list(perWorldDir)) {
                    stray.filter(p -> p.getFileName().toString().startsWith(LegacyConfigMigration.NEW_SERVER_FILE + ".tmp-"))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // best-effort cleanup only
                                }
                            });
                }
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    // Global migration precedence (review comment 52711, following on from 52706):
    // migrateServerConfig/migrateClientConfig now distinguish a genuine pre-existing admin
    // customization of the global config from a target that merely LOOKS like it exists because
    // NeoForge's own normal bootstrap created it with pure schema defaults after a prior failed
    // attempt -- by comparing every field's current value against that field's own schema
    // default via the real ConfigValue/ModConfigSpec machinery, which is exactly why this needs a
    // GameTest rather than plain JUnit. These use an ISOLATED temp directory (never the real
    // FMLPaths.CONFIGDIR.get()), so they never touch this run's actual shared global config.

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "globalServerConfigPreservesAGenuinePreExistingCustomization")
    public static void globalServerConfigPreservesAGenuinePreExistingCustomization(GameTestHelper helper) {
        withIsolatedConfigDir(helper, configDir -> {
            Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 7\n");
            // Genuinely customized: 42 is not noviceMaxRing's schema default (1).
            Path target = configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
            Files.writeString(target, "noviceMaxRing = 42\n");

            LegacyConfigMigration.migrateServerConfig(configDir);

            try (FileConfig config = FileConfig.of(target)) {
                config.load();
                helper.assertTrue(config.getInt("noviceMaxRing") == 42,
                        "a genuine pre-existing customization must never be overwritten by a legacy value");
            }
        });
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "globalServerConfigOverwritesATargetIndistinguishableFromDefaults")
    public static void globalServerConfigOverwritesATargetIndistinguishableFromDefaults(GameTestHelper helper) {
        withIsolatedConfigDir(helper, configDir -> {
            Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE), "noviceMaxRing = 7\n");
            // Exactly noviceMaxRing's own schema default (1) -- indistinguishable from a file
            // NeoForge's own bootstrap would have created after a prior failed migration attempt.
            Path target = configDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
            Files.writeString(target, "noviceMaxRing = 1\n");

            LegacyConfigMigration.migrateServerConfig(configDir);

            try (FileConfig config = FileConfig.of(target)) {
                config.load();
                helper.assertTrue(config.getInt("noviceMaxRing") == 7,
                        "a target indistinguishable from pure schema defaults must be treated as retry-eligible, not a customization");
            }
        });
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "globalClientConfigPreservesAGenuinePreExistingCustomization")
    public static void globalClientConfigPreservesAGenuinePreExistingCustomization(GameTestHelper helper) {
        withIsolatedConfigDir(helper, configDir -> {
            Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_CLIENT_FILE),
                    "[delivery]\nshowLegacyContractHud = true\n");
            // Genuinely customized: true is not showLegacyContractHud's schema default (false).
            Path target = configDir.resolve(LegacyConfigMigration.NEW_CLIENT_FILE);
            Files.writeString(target, "showLegacyContractHud = true\n");

            LegacyConfigMigration.migrateClientConfig(configDir);

            try (FileConfig config = FileConfig.of(target)) {
                config.load();
                helper.assertTrue((boolean) config.get(LegacyConfigMigration.NEW_CLIENT_KEY),
                        "a genuine pre-existing customization must never be overwritten by a legacy value");
            }
        });
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "globalClientConfigOverwritesATargetIndistinguishableFromDefaults")
    public static void globalClientConfigOverwritesATargetIndistinguishableFromDefaults(GameTestHelper helper) {
        withIsolatedConfigDir(helper, configDir -> {
            Files.writeString(configDir.resolve(LegacyConfigMigration.LEGACY_CLIENT_FILE),
                    "[delivery]\nshowLegacyContractHud = true\n");
            // Exactly showLegacyContractHud's own schema default (false) -- indistinguishable
            // from a file NeoForge's own bootstrap would have created after a prior failure.
            Path target = configDir.resolve(LegacyConfigMigration.NEW_CLIENT_FILE);
            Files.writeString(target, "showLegacyContractHud = false\n");

            LegacyConfigMigration.migrateClientConfig(configDir);

            try (FileConfig config = FileConfig.of(target)) {
                config.load();
                helper.assertTrue((boolean) config.get(LegacyConfigMigration.NEW_CLIENT_KEY),
                        "a target indistinguishable from pure schema defaults must be treated as retry-eligible, not a customization");
            }
        });
    }

    private interface IsolatedConfigDirTest {
        void run(Path configDir) throws IOException;
    }

    /** Runs {@code test} against a fresh, isolated temp directory (never the real {@code
     * FMLPaths.CONFIGDIR.get()}), so these global-config precedence tests never touch this run's
     * actual shared config files, then deletes it afterward regardless of outcome. */
    private static void withIsolatedConfigDir(GameTestHelper helper, IsolatedConfigDirTest test) {
        Path configDir;
        try {
            configDir = Files.createTempDirectory("courier-craft-global-config-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            test.run(configDir);
            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try (var walk = Files.walk(configDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup only
                    }
                });
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }
}
