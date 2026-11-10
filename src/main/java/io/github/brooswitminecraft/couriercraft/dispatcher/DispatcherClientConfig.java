package io.github.brooswitminecraft.couriercraft.dispatcher;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side display options for the Dispatcher's contract HUD. Standalone from Dynamic
 * Vehicles' own (much larger) {@code ClientConfig} -- this mod no longer depends on that one at
 * all (MINECRAFT-493 review); see {@link ContractWaypointOverlay}'s own javadoc for why.
 */
public final class DispatcherClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_LEGACY_CONTRACT_HUD;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SHOW_LEGACY_CONTRACT_HUD = builder
                .comment("Show the legacy contract HUD overlay alongside the waypoint marker. Disabled by default because the waypoint marker is the primary contract destination display.")
                .define("showLegacyContractHud", false);
        SPEC = builder.build();
    }

    private DispatcherClientConfig() {}
}
