package ar.emedicapp.catalog;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record Product(
            UUID id,
            String code,
            String name,
            String presentation,
            boolean requiresPrescription,
            boolean coldChain,
            boolean restricted,
            boolean active,
            long version,
            Map<String, Object> metadata) {}

    public record ProductInput(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String presentation,
            boolean requiresPrescription,
            boolean coldChain,
            boolean restricted,
            boolean active,
            @Min(0) long version,
            Map<String, Object> metadata) {}

    public record Chain(UUID id, String name, boolean active, long version) {}

    public record ChainInput(
            @NotBlank @Size(max = 200) String name, boolean active, @Min(0) long version) {}

    public record Branch(
            UUID id,
            UUID chainId,
            String name,
            String address,
            String latitude,
            String longitude,
            boolean active,
            boolean verified,
            boolean agreementAccepted,
            boolean deliveryEnabled,
            boolean pickupEnabled,
            boolean coldChainCapable,
            boolean restrictedCapable,
            boolean driverAvailable,
            String merchantName,
            String paymentInstructions,
            String deliveryRadiusKm,
            String opensAt,
            String closesAt,
            Map<String, Object> capabilities,
            long version,
            boolean subscriptionEligible,
            String subscriptionState) {}

    public record BranchInput(
            UUID chainId,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Pattern(regexp = "-?[0-9]{1,3}(\\.[0-9]{1,6})?") String latitude,
            @NotBlank @Pattern(regexp = "-?[0-9]{1,3}(\\.[0-9]{1,6})?") String longitude,
            boolean active,
            boolean verified,
            boolean agreementAccepted,
            boolean deliveryEnabled,
            boolean pickupEnabled,
            boolean coldChainCapable,
            boolean restrictedCapable,
            boolean driverAvailable,
            @NotBlank @Size(max = 200) String merchantName,
            @Size(max = 2000) String paymentInstructions,
            @NotBlank @Pattern(regexp = "[0-9]{1,5}(\\.[0-9]{1,2})?") String deliveryRadiusKm,
            @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String opensAt,
            @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String closesAt,
            Map<String, Object> capabilities,
            @Min(0) long version) {}

    public record Stock(
            UUID branchId,
            UUID productId,
            String productName,
            int quantity,
            int reservedQuantity,
            int availableQuantity,
            String price,
            String currency,
            long version,
            String source,
            Instant observedAt) {}

    public record StockInput(
            @Min(0) int quantity,
            @NotBlank @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,2})?") String price,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record Lot(
            UUID id,
            String lotCode,
            LocalDate expiresOn,
            int quantity,
            int reservedQuantity,
            String status,
            long version) {}

    public record Lots(UUID branchId, UUID productId, long stockVersion, List<Lot> lots) {}

    public record LotInput(
            @NotBlank @Size(max = 100) String lotCode,
            LocalDate expiresOn,
            @Min(0) int quantity,
            @NotBlank @Pattern(regexp = "AVAILABLE|QUARANTINED|EXPIRED|DEPLETED") String status,
            @Min(0) long expectedVersion,
            @Min(1) long stockVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record Plan(
            UUID id,
            String name,
            int minBranches,
            Integer maxBranches,
            String symbolicUnits,
            String monthlyPrice,
            String currency,
            boolean active,
            long version) {}

    public record PlanInput(
            @NotBlank @Size(max = 200) String name,
            @Min(1) int minBranches,
            @Min(1) Integer maxBranches,
            @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,2})?") String symbolicUnits,
            @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,2})?") String monthlyPrice,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            boolean active,
            @Min(0) long version) {}

    public record Subscription(
            UUID id,
            UUID planId,
            UUID chainId,
            UUID branchId,
            List<UUID> branchIds,
            String monthlyPrice,
            String currency,
            String status,
            Instant startsAt,
            Instant paidThroughAt,
            long version) {}

    public record SubscriptionInput(
            @NotNull UUID planId,
            UUID chainId,
            UUID branchId,
            @NotNull @Size(min = 1, max = 500) List<UUID> branchIds,
            @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,2})?") String monthlyPrice,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|CANCEL_AT_PERIOD_END|SUSPENDED|CANCELLED")
                    String status,
            Instant startsAt,
            Instant periodStartsAt,
            Instant periodEndsAt,
            @Size(max = 200) String paymentReference,
            UUID evidenceDocumentId,
            @Min(0) long version) {}

    public record Member(
            UUID id,
            UUID userId,
            String userName,
            String email,
            UUID branchId,
            String role,
            boolean active,
            boolean coldChainCapable,
            boolean restrictedCapable,
            UUID capabilityEvidenceDocumentId,
            String capabilityVerifiedAt,
            UUID capabilityVerifiedBy,
            long version) {}

    public record MemberInput(
            @NotNull UUID userId,
            @NotNull UUID branchId,
            @NotBlank @Pattern(regexp = "PHARMACY|PHARMACIST|DRIVER") String role,
            boolean active,
            boolean coldChainCapable,
            boolean restrictedCapable,
            UUID capabilityEvidenceDocumentId,
            @Min(0) long version) {}

    public record UserOption(UUID id, String name, String email, boolean active) {}
}
