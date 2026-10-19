-- Hechos físicos y financieros registrados por operadores; no integra bancos ni emite comprobantes fiscales.
ALTER TABLE fulfillment DROP CONSTRAINT fulfillment_status_check;
ALTER TABLE fulfillment ADD CONSTRAINT fulfillment_status_check CHECK (status IN
 ('PENDING','PREPARING','READY','ASSIGNED','ACCEPTED','PICKED_UP','DELIVERED','COLLECTED','INCIDENT','RETURN_REQUIRED','RETURNED','CANCELLED','RESOLVED'));
ALTER TABLE fulfillment ADD COLUMN task_expires_at timestamptz;
ALTER TABLE fulfillment ADD COLUMN pickup_expires_at timestamptz;
ALTER TABLE fulfillment ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 1);
ALTER TABLE fulfillment ADD COLUMN retry_scheduled_at timestamptz;
ALTER TABLE fulfillment ADD COLUMN custody_holder text NOT NULL DEFAULT 'PHARMACY' CHECK (custody_holder IN ('PHARMACY','DRIVER','RECIPIENT'));
ALTER TABLE fulfillment ADD COLUMN return_received_at timestamptz;
UPDATE fulfillment SET custody_holder=CASE WHEN status='PICKED_UP' THEN 'DRIVER' WHEN status IN ('DELIVERED','COLLECTED') THEN 'RECIPIENT' ELSE 'PHARMACY' END;
-- Las incidencias históricas conservan la última custodia comprobada, no suponen un retorno.
UPDATE fulfillment f SET custody_holder='DRIVER' WHERE status='INCIDENT' AND EXISTS
 (SELECT 1 FROM custody_event e WHERE e.fulfillment_id=f.id AND e.kind='DRIVER_RECEIVED')
 AND NOT EXISTS (SELECT 1 FROM custody_event e WHERE e.fulfillment_id=f.id AND e.kind IN ('RETURNED','DELIVERED','COLLECTED'));
ALTER TABLE custody_event DROP CONSTRAINT custody_event_kind_check;
ALTER TABLE custody_event ADD CONSTRAINT custody_event_kind_check CHECK (kind IN
 ('DISPENSED','DRIVER_RECEIVED','DISPATCHED','DELIVERED','COLLECTED','INCIDENT','RETURN_REQUIRED','RETURNED','REASSIGNED','TASK_REJECTED'));
ALTER TABLE custody_event ALTER COLUMN actor_user_id DROP NOT NULL;
ALTER TABLE custody_event ADD CONSTRAINT custody_event_system_actor_check CHECK (actor_user_id IS NOT NULL OR coalesce(snapshot->>'system'='true',false));
ALTER TABLE refund ADD COLUMN line_ids uuid[] NOT NULL DEFAULT '{}';
ALTER TABLE refund ADD COLUMN branch_id uuid REFERENCES branch(id);
UPDATE refund r SET branch_id=p.branch_id FROM payment p WHERE p.id=r.payment_id;
CREATE UNIQUE INDEX uq_refund_external_reference ON refund(branch_id,external_reference) WHERE external_reference IS NOT NULL AND status='VERIFIED';
CREATE TABLE financial_control (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES child_order(id),
 operation text NOT NULL CHECK (operation IN ('CANCEL','REFUND','DISPATCH')),
 fingerprint text NOT NULL, amount numeric(16,2) NOT NULL CHECK (amount>=0), currency char(3) NOT NULL,
 requested_by uuid NOT NULL REFERENCES app_user(id), approved_by uuid REFERENCES app_user(id),
 reason text NOT NULL, evidence_document_id uuid NOT NULL REFERENCES document(id),
 created_at timestamptz NOT NULL DEFAULT now(), approved_at timestamptz,
 UNIQUE(order_id,operation), CHECK (approved_by IS NULL OR approved_by<>requested_by),
 CHECK ((approved_by IS NULL)=(approved_at IS NULL))
);
CREATE TABLE fiscal_adjustment (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES child_order(id),
 invoice_document_id uuid NOT NULL REFERENCES document(id),
 kind text NOT NULL CHECK (kind IN ('CREDIT','DEBIT')), reference text NOT NULL,
 amount numeric(16,2) NOT NULL CHECK (amount>0), currency char(3) NOT NULL,
 evidence_document_id uuid NOT NULL REFERENCES document(id), reason text NOT NULL,
 created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(order_id,kind,reference)
);
