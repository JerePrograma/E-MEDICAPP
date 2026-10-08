package ar.emedicapp.documents;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.config.OperationalConfigService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentUploadLimitsTest {
    @TempDir Path directory;

    @Test
    void rejectsUserQuotaWithoutWritingMetadataOrLeavingFiles() throws Exception {
        rejectsQuota(
                new DocumentRepository.StorageUsage(91, 91),
                100,
                1000,
                "DOCUMENT_USER_STORAGE_LIMIT");
    }

    @Test
    void rejectsTotalQuotaWithoutWritingMetadataOrLeavingFiles() throws Exception {
        rejectsQuota(
                new DocumentRepository.StorageUsage(0, 991),
                1000,
                1000,
                "DOCUMENT_TOTAL_STORAGE_LIMIT");
    }

    private void rejectsQuota(
            DocumentRepository.StorageUsage usage,
            long userQuota,
            long totalQuota,
            String expectedCode)
            throws Exception {
        UUID owner = UUID.randomUUID();
        var access = mock(Access.class);
        when(access.actor())
                .thenReturn(
                        new Actor(
                                owner, "quota@example.test", "Persona", Set.of("BUYER"), Set.of()));
        var documents = mock(DocumentRepository.class);
        when(documents.storageUsage(owner)).thenReturn(usage);
        var storage = spy(new DocumentStorage(directory.toString(), 0));
        var audit = mock(AuditLog.class);
        var config = mock(OperationalConfigService.class);
        when(config.current())
                .thenReturn(
                        new OperationalConfigService.Settings(
                                1, "ARS", 20, 30, 7, "1000000", 1, true, true, true, Map.of()));
        var limits = new DocumentUploadLimits(userQuota, totalQuota, "", Clock.systemUTC());
        var service = new DocumentService(access, documents, storage, audit, config, limits);
        var file =
                new MockMultipartFile(
                        "file", "receta.pdf", "application/pdf", "%PDF-1.4\nFICTICIO".getBytes()) {
                    @Override
                    public long getSize() {
                        return 1;
                    }
                };
        assertThatThrownBy(() -> service.upload(file, "RECETA", null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo(expectedCode));
        var order = inOrder(documents);
        order.verify(documents).lockStorageQuota();
        order.verify(documents).storageUsage(owner);
        verify(storage, never()).writeBytes(any(), any());
        verify(documents, never()).create(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(audit);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void allowsExactQuotaAndRejectsAnAdditionalByteWithoutOverflow() {
        var limits = new DocumentUploadLimits(100, 200, "", Clock.systemUTC());
        limits.requireStorageAvailable(new DocumentRepository.StorageUsage(90, 190), 10);
        assertThatThrownBy(
                        () ->
                                limits.requireStorageAvailable(
                                        new DocumentRepository.StorageUsage(90, 190), 11))
                .isInstanceOf(AppException.class);
        var maximum =
                new DocumentUploadLimits(Long.MAX_VALUE, Long.MAX_VALUE, "", Clock.systemUTC());
        assertThatThrownBy(
                        () ->
                                maximum.requireStorageAvailable(
                                        new DocumentRepository.StorageUsage(
                                                Long.MAX_VALUE - 1, Long.MAX_VALUE - 1),
                                        2))
                .isInstanceOf(AppException.class);
    }

    @Test
    void publicLimitIsPerUserAndResetsAfterSixtySeconds() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var limits = new DocumentUploadLimits(100, 200, "https://demo.example.test", clock);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        for (int i = 0; i < 30; i++) limits.requireUploadAllowed(first);
        assertThatThrownBy(() -> limits.requireUploadAllowed(first))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("DOCUMENT_UPLOAD_RATE_LIMIT");
                            assertThat(error.status().value()).isEqualTo(429);
                        });
        limits.requireUploadAllowed(second);
        when(clock.millis()).thenReturn(59_999L);
        assertThatThrownBy(() -> limits.requireUploadAllowed(first))
                .isInstanceOf(AppException.class);
        when(clock.millis()).thenReturn(60_000L);
        limits.requireUploadAllowed(first);
    }

    @Test
    void localModeDoesNotLimitRepeatedUploads() {
        var limits = new DocumentUploadLimits(100, 200, "  ", Clock.systemUTC());
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 100; i++) limits.requireUploadAllowed(user);
    }

    @Test
    void parallelPublicAttemptsHaveOnlyThirtyWinners() throws Exception {
        var limits =
                new DocumentUploadLimits(100, 200, "https://demo.example.test", Clock.systemUTC());
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        UUID user = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 60; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        limits.requireUploadAllowed(user);
                                        return true;
                                    } catch (AppException expected) {
                                        assertThat(expected.code())
                                                .isEqualTo("DOCUMENT_UPLOAD_RATE_LIMIT");
                                        return false;
                                    }
                                }));
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(30);
        }
    }
}
