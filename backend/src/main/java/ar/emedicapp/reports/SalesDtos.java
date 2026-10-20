package ar.emedicapp.reports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SalesDtos {
    private SalesDtos() {}

    public enum Period {
        DAY,
        WEEK,
        MONTH,
        YEAR
    }

    public record BranchOption(UUID id, UUID chainId, String chainName, String name, String address) {}

    /** Cash movements in one currency; amounts are strings to preserve exact decimal values. */
    public record Totals(
            String currency,
            String gross,
            String refunds,
            String net,
            long paidOrders,
            long paymentCount,
            long refundCount) {}

    public record Bucket(Instant from, Instant toExclusive, String label, List<Totals> totals) {}

    public record BranchTotals(
            UUID branchId, String branchName, String chainName, List<Totals> totals) {}

    public record Movement(
            UUID id,
            String kind,
            UUID orderId,
            UUID purchaseId,
            UUID branchId,
            String branchName,
            String chainName,
            String amount,
            String currency,
            Instant verifiedAt,
            String reference) {}

    public record Report(
            Period period,
            LocalDate date,
            Instant from,
            Instant toExclusive,
            String timeZone,
            List<BranchOption> branches,
            List<Totals> totals,
            List<Bucket> buckets,
            List<BranchTotals> byBranch,
            List<Movement> movements,
            long movementCount,
            boolean movementsTruncated) {}
}
