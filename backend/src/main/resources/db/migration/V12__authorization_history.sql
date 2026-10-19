-- A corrected note never erases the evidence that a previous file was used.
CREATE TABLE family_authorization_usage (
 sha256 text PRIMARY KEY CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 document_id uuid NOT NULL REFERENCES document(id),
 authorization_id uuid NOT NULL REFERENCES family_authorization(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO family_authorization_usage(sha256,document_id,authorization_id,created_at)
SELECT DISTINCT ON(d.sha256) d.sha256,d.id,used.authorization_id,used.used_at
FROM (
 SELECT a.id authorization_id,a.document_id,a.created_at used_at FROM family_authorization a
 UNION ALL
 SELECT p.family_authorization_id,r.evidence_document_id,r.created_at
 FROM review r JOIN child_order o ON o.id=r.order_id JOIN purchase p ON p.id=o.purchase_id
 WHERE r.kind='FAMILY_AUTHORIZATION' AND p.family_authorization_id IS NOT NULL
 UNION ALL
 SELECT p.family_authorization_id,d.id,a.created_at
 FROM audit_event a JOIN purchase p ON p.id=a.entity_id
 JOIN document d ON d.id::text=a.after_data->>'previousDocumentId'
 WHERE a.action='AUTHORIZATION_REPLACED' AND p.family_authorization_id IS NOT NULL
) used JOIN document d ON d.id=used.document_id
ORDER BY d.sha256,used.used_at,used.authorization_id;
