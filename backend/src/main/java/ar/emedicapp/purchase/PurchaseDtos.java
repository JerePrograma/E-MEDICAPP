package ar.emedicapp.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PurchaseDtos {
    private PurchaseDtos() {}

    public record Item(UUID productId, int quantity) {}

    public record Create(
            String beneficiaryName,
            LocalDate beneficiaryBirthDate,
            Boolean thirdParty,
            UUID authorizationDocumentId,
            String mode,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String destinationAddress,
            UUID preferredBranchId,
            UUID prescriptionDocumentId,
            List<Item> items,
            String coverageProvider,
            String coveragePlan,
            String coverageMemberNumber,
            UUID coverageDocumentId) {
        public Create {
            thirdParty = Boolean.TRUE.equals(thirdParty);
        }
    }

    public record Version(long expectedVersion) {}

    public record ReviewLine(
            UUID productId,
            BigDecimal coverageAmount,
            String coverageReference,
            UUID coverageEvidenceDocumentId,
            Integer authorizedQuantity) {}

    public record Review(
            long expectedVersion,
            String decision,
            String reason,
            String prescriptionReference,
            String prescriptionIssuer,
            OffsetDateTime prescriptionExpiresAt,
            Boolean originVerified,
            Boolean authorizationChecked,
            UUID conditionEvidenceDocumentId,
            List<ReviewLine> lines) {
        public Review {
            originVerified = Boolean.TRUE.equals(originVerified);
            authorizationChecked = Boolean.TRUE.equals(authorizationChecked);
        }
    }

    public record Payment(
            long expectedVersion,
            String reference,
            UUID evidenceDocumentId,
            Boolean verifiedExternally) {
        public Payment {
            verifiedExternally = Boolean.TRUE.equals(verifiedExternally);
        }
    }

    public record PaymentReport(long expectedVersion, String reference, UUID evidenceDocumentId) {}

    public record PaymentReconciliation(
            long expectedVersion,
            String decision,
            String reference,
            UUID evidenceDocumentId,
            Boolean verifiedExternally,
            String reason) {}

    public record Dispense(
            long expectedVersion,
            String prescriptionConsumptionReference,
            UUID conditionEvidenceDocumentId,
            BigDecimal temperatureC,
            String conditionNote) {}

    public record Assign(long expectedVersion, UUID driverId) {}

    public record Pickup(
            long expectedVersion,
            String custodyNote,
            UUID conditionEvidenceDocumentId,
            BigDecimal temperatureC) {}

    public record Location(BigDecimal lat, BigDecimal lon, BigDecimal accuracy) {}

    public record Complete(
            long expectedVersion,
            String code,
            Boolean recipientVerified,
            String note,
            UUID conditionEvidenceDocumentId,
            BigDecimal temperatureC) {
        public Complete {
            recipientVerified = Boolean.TRUE.equals(recipientVerified);
        }
    }

    public record Resolve(
            long expectedVersion,
            String decision,
            String reason,
            UUID evidenceDocumentId,
            BigDecimal temperatureC) {}

    public record Cancel(long expectedVersion, String reason) {}

    public record Incident(long expectedVersion, String reason, UUID evidenceDocumentId) {}
}
