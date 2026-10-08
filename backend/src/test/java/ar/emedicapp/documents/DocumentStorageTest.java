package ar.emedicapp.documents;

import ar.emedicapp.common.AppException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentStorageTest {
    @TempDir Path directory;

    @Test
    void rejectsHtmlEvenWhenClientCallsItPdf() throws Exception {
        var storage = new DocumentStorage(directory.toString());
        var file =
                new MockMultipartFile(
                        "file", "receta.pdf", "application/pdf", "<html>unsafe</html>".getBytes());
        assertThatThrownBy(() -> storage.write(UUID.randomUUID(), file))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("UNSUPPORTED_DOCUMENT"));
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void storesByUuidAndNeverOverwrites() throws Exception {
        var storage = new DocumentStorage(directory.toString());
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4\nFICTICIO".getBytes();
        var file = new MockMultipartFile("file", "../../receta.pdf", "application/pdf", pdf);
        var stored = storage.write(id, file);
        assertThat(stored.key()).isEqualTo(id.toString());
        assertThat(Files.readAllBytes(directory.resolve(id.toString()))).isEqualTo(pdf);
        assertThatThrownBy(() -> storage.write(id, file))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(directory.resolve(id.toString()))).isEqualTo(pdf);
    }

    @Test
    void rejectsContentAboveLimit() throws Exception {
        var storage = new DocumentStorage(directory.toString());
        var file =
                new MockMultipartFile(
                        "file", "large.pdf", "application/pdf", "%PDF-1.4".getBytes()) {
                    @Override
                    public long getSize() {
                        return DocumentStorage.MAX_SIZE + 1;
                    }
                };
        assertThatThrownBy(() -> storage.write(UUID.randomUUID(), file))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_TOO_LARGE"));
    }

    @Test
    void refusesDatabasePathTraversal() throws Exception {
        var storage = new DocumentStorage(directory.toString());
        var file =
                new DocumentFile(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PRESCRIPTION",
                        null,
                        "receta.pdf",
                        "application/pdf",
                        10,
                        "../private-file",
                        null,
                        java.time.Instant.now());
        assertThatThrownBy(() -> storage.read(file)).isInstanceOf(AppException.class);
    }

    @Test
    void preservesConfiguredFreeSpaceBeforeCreatingAnyFile() throws Exception {
        byte[] pdf = "%PDF-1.4\nFICTICIO".getBytes();
        var storage = spy(new DocumentStorage(directory.toString(), 1024));
        doReturn(1024L + pdf.length - 1).when(storage).usableSpace();
        assertThatThrownBy(() -> storage.writeBytes(UUID.randomUUID(), pdf))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_STORAGE_LOW_SPACE"));
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
        doReturn(1024L + pdf.length).when(storage).usableSpace();
        var stored = storage.writeBytes(UUID.randomUUID(), pdf);
        assertThat(Files.size(directory.resolve(stored.key()))).isEqualTo(pdf.length);
    }

    @Test
    void readsActualBytesBeforeQuotaCalculationDespiteIncorrectDeclaredSize() throws Exception {
        byte[] pdf = "%PDF-1.4\nFICTICIO".getBytes();
        var file =
                new MockMultipartFile("file", "receta.pdf", "application/pdf", pdf) {
                    @Override
                    public long getSize() {
                        return 1;
                    }
                };
        var storage = new DocumentStorage(directory.toString());
        assertThat(storage.readUpload(file, 100)).hasSize(pdf.length);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void obtainsUsableSpaceFromTheActualDocumentFileStore() throws Exception {
        var storage = new DocumentStorage(directory.toString());
        assertThat(storage.usableSpace()).isPositive();
    }

    @Test
    void removesNewFileIfTheVolumeFallsBelowReserveDuringTheWrite() throws Exception {
        byte[] pdf = "%PDF-1.4\nFICTICIO".getBytes();
        var storage = spy(new DocumentStorage(directory.toString(), 1024));
        doReturn(4096L, 1023L).when(storage).usableSpace();
        assertThatThrownBy(() -> storage.writeBytes(UUID.randomUUID(), pdf))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_STORAGE_LOW_SPACE"));
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
    }
}
