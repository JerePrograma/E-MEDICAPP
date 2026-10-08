package ar.emedicapp.fulfillment;

import ar.emedicapp.purchase.PurchaseDtos;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FulfillmentController {
    private final FulfillmentService service;

    public FulfillmentController(FulfillmentService service) {
        this.service = service;
    }

    @PostMapping("/orders/{id}/prepare")
    public Map<String, Object> prepare(@PathVariable UUID id, @RequestBody PurchaseDtos.Version r) {
        return service.prepare(id, r);
    }

    @PostMapping("/orders/{id}/dispense")
    public Map<String, Object> dispense(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Dispense r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.dispense(id, r, key);
    }

    @PostMapping("/orders/{id}/assign-driver")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody PurchaseDtos.Assign r) {
        return service.assign(id, r);
    }

    @GetMapping("/branches/{id}/drivers")
    public List<Map<String, Object>> drivers(@PathVariable UUID id) {
        return service.drivers(id);
    }

    @GetMapping("/deliveries")
    public List<Map<String, Object>> list() {
        return service.deliveries();
    }

    @PostMapping("/deliveries/{id}/accept")
    public Map<String, Object> accept(@PathVariable UUID id, @RequestBody PurchaseDtos.Version r) {
        return service.acceptTask(id, r);
    }

    @PostMapping("/deliveries/{id}/pickup")
    public Map<String, Object> pickup(@PathVariable UUID id, @RequestBody PurchaseDtos.Pickup r) {
        return service.pickup(id, r);
    }

    @PostMapping("/deliveries/{id}/location")
    public Map<String, Object> location(
            @PathVariable UUID id, @RequestBody PurchaseDtos.Location r) {
        return service.location(id, r);
    }

    @GetMapping("/orders/{id}/receipt-code")
    public Map<String, Object> code(@PathVariable UUID id) {
        return service.code(id);
    }

    @PostMapping("/deliveries/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Complete r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return receipt(service.complete(id, r, key));
    }

    @PostMapping("/orders/{id}/pickup-complete")
    public ResponseEntity<Map<String, Object>> collect(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Complete r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return receipt(service.collect(id, r, key));
    }

    private ResponseEntity<Map<String, Object>> receipt(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("receiptRejected"))
                ? ResponseEntity.status(409).body(result)
                : ResponseEntity.ok(result);
    }
}
