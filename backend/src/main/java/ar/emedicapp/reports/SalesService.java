package ar.emedicapp.reports;

import static ar.emedicapp.reports.SalesDtos.*;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.AppException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SalesService {
    private static final int MOVEMENT_LIMIT = 500;
    private static final String MOVEMENTS =
            """
            WITH movements AS (
                SELECT p.id, 'PAYMENT'::text AS kind, p.order_id, o.purchase_id, p.branch_id,
                    p.amount, p.currency, p.verified_at, p.external_reference AS reference
                FROM payment p JOIN child_order o ON o.id=p.order_id
                WHERE p.branch_id IN (:branches) AND p.verified_at >= :from
                    AND p.verified_at < :to
                    AND p.status IN ('VERIFIED','PARTIALLY_REFUNDED','REFUNDED')
                UNION ALL
                SELECT r.id, 'REFUND'::text, p.order_id, o.purchase_id, p.branch_id,
                    r.amount, r.currency, r.verified_at, r.external_reference
                FROM refund r JOIN payment p ON p.id=r.payment_id
                    JOIN child_order o ON o.id=p.order_id
                WHERE p.branch_id IN (:branches) AND r.verified_at >= :from
                    AND r.verified_at < :to AND r.status='VERIFIED'
            )
            """;
    private static final String AGGREGATE =
            """
            currency,
            COALESCE(sum(CASE WHEN kind='PAYMENT' THEN amount ELSE 0 END),0) AS gross,
            COALESCE(sum(CASE WHEN kind='REFUND' THEN amount ELSE 0 END),0) AS refunds,
            count(DISTINCT CASE WHEN kind='PAYMENT' THEN order_id END) AS paid_orders,
            count(*) FILTER (WHERE kind='PAYMENT') AS payment_count,
            count(*) FILTER (WHERE kind='REFUND') AS refund_count
            """;

    private final JdbcClient jdbc;
    private final Access access;
    private final Clock clock;

    public SalesService(JdbcClient jdbc, Access access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(Period period, LocalDate selectedDate, UUID branchId) {
        var actor = access.actor();
        actor.requireRole("ADMIN", "PHARMACY_ADMIN", "PHARMACY", "PHARMACIST");
        var date = selectedDate == null ? LocalDate.now(clock) : selectedDate;
        if (date.getYear() < 1900 || date.getYear() > 9998)
            throw AppException.badRequest(
                    "REPORT_DATE_INVALID", "Elegí una fecha entre 1900 y 9998.");
        var range = range(period, date, clock);
        String scope =
                actor.hasRole("ADMIN")
                        ? ""
                        : """
                         WHERE EXISTS (SELECT 1 FROM membership m
                            JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role
                            WHERE m.user_id=:actor AND m.branch_id=b.id AND m.active
                                AND m.role IN ('PHARMACY_ADMIN','PHARMACY','PHARMACIST'))
                         """;
        var branchQuery =
                jdbc.sql(
                        "SELECT b.id,b.chain_id,c.name AS chain_name,b.name,b.address"
                                + " FROM branch b LEFT JOIN chain c ON c.id=b.chain_id "
                                + scope
                                + " ORDER BY c.name NULLS LAST,b.name,b.id");
        if (!actor.hasRole("ADMIN")) branchQuery.param("actor", actor.id());
        var branches =
                branchQuery
                        .query(
                                (rs, row) ->
                                        new BranchOption(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("chain_id", UUID.class),
                                                rs.getString("chain_name"),
                                                rs.getString("name"),
                                                rs.getString("address")))
                        .list();
        if (branchId != null && branches.stream().noneMatch(b -> b.id().equals(branchId)))
            throw AppException.forbidden(
                    "BRANCH_ACCESS_DENIED", "No tenés acceso a esta sucursal.");
        var selected = branches.stream().filter(b -> branchId == null || b.id().equals(branchId)).toList();
        var ranges = bucketRanges(period, range);
        if (selected.isEmpty())
            return new Report(
                    period,
                    date,
                    range.from().toInstant(),
                    range.to().toInstant(),
                    clock.getZone().getId(),
                    branches,
                    List.of(),
                    ranges.stream()
                            .map(b -> new Bucket(b.from().toInstant(), b.to().toInstant(), b.label(), List.of()))
                            .toList(),
                    List.of(),
                    List.of(),
                    0,
                    false);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("branches", selected.stream().map(BranchOption::id).toList());
        parameters.put("from", range.from().toOffsetDateTime());
        parameters.put("to", range.to().toOffsetDateTime());
        var totals =
                jdbc.sql(MOVEMENTS + " SELECT " + AGGREGATE + " FROM movements GROUP BY currency ORDER BY currency")
                        .params(parameters)
                        .query((rs, row) -> totals(rs))
                        .list();
        var currencies = totals.stream().map(Totals::currency).toList();
        var byBranchRows =
                jdbc.sql(MOVEMENTS + " SELECT branch_id," + AGGREGATE + " FROM movements GROUP BY branch_id,currency")
                        .params(parameters)
                        .query((rs, row) -> Map.entry(rs.getObject("branch_id", UUID.class), totals(rs)))
                        .list();
        Map<UUID, Map<String, Totals>> branchTotals = new LinkedHashMap<>();
        for (var row : byBranchRows)
            branchTotals.computeIfAbsent(row.getKey(), ignored -> new LinkedHashMap<>())
                    .put(row.getValue().currency(), row.getValue());
        var byBranch =
                selected.stream()
                        .map(b -> new BranchTotals(b.id(), b.name(), b.chainName(), filled(currencies, branchTotals.get(b.id()))))
                        .toList();

        // Explicit instant boundaries preserve shorter/longer calendar days in the business zone.
        var values = new ArrayList<String>();
        for (int i = 0; i < ranges.size(); i++) {
            values.add("(" + i + ",cast(:start" + i + " as timestamptz),cast(:end" + i + " as timestamptz))");
            parameters.put("start" + i, ranges.get(i).from().toOffsetDateTime());
            parameters.put("end" + i, ranges.get(i).to().toOffsetDateTime());
        }
        var bucketRows =
                jdbc.sql(
                                MOVEMENTS
                                        + ", bucket_ranges(idx,starts_at,ends_at) AS (VALUES "
                                        + String.join(",", values)
                                        + ") SELECT idx,"
                                        + AGGREGATE
                                        + " FROM movements JOIN bucket_ranges ON verified_at>=starts_at"
                                        + " AND verified_at<ends_at GROUP BY idx,currency")
                        .params(parameters)
                        .query((rs, row) -> Map.entry(rs.getInt("idx"), totals(rs)))
                        .list();
        Map<Integer, Map<String, Totals>> bucketTotals = new LinkedHashMap<>();
        for (var row : bucketRows)
            bucketTotals.computeIfAbsent(row.getKey(), ignored -> new LinkedHashMap<>())
                    .put(row.getValue().currency(), row.getValue());
        var buckets = new ArrayList<Bucket>();
        for (int i = 0; i < ranges.size(); i++) {
            var b = ranges.get(i);
            buckets.add(new Bucket(b.from().toInstant(), b.to().toInstant(), b.label(), filled(currencies, bucketTotals.get(i))));
        }
        var movements =
                jdbc.sql(
                                MOVEMENTS
                                        + " SELECT m.*,b.name AS branch_name,c.name AS chain_name FROM movements m"
                                        + " JOIN branch b ON b.id=m.branch_id LEFT JOIN chain c ON c.id=b.chain_id"
                                        + " ORDER BY m.verified_at DESC,m.id DESC LIMIT "
                                        + MOVEMENT_LIMIT)
                        .params(parameters)
                        .query(
                                (rs, row) ->
                                        new Movement(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("kind"),
                                                rs.getObject("order_id", UUID.class),
                                                rs.getObject("purchase_id", UUID.class),
                                                rs.getObject("branch_id", UUID.class),
                                                rs.getString("branch_name"),
                                                rs.getString("chain_name"),
                                                decimal(rs.getBigDecimal("amount")),
                                                rs.getString("currency"),
                                                rs.getTimestamp("verified_at").toInstant(),
                                                rs.getString("reference")))
                        .list();
        long movementCount = totals.stream().mapToLong(t -> t.paymentCount() + t.refundCount()).sum();
        return new Report(
                period,
                date,
                range.from().toInstant(),
                range.to().toInstant(),
                clock.getZone().getId(),
                branches,
                totals,
                buckets,
                byBranch,
                movements,
                movementCount,
                movementCount > movements.size());
    }

    record Range(ZonedDateTime from, ZonedDateTime to) {}

    record BucketRange(ZonedDateTime from, ZonedDateTime to, String label) {}

    static Range range(Period period, LocalDate date, Clock clock) {
        LocalDate start =
                switch (period) {
                    case DAY -> date;
                    case WEEK -> date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                    case MONTH -> date.withDayOfMonth(1);
                    case YEAR -> date.withDayOfYear(1);
                };
        var end =
                switch (period) {
                    case DAY -> start.plusDays(1);
                    case WEEK -> start.plusWeeks(1);
                    case MONTH -> start.plusMonths(1);
                    case YEAR -> start.plusYears(1);
                };
        return new Range(start.atStartOfDay(clock.getZone()), end.atStartOfDay(clock.getZone()));
    }

    static List<BucketRange> bucketRanges(Period period, Range range) {
        var buckets = new ArrayList<BucketRange>();
        for (var start = range.from(); start.isBefore(range.to()); ) {
            var next =
                    switch (period) {
                        case DAY -> start.plusHours(1);
                        case WEEK, MONTH -> start.toLocalDate().plusDays(1).atStartOfDay(start.getZone());
                        case YEAR -> start.toLocalDate().plusMonths(1).atStartOfDay(start.getZone());
                    };
            if (next.isAfter(range.to())) next = range.to();
            String label =
                    switch (period) {
                        case DAY -> start.format(DateTimeFormatter.ofPattern("HH:mm"));
                        case WEEK, MONTH -> start.toLocalDate().toString();
                        case YEAR -> start.format(DateTimeFormatter.ofPattern("uuuu-MM"));
                    };
            buckets.add(new BucketRange(start, next, label));
            start = next;
        }
        return buckets;
    }

    private static Totals totals(ResultSet rs) throws SQLException {
        var gross = rs.getBigDecimal("gross");
        var refunds = rs.getBigDecimal("refunds");
        return new Totals(
                rs.getString("currency"),
                decimal(gross),
                decimal(refunds),
                decimal(gross.subtract(refunds)),
                rs.getLong("paid_orders"),
                rs.getLong("payment_count"),
                rs.getLong("refund_count"));
    }

    private static List<Totals> filled(List<String> currencies, Map<String, Totals> values) {
        return currencies.stream()
                .map(currency -> values == null || !values.containsKey(currency)
                        ? new Totals(currency, "0.00", "0.00", "0.00", 0, 0, 0)
                        : values.get(currency))
                .toList();
    }

    private static String decimal(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
