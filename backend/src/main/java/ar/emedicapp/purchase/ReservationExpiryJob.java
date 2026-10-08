package ar.emedicapp.purchase;

import ar.emedicapp.payments.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.UUID;

@Configuration
@EnableScheduling
public class ReservationExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private final PurchaseStore s;
    private final PaymentService payments;

    public ReservationExpiryJob(PurchaseStore s, PaymentService payments) {
        this.s = s;
        this.payments = payments;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void expire() {
        for (var row :
                s.rows(
                        "SELECT DISTINCT p.id FROM purchase p JOIN child_order o ON o.purchase_id=p.id JOIN reservation r ON r.order_id=o.id WHERE p.status IN ('ACCEPTED','PARTIALLY_PAID') AND r.status='HELD' AND r.expires_at<=now() LIMIT 100")) {
            UUID purchase = PurchaseStore.id(row.get("id"));
            try {
                payments.expirePurchase(purchase);
            } catch (RuntimeException exception) {
                // Group transactions remain independent; no patient data or database payload is
                // logged.
                LOG.warn(
                        "No se pudo expirar el grupo {}; se reintentará en el próximo ciclo ({})",
                        purchase,
                        exception.getClass().getSimpleName());
            }
        }
    }
}
