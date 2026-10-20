-- The cash report reads confirmed movements by their confirmation time, including historical refunds.
CREATE INDEX ix_payment_report_verified ON payment(branch_id,verified_at)
 WHERE status IN ('VERIFIED','PARTIALLY_REFUNDED','REFUNDED');
CREATE INDEX ix_refund_report_verified ON refund(verified_at,payment_id)
 WHERE status='VERIFIED';
