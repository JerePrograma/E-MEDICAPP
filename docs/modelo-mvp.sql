-- E-MEDICAPP MVP. PostgreSQL 18.6. Contrato coordinado, 35 tablas.
-- Referencia consolidada V1-V6. Flyway ejecuta las migraciones originales del backend.
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

-- V2: cierre explícito de una incidencia sin afirmar una entrega física inexistente.
ALTER TABLE child_order DROP CONSTRAINT child_order_status_check;
ALTER TABLE child_order ADD CONSTRAINT child_order_status_check CHECK(status IN
 ('PROPOSED','ACCEPTED','PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED','RESOLVED'));
ALTER TABLE fulfillment DROP CONSTRAINT fulfillment_status_check;
ALTER TABLE fulfillment ADD CONSTRAINT fulfillment_status_check CHECK(status IN
 ('PENDING','PREPARING','READY','ASSIGNED','ACCEPTED','PICKED_UP','DELIVERED','COLLECTED','INCIDENT','CANCELLED','RESOLVED'));

-- V3: alcance histórico de un período, precio/moneda en columnas SQL.
ALTER TABLE subscription_period ADD COLUMN snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;
UPDATE subscription_period sp SET snapshot=jsonb_build_object(
 'contractId',sc.id,'contractVersion',sc.version,'planId',sc.plan_id,
 'branchIds',COALESCE((SELECT jsonb_agg(bs.branch_id ORDER BY bs.branch_id)
                     FROM branch_subscription bs WHERE bs.contract_id=sc.id AND bs.active),'[]'::jsonb),
 'snapshotOrigin','MIGRATION_CURRENT_CONFIGURATION')
FROM subscription_contract sc WHERE sc.id=sp.contract_id;

-- V4: resolución de compra distinta de la entrega completa.
ALTER TABLE purchase DROP CONSTRAINT purchase_status_check;
ALTER TABLE purchase ADD CONSTRAINT purchase_status_check CHECK(status IN
 ('DRAFT','ROUTING','QUOTED','ACCEPTED','PARTIALLY_PAID','PAID','FULFILLING','COMPLETED','COMPENSATING','CANCELLED','UNAVAILABLE','RESOLVED'));

-- V5: vencimientos automáticos mantienen solicitante NULL y auditoría SYSTEM.
ALTER TABLE refund ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE payment_operation ALTER COLUMN actor_user_id DROP NOT NULL;

-- V6: credencial de cobertura con acceso clínico privado.
ALTER TABLE document DROP CONSTRAINT document_kind_check;
ALTER TABLE document ADD CONSTRAINT document_kind_check CHECK
  (kind IN ('PRESCRIPTION','FAMILY_AUTHORIZATION','HEALTH_COVERAGE','PAYMENT_RECEIPT','INVOICE','DELIVERY_PROOF','CAPABILITY','OTHER'));
