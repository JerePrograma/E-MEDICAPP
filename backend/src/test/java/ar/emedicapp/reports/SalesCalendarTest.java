package ar.emedicapp.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

class SalesCalendarTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("America/Argentina/Mendoza"));

    @Test
    void calendarIntervalsCrossYearsAndLeapDaysAtLocalMidnight() {
        var week = SalesService.range(SalesDtos.Period.WEEK, LocalDate.of(2026, 1, 1), clock);
        assertThat(week.from().toInstant()).isEqualTo(Instant.parse("2025-12-29T03:00:00Z"));
        assertThat(week.to().toInstant()).isEqualTo(Instant.parse("2026-01-05T03:00:00Z"));
        var february = SalesService.range(SalesDtos.Period.MONTH, LocalDate.of(2024, 2, 29), clock);
        assertThat(SalesService.bucketRanges(SalesDtos.Period.MONTH, february)).hasSize(29);
        var year = SalesService.range(SalesDtos.Period.YEAR, LocalDate.of(2024, 12, 31), clock);
        assertThat(year.from().toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(year.to().toLocalDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(SalesService.bucketRanges(SalesDtos.Period.YEAR, year)).hasSize(12);
    }

    @Test
    void hourlyBucketsCoverTheWholeDayEvenAcrossAHistoricalClockChange() {
        var range = SalesService.range(SalesDtos.Period.DAY, LocalDate.of(2008, 3, 15), clock);
        var buckets = SalesService.bucketRanges(SalesDtos.Period.DAY, range);
        assertThat(buckets.getFirst().from()).isEqualTo(range.from());
        assertThat(buckets.getLast().to()).isEqualTo(range.to());
        for (int i = 1; i < buckets.size(); i++)
            assertThat(buckets.get(i).from().toInstant()).isEqualTo(buckets.get(i - 1).to().toInstant());
    }
}
