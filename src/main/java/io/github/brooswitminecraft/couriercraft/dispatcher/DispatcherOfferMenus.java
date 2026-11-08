package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.brooswitminecraft.couriercraft.CourierCraftMod;

/** Registers the {@link DispatcherOfferMenu} menu type (MINECRAFT-109). */
public final class DispatcherOfferMenus {
    private DispatcherOfferMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CourierCraftMod.LEGACY_REGISTRY_NAMESPACE);

    public static final DeferredHolder<MenuType<?>, MenuType<DispatcherOfferMenu>> DISPATCHER_OFFER = MENUS.register("dispatcher_offer",
            () -> IMenuTypeExtension.create(DispatcherOfferMenu::new));
}
