package ar.emedicapp.documents;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.config.OperationalConfigService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentUploadLimitTest {
    @Test
    void respectsReducedOperationalLimitBeforeWritingOrPersisting() {
        var access = mock(Access.class);
        var documents = mock(DocumentRepository.class);
        var storage = mock(DocumentStorage.class);
        var audit = mock(AuditLog.class);
        var config = mock(OperationalConfigService.class);
        when(access.actor())
                .thenReturn(
                        new Actor(
                                UUID.randomUUID(),
                                "buyer@example.test",
                                "Persona",
                                Set.of("BUYER"),
                                Set.of()));
        when(config.current())
                .thenReturn(
                        new OperationalConfigService.Settings(
                                1, "ARS", 20, 30, 7, "1000000", 1, true, true, true, Map.of()));
        var file =
                new MockMultipartFile(
                        "file", "receta.pdf", "application/pdf", "%PDF-1.4".getBytes()) {
                    @Override
                    public long getSize() {
                        return 1024L * 1024 + 1;
                    }
                };
        var service =
                new DocumentService(
                        access,
                        documents,
                        storage,
                        audit,
                        config,
                        mock(DocumentUploadLimits.class));
        assertThatThrownBy(() -> service.upload(file, "RECETA", null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("DOCUMENT_TOO_LARGE");
                            assertThat(error.getMessage()).contains("1 MB");
                        });
        verifyNoInteractions(storage, documents, audit);
    }
}
