-- E-MEDICAPP. Referencia consolidada del modelo, actualizada el 15/10/2026.
-- PostgreSQL 18.6. 42 tablas de negocio; Flyway conserva su tabla de historial aparte.
-- Migraciones V1-V14 copiadas en orden. Referencia para una base NUEVA, no actualización manual.
-- El backend ejecuta las migraciones originales mediante Flyway. No ejecutar este archivo sobre datos existentes.
-- UUID generados por Java. Fechas UTC. Sin usuarios, contraseñas ni semillas comerciales.
-- Autorización por servicio y claves de alcance; no se afirma RLS implementada.
-- Snapshots y registros históricos no se borran desde las APIs administrativas.

-- Fuente: V1__mvp.sql
-- E-MEDICAPP MVP. PostgreSQL 18.6. Contrato coordinado, 35 tablas.
-- Flyway crea este esquema; este archivo no se ejecutó durante su diseño.
-- UUID generados por Java. Fechas UTC. Sin usuarios, contraseñas ni seeds comerciales.
-- Autorización por servicio + FK de alcance; no se afirma RLS implementada.
-- Snapshots y registros históricos no se borran desde las APIs administrativas.

CREATE TABLE app_user (
 id uuid PRIMARY KEY, email text NOT NULL UNIQUE, name text NOT NULL,
 password_hash text NOT NULL, birth_date date NOT NULL, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), CHECK (length(trim(email)) > 3)
);
CREATE TABLE user_role (
 user_id uuid NOT NULL REFERENCES app_user(id), role text NOT NULL,
 PRIMARY KEY(user_id,role), CHECK(role IN ('ADMIN','BUYER','PHARMACY','PHARMACIST','DRIVER'))
);
CREATE TABLE chain (
 id uuid PRIMARY KEY, name text NOT NULL, active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE branch (
 id uuid PRIMARY KEY, chain_id uuid REFERENCES chain(id), name text NOT NULL, address text NOT NULL,
 latitude numeric(9,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude numeric(10,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 active boolean NOT NULL DEFAULT true, verified boolean NOT NULL DEFAULT false,
 agreement_accepted boolean NOT NULL DEFAULT false, delivery_enabled boolean NOT NULL DEFAULT true,
 pickup_enabled boolean NOT NULL DEFAULT true, cold_chain_capable boolean NOT NULL DEFAULT false,
 restricted_capable boolean NOT NULL DEFAULT false, driver_available boolean NOT NULL DEFAULT false,
 merchant_name text NOT NULL DEFAULT '', payment_instructions text NOT NULL DEFAULT '',
 capabilities jsonb NOT NULL DEFAULT '{}'::jsonb, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE membership (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), branch_id uuid NOT NULL REFERENCES branch(id),
 role text NOT NULL CHECK(role IN ('PHARMACY','PHARMACIST','DRIVER')), active boolean NOT NULL DEFAULT true,
 capabilities jsonb NOT NULL DEFAULT '{}'::jsonb, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,branch_id,role), UNIQUE(id,branch_id)
);
CREATE VIEW user_branch AS SELECT DISTINCT user_id,branch_id FROM membership WHERE active;
CREATE TABLE product (
 id uuid PRIMARY KEY, code text NOT NULL UNIQUE, name text NOT NULL, presentation text NOT NULL,
 requires_prescription boolean NOT NULL DEFAULT false, cold_chain boolean NOT NULL DEFAULT false,
 restricted boolean NOT NULL DEFAULT false, active boolean NOT NULL DEFAULT true,
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE branch_stock (
 branch_id uuid NOT NULL REFERENCES branch(id), product_id uuid NOT NULL REFERENCES product(id),
 quantity integer NOT NULL DEFAULT 0 CHECK(quantity >= 0), reserved_quantity integer NOT NULL DEFAULT 0,
 price numeric(16,2) NOT NULL CHECK(price >= 0), currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'),
 active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','DEMO','INTEGRATED')),
 observed_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(branch_id,product_id), CHECK(reserved_quantity >= 0 AND reserved_quantity <= quantity)
);
CREATE TABLE stock_lot (
 id uuid PRIMARY KEY, branch_id uuid NOT NULL, product_id uuid NOT NULL,
 lot_code text NOT NULL, expires_on date, quantity integer NOT NULL CHECK(quantity >= 0),
 reserved_quantity integer NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0),
 status text NOT NULL DEFAULT 'AVAILABLE' CHECK(status IN ('AVAILABLE','QUARANTINED','EXPIRED','DEPLETED')),
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 UNIQUE(id,branch_id,product_id), UNIQUE(branch_id,product_id,lot_code),
 FOREIGN KEY(branch_id,product_id) REFERENCES branch_stock(branch_id,product_id), CHECK(reserved_quantity <= quantity)
);
CREATE TABLE document (
 id uuid PRIMARY KEY, owner_user_id uuid NOT NULL REFERENCES app_user(id), kind text NOT NULL,
 branch_id uuid REFERENCES branch(id), original_name text NOT NULL, content_type text NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes > 0), storage_key text NOT NULL UNIQUE,
 sha256 char(64), snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
 CHECK(kind IN ('PRESCRIPTION','FAMILY_AUTHORIZATION','PAYMENT_RECEIPT','INVOICE','DELIVERY_PROOF','CAPABILITY','OTHER'))
);
CREATE TABLE beneficiary (
 id uuid PRIMARY KEY, owner_user_id uuid NOT NULL REFERENCES app_user(id), name text NOT NULL, birth_date date NOT NULL,
 identity_reference text NOT NULL DEFAULT '', age_verified boolean NOT NULL DEFAULT false,
 verified_by uuid REFERENCES app_user(id), verified_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(NOT age_verified OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE TABLE family_authorization (
 id uuid PRIMARY KEY, beneficiary_id uuid NOT NULL REFERENCES beneficiary(id), buyer_user_id uuid NOT NULL REFERENCES app_user(id),
 receiver_name text NOT NULL DEFAULT '', receiver_identity text NOT NULL DEFAULT '',
 document_id uuid NOT NULL REFERENCES document(id), scope text NOT NULL CHECK(scope IN ('BUY','RECEIVE','BUY_AND_RECEIVE')),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED','EXPIRED')),
 reviewed_by uuid REFERENCES app_user(id), reviewed_at timestamptz, expires_at timestamptz, reason text,
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(status <> 'APPROVED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE TABLE prescription (
 id uuid PRIMARY KEY, owner_user_id uuid NOT NULL REFERENCES app_user(id), beneficiary_id uuid NOT NULL REFERENCES beneficiary(id),
 document_id uuid NOT NULL REFERENCES document(id), external_reference text, issuer text NOT NULL DEFAULT '',
 provider_verification text NOT NULL DEFAULT 'UNKNOWN' CHECK(provider_verification IN ('UNKNOWN','MANUALLY_VERIFIED','EXTERNALLY_VERIFIED','REJECTED')),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CONSUMED')),
 expires_at timestamptz, metadata jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(issuer,external_reference)
);
CREATE TABLE prescription_item (
 id uuid PRIMARY KEY, prescription_id uuid NOT NULL REFERENCES prescription(id), product_id uuid NOT NULL REFERENCES product(id),
 authorized_quantity integer NOT NULL CHECK(authorized_quantity > 0), reserved_quantity integer NOT NULL DEFAULT 0,
 dispensed_quantity integer NOT NULL DEFAULT 0, unit text NOT NULL DEFAULT 'PACKAGE',
 CHECK(reserved_quantity >= 0 AND dispensed_quantity >= 0 AND reserved_quantity + dispensed_quantity <= authorized_quantity),
 UNIQUE(prescription_id,product_id)
);
CREATE TABLE plan (
 id uuid PRIMARY KEY, name text NOT NULL, min_branches integer NOT NULL CHECK(min_branches > 0),
 max_branches integer, symbolic_units numeric(16,2), monthly_price numeric(16,2), currency char(3),
 active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(max_branches IS NULL OR max_branches >= min_branches),
 CHECK(symbolic_units IS NULL OR symbolic_units > 0), CHECK(monthly_price IS NULL OR monthly_price > 0),
 CHECK((monthly_price IS NULL AND currency IS NULL) OR (monthly_price IS NOT NULL AND currency ~ '^[A-Z]{3}$'))
);
CREATE TABLE subscription_contract (
 id uuid PRIMARY KEY, plan_id uuid NOT NULL REFERENCES plan(id), chain_id uuid REFERENCES chain(id),
 branch_id uuid REFERENCES branch(id), monthly_price numeric(16,2), currency char(3),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','CANCEL_AT_PERIOD_END','SUSPENDED','CANCELLED')),
 starts_at timestamptz NOT NULL DEFAULT now(), cancel_effective_at timestamptz,
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((chain_id IS NOT NULL) <> (branch_id IS NOT NULL)), CHECK(monthly_price IS NULL OR monthly_price > 0),
 CHECK((monthly_price IS NULL AND currency IS NULL) OR (monthly_price IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
 CHECK(status NOT IN ('ACTIVE','CANCEL_AT_PERIOD_END') OR monthly_price IS NOT NULL)
);
CREATE TABLE branch_subscription (
 contract_id uuid NOT NULL REFERENCES subscription_contract(id), branch_id uuid NOT NULL REFERENCES branch(id),
 active boolean NOT NULL DEFAULT true, starts_at timestamptz NOT NULL DEFAULT now(), ends_at timestamptz,
 PRIMARY KEY(contract_id,branch_id), CHECK(ends_at IS NULL OR ends_at > starts_at)
);
CREATE TABLE subscription_period (
 id uuid PRIMARY KEY, contract_id uuid NOT NULL REFERENCES subscription_contract(id), starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL, amount numeric(16,2) NOT NULL CHECK(amount > 0), currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PAID','CANCELLED')),
 evidence_document_id uuid REFERENCES document(id), invoice_document_id uuid REFERENCES document(id),
 verified_by uuid REFERENCES app_user(id), verified_at timestamptz, payment_reference text,
 source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','DEMO')),
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(ends_at > starts_at), UNIQUE(contract_id,starts_at),
 CHECK(status <> 'PAID' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL AND payment_reference IS NOT NULL))
);
CREATE TABLE purchase (
 id uuid PRIMARY KEY, buyer_user_id uuid NOT NULL REFERENCES app_user(id), beneficiary_id uuid NOT NULL REFERENCES beneficiary(id),
 family_authorization_id uuid REFERENCES family_authorization(id), mode text NOT NULL CHECK(mode IN ('DELIVERY','PICKUP')),
 delivery_address text NOT NULL DEFAULT '', latitude numeric(9,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude numeric(10,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ROUTING','QUOTED','ACCEPTED','PARTIALLY_PAID','PAID','FULFILLING','COMPLETED','COMPENSATING','CANCELLED','UNAVAILABLE')),
 currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'), total numeric(16,2) NOT NULL DEFAULT 0 CHECK(total >= 0),
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), accepted_quote_id uuid,
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE purchase_item (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES purchase(id), product_id uuid NOT NULL REFERENCES product(id),
 quantity integer NOT NULL CHECK(quantity > 0), prescription_item_id uuid REFERENCES prescription_item(id),
 UNIQUE(id,purchase_id), UNIQUE(purchase_id,product_id)
);
CREATE TABLE child_order (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES purchase(id), branch_id uuid NOT NULL REFERENCES branch(id),
 status text NOT NULL DEFAULT 'PROPOSED' CHECK(status IN ('PROPOSED','ACCEPTED','PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED')),
 total numeric(16,2) NOT NULL DEFAULT 0 CHECK(total >= 0), currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'),
 invoice_document_id uuid REFERENCES document(id), accepted_quote_id uuid,
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,branch_id), UNIQUE(id,purchase_id), UNIQUE(purchase_id,branch_id)
);
CREATE TABLE quote (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES purchase(id), version integer NOT NULL CHECK(version > 0),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ISSUED','ACCEPTED','EXPIRED','SUPERSEDED','REJECTED')),
 subtotal numeric(16,2) NOT NULL DEFAULT 0 CHECK(subtotal >= 0), discount numeric(16,2) NOT NULL DEFAULT 0 CHECK(discount >= 0),
 coverage_amount numeric(16,2) NOT NULL DEFAULT 0 CHECK(coverage_amount >= 0), delivery_fee numeric(16,2) NOT NULL DEFAULT 0 CHECK(delivery_fee = 0),
 total numeric(16,2) NOT NULL DEFAULT 0 CHECK(total >= 0), currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'),
 expires_at timestamptz NOT NULL, accepted_by uuid REFERENCES app_user(id), accepted_at timestamptz,
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(purchase_id,version), UNIQUE(id,purchase_id), CHECK(total = subtotal - discount - coverage_amount),
 CHECK(status <> 'ACCEPTED' OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL))
);
ALTER TABLE purchase ADD CONSTRAINT fk_purchase_accepted_quote FOREIGN KEY(accepted_quote_id,id) REFERENCES quote(id,purchase_id);
ALTER TABLE child_order ADD CONSTRAINT fk_order_accepted_quote FOREIGN KEY(accepted_quote_id,purchase_id) REFERENCES quote(id,purchase_id);
CREATE TABLE quote_line (
 id uuid PRIMARY KEY, quote_id uuid NOT NULL REFERENCES quote(id), order_id uuid NOT NULL REFERENCES child_order(id),
 purchase_item_id uuid NOT NULL REFERENCES purchase_item(id), product_id uuid NOT NULL REFERENCES product(id),
 quantity integer NOT NULL CHECK(quantity > 0), unit_price numeric(16,2) NOT NULL CHECK(unit_price >= 0),
 discount numeric(16,2) NOT NULL DEFAULT 0 CHECK(discount >= 0), coverage_amount numeric(16,2) NOT NULL DEFAULT 0 CHECK(coverage_amount >= 0),
 total numeric(16,2) NOT NULL CHECK(total >= 0), snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 UNIQUE(id,order_id), CHECK(total = quantity * unit_price - discount - coverage_amount)
);
CREATE TABLE order_item (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES child_order(id), purchase_item_id uuid NOT NULL REFERENCES purchase_item(id),
 product_id uuid NOT NULL REFERENCES product(id), quantity integer NOT NULL CHECK(quantity > 0),
 quote_line_id uuid, dispensed_quantity integer NOT NULL DEFAULT 0 CHECK(dispensed_quantity >= 0),
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, UNIQUE(id,order_id), CHECK(dispensed_quantity <= quantity),
 FOREIGN KEY(quote_line_id,order_id) REFERENCES quote_line(id,order_id)
);
CREATE TABLE review (
 id uuid PRIMARY KEY, order_id uuid REFERENCES child_order(id), branch_id uuid NOT NULL REFERENCES branch(id),
 quote_id uuid REFERENCES quote(id), prescription_id uuid REFERENCES prescription(id),
 kind text NOT NULL CHECK(kind IN ('PRESCRIPTION','COVERAGE','SUBSTITUTION','HIGH_VALUE','AVAILABILITY','FAMILY_AUTHORIZATION')),
 status text NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','MORE_EVIDENCE')),
 reviewed_by uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL DEFAULT '',
 evidence_document_id uuid REFERENCES document(id), expires_at timestamptz,
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(order_id,branch_id) REFERENCES child_order(id,branch_id),
 CHECK(kind <> 'PRESCRIPTION' OR prescription_id IS NOT NULL)
);
CREATE TABLE routing_attempt (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES purchase(id), branch_id uuid NOT NULL REFERENCES branch(id),
 partition_key text NOT NULL DEFAULT 'FULL', status text NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REJECTED','EXPIRED','UNAVAILABLE')),
 reason text NOT NULL DEFAULT '', rank integer NOT NULL CHECK(rank >= 0),
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_routing_pending ON routing_attempt(purchase_id,partition_key) WHERE status='PENDING';
CREATE TABLE reservation (
 id uuid PRIMARY KEY, order_id uuid NOT NULL, order_item_id uuid NOT NULL, branch_id uuid NOT NULL,
 product_id uuid NOT NULL, lot_id uuid, prescription_item_id uuid REFERENCES prescription_item(id),
 quantity integer NOT NULL CHECK(quantity > 0), dispensed_quantity integer NOT NULL DEFAULT 0 CHECK(dispensed_quantity >= 0),
 status text NOT NULL DEFAULT 'HELD' CHECK(status IN ('HELD','RELEASED','CONSUMED','EXPIRED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(order_id,branch_id) REFERENCES child_order(id,branch_id),
 FOREIGN KEY(order_item_id,order_id) REFERENCES order_item(id,order_id),
 FOREIGN KEY(branch_id,product_id) REFERENCES branch_stock(branch_id,product_id),
 FOREIGN KEY(lot_id,branch_id,product_id) REFERENCES stock_lot(id,branch_id,product_id), CHECK(dispensed_quantity <= quantity)
);
CREATE TABLE stock_movement (
 id uuid PRIMARY KEY, branch_id uuid NOT NULL, product_id uuid NOT NULL, lot_id uuid,
 reservation_id uuid REFERENCES reservation(id), actor_user_id uuid REFERENCES app_user(id),
 kind text NOT NULL CHECK(kind IN ('ADJUST','RESERVE','RELEASE','DISPENSE','QUARANTINE')),
 quantity_delta integer NOT NULL, reserved_delta integer NOT NULL DEFAULT 0,
 reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(branch_id,product_id) REFERENCES branch_stock(branch_id,product_id),
 FOREIGN KEY(lot_id,branch_id,product_id) REFERENCES stock_lot(id,branch_id,product_id)
);
CREATE TABLE payment (
 id uuid PRIMARY KEY, order_id uuid NOT NULL, branch_id uuid NOT NULL,
 amount numeric(16,2) NOT NULL CHECK(amount > 0), currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUBMITTED','VERIFIED','REJECTED','PARTIALLY_REFUNDED','REFUNDED')),
 evidence_document_id uuid REFERENCES document(id), external_reference text,
 verified_by uuid REFERENCES app_user(id), verified_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY(order_id,branch_id) REFERENCES child_order(id,branch_id),
 CHECK(status NOT IN ('VERIFIED','PARTIALLY_REFUNDED','REFUNDED') OR (verified_by IS NOT NULL AND verified_at IS NOT NULL AND external_reference IS NOT NULL))
);
CREATE UNIQUE INDEX uq_payment_reference ON payment(branch_id,external_reference) WHERE external_reference IS NOT NULL;
CREATE TABLE payment_operation (
 id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payment(id), actor_user_id uuid NOT NULL REFERENCES app_user(id),
 kind text NOT NULL CHECK(kind IN ('SUBMITTED','VERIFIED','REJECTED','REFUND_REQUESTED','REFUND_VERIFIED','CORRECTION')),
 amount numeric(16,2) NOT NULL CHECK(amount >= 0), currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 external_reference text, evidence_document_id uuid REFERENCES document(id), reason text NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE refund (
 id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payment(id), requested_by uuid NOT NULL REFERENCES app_user(id),
 amount numeric(16,2) NOT NULL CHECK(amount > 0), currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN ('REQUESTED','PENDING','VERIFIED','REJECTED')),
 reason text NOT NULL, external_reference text, evidence_document_id uuid REFERENCES document(id),
 verified_by uuid REFERENCES app_user(id), verified_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL AND external_reference IS NOT NULL))
);
CREATE TABLE fulfillment (
 id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE, branch_id uuid NOT NULL,
 mode text NOT NULL CHECK(mode IN ('DELIVERY','PICKUP')),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PREPARING','READY','ASSIGNED','ACCEPTED','PICKED_UP','DELIVERED','COLLECTED','INCIDENT','CANCELLED')),
 driver_user_id uuid REFERENCES app_user(id), receiver_name text NOT NULL DEFAULT '', receiver_identity text NOT NULL DEFAULT '',
 address text NOT NULL DEFAULT '', proof_code_hash text, proof_expires_at timestamptz,
 proof_failed_attempts integer NOT NULL DEFAULT 0 CHECK(proof_failed_attempts >= 0), proof_document_id uuid REFERENCES document(id),
 pharmacy_cost numeric(16,2) NOT NULL DEFAULT 0 CHECK(pharmacy_cost >= 0), currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'),
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 completed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,branch_id), FOREIGN KEY(order_id,branch_id) REFERENCES child_order(id,branch_id)
);
CREATE TABLE custody_event (
 id uuid PRIMARY KEY, fulfillment_id uuid NOT NULL, branch_id uuid NOT NULL, actor_user_id uuid NOT NULL REFERENCES app_user(id),
 kind text NOT NULL CHECK(kind IN ('DISPENSED','DRIVER_RECEIVED','DISPATCHED','DELIVERED','COLLECTED','INCIDENT','RETURNED','REASSIGNED')),
 evidence_document_id uuid REFERENCES document(id), temperature_c numeric(6,2), condition text NOT NULL DEFAULT '',
 reason text NOT NULL DEFAULT '', snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(fulfillment_id,branch_id) REFERENCES fulfillment(id,branch_id)
);
CREATE TABLE audit_event (
 id uuid PRIMARY KEY, actor_user_id uuid REFERENCES app_user(id), branch_id uuid REFERENCES branch(id),
 action text NOT NULL, entity_type text NOT NULL, entity_id uuid,
 before_data jsonb NOT NULL DEFAULT '{}'::jsonb, after_data jsonb NOT NULL DEFAULT '{}'::jsonb,
 reason text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE idempotency_record (
 id uuid PRIMARY KEY, actor_user_id uuid NOT NULL REFERENCES app_user(id), scope text NOT NULL, request_key text NOT NULL,
 request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 status text NOT NULL CHECK(status IN ('PROCESSING','SUCCEEDED','FAILED')), response_status integer, response_body jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 UNIQUE(actor_user_id,scope,request_key)
);
CREATE TABLE operational_setting (
 id uuid PRIMARY KEY, version bigint NOT NULL UNIQUE CHECK(version > 0),
 currency char(3) NOT NULL DEFAULT 'ARS' CHECK(currency ~ '^[A-Z]{3}$'),
 quote_minutes integer NOT NULL DEFAULT 20 CHECK(quote_minutes BETWEEN 1 AND 1440),
 reservation_minutes integer NOT NULL DEFAULT 30 CHECK(reservation_minutes BETWEEN 1 AND 1440),
 grace_days integer NOT NULL DEFAULT 7 CHECK(grace_days BETWEEN 0 AND 90),
 high_value_threshold numeric(16,2) NOT NULL DEFAULT 1000000 CHECK(high_value_threshold > 0),
 max_upload_mb integer NOT NULL DEFAULT 10 CHECK(max_upload_mb BETWEEN 1 AND 50),
 delivery_enabled boolean NOT NULL DEFAULT true, pickup_enabled boolean NOT NULL DEFAULT true,
 fallback_enabled boolean NOT NULL DEFAULT true, messages jsonb NOT NULL DEFAULT '{}'::jsonb,
 updated_by uuid REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_membership_user ON membership(user_id) WHERE active;
CREATE INDEX ix_branch_chain ON branch(chain_id);
CREATE INDEX ix_stock_product ON branch_stock(product_id,branch_id) WHERE active;
CREATE INDEX ix_document_owner ON document(owner_user_id,created_at DESC);
CREATE INDEX ix_purchase_buyer ON purchase(buyer_user_id,created_at DESC);
CREATE INDEX ix_order_branch ON child_order(branch_id,status,created_at DESC);
CREATE INDEX ix_reservation_expiry ON reservation(expires_at) WHERE status='HELD';
CREATE INDEX ix_prescription_item ON prescription_item(prescription_id);
CREATE INDEX ix_review_order ON review(order_id,kind,created_at DESC);
CREATE INDEX ix_payment_order ON payment(order_id,created_at DESC);
CREATE INDEX ix_fulfillment_driver ON fulfillment(driver_user_id,status);
CREATE INDEX ix_audit_entity ON audit_event(entity_type,entity_id,created_at DESC);
CREATE INDEX ix_period_contract ON subscription_period(contract_id,ends_at DESC);

-- Invariantes de servicio transaccionales: suma de líneas / particiones, revisión vigente por
-- sucursal/cotización, autorización adulto, dos revisores distintos alto valor, cantidades Rx,
-- reserva/decremento atómico lotes+stock, reembolso acumulado <= cobro, cadete propio elegible,
-- paid subscription prioritiza sin usar U.M. como dinero. Config y snapshots versionados.
-- El propietario stock POS y consumo oficial externo de recetas siguen siendo límites manuales.

-- Fuente: V2__incident_resolution.sql
-- Una incidencia cerrada no afirma una entrega física que no ocurrió.
ALTER TABLE child_order DROP CONSTRAINT child_order_status_check;
ALTER TABLE child_order ADD CONSTRAINT child_order_status_check CHECK(status IN
 ('PROPOSED','ACCEPTED','PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED','RESOLVED'));
ALTER TABLE fulfillment DROP CONSTRAINT fulfillment_status_check;
ALTER TABLE fulfillment ADD CONSTRAINT fulfillment_status_check CHECK(status IN
 ('PENDING','PREPARING','READY','ASSIGNED','ACCEPTED','PICKED_UP','DELIVERED','COLLECTED','INCIDENT','CANCELLED','RESOLVED'));

-- Fuente: V3__subscription_period_snapshot.sql
-- Conservar el alcance y plan con los que se pagó cada período, aunque el contrato
-- se modifique al finalizarlo. Importe y moneda siguen siendo columnas SQL.
ALTER TABLE subscription_period ADD COLUMN snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;
UPDATE subscription_period sp SET snapshot=jsonb_build_object(
 'contractId',sc.id,'contractVersion',sc.version,'planId',sc.plan_id,
 'branchIds',COALESCE((SELECT jsonb_agg(bs.branch_id ORDER BY bs.branch_id)
                     FROM branch_subscription bs WHERE bs.contract_id=sc.id AND bs.active),'[]'::jsonb),
 'snapshotOrigin','MIGRATION_CURRENT_CONFIGURATION')
FROM subscription_contract sc WHERE sc.id=sp.contract_id;

-- Fuente: V4__purchase_incident_resolution.sql
-- Cerrar una compra por resolución de incidencia no equivale a entregarla.
ALTER TABLE purchase DROP CONSTRAINT purchase_status_check;
ALTER TABLE purchase ADD CONSTRAINT purchase_status_check CHECK(status IN
 ('DRAFT','ROUTING','QUOTED','ACCEPTED','PARTIALLY_PAID','PAID','FULFILLING','COMPLETED','COMPENSATING','CANCELLED','UNAVAILABLE','RESOLVED'));

-- Fuente: V5__system_requested_refunds.sql
-- Un vencimiento automático registra un origen SYSTEM, sin suplantar al comprador.
-- El solicitante humano sigue registrado para solicitudes manuales.
ALTER TABLE refund ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE payment_operation ALTER COLUMN actor_user_id DROP NOT NULL;

-- Fuente: V6__private_health_coverage.sql
ALTER TABLE document DROP CONSTRAINT document_kind_check;
ALTER TABLE document ADD CONSTRAINT document_kind_check CHECK
  (kind IN ('PRESCRIPTION','FAMILY_AUTHORIZATION','HEALTH_COVERAGE','PAYMENT_RECEIPT','INVOICE','DELIVERY_PROOF','CAPABILITY','OTHER'));

-- Fuente: V7__auth_verification.sql
-- Existing DEMO identities and deliberately provisioned administrators keep access.
-- New consumer accounts are never verified by their registration or by missing SMTP.
ALTER TABLE app_user
 ADD COLUMN dni text,
 ADD COLUMN phone text NOT NULL DEFAULT '',
 ADD COLUMN address text NOT NULL DEFAULT '',
 ADD COLUMN email_verified boolean NOT NULL DEFAULT false,
 ADD COLUMN email_verified_at timestamptz,
 ADD COLUMN credentials_version bigint NOT NULL DEFAULT 0 CHECK(credentials_version >= 0),
 ADD CONSTRAINT ck_user_dni CHECK(dni IS NULL OR dni ~ '^[0-9]{7,12}$');
CREATE UNIQUE INDEX uq_user_dni ON app_user(dni) WHERE dni IS NOT NULL;
UPDATE app_user u SET email_verified=true,email_verified_at=u.created_at
 WHERE lower(u.email) LIKE '%@demo.invalid'
 OR EXISTS(SELECT 1 FROM user_role r WHERE r.user_id=u.id AND r.role='ADMIN');

CREATE TABLE auth_token (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 purpose text NOT NULL CHECK(purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
 token_hash char(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 used_at timestamptz,
 CHECK(expires_at > created_at)
);
CREATE UNIQUE INDEX uq_auth_token_pending ON auth_token(user_id,purpose) WHERE used_at IS NULL;
CREATE INDEX ix_auth_token_user ON auth_token(user_id,created_at DESC);

-- Fuente: V8__fulfillment_finance.sql
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

-- Fuente: V9__qa_purchase_controls.sql
-- Additive changes preserve existing purchases, documents and financial history.
ALTER TABLE operational_setting ADD COLUMN task_acceptance_minutes integer NOT NULL DEFAULT 15 CHECK(task_acceptance_minutes BETWEEN 1 AND 240);
ALTER TABLE operational_setting ADD COLUMN pickup_deadline_hours integer NOT NULL DEFAULT 48 CHECK(pickup_deadline_hours BETWEEN 1 AND 168);
ALTER TABLE operational_setting ADD COLUMN receipt_code_minutes integer NOT NULL DEFAULT 10 CHECK(receipt_code_minutes BETWEEN 1 AND 60);

ALTER TABLE purchase DROP CONSTRAINT purchase_status_check;
ALTER TABLE purchase ADD CONSTRAINT purchase_status_check CHECK(status IN ('DRAFT','ROUTING','UNAVAILABLE','QUOTED','ACCEPTED','PARTIALLY_PAID','PAID','FULFILLING','COMPENSATING','COMPLETED','CANCELLED','RESOLVED','REJECTED'));

ALTER TABLE order_item ADD COLUMN active boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX uq_order_active_product ON order_item(order_id,product_id) WHERE active;
ALTER TABLE app_user ADD COLUMN access_version bigint NOT NULL DEFAULT 1 CHECK(access_version>0);

ALTER TABLE user_role DROP CONSTRAINT user_role_role_check;
ALTER TABLE user_role ADD CONSTRAINT user_role_role_check CHECK(role IN ('ADMIN','BUYER','PHARMACY_ADMIN','PHARMACY','PHARMACIST','DRIVER'));
ALTER TABLE membership DROP CONSTRAINT membership_role_check;
ALTER TABLE membership ADD CONSTRAINT membership_role_check CHECK(role IN ('PHARMACY_ADMIN','PHARMACY','PHARMACIST','DRIVER'));

CREATE INDEX ix_quote_pending_expiry ON quote(expires_at,purchase_id) WHERE status='ISSUED';

-- Fuente: V10__documented_replacements.sql
CREATE TABLE replacement_request (
 id uuid PRIMARY KEY, original_order_id uuid NOT NULL REFERENCES child_order(id),
 original_fulfillment_id uuid NOT NULL REFERENCES fulfillment(id),
 buyer_user_id uuid NOT NULL REFERENCES app_user(id), branch_id uuid NOT NULL REFERENCES branch(id),
 replacement_purchase_id uuid UNIQUE REFERENCES purchase(id), replacement_order_id uuid UNIQUE REFERENCES child_order(id),
 status text NOT NULL CHECK(status IN ('PROPOSED','ACCEPTED','READY','REJECTED','EXPIRED','FULFILLED','CANCELLED')),
 original_disposition text NOT NULL CHECK(original_disposition IN ('RETURNED','NO_RETURN_DOCUMENTED')),
 reason text NOT NULL, disposition_note text NOT NULL, evidence_document_id uuid NOT NULL REFERENCES document(id),
 proposed_by uuid NOT NULL REFERENCES app_user(id), accepted_at timestamptz,
 expires_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_active_replacement ON replacement_request(original_order_id) WHERE status IN ('PROPOSED','ACCEPTED','READY');
CREATE TABLE replacement_line (
 id uuid PRIMARY KEY, replacement_id uuid NOT NULL REFERENCES replacement_request(id),
 original_order_item_id uuid NOT NULL REFERENCES order_item(id), product_id uuid NOT NULL REFERENCES product(id),
 quantity integer NOT NULL CHECK(quantity>0), commercial_unit_price numeric(16,2) NOT NULL CHECK(commercial_unit_price>=0),
 snapshot jsonb NOT NULL, UNIQUE(replacement_id,original_order_item_id)
);

-- Fuente: V11__substitution_proposals.sql
ALTER TABLE purchase_item ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE purchase_item ADD COLUMN supersedes_id uuid REFERENCES purchase_item(id);
ALTER TABLE purchase_item DROP CONSTRAINT purchase_item_purchase_id_product_id_key;
CREATE UNIQUE INDEX uq_purchase_item_active ON purchase_item(purchase_id,product_id) WHERE active;
CREATE TABLE substitution_proposal (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES purchase(id), order_id uuid NOT NULL REFERENCES child_order(id),
 branch_id uuid NOT NULL REFERENCES branch(id), original_item_id uuid NOT NULL REFERENCES purchase_item(id),
 product_id uuid NOT NULL REFERENCES product(id), quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 100),
 reason text NOT NULL CHECK(length(reason) BETWEEN 3 AND 2000), evidence_document_id uuid REFERENCES document(id),
 prescription_compatible boolean NOT NULL DEFAULT false, proposed_by uuid NOT NULL REFERENCES app_user(id),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REJECTED','EXPIRED')),
 expires_at timestamptz NOT NULL, responded_by uuid REFERENCES app_user(id), responded_at timestamptz,
 snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(order_id,branch_id) REFERENCES child_order(id,branch_id)
);
CREATE UNIQUE INDEX uq_substitution_pending ON substitution_proposal(purchase_id) WHERE status='PENDING';
CREATE INDEX ix_substitution_expiry ON substitution_proposal(expires_at) WHERE status='PENDING';

-- Fuente: V12__authorization_history.sql
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

-- Fuente: V13__payment_method.sql
-- Preserve the absence of a declared method in historical payments.
ALTER TABLE payment ADD COLUMN method text;
ALTER TABLE payment ADD CONSTRAINT payment_method_check
 CHECK(method IS NULL OR method IN ('TRANSFER','CASH','CARD','OTHER','EXTERNAL_MANUAL'));

-- Fuente: V14__chain_logo.sql
-- Chain identity images are separate from private purchase and capability documents.
-- Only normalized PNGs, at most 512 pixels per side, are stored by the catalog service.
ALTER TABLE chain ADD COLUMN logo_png bytea;
ALTER TABLE chain ADD CONSTRAINT chain_logo_size_check
 CHECK (logo_png IS NULL OR octet_length(logo_png) BETWEEN 1 AND 2097152);
