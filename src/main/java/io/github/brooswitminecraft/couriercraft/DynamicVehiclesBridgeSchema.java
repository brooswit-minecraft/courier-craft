package io.github.brooswitminecraft.couriercraft;

/**
 * MINECRAFT-493 review (comment 52567, finding 1): the actual, Minecraft/NeoForge-free
 * compatibility check -- isolated from {@link DynamicVehiclesCompatibility}'s {@code
 * ModList}-gated entry point so it is directly unit-testable, including against Dynamic
 * Vehicles' real released 0.82.1 jar (see {@code DynamicVehiclesBridgeSchemaTest}, which proves
 * that exact jar -- pre-split, lacking this marker entirely -- is truthfully rejected).
 */
final class DynamicVehiclesBridgeSchema {
    static final String MARKER_CLASS = "io.github.brooswitminecraft.dynamicvehicles.CourierCraftBridgeSupport";
    static final int REQUIRED_SCHEMA = 1;

    private DynamicVehiclesBridgeSchema() {}

    /** Entry point used in production: always checks against Dynamic Vehicles' real marker class. */
    static void verifyCompatible() {
        verifyCompatible(MARKER_CLASS);
    }

    /**
     * @param markerClassName the fully-qualified marker class to look up (a parameter so tests
     *     can point this at fixture classes without colliding with the real Dynamic Vehicles
     *     class name on the test classpath)
     * @throws IllegalStateException with an actionable message if the marker class is absent
     *     (installed Dynamic Vehicles is pre-split) or declares an incompatible schema version
     */
    static void verifyCompatible(String markerClassName) {
        int schema;
        try {
            Class<?> marker = Class.forName(markerClassName);
            schema = (int) marker.getField("SCHEMA_VERSION").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The installed Dynamic Vehicles is a pre-split (MINECRAFT-493) build that still registers "
                            + "its own Dispatcher/Dispatch Board/menu under the same \"dynamicvehicles\" IDs "
                            + "Courier Craft now also registers, which would crash on duplicate registration. "
                            + "Upgrade Dynamic Vehicles to the first release after MINECRAFT-493 (PR #212), or "
                            + "remove it -- Courier Craft runs fully standalone without it.", e);
        }
        if (schema != REQUIRED_SCHEMA) {
            throw new IllegalStateException(
                    "The installed Dynamic Vehicles' Courier Craft bridge schema is " + schema
                            + ", but this Courier Craft build requires " + REQUIRED_SCHEMA
                            + ". Install matching versions of both mods.");
        }
    }
}
