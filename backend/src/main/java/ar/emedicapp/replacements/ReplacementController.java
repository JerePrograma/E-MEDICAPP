package ar.emedicapp.replacements;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ReplacementController {
    private final ReplacementService service;

    public ReplacementController(ReplacementService service) {
        this.service = service;
    }

    @GetMapping("/orders/{id}/replacements")
    public List<Map<String, Object>> list(@PathVariable UUID id) {
        return service.list(id);
    }

    @GetMapping("/replacements/{id}")
    public Map<String, Object> view(@PathVariable UUID id) {
        return service.view(id);
    }

    @PostMapping("/orders/{id}/replacements")
    public Map<String, Object> propose(
            @PathVariable UUID id,
            @RequestBody ReplacementDtos.Propose r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.propose(id, r, key);
    }

    @PostMapping("/replacements/{id}/accept")
    public Map<String, Object> accept(
            @PathVariable UUID id,
            @RequestBody ReplacementDtos.Accept r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.accept(id, r, key);
    }

    @PostMapping("/replacements/{id}/reject")
    public Map<String, Object> reject(
            @PathVariable UUID id, @RequestBody ReplacementDtos.Reject r) {
        return service.reject(id, r);
    }

    @PostMapping("/replacements/{id}/review")
    public Map<String, Object> review(
            @PathVariable UUID id,
            @RequestBody ReplacementDtos.Review r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.review(id, r, key);
    }
}
