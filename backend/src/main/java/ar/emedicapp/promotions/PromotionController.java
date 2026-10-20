package ar.emedicapp.promotions;

import ar.emedicapp.catalog.CatalogDtos.Branch;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PromotionController {
    private final PromotionService service;

    public PromotionController(PromotionService service) {
        this.service = service;
    }

    @GetMapping("/catalog/promotions")
    public List<PromotionDtos.Promotion> current() {
        return service.current();
    }

    @GetMapping("/pharmacy/promotions")
    public List<PromotionDtos.Promotion> managed() {
        return service.managed();
    }

    @GetMapping("/pharmacy/promotion-branches")
    public List<Branch> branches() {
        return service.manageableBranches();
    }

    @PostMapping("/pharmacy/promotions")
    public PromotionDtos.Promotion create(@Valid @RequestBody PromotionDtos.Input body) {
        return service.save(null, body);
    }

    @PutMapping("/pharmacy/promotions/{id}")
    public PromotionDtos.Promotion update(
            @PathVariable UUID id, @Valid @RequestBody PromotionDtos.Input body) {
        return service.save(id, body);
    }
}
