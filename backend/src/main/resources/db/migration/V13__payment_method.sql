-- Preserve the absence of a declared method in historical payments.
ALTER TABLE payment ADD COLUMN method text;
ALTER TABLE payment ADD CONSTRAINT payment_method_check
 CHECK(method IS NULL OR method IN ('TRANSFER','CASH','CARD','OTHER','EXTERNAL_MANUAL'));
