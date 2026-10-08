package ar.emedicapp.config;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.common.Json;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalConfigService {
    private final JdbcClient jdbc;
    private final Access access;
    private final AuditLog audit;
    private final Json json;

    public OperationalConfigService(JdbcClient jdbc, Access access, AuditLog audit, Json json) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    public record Settings(
            long version,
            String currency,
            int quoteMinutes,
            int reservationMinutes,
            int graceDays,
            String highValueThreshold,
            int maxUploadMb,
            boolean deliveryEnabled,
            boolean pickupEnabled,
            boolean fallbackEnabled,
            Map<String, Object> messages) {
        public BigDecimal highValueAmount() {
            return new BigDecimal(highValueThreshold);
        }

        public Map<String, Object> snapshot() {
            var value = new LinkedHashMap<String, Object>();
            value.put("version", version);
            value.put("currency", currency);
            value.put("quoteMinutes", quoteMinutes);
            value.put("reservationMinutes", reservationMinutes);
            value.put("graceDays", graceDays);
            value.put("highValueThreshold", highValueThreshold);
            value.put("maxUploadMb", maxUploadMb);
            value.put("deliveryEnabled", deliveryEnabled);
            value.put("pickupEnabled", pickupEnabled);
            value.put("fallbackEnabled", fallbackEnabled);
            value.put("messages", messages);
            return Map.copyOf(value);
        }
    }

    public record Update(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(1) @Max(1440) int quoteMinutes,
            @Min(1) @Max(1440) int reservationMinutes,
            @Min(0) @Max(90) int graceDays,
            @NotBlank @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,2})?") String highValueThreshold,
            @Min(1) @Max(50) int maxUploadMb,
            boolean deliveryEnabled,
            boolean pickupEnabled,
            boolean fallbackEnabled,
            Map<String, Object> messages) {}

    public Settings current() {
        return jdbc.sql("SELECT * FROM operational_setting ORDER BY version DESC LIMIT 1")
                .query(
                        (rs, row) ->
                                new Settings(
                                        rs.getLong("version"),
                                        rs.getString("currency"),
                                        rs.getInt("quote_minutes"),
                                        rs.getInt("reservation_minutes"),
                                        rs.getInt("grace_days"),
                                        rs.getBigDecimal("high_value_threshold").toPlainString(),
                                        rs.getInt("max_upload_mb"),
                                        rs.getBoolean("delivery_enabled"),
                                        rs.getBoolean("pickup_enabled"),
                                        rs.getBoolean("fallback_enabled"),
                                        json.decodeMap(rs.getString("messages"))))
                .optional()
                .orElseGet(
                        () ->
                                new Settings(
                                        0,
                                        "ARS",
                                        20,
                                        30,
                                        7,
                                        "1000000.00",
                                        10,
                                        true,
                                        true,
                                        true,
                                        Map.of()));
    }

    public Map<String, Object> snapshot() {
        return current().snapshot();
    }

    @Transactional
    public Settings update(Update input) {
        var actor = access.actor();
        actor.requireRole("ADMIN");
        jdbc.sql("SELECT pg_advisory_xact_lock(736201)").query(Object.class).list();
        var before = current();
        if (before.version() != input.version()) {
            throw AppException.conflict(
                    "CONFIG_CHANGED",
                    "La configuración cambió. Actualizá la pantalla y volvé a guardar.");
        }
        var threshold = new BigDecimal(input.highValueThreshold());
        if (threshold.signum() <= 0 || (!input.deliveryEnabled() && !input.pickupEnabled())) {
            throw AppException.badRequest(
                    "INVALID_CONFIG",
                    "El umbral debe ser positivo y debe haber al menos una modalidad habilitada.");
        }
        var messages = input.messages() == null ? Map.<String, Object>of() : input.messages();
        if (messages.size() > 30
                || messages.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !(e.getValue() instanceof String value)
                                                || value.length() > 1000
                                                || e.getKey().length() > 80)) {
            throw AppException.badRequest(
                    "INVALID_MESSAGES", "Los mensajes admiten hasta 30 textos de 1000 caracteres.");
        }
        var id = UUID.randomUUID();
        jdbc.sql(
                        """
            INSERT INTO operational_setting(id,version,currency,quote_minutes,reservation_minutes,grace_days,
              high_value_threshold,max_upload_mb,delivery_enabled,pickup_enabled,fallback_enabled,messages,updated_by)
            VALUES(:id,:version,:currency,:quote,:reservation,:grace,:threshold,:upload,:delivery,:pickup,:fallback,CAST(:messages AS jsonb),:actor)
            """)
                .param("id", id)
                .param("version", before.version() + 1)
                .param("currency", input.currency())
                .param("quote", input.quoteMinutes())
                .param("reservation", input.reservationMinutes())
                .param("grace", input.graceDays())
                .param("threshold", threshold)
                .param("upload", input.maxUploadMb())
                .param("delivery", input.deliveryEnabled())
                .param("pickup", input.pickupEnabled())
                .param("fallback", input.fallbackEnabled())
                .param("messages", json.encode(messages))
                .param("actor", actor.id())
                .update();
        var after = current();
        audit.record(
                actor,
                "CONFIG_UPDATED",
                "operational_setting",
                id,
                Map.of("before", before.snapshot(), "after", after.snapshot()));
        return after;
    }
}
