package io.github.brooswitminecraft.couriercraft;

import net.neoforged.fml.ModList;

/**
 * MINECRAFT-493 review (comment 52567, finding 1): fails mod construction loudly and specifically
 * -- before either mod's own registries populate -- when Dynamic Vehicles is installed but is a
 * pre-split build, rather than letting that collision surface later as an opaque duplicate-
 * registry-id crash with no actionable message. See {@link DynamicVehiclesBridgeSchema} for the
 * actual (unit-tested) check; this class only adds the {@code ModList} gate, which is why it is
 * NOT itself unit-tested (no NeoForge classes on the plain JUnit test classpath -- see that
 * class's own javadoc for the real coverage).
 */
final class DynamicVehiclesCompatibility {
    private static final String DYNAMIC_VEHICLES_MODID = "dynamicvehicles";

    private DynamicVehiclesCompatibility() {}

    static void verify() {
        if (ModList.get().isLoaded(DYNAMIC_VEHICLES_MODID)) {
            DynamicVehiclesBridgeSchema.verifyCompatible();
        }
    }
}
