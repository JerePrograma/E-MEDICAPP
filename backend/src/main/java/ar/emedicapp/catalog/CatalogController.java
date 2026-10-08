package ar.emedicapp.catalog;

import static ar.emedicapp.catalog.CatalogDtos.*;
import ar.emedicapp.common.Access;
import ar.emedicapp.config.OperationalConfigService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService catalog;
    private final OperationalConfigService config;
    private final Access access;

    public CatalogController(
            CatalogService catalog, OperationalConfigService config, Access access) {
        this.catalog = catalog;
        this.config = config;
        this.access = access;
    }

    @GetMapping("/catalog/products")
    public List<Product> products() {
        return catalog.publicProducts();
    }

    @GetMapping("/catalog/branches")
    public List<Branch> branches() {
        return catalog.publicBranches();
    }

    @GetMapping("/admin/overview")
    public CatalogService.Overview overview() {
        return catalog.overview();
    }

    @PostMapping("/admin/products")
    public Product createProduct(@Valid @RequestBody ProductInput body) {
        return catalog.saveProduct(null, body);
    }

    @PutMapping("/admin/products/{id}")
    public Product updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductInput body) {
        return catalog.saveProduct(id, body);
    }

    @PostMapping("/admin/chains")
    public Chain createChain(@Valid @RequestBody ChainInput body) {
        return catalog.saveChain(null, body);
    }

    @PutMapping("/admin/chains/{id}")
    public Chain updateChain(@PathVariable UUID id, @Valid @RequestBody ChainInput body) {
        return catalog.saveChain(id, body);
    }

    @PostMapping("/admin/branches")
    public Branch createBranch(@Valid @RequestBody BranchInput body) {
        return catalog.saveBranch(null, body);
    }

    @PutMapping("/admin/branches/{id}")
    public Branch updateBranch(@PathVariable UUID id, @Valid @RequestBody BranchInput body) {
        return catalog.saveBranch(id, body);
    }

    @PostMapping("/admin/plans")
    public Plan createPlan(@Valid @RequestBody PlanInput body) {
        return catalog.savePlan(null, body);
    }

    @PutMapping("/admin/plans/{id}")
    public Plan updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanInput body) {
        return catalog.savePlan(id, body);
    }

    @PostMapping("/admin/subscriptions")
    public Subscription createSubscription(@Valid @RequestBody SubscriptionInput body) {
        return catalog.saveSubscription(null, body);
    }

    @PutMapping("/admin/subscriptions/{id}")
    public Subscription updateSubscription(
            @PathVariable UUID id, @Valid @RequestBody SubscriptionInput body) {
        return catalog.saveSubscription(id, body);
    }

    @PostMapping("/admin/memberships")
    public Member createMembership(@Valid @RequestBody MemberInput body) {
        return catalog.saveMember(null, body);
    }

    @PutMapping("/admin/memberships/{id}")
    public Member updateMembership(@PathVariable UUID id, @Valid @RequestBody MemberInput body) {
        return catalog.saveMember(id, body);
    }

    @GetMapping("/branches/{id}/stock")
    public List<Stock> stock(@PathVariable UUID id) {
        return catalog.stock(id);
    }

    @PutMapping("/branches/{id}/stock/{productId}")
    public Stock saveStock(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @Valid @RequestBody StockInput body) {
        return catalog.saveStock(id, productId, body);
    }

    @GetMapping("/branches/{id}/stock/{productId}/lots")
    public Lots lots(@PathVariable UUID id, @PathVariable UUID productId) {
        return catalog.lots(id, productId);
    }

    @PostMapping("/branches/{id}/stock/{productId}/lots")
    public Lots createLot(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @Valid @RequestBody LotInput body) {
        return catalog.saveLot(id, productId, null, body);
    }

    @PutMapping("/branches/{id}/stock/{productId}/lots/{lotId}")
    public Lots updateLot(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @PathVariable UUID lotId,
            @Valid @RequestBody LotInput body) {
        return catalog.saveLot(id, productId, lotId, body);
    }

    @GetMapping("/admin/settings")
    public OperationalConfigService.Settings settings() {
        access.actor().requireRole("ADMIN");
        return config.current();
    }

    @PutMapping("/admin/settings")
    public OperationalConfigService.Settings settings(
            @Valid @RequestBody OperationalConfigService.Update body) {
        return config.update(body);
    }
}
