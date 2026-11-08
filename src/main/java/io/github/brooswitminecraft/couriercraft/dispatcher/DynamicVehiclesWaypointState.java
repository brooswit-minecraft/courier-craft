package io.github.brooswitminecraft.couriercraft.dispatcher;

/**
 * Isolates every reference to Dynamic Vehicles' {@code hud.MissionWaypointLayout}'s reservation
 * state -- the pure, Minecraft-free part of sharing the Dispatcher marker's occupied screen space
 * with Pathfinder's own waypoint. Deliberately kept free of any Minecraft/NeoForge type (unlike
 * {@link ContractWaypointOverlay}, which implements {@code LayeredDraw.Layer}) so this class
 * alone can be referenced from the plain JUnit {@code test} sourceSet, which has no Minecraft/
 * NeoForge classes on its classpath at all -- only Dynamic Vehicles' real jar, added there
 * deliberately (see {@code build.gradle}) so {@code ContractWaypointOverlayCoinstalledTest} can
 * prove this sharing genuinely happens against Dynamic Vehicles' real state, rather than merely
 * asserting it in a comment (MINECRAFT-493 review comments 52327/52328/52330).
 *
 * <p>Every reference here is only ever linked when {@link ContractWaypointOverlay}'s own {@code
 * ModList.isLoaded} check is true and one of these methods is actually called -- never merely by
 * this class loading, which would otherwise require Dynamic Vehicles' jar on the runtime
 * classpath unconditionally (standard optional-mod-dependency pattern).
 */
final class DynamicVehiclesWaypointState {
    private DynamicVehiclesWaypointState() {}

    static void clear() {
        io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout.clear(
                io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout.Mission.DISPATCHER);
    }

    /**
     * Performs exactly the reservation step {@link ContractWaypointOverlay#render} needs (via
     * Dynamic Vehicles' real {@code MissionWaypointLayout.reserve}) before painting -- kept
     * separate from the actual paint call (which needs a real {@code GuiGraphics}/{@code
     * Minecraft} instance) specifically so the reservation/overlap-avoidance math, independent of
     * actually painting pixels, is directly callable (and testable) without either.
     */
    static io.github.brooswitminecraft.dynamicvehicles.hud.Rect reserve(
            io.github.brooswitminecraft.dynamicvehicles.hud.Rect candidate, int viewportHeight) {
        return io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout.reserve(
                io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout.Mission.DISPATCHER, candidate, viewportHeight);
    }
}
