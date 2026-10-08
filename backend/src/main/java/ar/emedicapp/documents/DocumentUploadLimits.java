package ar.emedicapp.documents;

import ar.emedicapp.common.AppException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DocumentUploadLimits {
    private static final int MAX_USERS = 5000;
    private static final int UPLOADS_PER_WINDOW = 30;
    private static final long WINDOW_MILLIS = 60_000;
    private final long userQuotaBytes;
    private final long totalQuotaBytes;
    private final boolean publicAccess;
    private final Clock clock;
    private final LinkedHashMap<UUID, Window> attempts = new LinkedHashMap<>(16, 0.75f, true);

    public DocumentUploadLimits(
            @Value("${EMEDICAPP_DOCUMENT_USER_QUOTA_BYTES:262144000}") long userQuotaBytes,
            @Value("${EMEDICAPP_DOCUMENT_TOTAL_QUOTA_BYTES:5368709120}") long totalQuotaBytes,
            @Value("${EMEDICAPP_PUBLIC_URL:}") String publicUrl,
            Clock clock) {
        if (userQuotaBytes < 1 || totalQuotaBytes < 1)
            throw new IllegalArgumentException("Las cuotas de documentos deben ser positivas.");
        this.userQuotaBytes = userQuotaBytes;
        this.totalQuotaBytes = totalQuotaBytes;
        this.publicAccess = !publicUrl.isBlank();
        this.clock = clock;
    }

    synchronized void requireUploadAllowed(UUID userId) {
        if (!publicAccess) return;
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().since >= WINDOW_MILLIS);
        var window = attempts.computeIfAbsent(userId, ignored -> new Window(now));
        if (attempts.size() > MAX_USERS) attempts.remove(attempts.keySet().iterator().next());
        if (++window.count > UPLOADS_PER_WINDOW)
            throw new AppException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "DOCUMENT_UPLOAD_RATE_LIMIT",
                    "Alcanzaste el límite de 30 cargas por minuto. Esperá un minuto y reintentá.");
    }

    void requireStorageAvailable(DocumentRepository.StorageUsage usage, long incomingBytes) {
        if (incomingBytes > userQuotaBytes || usage.userBytes() > userQuotaBytes - incomingBytes)
            throw new AppException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "DOCUMENT_USER_STORAGE_LIMIT",
                    "Alcanzaste el espacio disponible para documentos de tu cuenta. Contactá a administración.");
        if (incomingBytes > totalQuotaBytes || usage.totalBytes() > totalQuotaBytes - incomingBytes)
            throw new AppException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "DOCUMENT_TOTAL_STORAGE_LIMIT",
                    "El sistema alcanzó su capacidad de documentos. Contactá a administración para ampliar el espacio.");
    }

    private static final class Window {
        private final long since;
        private int count;

        private Window(long since) {
            this.since = since;
        }
    }
}
