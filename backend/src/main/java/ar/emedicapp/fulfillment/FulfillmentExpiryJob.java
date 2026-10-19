package ar.emedicapp.fulfillment;

import ar.emedicapp.purchase.PurchaseStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(FulfillmentExpiryJob.class);
    private final PurchaseStore store;
    private final FulfillmentService fulfillment;

    public FulfillmentExpiryJob(PurchaseStore store, FulfillmentService fulfillment) {
        this.store = store;
        this.fulfillment = fulfillment;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void expire() {
        for (var row :
                store.rows(
                        "SELECT id FROM fulfillment WHERE (status='ASSIGNED' AND"
                            + " task_expires_at<=now()) OR (mode='PICKUP' AND status='READY' AND"
                            + " pickup_expires_at<=now()) ORDER BY created_at LIMIT 100")) {
            try {
                fulfillment.expire(PurchaseStore.id(row.get("id")));
            } catch (RuntimeException ex) {
                LOG.warn(
                        "No se pudo cerrar una tarea o retiro vencido; se reintentará ({})",
                        ex.getClass().getSimpleName());
            }
        }
    }
}
