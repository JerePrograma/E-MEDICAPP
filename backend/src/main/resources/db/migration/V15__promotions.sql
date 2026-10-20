-- Promotions describe a pharmacy's advertised conditions and participating branches.
-- They never change accepted quotes or apply a checkout discount automatically.
CREATE TABLE promotion (
 id uuid PRIMARY KEY,
 chain_id uuid REFERENCES chain(id),
 title text NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 160),
 description text NOT NULL CHECK(length(trim(description)) BETWEEN 1 AND 4000),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 featured boolean NOT NULL DEFAULT false,
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 created_by uuid NOT NULL REFERENCES app_user(id),
 updated_by uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at > starts_at)
);
CREATE TABLE promotion_branch (
 promotion_id uuid NOT NULL REFERENCES promotion(id),
 branch_id uuid NOT NULL REFERENCES branch(id),
 PRIMARY KEY(promotion_id,branch_id)
);
CREATE INDEX promotion_current_idx ON promotion(starts_at,ends_at) WHERE active;
CREATE INDEX promotion_branch_scope_idx ON promotion_branch(branch_id,promotion_id);
