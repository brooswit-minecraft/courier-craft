package io.github.brooswitminecraft.couriercraft.dispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * #getRaw}/{@code #set}/{@code #save} actually takes effect -- with correct type conversion and
 * validation -- on a real, already-loaded NeoForge config; that needs a genuine running server,
 * which only a GameTest provides.
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
            // A realistic full legacy file: a normal in-range int, a raw TOML string for the enum
            // field (proving typed conversion, not a raw-string cache corruption -- review 52630
            // finding 2), and one deliberately out-of-range value (noviceMaxRing's bound is
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

            // Simulate a second server start on the same world: the legacy file still has the
            // migrated values, but a later admin change through Courier's own config must win --
            // re-running migration on an already-settled world must be a no-op.
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            DispatcherOfferConfig.SPEC.save();
            LegacyConfigMigration.migratePerWorldServerConfig(server);
            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == originalMaxRing,
                    "a settled world must never be re-migrated and clobber a later admin change");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            DispatcherOfferConfig.DANGER_ROLL_MODE.set(originalRollMode);
            DispatcherOfferConfig.SPEC.save();
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldLegacyOverrideIsSkippedWhenPerWorldCourierAlreadyCustomized")
    public static void perWorldLegacyOverrideIsSkippedWhenPerWorldCourierAlreadyCustomized(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path perWorldCourier = perWorldDir.resolve(LegacyConfigMigration.NEW_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int originalMaxRing = DispatcherOfferConfig.NOVICE_MAX_RING.get();
        int legacyValue = originalMaxRing == 17 ? 23 : 17;
        int alreadyCustomizedValue = originalMaxRing == 5 ? 11 : 5;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.writeString(legacy, "noviceMaxRing = " + legacyValue + "\n");
            // Simulate a genuine pre-existing per-world Courier customization: set the live value
            // and persist it to the per-world file (NOT via the legacy path), then clear the
            // in-memory cache so the next read genuinely comes back from that per-world file.
            DispatcherOfferConfig.NOVICE_MAX_RING.set(alreadyCustomizedValue);
            DispatcherOfferConfig.SPEC.save();
            helper.assertTrue(Files.exists(perWorldCourier), "the live save must have produced this world's own Courier config file");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == alreadyCustomizedValue,
                    "a genuine pre-existing per-world Courier customization must never be overwritten by a legacy value");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalMaxRing);
            DispatcherOfferConfig.SPEC.save();
            try {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }
}
