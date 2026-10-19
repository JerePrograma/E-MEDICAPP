package ar.emedicapp.purchase;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class PurchaseController {
    private final PurchaseService service;

    public PurchaseController(PurchaseService service) {
        this.service = service;
    }

    @PostMapping("/purchases")
    public Map<String, Object> create(
            @RequestBody PurchaseDtos.Create r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.create(r, key);
    }

    @GetMapping("/purchases")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/purchases/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/orders/{id}/review")
    public Map<String, Object> review(@PathVariable UUID id, @RequestBody PurchaseDtos.Review r) {
        return service.review(id, r);
    }

    @PostMapping("/purchases/{id}/accept")
    public Map<String, Object> accept(
            @PathVariable UUID id,
            @RequestBody PurchaseDtos.Version r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.accept(id, r, key);
    }

    @PostMapping("/orders/{id}/authorization")
    public Map<String, Object> authorization(
            @PathVariable UUID id, @RequestBody PurchaseDtos.AuthorizationReview body) {
        return service.reviewAuthorization(id, body);
    }

    @PostMapping("/purchases/{id}/authorization/revoke")
    public Map<String, Object> revokeAuthorization(
            @PathVariable UUID id, @RequestBody PurchaseDtos.Version body) {
        return service.revokeAuthorization(id, body);
    }

    @PostMapping("/purchases/{id}/authorization")
    public Map<String, Object> replaceAuthorization(
            @PathVariable UUID id, @RequestBody PurchaseDtos.AuthorizationReplacement body) {
        return service.replaceAuthorization(id, body);
    }

    @PostMapping("/purchases/{id}/reroute")
    public Map<String, Object> reroute(@PathVariable UUID id, @RequestBody PurchaseDtos.Version r) {
        return service.reroute(id, r);
    }
}
