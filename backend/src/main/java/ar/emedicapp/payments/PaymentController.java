package ar.emedicapp.payments;

import ar.emedicapp.purchase.PurchaseDtos;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/orders/{id}/payment")
    public Map<String, Object> payment(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.Confirm r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.payment(id, r, key);
    }

    @PostMapping("/orders/{id}/payment-report")
    public Map<String, Object> paymentReport(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.PaymentReport r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.reportPayment(id, r, key);
    }

    @PostMapping("/orders/{id}/reconcile-payment")
    public Map<String, Object> reconcilePayment(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.Reconcile r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.reconcilePayment(id, r, key);
    }

    @PostMapping("/orders/{id}/refund")
    public Map<String, Object> refund(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.RefundConfirm r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.refund(id, r, key);
    }

    @PostMapping("/orders/{id}/refund-request")
    public Map<String, Object> requestRefund(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.RefundRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.requestRefund(id, r, key);
    }

    @PostMapping("/orders/{id}/financial-control")
    public Map<String, Object> control(@PathVariable UUID id, @RequestBody PaymentDtos.Control r) {
        return service.control(id, r);
    }

    @PostMapping("/orders/{id}/fiscal-adjustment")
    public Map<String, Object> adjustment(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.FiscalAdjustment r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.fiscalAdjustment(id, r, key);
    }

    @PostMapping("/purchases/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Cancel r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.cancelPurchase(id, r, key);
    }

    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancelOrder(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Cancel r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.cancelOrder(id, r, key);
    }

    @PostMapping("/orders/{id}/invoice")
    public Map<String, Object> invoice(
            @PathVariable UUID id, @RequestBody PaymentService.Invoice r) {
        return service.invoice(id, r);
    }

    @PostMapping("/orders/{id}/invoice-issue")
    public Map<String, Object> invoiceIssue(
            @PathVariable UUID id,
            @RequestBody PaymentDtos.InvoiceIssue r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.invoiceIssue(id, r, key);
    }

    @PostMapping("/orders/{id}/incident")
    public Map<String, Object> incident(
            @PathVariable UUID id, @RequestBody PaymentDtos.Incident r) {
        return service.reportIncident(id, r);
    }

    @PostMapping("/orders/{id}/resolve-incident")
    public Map<String, Object> resolve(
            @PathVariable UUID id, @RequestBody PaymentDtos.Resolution r) {
        return service.resolveIncident(id, r);
    }
}
