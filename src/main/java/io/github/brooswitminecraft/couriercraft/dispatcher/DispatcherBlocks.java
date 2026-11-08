package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.couriercraft.CourierCraftMod;

/**
 * The Dispatcher's own job-site block (MINECRAFT-196), replacing the borrowed Bell/{@code
 * PoiTypes#MEETING}. A plain survival-craftable block whose states exist nowhere else, so
 * {@link DispatcherPoiTypes#DISPATCH_BOARD} can claim them without colliding with any
 * vanilla or modded PoiType registration. Since MINECRAFT-323 it is a {@link DispatchBoardBlock}
 * (right-click dispatcher interaction, backed by {@link DispatchBoardBlockEntity}'s persisted
 * UUID), not a plain simple block, but registers no new {@link net.minecraft.world.level.block.state.BlockState}
 * properties, so the PoiType's state-set claim above is unaffected.
 */
public final class DispatcherBlocks {
    private DispatcherBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CourierCraftMod.LEGACY_REGISTRY_NAMESPACE);
    // Same legacy namespace as BLOCKS: the dispatch board's BlockItem must keep the registry
    // name "dynamicvehicles:dispatch_board" too, or a saved inventory slot/recipe referencing
    // that item ID stops resolving after the split.
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CourierCraftMod.LEGACY_REGISTRY_NAMESPACE);

    public static final DeferredBlock<DispatchBoardBlock> DISPATCH_BOARD = BLOCKS.registerBlock("dispatch_board",
            DispatchBoardBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> DISPATCH_BOARD_ITEM =
            ITEMS.registerSimpleBlockItem(DISPATCH_BOARD);
}
