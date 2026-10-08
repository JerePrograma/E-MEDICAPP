-- Una incidencia cerrada no afirma una entrega física que no ocurrió.
ALTER TABLE child_order DROP CONSTRAINT child_order_status_check;
ALTER TABLE child_order ADD CONSTRAINT child_order_status_check CHECK(status IN
 ('PROPOSED','ACCEPTED','PAID','PREPARING','DISPENSED','DISPATCHED','COMPLETED','CANCELLED','RESOLVED'));
ALTER TABLE fulfillment DROP CONSTRAINT fulfillment_status_check;
ALTER TABLE fulfillment ADD CONSTRAINT fulfillment_status_check CHECK(status IN
 ('PENDING','PREPARING','READY','ASSIGNED','ACCEPTED','PICKED_UP','DELIVERED','COLLECTED','INCIDENT','CANCELLED','RESOLVED'));
