package ar.emedicapp.documents;

import java.time.Instant;
import java.util.UUID;

public record DocumentMetadata(
        UUID id,
        UUID ownerUserId,
        String kind,
        String documentType,
        UUID branchId,
        String originalName,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        String downloadUrl) {}
