package ar.emedicapp.catalog;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionCalendarTest {
    private static final ZoneId MENDOZA = ZoneId.of("America/Argentina/Mendoza");

    @Test
    void monthUsesLocalContractDateWhenUtcAlreadyHasTheFollowingDay() {
        assertEquals(
                Instant.parse("2026-12-01T02:00:00Z"),
                CatalogService.monthlyPeriodEnd(Instant.parse("2026-10-31T02:00:00Z"), MENDOZA));
    }

    @Test
    void leapFebruaryClampsTheLocalDayWithoutRemovingAPaidDay() {
        assertEquals(
                Instant.parse("2028-03-01T02:00:00Z"),
                CatalogService.monthlyPeriodEnd(Instant.parse("2028-01-31T02:00:00Z"), MENDOZA));
    }
}
