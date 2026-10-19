package ar.emedicapp.replacements;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReplacementDtos {
    private ReplacementDtos() {}

    public record Line(UUID lineId, int quantity) {}

    public record Propose(
            long expectedVersion,
            List<Line> lines,
            String reason,
            UUID evidenceDocumentId,
            String originalDisposition,
            String dispositionNote,
            String mode,
            String deliveryAddress,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer deliveryMinutes) {}

    public record Accept(
            long expectedVersion,
            String receiverName,
            String receiverIdentity,
            UUID prescriptionDocumentId,
            UUID authorizationDocumentId) {}

    public record Reject(long expectedVersion, String reason) {}

    public record ReviewLine(UUID lineId, Integer authorizedQuantity) {}

    public record Review(
            long expectedVersion,
            String reason,
            Boolean originVerified,
            Boolean authorizationChecked,
            String prescriptionReference,
            String issuer,
            Instant expiresAt,
            UUID conditionEvidenceDocumentId,
            List<ReviewLine> lines) {}
}
