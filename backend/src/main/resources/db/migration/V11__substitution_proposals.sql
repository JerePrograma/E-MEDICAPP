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
