package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Additive world-projected waypoint marker for the active delivery contract.
 *
 * <p>MINECRAFT-493 review (comment 52304): the original Dynamic Vehicles version of this overlay
 * shared {@code hud.MissionWaypointLayout} with Pathfinder's own waypoint, so an active marker
 * from each never overlapped. This mod keeps its own copy of that same kernel ({@link
 * MissionWaypointLayout}, {@link MissionWaypointRenderer}) so it still renders a marker when run
 * fully standalone (no Dynamic Vehicles installed at all) -- the common case this mod's own
 * {@code neoforge.mods.toml} and CI need to support, since a required dependency on Dynamic
 * Vehicles transitively requires ITS OWN required dependencies (a licensed physics companion and
 * a separately-released terrain mod) just to render a HUD marker, confirmed by CI refusing to
 * load the mod at all once tried.
 *
 * <p>When Dynamic Vehicles IS present (an optional, client-only {@code neoforge.mods.toml}
 * dependency -- never required), {@link #render} instead delegates to ITS real {@code
 * hud.MissionWaypointLayout}/{@code MissionWaypointRenderer}, the same instance Pathfinder's own
 * waypoint renders through, so the two mods' markers keep coordinating overlap avoidance exactly
 * as before the split. {@link #dynamicVehiclesLoaded} gates which path runs; every reference to
 * Dynamic Vehicles' own classes below is therefore only ever linked when that check is true, so
 * their absence is harmless when it is false (standard optional-mod-dependency pattern -- never
 * put a plain static import of an optional dependency's class at the top of this file, or every
 * caller pays for resolving it unconditionally). {@link DynamicVehiclesWaypointState} isolates
 * the Minecraft-free part of that sharing (the reservation math) so it is also independently,
 * directly testable -- see {@code ContractWaypointOverlayCoinstalledTest}.
 */
public final class ContractWaypointOverlay implements LayeredDraw.Layer {
    private static final String DYNAMIC_VEHICLES_MODID = "dynamicvehicles";

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DeliveryHudPayload hud = ClientContractHud.current();
        // MINECRAFT-493 temporary diagnostic (coinstalled acceptance clear-after-inactive
        // investigation): remove once the real cause is found.
        io.github.brooswitminecraft.couriercraft.CourierCraftMod.LOGGER.info(
                "MINECRAFT493_DIAG render() hud={} dynamicVehiclesLoaded={}", hud, dynamicVehiclesLoaded());
        Minecraft minecraft = Minecraft.getInstance();
        if (!hud.active() || minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        int color = DangerGauge.colorArgb(hud.danger(), hud.dangerMin(), hud.dangerMax());
        double dx = hud.destinationX() - minecraft.player.getX();
        double dz = hud.destinationZ() - minecraft.player.getZ();
        String distance = OfferDistanceFormat.format(Math.sqrt(dx * dx + dz * dz));
        if (dynamicVehiclesLoaded()) {
            io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointRenderer.renderMarker(graphics, minecraft,
                    io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout.Mission.DISPATCHER,
                    hud.destinationX(), hud.destinationY(), hud.destinationZ(), color, distance, List.of());
        } else {
            // No marker occupies screen space this frame; clear so Pathfinder (rendered after
            // this overlay) never avoids a now-stale Dispatcher panel (MINECRAFT-411 PR #177
            // review) -- only meaningful for this mod's own standalone kernel copy; the
            // Dynamic-Vehicles-backed branch above shares DynamicVehiclesWaypointState's clear
            // instead.
            MissionWaypointRenderer.renderMarker(graphics, minecraft, MissionWaypointLayout.Mission.DISPATCHER,
                    hud.destinationX(), hud.destinationY(), hud.destinationZ(), color, distance, List.of());
        }
    }

    private void clear() {
        io.github.brooswitminecraft.couriercraft.CourierCraftMod.LOGGER.info(
                "MINECRAFT493_DIAG clear() dynamicVehiclesLoaded={}", dynamicVehiclesLoaded());
        if (dynamicVehiclesLoaded()) {
            DynamicVehiclesWaypointState.clear();
        } else {
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.DISPATCHER);
        }
    }

    private static boolean dynamicVehiclesLoaded() {
        return ModList.get().isLoaded(DYNAMIC_VEHICLES_MODID);
    }
}
