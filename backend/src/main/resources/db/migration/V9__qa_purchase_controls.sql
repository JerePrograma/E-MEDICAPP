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
