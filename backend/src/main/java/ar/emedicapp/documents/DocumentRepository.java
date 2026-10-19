package ar.emedicapp.documents;

import ar.emedicapp.common.Actor;
import ar.emedicapp.common.Json;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private static final RowMapper<DocumentFile> MAPPER =
            (rs, row) ->
                    new DocumentFile(
                            rs.getObject("id", UUID.class),
                            rs.getObject("owner_user_id", UUID.class),
                            rs.getString("kind"),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("original_name"),
                            rs.getString("content_type"),
                            rs.getLong("size_bytes"),
                            rs.getString("storage_key"),
                            rs.getString("sha256"),
                            rs.getTimestamp("created_at").toInstant());
    private final JdbcClient jdbc;
    private final Json json;

    public DocumentRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void lockStorageQuota() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("La carga de documentos requiere una transacción.");
        jdbc.sql("SELECT pg_advisory_xact_lock(736202)").query(Object.class).list();
    }

    StorageUsage storageUsage(UUID ownerId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(size_bytes) FILTER (WHERE owner_user_id=:owner),0) AS user_bytes,
                            COALESCE(SUM(size_bytes),0) AS total_bytes
                        FROM document
                        """)
                .param("owner", ownerId)
                .query(
                        (rs, row) ->
                                new StorageUsage(
                                        rs.getLong("user_bytes"), rs.getLong("total_bytes")))
                .single();
    }

    record StorageUsage(long userBytes, long totalBytes) {}

    void create(
            UUID id,
            Actor actor,
            DocumentKind kind,
            UUID branchId,
            String originalName,
            DocumentStorage.Stored stored) {
        jdbc.sql(
                        """
                        INSERT INTO document (id, owner_user_id, kind, branch_id, original_name, content_type,
                            size_bytes, storage_key, sha256, snapshot)
                        VALUES (:id, :owner, :kind, :branch, :name, :type, :size, :key, :hash, CAST(:snapshot AS jsonb))
                        """)
                .param("id", id)
                .param("owner", actor.id())
                .param("kind", kind.databaseValue())
                .param("branch", branchId)
                .param("name", originalName)
                .param("type", stored.contentType())
                .param("size", stored.size())
                .param("key", stored.key())
                .param("hash", stored.sha256())
                .param(
                        "snapshot",
                        json.encode(Map.of("uploadedOnly", true, "validation", "PENDING")))
                .update();
    }

    Optional<DocumentFile> find(UUID id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    List<DocumentMetadata> owned(UUID userId) {
        return jdbc
                .sql(
                        "SELECT * FROM document WHERE owner_user_id = :userId ORDER BY created_at"
                                + " DESC LIMIT 100")
                .param("userId", userId)
                .query(MAPPER)
                .list()
                .stream()
                .map(DocumentFile::metadata)
                .toList();
    }

    boolean clinicalOrderAccess(UUID documentId, UUID userId) {
        return jdbc.sql(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM child_order o JOIN purchase p ON p.id = o.purchase_id
                            JOIN membership m ON m.branch_id = o.branch_id AND m.user_id = :userId
                                AND m.active AND m.role = 'PHARMACIST'
                            JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role
                            WHERE o.status <> 'CANCELLED' AND p.status NOT IN ('CANCELLED','UNAVAILABLE') AND (
                                EXISTS (SELECT 1 FROM purchase_item pi JOIN prescription_item ri ON ri.id = pi.prescription_item_id
                                    JOIN prescription r ON r.id = ri.prescription_id
                                    WHERE pi.purchase_id = p.id AND r.document_id = :documentId)
                                OR EXISTS (SELECT 1 FROM family_authorization a
                                    WHERE a.id = p.family_authorization_id AND a.document_id = :documentId)
                                OR p.snapshot->'coverageRequest'->>'evidenceDocumentId'=CAST(:documentId AS text)
                                OR p.snapshot->>'prescriptionDocumentId'=CAST(:documentId AS text)
                                OR EXISTS (SELECT 1 FROM review r WHERE r.order_id=o.id
                                    AND r.kind IN ('COVERAGE','SUBSTITUTION') AND r.evidence_document_id=:documentId)
                            )
                        )
                        """)
                .param("userId", userId)
                .param("documentId", documentId)
                .query(Boolean.class)
                .single();
    }

    boolean coverageReviewEvidence(UUID documentId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM review WHERE kind='COVERAGE' AND"
                                + " evidence_document_id=:id)")
                .param("id", documentId)
                .query(Boolean.class)
                .single();
    }

    boolean commercialBranchAccess(UUID branchId, UUID userId) {
        return jdbc.sql(
                        """
                        SELECT EXISTS (SELECT 1 FROM membership m JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role
                            WHERE m.branch_id = :branchId AND m.user_id = :userId
                            AND m.active AND m.role IN ('PHARMACY_ADMIN','PHARMACY','PHARMACIST'))
                        """)
                .param("branchId", branchId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    boolean buyerInvoiceAccess(UUID documentId, UUID buyerId) {
        return jdbc.sql(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM child_order o JOIN purchase p ON p.id=o.purchase_id
                            JOIN document d ON d.id=:id
                            WHERE p.buyer_user_id=:buyerId AND (
                                (d.kind='INVOICE' AND (
                                    o.invoice_document_id=d.id
                                    OR EXISTS(SELECT 1 FROM fiscal_adjustment a
                                        WHERE a.order_id=o.id AND a.invoice_document_id=o.invoice_document_id
                                          AND a.evidence_document_id=d.id)))
                                OR (d.kind='PAYMENT_RECEIPT' AND EXISTS(
                                    SELECT 1 FROM refund r JOIN payment pay ON pay.id=r.payment_id
                                    WHERE pay.order_id=o.id AND r.evidence_document_id=d.id))
                            )
                        )
                        """)
                .param("id", documentId)
                .param("buyerId", buyerId)
                .query(Boolean.class)
                .single();
    }

    boolean paymentOrderAccess(UUID documentId, UUID userId) {
        return jdbc.sql(
                        """
                        SELECT EXISTS(SELECT 1 FROM child_order o JOIN membership m ON m.branch_id=o.branch_id
                            JOIN user_role ur ON ur.user_id=m.user_id AND ur.role=m.role
                            WHERE m.user_id=:user AND m.active AND m.role IN ('PHARMACY_ADMIN','PHARMACY','PHARMACIST')
                            AND (o.snapshot->'paymentSubmission'->>'evidenceDocumentId'=:document
                                OR EXISTS(SELECT 1 FROM payment p WHERE p.order_id=o.id AND p.evidence_document_id=:id)))
                        """)
                .param("user", userId)
                .param("document", documentId.toString())
                .param("id", documentId)
                .query(Boolean.class)
                .single();
    }
}
