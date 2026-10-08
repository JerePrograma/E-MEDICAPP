-- Cerrar una compra por resolución de incidencia no equivale a entregarla.
ALTER TABLE purchase DROP CONSTRAINT purchase_status_check;
ALTER TABLE purchase ADD CONSTRAINT purchase_status_check CHECK(status IN
 ('DRAFT','ROUTING','QUOTED','ACCEPTED','PARTIALLY_PAID','PAID','FULFILLING','COMPLETED','COMPENSATING','CANCELLED','UNAVAILABLE','RESOLVED'));
