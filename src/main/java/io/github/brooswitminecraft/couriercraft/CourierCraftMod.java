package io.github.brooswitminecraft.couriercraft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Courier Craft. Registers the mod with no gameplay logic;
 * add your own event listeners, registries, and config here.
 */
@Mod(CourierCraftMod.MODID)
public class CourierCraftMod {
    public static final String MODID = "couriercraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CourierCraftMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Courier Craft loaded");
    }
}
