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
