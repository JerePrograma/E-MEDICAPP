package ar.emedicapp.documents;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.config.OperationalConfigService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private final Access access;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final AuditLog audit;
    private final OperationalConfigService config;
    private final DocumentUploadLimits limits;

    public DocumentService(
            Access access,
            DocumentRepository documents,
            DocumentStorage storage,
            AuditLog audit,
            OperationalConfigService config,
            DocumentUploadLimits limits) {
        this.access = access;
        this.documents = documents;
        this.storage = storage;
        this.audit = audit;
        this.config = config;
        this.limits = limits;
    }

    @Transactional(rollbackFor = IOException.class)
    public DocumentMetadata upload(MultipartFile file, String publicKind, UUID branchId)
            throws IOException {
        Actor actor = access.actor();
        limits.requireUploadAllowed(actor.id());
        DocumentKind kind = DocumentKind.parse(publicKind);
        if (kind.health() && kind != DocumentKind.COBERTURA && branchId != null)
            throw AppException.badRequest(
                    "HEALTH_DOCUMENT_BRANCH",
                    "La receta, credencial o autorización se asigna a la compra después de cargarla.");
        if ((kind == DocumentKind.FACTURA || kind == DocumentKind.CAPACIDAD) && branchId == null)
            throw AppException.badRequest(
                    "DOCUMENT_BRANCH_REQUIRED", "Indicá la sucursal correspondiente al documento.");
        if (branchId != null) {
            if (kind == DocumentKind.COBERTURA) access.requireBranchRole(branchId, "PHARMACIST");
            else if (kind == DocumentKind.ENTREGA)
                access.requireBranchRole(branchId, "DRIVER", "PHARMACY", "PHARMACIST", "ADMIN");
            else access.requireBranchRole(branchId, "PHARMACY", "PHARMACIST", "ADMIN");
        }
        long limit =
                Math.min(config.current().maxUploadMb() * 1024L * 1024L, DocumentStorage.MAX_SIZE);
        if (file != null && file.getSize() > limit)
            throw AppException.badRequest(
                    "DOCUMENT_TOO_LARGE",
                    "El archivo puede tener hasta "
                            + (limit / (1024 * 1024))
                            + " MB según la configuración vigente.");
        UUID id = UUID.randomUUID();
        byte[] bytes = storage.readUpload(file, limit);
        documents.lockStorageQuota();
        limits.requireStorageAvailable(documents.storageUsage(actor.id()), bytes.length);
        var stored = storage.writeBytes(id, bytes);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) {
                                try {
                                    storage.delete(stored.key());
                                } catch (IOException exception) {
                                    /* Orphan private file; cleanup may retry safely by UUID. */
                                }
                            }
                        }
                    });
        }
        try {
            documents.create(
                    id, actor, kind, branchId, safeName(file.getOriginalFilename()), stored);
            audit.record(
                    actor,
                    "DOCUMENT_UPLOADED",
                    "DOCUMENT",
                    id,
                    Map.of(
                            "kind",
                            kind.name(),
                            "sizeBytes",
                            stored.size(),
                            "validation",
                            "PENDING"));
            return documents.find(id).orElseThrow().metadata();
        } catch (RuntimeException exception) {
            try {
                storage.delete(stored.key());
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public List<DocumentMetadata> list() {
        return documents.owned(access.actor().id());
    }

    public DocumentMetadata metadata(UUID id) {
        return authorized(id, access.actor()).metadata();
    }

    public Download download(UUID id) throws IOException {
        var actor = access.actor();
        var file = authorized(id, actor);
        byte[] bytes = storage.read(file);
        audit.record(actor, "DOCUMENT_DOWNLOADED", "DOCUMENT", id, Map.of("kind", file.kind()));
        return new Download(file.metadata(), bytes);
    }

    private DocumentFile authorized(UUID id, Actor actor) {
        var file =
                documents
                        .find(id)
                        .orElseThrow(
                                () ->
                                        AppException.notFound(
                                                "DOCUMENT_NOT_FOUND",
                                                "No se encontró el documento."));
        if (file.ownerUserId().equals(actor.id())) return file;
        if (file.health()
                || (file.kind().equals("OTHER") && documents.coverageReviewEvidence(id))) {
            if (actor.hasRole("PHARMACIST") && documents.clinicalOrderAccess(id, actor.id()))
                return file;
        } else {
            if (file.branchId() != null
                    && (actor.hasRole("ADMIN")
                            || (actor.hasRole("PHARMACY", "PHARMACIST")
                                    && documents.commercialBranchAccess(
                                            file.branchId(), actor.id())))) return file;
            if (file.kind().equals("INVOICE") && documents.buyerInvoiceAccess(id, actor.id()))
                return file;
            if (file.kind().equals("PAYMENT_RECEIPT")
                    && actor.hasRole("PHARMACY", "PHARMACIST")
                    && documents.paymentOrderAccess(id, actor.id())) return file;
        }
        throw AppException.forbidden("DOCUMENT_ACCESS_DENIED", "No tenés acceso a este documento.");
    }

    private static String safeName(String value) {
        String name = value == null ? "documento" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "documento";
        return name.length() > 150 ? name.substring(0, 150) : name;
    }

    public record Download(DocumentMetadata metadata, byte[] bytes) {}
}
