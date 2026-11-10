package io.github.brooswitminecraft.couriercraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-493 review (comment 52567, finding 1): proves the compatibility handshake actually
 * catches the real incompatible pairing it exists for, rather than only a hypothetical one.
 * {@code dynamicVehiclesJar} on this test's classpath (see {@code build.gradle}) is Dynamic
 * Vehicles' real, released 0.82.1 jar -- genuinely pre-split, so it genuinely lacks {@link
 * DynamicVehiclesBridgeSchema#MARKER_CLASS} entirely. The mismatched/matching-schema cases use
 * small fixture classes (under the {@code fixtures} subpackage) instead of a second real jar,
 * since no post-split Dynamic Vehicles release exists yet to test against.
 */
class DynamicVehiclesBridgeSchemaTest {
    @Test
    void realPreSplitReleasedJarIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                DynamicVehiclesBridgeSchema::verifyCompatible);
        assertTrue(failure.getMessage().contains("pre-split"), failure.getMessage());
    }

    @Test
    void missingMarkerClassIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DynamicVehiclesBridgeSchema.verifyCompatible("io.github.brooswitminecraft.couriercraft.fixtures.NoSuchMarker"));
        assertTrue(failure.getMessage().contains("pre-split"), failure.getMessage());
    }

    @Test
    void mismatchedSchemaVersionIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DynamicVehiclesBridgeSchema.verifyCompatible("io.github.brooswitminecraft.couriercraft.fixtures.WrongSchemaMarker"));
        assertTrue(failure.getMessage().contains("bridge schema"), failure.getMessage());
    }

    @Test
    void matchingSchemaVersionIsAccepted() {
        assertDoesNotThrow(
                () -> DynamicVehiclesBridgeSchema.verifyCompatible("io.github.brooswitminecraft.couriercraft.fixtures.MatchingSchemaMarker"));
    }
}
