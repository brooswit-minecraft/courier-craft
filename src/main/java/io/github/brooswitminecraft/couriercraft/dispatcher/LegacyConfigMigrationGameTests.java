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
 * MINECRAFT-493 review (comment 52602/52604): {@code LegacyConfigMigrationTest} covers the
 * Minecraft-free decision core ({@code LegacyConfigMigration#decidePerWorldOverrides}) directly,
 * including two-distinct-worlds and global-fallback cases a plain JUnit test can reach. What it
 * CANNOT reach is whether applying a decided override through {@code ModConfigSpec.ConfigValue
 * #set}/{@code #save} actually takes effect on a real, already-loaded NeoForge config -- that
 * needs a genuine running server, which only a GameTest provides.
 */
@GameTestHolder(io.github.brooswitminecraft.couriercraft.CourierCraftMod.MODID)
@PrefixGameTestTemplate(false)
public final class LegacyConfigMigrationGameTests {
    private LegacyConfigMigrationGameTests() {}

    @GameTest(templateNamespace = "dynamicvehicles", template = "dispatch_profession",
            batch = "perWorldLegacyOverrideAppliesToTheLiveRunningConfig")
    public static void perWorldLegacyOverrideAppliesToTheLiveRunningConfig(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path perWorldDir = server.getWorldPath(new LevelResource("serverconfig"));
        Path legacy = perWorldDir.resolve(LegacyConfigMigration.LEGACY_SERVER_FILE);
        Path marker = perWorldDir.resolve(LegacyConfigMigration.PER_WORLD_MIGRATION_MARKER);
        int originalValue = DispatcherOfferConfig.NOVICE_MAX_RING.get();
        // Distinct from both the schema default (1) and whatever this GameTestServer's own world
        // might already carry, so a false pass can't hide behind a coincidental match.
        int migratedValue = originalValue == 17 ? 23 : 17;

        try {
            Files.createDirectories(perWorldDir);
            Files.deleteIfExists(marker);
            Files.writeString(legacy, "noviceMaxRing = " + migratedValue + "\n");

            LegacyConfigMigration.migratePerWorldServerConfig(server);

            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == migratedValue,
                    "the per-world legacy override must take effect on the live, already-loaded config");
            helper.assertTrue(Files.exists(marker), "this world must be marked settled after its first migration");

            // Simulate a second server start on the same world: the legacy file still says
            // migratedValue, but a later admin change through Courier's own config must win --
            // re-running migration on an already-settled world must be a no-op.
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalValue);
            DispatcherOfferConfig.SPEC.save();
            LegacyConfigMigration.migratePerWorldServerConfig(server);
            helper.assertTrue(DispatcherOfferConfig.NOVICE_MAX_RING.get() == originalValue,
                    "a settled world must never be re-migrated and clobber a later admin change");

            helper.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DispatcherOfferConfig.NOVICE_MAX_RING.set(originalValue);
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
