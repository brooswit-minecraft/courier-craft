package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * MINECRAFT-493 review: automated evidence that a world last played under the pre-split Dynamic
 * Vehicles (the one that owned the Dispatcher directly) keeps resolving after the supported
 * upgrade pairing -- upgrading Dynamic Vehicles past this split AND installing Courier Craft
 * alongside it. Installing only the new Dynamic Vehicles without Courier Craft is not that pair
 * (the Dispatcher simply doesn't come back, same as removing any other mod); these tests don't
 * cover that case because there is nothing to assert there beyond "Courier Craft's classes are
 * absent." No live world is touched here (the ticket's own "no live world edits" scope) -- this
 * is a regression guard against the two things an existing save actually depends on:
 *
 * <ol>
 *   <li>the registry names a saved chunk/entity/BlockEntity references by string ({@link
 *       #legacyRegistryNamesStillResolveAfterTheSplit}), and</li>
 *   <li>the exact NBT schema {@code dynamicvehicles_contracts}'s on-disk SavedData file used
 *       before the split ({@link #preSplitSavedContractNbtLoadsThroughTheRelocatedStorage}).</li>
 * </ol>
 */
@GameTestHolder(io.github.brooswitminecraft.couriercraft.CourierCraftMod.MODID)
@PrefixGameTestTemplate(false)
public final class UpgradeCompatibilityGameTests {
    private UpgradeCompatibilityGameTests() {}

    @GameTest(template = "dynamicvehicles:dispatch_profession")
    public static void legacyRegistryNamesStillResolveAfterTheSplit(GameTestHelper helper) {
        ResourceLocation dispatchBoard = ResourceLocation.parse("dynamicvehicles:dispatch_board");
        ResourceLocation dispatcher = ResourceLocation.parse("dynamicvehicles:dispatcher");

        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(dispatchBoard),
                "a world with a placed Dispatch Board must still find its block by the pre-split registry name");
        helper.assertTrue(BuiltInRegistries.BLOCK.get(dispatchBoard) == DispatcherBlocks.DISPATCH_BOARD.get(),
                "the resolved block must be Courier Craft's own Dispatch Board, not a stale or missing registration");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(dispatchBoard),
                "an inventory slot/recipe saved against the Dispatch Board item must still find it by the pre-split registry name");
        helper.assertTrue(BuiltInRegistries.VILLAGER_PROFESSION.containsKey(dispatcher),
                "a world with a spawned Dispatcher villager must still find its profession by the pre-split registry name");
        helper.assertTrue(BuiltInRegistries.VILLAGER_PROFESSION.get(dispatcher) == DispatcherProfession.DISPATCHER.get(),
                "the resolved profession must be Courier Craft's own Dispatcher, not a stale or missing registration");
        helper.assertTrue(BuiltInRegistries.POINT_OF_INTEREST_TYPE.containsKey(dispatchBoard),
                "the Dispatch Board's POI type must still resolve by the pre-split registry name");

        helper.succeed();
    }

    @GameTest(template = "dynamicvehicles:dispatch_profession")
    public static void preSplitSavedContractNbtLoadsThroughTheRelocatedStorage(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        UUID sourceVillagerId = UUID.randomUUID();

        // Exactly the field names/types and "Contracts" list shape dynamic-vehicles' own
        // DeliveryContractStorage wrote to its dynamicvehicles_contracts SavedData file before
        // this split -- unchanged by the move (see DeliveryContractStorage#save/#load). A real
        // pre-split world's save-on-disk data looks exactly like this.
        CompoundTag entry = new CompoundTag();
        entry.putLong("PlayerIdHi", playerId.getMostSignificantBits());
        entry.putLong("PlayerIdLo", playerId.getLeastSignificantBits());
        entry.putLong("SourceIdHi", sourceVillagerId.getMostSignificantBits());
        entry.putLong("SourceIdLo", sourceVillagerId.getLeastSignificantBits());
        entry.putString("Dimension", "minecraft:overworld");
        entry.putInt("RegionX", 3);
        entry.putInt("RegionZ", -2);
        entry.putLong("StartX", 100);
        entry.putLong("StartY", 64);
        entry.putLong("StartZ", -200);
        entry.putDouble("Danger", 0.5);
        entry.putDouble("Reward", 7.0);
        entry.putLong("AcceptedAtTick", 1000);
        entry.putLong("DeadlineTick", 25000);
        ListTag contracts = new ListTag();
        contracts.add(entry);
        CompoundTag legacyTag = new CompoundTag();
        legacyTag.put("Contracts", contracts);
        legacyTag.put("FoundedSettlements", new ListTag());

        DeliveryContractStorage loaded = DeliveryContractStorage.load(legacyTag, helper.getLevel().registryAccess());

        helper.assertTrue(loaded.book().snapshot().size() == 1, "the pre-split contract must survive the load");
        DeliveryContract contract = loaded.book().snapshot().get(0);
        helper.assertTrue(contract.playerId().equals(playerId), "player id must round-trip from the legacy NBT");
        helper.assertTrue(contract.sourceVillagerId().equals(sourceVillagerId), "source villager id must round-trip from the legacy NBT");
        helper.assertTrue(contract.destinationDimension().equals("minecraft:overworld"), "destination dimension must round-trip from the legacy NBT");
        helper.assertTrue(contract.destinationRegionX() == 3 && contract.destinationRegionZ() == -2, "destination region must round-trip from the legacy NBT");
        helper.assertTrue(contract.destinationStartX() == 100 && contract.destinationStartY() == 64 && contract.destinationStartZ() == -200,
                "destination start position must round-trip from the legacy NBT");
        helper.assertTrue(contract.deadlineTick() == 25000, "deadline tick must round-trip from the legacy NBT");

        helper.succeed();
    }
}
