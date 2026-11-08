package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.util.List;

/**
 * Additive world-projected waypoint marker for the active delivery contract.
 *
 * <p>MINECRAFT-493 review (comment 52304): the original Dynamic Vehicles version of this overlay
 * shared {@link MissionWaypointLayout} with Pathfinder's own waypoint, so an active marker from
 * each never overlapped. {@link MissionWaypointLayout} and {@link MissionWaypointRenderer} are
 * kept here as this mod's own copy instead of a dependency on Dynamic Vehicles: a required
 * dependency on it transitively requires Dynamic Vehicles' own required dependencies (a licensed
 * physics companion and a separately-released terrain mod) just to render a HUD marker, which CI
 * confirmed by actually trying it -- Dynamic Vehicles refuses to load without them, so the
 * gameTestServer run crashed outright. A soft/optional dependency has the same problem whenever
 * it is actually present and loaded.
 *
 * <p>Known limitation this leaves open: when both mods are installed together, Dispatcher's and
 * Pathfinder's markers each read their own mod's independent copy of {@code MissionWaypointLayout}
 * and so no longer coordinate overlap avoidance between the two -- a coincident Dispatcher and
 * Pathfinder marker can draw on top of each other again, the exact MINECRAFT-411/PR #177 bug this
 * class originally fixed. Flagged as a follow-up rather than solved here (a shared, dependency-free
 * "common" library between the two mods is the likely real fix, not something to improvise under
 * this ticket's bounded scope).
 */
public final class ContractWaypointOverlay implements LayeredDraw.Layer {
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DeliveryHudPayload hud = ClientContractHud.current();
        Minecraft minecraft = Minecraft.getInstance();
        if (!hud.active() || minecraft.player == null || minecraft.level == null) {
            // No marker occupies screen space this frame; clear so Pathfinder (rendered after this
            // overlay) never avoids a now-stale Dispatcher panel (MINECRAFT-411 PR #177 review).
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.DISPATCHER);
            return;
        }
        int color = DangerGauge.colorArgb(hud.danger(), hud.dangerMin(), hud.dangerMax());
        double dx = hud.destinationX() - minecraft.player.getX();
        double dz = hud.destinationZ() - minecraft.player.getZ();
        String distance = OfferDistanceFormat.format(Math.sqrt(dx * dx + dz * dz));
        MissionWaypointRenderer.renderMarker(graphics, minecraft, MissionWaypointLayout.Mission.DISPATCHER,
                hud.destinationX(), hud.destinationY(), hud.destinationZ(), color, distance, List.of());
    }
}
