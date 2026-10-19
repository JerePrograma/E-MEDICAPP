package ar.emedicapp.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}

    public record Confirm(
            long expectedVersion,
            String reference,
            UUID evidenceDocumentId,
            Boolean verifiedExternally,
            BigDecimal amount,
            String currency,
            String method) {
        public Confirm(
                long expectedVersion,
                String reference,
                UUID evidenceDocumentId,
                Boolean verifiedExternally,
                BigDecimal amount,
                String currency) {
            this(
                    expectedVersion,
                    reference,
                    evidenceDocumentId,
                    verifiedExternally,
                    amount,
                    currency,
                    "EXTERNAL_MANUAL");
        }
    }

    public record Reconcile(
            long expectedVersion,
            String decision,
            String reference,
            UUID evidenceDocumentId,
            Boolean verifiedExternally,
            String reason,
            BigDecimal amount,
            String currency,
            String method) {
        public Reconcile(
                long expectedVersion,
                String decision,
                String reference,
                UUID evidenceDocumentId,
                Boolean verifiedExternally,
                String reason,
                BigDecimal amount,
                String currency) {
            this(
                    expectedVersion,
                    decision,
                    reference,
                    evidenceDocumentId,
                    verifiedExternally,
                    reason,
                    amount,
                    currency,
                    "EXTERNAL_MANUAL");
        }
    }

    public record RefundRequest(
            long expectedVersion,
            BigDecimal amount,
            String currency,
            String reason,
            UUID evidenceDocumentId,
            List<UUID> lineIds) {
        public RefundRequest {
            lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
        }
    }

    public record RefundConfirm(
            long expectedVersion,
            UUID refundId,
            BigDecimal amount,
            String currency,
            String reference,
            UUID evidenceDocumentId,
            Boolean verifiedExternally) {}

    public record Control(
            long expectedVersion, String operation, String reason, UUID evidenceDocumentId) {}

    public record FiscalAdjustment(
            long expectedVersion,
            String kind,
            String reference,
            BigDecimal amount,
            String currency,
            UUID evidenceDocumentId,
            String reason) {}

    public record InvoiceIssue(long expectedVersion, String reason, UUID evidenceDocumentId) {}

    public record Incident(
            long expectedVersion, String kind, String reason, UUID evidenceDocumentId) {}

    public record Resolution(
            long expectedVersion,
            String decision,
            String reason,
            UUID evidenceDocumentId,
            BigDecimal temperatureC,
            java.time.Instant retryScheduledAt,
            String coordinationNote) {}
}
