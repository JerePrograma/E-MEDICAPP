package ar.emedicapp.documents;

import ar.emedicapp.common.AppException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DocumentStorage {
    public static final long MAX_SIZE = 50L * 1024 * 1024;
    private final Path root;
    private final long minimumFreeBytes;

    @Autowired
    public DocumentStorage(
            @Value("${emedicapp.documents.storage-dir:./.runtime/documents}") String directory,
            @Value("${EMEDICAPP_DOCUMENT_MIN_FREE_BYTES:1073741824}") long minimumFreeBytes)
            throws IOException {
        if (minimumFreeBytes < 0)
            throw new IllegalArgumentException(
                    "La reserva de espacio libre no puede ser negativa.");
        var target = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(target);
        root = target.toRealPath();
        this.minimumFreeBytes = minimumFreeBytes;
    }

    DocumentStorage(String directory) throws IOException {
        this(directory, 1024L * 1024 * 1024);
    }

    Stored write(UUID id, MultipartFile file) throws IOException {
        return write(id, file, MAX_SIZE);
    }

    Stored write(UUID id, MultipartFile file, long configuredLimit) throws IOException {
        return writeBytes(id, readUpload(file, configuredLimit));
    }

    byte[] readUpload(MultipartFile file, long configuredLimit) throws IOException {
        long limit = Math.max(1, Math.min(configuredLimit, MAX_SIZE));
        if (file == null || file.isEmpty())
            throw AppException.badRequest("EMPTY_DOCUMENT", "Seleccioná un archivo.");
        if (file.getSize() > limit) throw tooLarge(limit);
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes((int) limit + 1);
        }
        if (bytes.length > limit) throw tooLarge(limit);
        if (bytes.length == 0)
            throw AppException.badRequest("EMPTY_DOCUMENT", "Seleccioná un archivo.");
        detectContentType(bytes);
        return bytes;
    }

    private static AppException tooLarge(long limit) {
        return AppException.badRequest(
                "DOCUMENT_TOO_LARGE",
                "El archivo puede tener hasta "
                        + (limit / (1024 * 1024))
                        + " MB según la configuración vigente.");
    }

    public Stored writeBytes(UUID id, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_SIZE)
            throw AppException.badRequest(
                    "DOCUMENT_TOO_LARGE", "El archivo debe tener entre 1 byte y 50 MB.");
        String contentType = detectContentType(bytes);
        String storageKey = id.toString();
        long usableBytes = usableSpace();
        if (usableBytes < bytes.length || usableBytes - bytes.length < minimumFreeBytes)
            throw lowSpace();
        Path target = path(storageKey);
        boolean created = false;
        try {
            try (var output =
                    Files.newOutputStream(
                            target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(bytes);
            }
            if (usableSpace() < minimumFreeBytes) throw lowSpace();
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
        return new Stored(storageKey, contentType, bytes.length, sha256(bytes));
    }

    long usableSpace() throws IOException {
        return Files.getFileStore(root).getUsableSpace();
    }

    private static AppException lowSpace() {
        return new AppException(
                HttpStatus.INSUFFICIENT_STORAGE,
                "DOCUMENT_STORAGE_LOW_SPACE",
                "No hay espacio libre suficiente para guardar el documento de forma segura. Contactá a administración.");
    }

    byte[] read(DocumentFile file) throws IOException {
        Path path = path(file.storageKey());
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw AppException.notFound(
                    "DOCUMENT_FILE_NOT_FOUND",
                    "El archivo no está disponible. Contactá a soporte.");
        if (Files.size(path) != file.sizeBytes() || Files.size(path) > MAX_SIZE)
            throw AppException.conflict(
                    "DOCUMENT_INTEGRITY_ERROR", "No se pudo comprobar la integridad del archivo.");
        byte[] bytes = Files.readAllBytes(path);
        if (file.sha256() != null
                && !MessageDigest.isEqual(
                        file.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw AppException.conflict(
                    "DOCUMENT_INTEGRITY_ERROR", "No se pudo comprobar la integridad del archivo.");
        return bytes;
    }

    void delete(String storageKey) throws IOException {
        Files.deleteIfExists(path(storageKey));
    }

    public void deleteDemoFile(String storageKey) throws IOException {
        delete(storageKey);
    }

    private Path path(String storageKey) {
        if (!storageKey.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw AppException.conflict("INVALID_STORAGE_KEY", "El archivo no está disponible.");
        Path result = root.resolve(storageKey).normalize();
        if (!result.getParent().equals(root))
            throw AppException.forbidden("INVALID_DOCUMENT_PATH", "No se pudo acceder al archivo.");
        return result;
    }

    static String detectContentType(byte[] bytes) {
        if (startsWith(bytes, new byte[] {37, 80, 68, 70, 45})) return "application/pdf";
        if (startsWith(bytes, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
            return "image/png";
        if (startsWith(bytes, new byte[] {(byte) 255, (byte) 216, (byte) 255})) return "image/jpeg";
        throw AppException.badRequest(
                "UNSUPPORTED_DOCUMENT",
                "Solo se aceptan archivos PDF, PNG o JPEG con formato reconocible.");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Stored(String key, String contentType, long size, String sha256) {}
}
