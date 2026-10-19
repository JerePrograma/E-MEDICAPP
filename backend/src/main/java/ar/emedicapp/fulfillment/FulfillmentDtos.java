package ar.emedicapp.fulfillment;

import java.math.BigDecimal;
import java.util.UUID;

public final class FulfillmentDtos {
    private FulfillmentDtos() {}

    public record Rejection(long expectedVersion, String reason) {}

    public record ReturnReceived(
            long expectedVersion,
            String custodyNote,
            UUID evidenceDocumentId,
            BigDecimal temperatureC) {}

    public record Receipt(
            long expectedVersion,
            String code,
            Boolean recipientVerified,
            String note,
            UUID conditionEvidenceDocumentId,
            BigDecimal temperatureC,
            String recipientName,
            String recipientIdentity,
            UUID authorizationDocumentId,
            Boolean packageMatches) {}
}
