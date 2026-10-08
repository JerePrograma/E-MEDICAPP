package ar.emedicapp.documents;

import java.time.Instant;
import java.util.UUID;

record DocumentFile(
        UUID id,
        UUID ownerUserId,
        String kind,
        UUID branchId,
        String originalName,
        String contentType,
        long sizeBytes,
        String storageKey,
        String sha256,
        Instant createdAt) {
    DocumentMetadata metadata() {
        return new DocumentMetadata(
                id,
                ownerUserId,
                DocumentKind.publicValue(kind),
                kind,
                branchId,
                originalName,
                contentType,
                sizeBytes,
                createdAt,
                "/api/documents/" + id);
    }

    boolean health() {
        return kind.equals("PRESCRIPTION")
                || kind.equals("FAMILY_AUTHORIZATION")
                || kind.equals("HEALTH_COVERAGE");
    }
}
