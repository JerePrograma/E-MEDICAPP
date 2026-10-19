package ar.emedicapp.replacements;

import ar.emedicapp.purchase.PurchaseStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReplacementExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReplacementExpiryJob.class);
    private final PurchaseStore store;
    private final ReplacementService service;

    public ReplacementExpiryJob(PurchaseStore store, ReplacementService service) {
        this.store = store;
        this.service = service;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void expire() {
        for (var row :
                store.rows(
                        "SELECT r.id FROM replacement_request r WHERE (r.status='PROPOSED' AND"
                            + " r.expires_at<=now()) OR (r.status IN ('ACCEPTED','READY') AND"
                            + " EXISTS(SELECT 1 FROM child_order o JOIN reservation held ON"
                            + " held.order_id=o.id WHERE o.id=r.replacement_order_id AND o.status"
                            + " IN ('PAID','PREPARING') AND o.total=0 AND held.status='HELD' AND"
                            + " held.expires_at<=now())) ORDER BY r.created_at LIMIT 100")) {
            try {
                service.expire(PurchaseStore.id(row.get("id")));
            } catch (RuntimeException ex) {
                LOG.warn(
                        "No se pudo vencer una reposición pendiente; se reintentará ({})",
                        ex.getClass().getSimpleName());
            }
        }
    }
}
