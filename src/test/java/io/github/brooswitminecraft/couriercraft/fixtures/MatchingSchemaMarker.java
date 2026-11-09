package io.github.brooswitminecraft.couriercraft.fixtures;

/** Test fixture only: simulates a future Dynamic Vehicles build with a compatible bridge schema,
 * for {@code DynamicVehiclesBridgeSchemaTest}. Kept in sync with {@code
 * DynamicVehiclesBridgeSchema#REQUIRED_SCHEMA} by the test asserting an accepted outcome, not by
 * a shared constant -- this fixture is deliberately independent of production code. */
public final class MatchingSchemaMarker {
    public static final int SCHEMA_VERSION = 1;

    private MatchingSchemaMarker() {}
}
