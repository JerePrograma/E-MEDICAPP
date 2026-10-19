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
