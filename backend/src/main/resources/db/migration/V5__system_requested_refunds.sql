-- Un vencimiento automático registra un origen SYSTEM, sin suplantar al comprador.
-- El solicitante humano sigue registrado para solicitudes manuales.
ALTER TABLE refund ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE payment_operation ALTER COLUMN actor_user_id DROP NOT NULL;
