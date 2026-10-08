package ar.emedicapp.appbootstrap;

import ar.emedicapp.config.OperationalConfigService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BootstrapController {
    private final AppMode mode;
    private final OperationalConfigService config;

    public BootstrapController(AppMode mode, OperationalConfigService config) {
        this.mode = mode;
        this.config = config;
    }

    @GetMapping("/api/bootstrap")
    Bootstrap bootstrap() {
        var settings = config.current();
        return new Bootstrap(
                mode.value(),
                Map.of(
                        "delivery",
                        settings.deliveryEnabled(),
                        "pickup",
                        settings.pickupEnabled(),
                        "splitPurchases",
                        true,
                        "externalPayments",
                        false,
                        "automaticPrescriptionValidation",
                        false,
                        "providers",
                        Map.of(
                                "stock",
                                "MANUAL",
                                "prescriptions",
                                "MANUAL",
                                "coverage",
                                "MANUAL",
                                "payments",
                                "MANUAL",
                                "subscription",
                                "MANUAL")));
    }

    public record Bootstrap(String mode, Map<String, Object> features) {}
}
