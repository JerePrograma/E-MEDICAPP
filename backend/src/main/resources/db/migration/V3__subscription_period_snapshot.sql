-- Conservar el alcance y plan con los que se pagó cada período, aunque el contrato
-- se modifique al finalizarlo. Importe y moneda siguen siendo columnas SQL.
ALTER TABLE subscription_period ADD COLUMN snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;
UPDATE subscription_period sp SET snapshot=jsonb_build_object(
 'contractId',sc.id,'contractVersion',sc.version,'planId',sc.plan_id,
 'branchIds',COALESCE((SELECT jsonb_agg(bs.branch_id ORDER BY bs.branch_id)
                     FROM branch_subscription bs WHERE bs.contract_id=sc.id AND bs.active),'[]'::jsonb),
 'snapshotOrigin','MIGRATION_CURRENT_CONFIGURATION')
FROM subscription_contract sc WHERE sc.id=sp.contract_id;
