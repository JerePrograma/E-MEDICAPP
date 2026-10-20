package ar.emedicapp.promotions;

import ar.emedicapp.catalog.CatalogDtos.Branch;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PromotionDtos {
    private PromotionDtos() {}

    public record Promotion(
            UUID id,
            UUID chainId,
            String chainName,
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            boolean featured,
            boolean active,
            long version,
            List<UUID> branchIds,
            List<Branch> branches) {}

    public record Input(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String description,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            boolean featured,
            boolean active,
            @NotNull @Size(min = 1, max = 500) List<@NotNull UUID> branchIds,
            @Min(0) long version,
            @NotBlank @Size(max = 500) String reason) {}
}
