package ar.emedicapp.purchase;

import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BranchAvailabilityTest {
    @Test
    void dailyHoursHaveInclusiveOpeningAndExclusiveClosing() {
        var caps = Map.<String, Object>of("opensAt", "08:00", "closesAt", "20:00");
        assertThat(BranchAvailability.open(caps, LocalTime.of(8, 0))).isTrue();
        assertThat(BranchAvailability.open(caps, LocalTime.of(20, 0))).isFalse();
    }

    @Test
    void overnightWindowAndManualAvailabilityAreSupported() {
        var caps = Map.<String, Object>of("opensAt", "20:00", "closesAt", "08:00");
        assertThat(BranchAvailability.open(caps, LocalTime.of(1, 0))).isTrue();
        assertThat(BranchAvailability.open(caps, LocalTime.of(12, 0))).isFalse();
        assertThat(BranchAvailability.open(Map.of(), LocalTime.NOON)).isTrue();
    }

    @Test
    void deliveryRadiusRejectsOutOfZoneAndMalformedValues() {
        assertThat(BranchAvailability.withinRadius(Map.of("deliveryRadiusKm", "3.5"), 3.5))
                .isTrue();
        assertThat(BranchAvailability.withinRadius(Map.of("deliveryRadiusKm", "3.5"), 3.6))
                .isFalse();
        assertThat(BranchAvailability.withinRadius(Map.of("deliveryRadiusKm", "invalid"), 1))
                .isFalse();
    }
}
