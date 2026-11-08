package io.github.brooswitminecraft.couriercraft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

import io.github.brooswitminecraft.couriercraft.dispatcher.ContractHudOverlay;
import io.github.brooswitminecraft.couriercraft.dispatcher.ContractTickHandler;
import io.github.brooswitminecraft.couriercraft.dispatcher.ContractWaypointOverlay;
import io.github.brooswitminecraft.couriercraft.dispatcher.DeliveryHudPayload;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatchBoardBlockEntities;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherBlocks;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherInteractionHandler;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherOfferAcceptPayload;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherOfferConfig;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherOfferMenus;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherPoiTypes;
import io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherProfession;
import io.github.brooswitminecraft.couriercraft.dispatcher.PillagerAmbushHandler;

/**
 * Entry point for Courier Craft: the Dispatcher villager and its delivery-contract gameplay,
 * extracted from Dynamic Vehicles (MINECRAFT-493). Registry names under {@link
 * #LEGACY_REGISTRY_NAMESPACE} are deliberately unchanged from Dynamic Vehicles so existing saves
 * (placed Dispatch Boards, spawned Dispatcher villagers, saved contracts) keep resolving after
 * the split; see {@link #LEGACY_REGISTRY_NAMESPACE}'s own doc.
 */
@Mod(CourierCraftMod.MODID)
public class CourierCraftMod {
    public static final String MODID = "couriercraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The pre-split registry/network namespace ("dynamicvehicles"), kept for every registry
     * object and payload channel that already shipped under it before this mod existed --
     * blocks, block entities, POI types, the villager profession, menus, and network payloads.
     * A resource location's namespace need not match the owning mod's id, so this is legal and
     * is the only thing standing between an existing save and a "missing registry" warning on
     * upgrade. Do not use this for anything new; new Courier Craft registry entries should use
     * {@link #MODID} instead.
     */
    public static final String LEGACY_REGISTRY_NAMESPACE = "dynamicvehicles";

    public CourierCraftMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, DispatcherOfferConfig.SPEC);

        DispatcherBlocks.BLOCKS.register(modEventBus);
        DispatcherBlocks.ITEMS.register(modEventBus);
        DispatchBoardBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        DispatcherPoiTypes.POI_TYPES.register(modEventBus);
        DispatcherProfession.PROFESSIONS.register(modEventBus);
        DispatcherOfferMenus.MENUS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.addListener(DispatcherInteractionHandler::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onServerTick);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ContractTickHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PillagerAmbushHandler::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(PillagerAmbushHandler::onEntityLeaveLevel);

        LOGGER.info("Courier Craft loaded");
    }

    private void addCreative(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DispatcherBlocks.DISPATCH_BOARD_ITEM);
        }
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(DispatcherOfferAcceptPayload.TYPE, DispatcherOfferAcceptPayload.STREAM_CODEC, DispatcherOfferAcceptPayload::handle);
        registrar.playToClient(DeliveryHudPayload.TYPE, DeliveryHudPayload.STREAM_CODEC, DeliveryHudPayload::handle);
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {}

        @SubscribeEvent
        public static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
            event.register(DispatcherOfferMenus.DISPATCHER_OFFER.get(), io.github.brooswitminecraft.couriercraft.dispatcher.DispatcherOfferScreen::new);
        }

        @SubscribeEvent
        public static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "delivery_hud"), new ContractHudOverlay());
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "delivery_waypoint"), new ContractWaypointOverlay());
        }
    }
}
