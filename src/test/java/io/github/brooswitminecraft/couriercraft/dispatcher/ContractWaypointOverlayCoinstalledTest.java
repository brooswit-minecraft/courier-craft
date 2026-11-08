package io.github.brooswitminecraft.couriercraft.dispatcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout;
import io.github.brooswitminecraft.dynamicvehicles.hud.Rect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MINECRAFT-493 review (comments 52327/52328/52330): hosted proof that {@link
 * ContractWaypointOverlay.DynamicVehiclesBridge} genuinely shares Dynamic Vehicles' REAL {@code
 * hud.MissionWaypointLayout} state with Pathfinder's own waypoint when both mods are coinstalled
 * -- not merely asserted in a javadoc comment. This is the {@code test} sourceSet specifically
 * (see {@code build.gradle}'s {@code testImplementation files(dynamicVehiclesJar)}): the plain
 * JUnit {@code test} task never goes through NeoForge's mod loader, so having Dynamic Vehicles'
 * real jar here carries none of the "tries to load it as a mod, needs Sable/dynamic-terrain"
 * risk the earlier required-dependency attempt hit on the main runtime classpath.
 *
 * <p>The bridge's {@code reserveForTesting} performs exactly the reservation step {@code
 * renderMarker} does (via Dynamic Vehicles' real {@code MissionWaypointLayout.reserve}), without
 * needing a real {@code GuiGraphics}/{@code Minecraft} instance to draw with.
 */
class ContractWaypointOverlayCoinstalledTest {
    private static final int VIEWPORT_HEIGHT = 200;

    @BeforeEach
    void resetState() {
        // No package-private resetForTesting access from here (different package); public clear
        // on both missions is sufficient to start each test from a known-empty state.
        ContractWaypointOverlay.DynamicVehiclesBridge.clear();
        MissionWaypointLayout.clear(MissionWaypointLayout.Mission.PATHFINDER);
    }

    @Test
    void dispatcherAndPathfinderSharedReservationLifecycle() {
        // Dispatcher reserves through Courier Craft's bridge -- the real call renderMarker makes.
        Rect dispatcherCandidate = new Rect(10, 10, 60, 30);
        Rect reservedDispatcher = ContractWaypointOverlay.DynamicVehiclesBridge.reserveForTesting(dispatcherCandidate, VIEWPORT_HEIGHT);
        assertEquals(dispatcherCandidate, reservedDispatcher, "first reservation this frame is unmoved");

        // Pathfinder reserves directly through Dynamic Vehicles' own real API (the same one its
        // PathfinderWaypointOverlay calls) at an overlapping spot.
        Rect pathfinderCandidate = new Rect(10, 10, 60, 30);
        Rect reservedPathfinder = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderCandidate, VIEWPORT_HEIGHT);

        assertFalse(reservedDispatcher.overlaps(reservedPathfinder),
                "Dispatcher (via Courier Craft's bridge) and Pathfinder (via Dynamic Vehicles directly) must not overlap when sharing real state");

        // Shared bottom-edge placement: Dispatcher's panel sits flush against the bottom edge;
        // Pathfinder's candidate overlaps it, so a naive downward shift would push Pathfinder
        // below the screen -- MissionWaypointLayout must shift up instead.
        ContractWaypointOverlay.DynamicVehiclesBridge.clear();
        MissionWaypointLayout.clear(MissionWaypointLayout.Mission.PATHFINDER);
        Rect dispatcherAtBottomEdge = new Rect(10, VIEWPORT_HEIGHT - 20, 60, VIEWPORT_HEIGHT);
        ContractWaypointOverlay.DynamicVehiclesBridge.reserveForTesting(dispatcherAtBottomEdge, VIEWPORT_HEIGHT);
        Rect pathfinderNearBottomEdge = new Rect(10, VIEWPORT_HEIGHT - 15, 60, VIEWPORT_HEIGHT + 5);
        Rect resolvedPathfinder = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, pathfinderNearBottomEdge, VIEWPORT_HEIGHT);
        assertFalse(resolvedPathfinder.top() < 0, "resolved panel must not be pushed above the top of the viewport");
        assertFalse(resolvedPathfinder.bottom() > VIEWPORT_HEIGHT, "resolved panel must not be pushed below the bottom of the viewport");

        // Clearing the Dispatcher slot (through the bridge) releases it: Pathfinder's next
        // reservation at the same spot must land unmoved.
        ContractWaypointOverlay.DynamicVehiclesBridge.clear();
        Rect pathfinderAfterDispatcherCleared = MissionWaypointLayout.reserve(MissionWaypointLayout.Mission.PATHFINDER, dispatcherCandidate, VIEWPORT_HEIGHT);
        assertEquals(dispatcherCandidate, pathfinderAfterDispatcherCleared,
                "clearing the Dispatcher slot through the bridge must release it for Pathfinder");
    }
}
