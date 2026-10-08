package ar.emedicapp.catalog;

import static ar.emedicapp.catalog.CatalogDtos.*;
import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.common.Json;
import ar.emedicapp.config.OperationalConfigService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final JdbcClient jdbc;
    private final Access access;
    private final AuditLog audit;
    private final Json json;
    private final OperationalConfigService config;
    private final Clock clock;

    @Value("${emedicapp.mode:DEMO}")
    private String mode = "DEMO";

    public CatalogService(
            JdbcClient jdbc,
            Access access,
            AuditLog audit,
            Json json,
            OperationalConfigService config,
            Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.config = config;
        this.clock = clock;
    }

    static Instant monthlyPeriodEnd(Instant startsAt, ZoneId zone) {
        return startsAt.atZone(zone).plusMonths(1).toInstant();
    }

    public List<Product> publicProducts() {
        return products(true);
    }

    private List<Product> products(boolean activeOnly) {
        return jdbc.sql(
                        "SELECT * FROM product "
                                + (activeOnly ? "WHERE active " : "")
                                + "ORDER BY name,presentation")
                .query(this::product)
                .list();
    }

    private Product product(ResultSet rs, int row) throws SQLException {
        return new Product(
                uuid(rs, "id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("presentation"),
                rs.getBoolean("requires_prescription"),
                rs.getBoolean("cold_chain"),
                rs.getBoolean("restricted"),
                rs.getBoolean("active"),
                rs.getLong("version"),
                json.decodeMap(rs.getString("metadata")));
    }

    private List<Chain> chains() {
        return jdbc.sql("SELECT * FROM chain ORDER BY name")
                .query(
                        (rs, row) ->
                                new Chain(
                                        uuid(rs, "id"),
                                        rs.getString("name"),
                                        rs.getBoolean("active"),
                                        rs.getLong("version")))
                .list();
    }

    private static final String BRANCH_SELECT =
            """
        SELECT b.*, CASE
          WHEN EXISTS(SELECT 1 FROM branch_subscription bs JOIN subscription_contract sc ON sc.id=bs.contract_id
            JOIN subscription_period sp ON sp.contract_id=sc.id
            WHERE bs.branch_id=b.id AND bs.active AND bs.starts_at<=now() AND (bs.ends_at IS NULL OR bs.ends_at>now())
              AND sc.status IN ('ACTIVE','CANCEL_AT_PERIOD_END') AND sc.starts_at<=now()
              AND sp.status='PAID' AND (sp.source='MANUAL' OR :demo) AND sp.starts_at<=now() AND sp.ends_at>now()) THEN 'PAID'
          WHEN EXISTS(SELECT 1 FROM branch_subscription bs JOIN subscription_contract sc ON sc.id=bs.contract_id
            WHERE bs.branch_id=b.id AND bs.active AND sc.status='ACTIVE'
              AND COALESCE((SELECT max(sp.ends_at) FROM subscription_period sp WHERE sp.contract_id=sc.id AND sp.status='PAID' AND (sp.source='MANUAL' OR :demo)),sc.starts_at)
                  + make_interval(days=>:grace)>=now()) THEN 'GRACE'
          WHEN EXISTS(SELECT 1 FROM branch_subscription bs JOIN subscription_contract sc ON sc.id=bs.contract_id
            WHERE bs.branch_id=b.id AND bs.active AND sc.status IN ('ACTIVE','SUSPENDED')) THEN 'SUSPENDED'
          ELSE 'NONE' END AS subscription_state
        FROM branch b LEFT JOIN chain c ON c.id=b.chain_id
        """;

    public List<Branch> publicBranches() {
        var where =
                " WHERE b.active AND b.verified AND b.agreement_accepted AND (c.id IS NULL OR c.active)";
        return jdbc.sql(
                        "SELECT * FROM ("
                                + BRANCH_SELECT
                                + where
                                + ") eligible WHERE subscription_state <> 'SUSPENDED' ORDER BY name")
                .param("grace", config.current().graceDays())
                .param("demo", mode.equals("DEMO"))
                .query((rs, row) -> branch(rs, false))
                .list();
    }

    private List<Branch> branches() {
        return jdbc.sql(BRANCH_SELECT + " ORDER BY b.name")
                .param("grace", config.current().graceDays())
                .param("demo", mode.equals("DEMO"))
                .query((rs, row) -> branch(rs, true))
                .list();
    }

    private Branch branch(ResultSet rs, boolean administrative) throws SQLException {
        var state = rs.getString("subscription_state");
        var capabilities = json.decodeMap(rs.getString("capabilities"));
        return new Branch(
                uuid(rs, "id"),
                uuid(rs, "chain_id"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getBigDecimal("latitude").toPlainString(),
                rs.getBigDecimal("longitude").toPlainString(),
                rs.getBoolean("active"),
                rs.getBoolean("verified"),
                rs.getBoolean("agreement_accepted"),
                rs.getBoolean("delivery_enabled"),
                rs.getBoolean("pickup_enabled"),
                rs.getBoolean("cold_chain_capable"),
                rs.getBoolean("restricted_capable"),
                rs.getBoolean("driver_available"),
                rs.getString("merchant_name"),
                administrative ? rs.getString("payment_instructions") : "",
                String.valueOf(capabilities.getOrDefault("deliveryRadiusKm", "20.00")),
                capabilities.get("opensAt") == null
                        ? null
                        : String.valueOf(capabilities.get("opensAt")),
                capabilities.get("closesAt") == null
                        ? null
                        : String.valueOf(capabilities.get("closesAt")),
                administrative ? capabilities : Map.of(),
                rs.getLong("version"),
                state.equals("PAID"),
                state);
    }

    public record Overview(
            List<Product> products,
            List<Chain> chains,
            List<Branch> branches,
            List<Plan> plans,
            List<Subscription> subscriptions,
            List<Member> memberships,
            List<UserOption> users,
            OperationalConfigService.Settings config) {}

    @Transactional(readOnly = true)
    public Overview overview() {
        access.actor().requireRole("ADMIN");
        return new Overview(
                products(false),
                chains(),
                branches(),
                plans(),
                subscriptions(),
                memberships(),
                jdbc.sql("SELECT id,name,email,active FROM app_user ORDER BY name")
                        .query(
                                (rs, row) ->
                                        new UserOption(
                                                uuid(rs, "id"),
                                                rs.getString("name"),
                                                rs.getString("email"),
                                                rs.getBoolean("active")))
                        .list(),
                config.current());
    }

    @Transactional
    public Product saveProduct(UUID id, ProductInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        var values = new HashMap<String, Object>();
        values.put("id", actualId);
        values.put("code", input.code().trim());
        values.put("name", input.name().trim());
        values.put("presentation", input.presentation().trim());
        values.put("rx", input.requiresPrescription());
        values.put("cold", input.coldChain());
        values.put("restricted", input.restricted());
        values.put("active", input.active());
        values.put("metadata", json.encode(safeMetadata(input.metadata())));
        values.put("version", input.version());
        if (id == null)
            jdbc.sql(
                            """
            INSERT INTO product(id,code,name,presentation,requires_prescription,cold_chain,restricted,active,metadata)
            VALUES(:id,:code,:name,:presentation,:rx,:cold,:restricted,:active,CAST(:metadata AS jsonb))
            """)
                    .params(values)
                    .update();
        else
            changed(
                    jdbc.sql(
                                    """
            UPDATE product SET code=:code,name=:name,presentation=:presentation,requires_prescription=:rx,
              cold_chain=:cold,restricted=:restricted,active=:active,metadata=CAST(:metadata AS jsonb),version=version+1,updated_at=now()
            WHERE id=:id AND version=:version
            """)
                            .params(values)
                            .update(),
                    "product",
                    id);
        audit.record(
                actor,
                id == null ? "PRODUCT_CREATED" : "PRODUCT_UPDATED",
                "product",
                actualId,
                values);
        return jdbc.sql("SELECT * FROM product WHERE id=:id")
                .param("id", actualId)
                .query(this::product)
                .single();
    }

    @Transactional
    public Chain saveChain(UUID id, ChainInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        if (id == null)
            jdbc.sql("INSERT INTO chain(id,name,active) VALUES(:id,:name,:active)")
                    .param("id", actualId)
                    .param("name", input.name().trim())
                    .param("active", input.active())
                    .update();
        else
            changed(
                    jdbc.sql(
                                    "UPDATE chain SET name=:name,active=:active,version=version+1 WHERE id=:id AND version=:version")
                            .param("id", id)
                            .param("name", input.name().trim())
                            .param("active", input.active())
                            .param("version", input.version())
                            .update(),
                    "chain",
                    id);
        audit.record(
                actor,
                id == null ? "CHAIN_CREATED" : "CHAIN_UPDATED",
                "chain",
                actualId,
                Map.of("name", input.name(), "active", input.active()));
        return jdbc.sql("SELECT * FROM chain WHERE id=:id")
                .param("id", actualId)
                .query(
                        (rs, row) ->
                                new Chain(
                                        uuid(rs, "id"),
                                        rs.getString("name"),
                                        rs.getBoolean("active"),
                                        rs.getLong("version")))
                .single();
    }

    @Transactional
    public Branch saveBranch(UUID id, BranchInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        var lat = new BigDecimal(input.latitude());
        var lon = new BigDecimal(input.longitude());
        if (lat.abs().compareTo(new BigDecimal("90")) > 0
                || lon.abs().compareTo(new BigDecimal("180")) > 0)
            throw AppException.badRequest(
                    "INVALID_LOCATION", "La latitud o longitud está fuera de rango.");
        if (input.active() && !input.deliveryEnabled() && !input.pickupEnabled())
            throw AppException.badRequest(
                    "NO_BRANCH_MODE", "La sucursal activa debe habilitar entrega o retiro.");
        var radius = new BigDecimal(input.deliveryRadiusKm());
        if (radius.signum() <= 0)
            throw AppException.badRequest(
                    "DELIVERY_RADIUS", "El radio de entrega debe ser positivo.");
        if ((input.opensAt() == null) != (input.closesAt() == null)
                || (input.opensAt() != null && input.opensAt().equals(input.closesAt())))
            throw AppException.badRequest(
                    "BRANCH_HOURS",
                    "Indicá apertura y cierre distintos, o dejá ambos vacíos para disponibilidad manual.");
        var values = new HashMap<String, Object>();
        values.put("id", actualId);
        values.put("chain", input.chainId());
        values.put("name", input.name().trim());
        values.put("address", input.address().trim());
        values.put("lat", lat);
        values.put("lon", lon);
        values.put("active", input.active());
        values.put("verified", input.verified());
        values.put("agreement", input.agreementAccepted());
        values.put("delivery", input.deliveryEnabled());
        values.put("pickup", input.pickupEnabled());
        values.put("cold", input.coldChainCapable());
        values.put("restricted", input.restrictedCapable());
        values.put("driver", input.driverAvailable());
        values.put("merchant", input.merchantName().trim());
        values.put(
                "instructions",
                input.paymentInstructions() == null ? "" : input.paymentInstructions());
        var capabilities = new HashMap<String, Object>(safeMetadata(input.capabilities()));
        capabilities.put("deliveryRadiusKm", radius.toPlainString());
        if (input.opensAt() == null) {
            capabilities.remove("opensAt");
            capabilities.remove("closesAt");
        } else {
            capabilities.put("opensAt", input.opensAt());
            capabilities.put("closesAt", input.closesAt());
        }
        if (input.coldChainCapable() || input.restrictedCapable()) {
            UUID evidence;
            try {
                evidence =
                        UUID.fromString(
                                String.valueOf(capabilities.get("capabilityEvidenceDocumentId")));
            } catch (IllegalArgumentException failure) {
                throw AppException.badRequest(
                        "CAPABILITY_EVIDENCE_REQUIRED",
                        "Registrá evidencia de capacidad antes de habilitar frío o medicamentos restringidos.");
            }
            if (!jdbc.sql(
                            "SELECT EXISTS(SELECT 1 FROM document WHERE id=:id AND kind='CAPABILITY' AND branch_id=:branch)")
                    .param("id", evidence)
                    .param("branch", actualId)
                    .query(Boolean.class)
                    .single())
                throw AppException.badRequest(
                        "CAPABILITY_DOCUMENT",
                        "La evidencia de capacidad no existe o corresponde a otra sucursal.");
            capabilities.put("verifiedAt", Instant.now().toString());
            capabilities.put("verifiedBy", actor.id().toString());
        }
        values.put("capabilities", json.encode(capabilities));
        values.put("version", input.version());
        if (id == null)
            jdbc.sql(
                            """
            INSERT INTO branch(id,chain_id,name,address,latitude,longitude,active,verified,agreement_accepted,
              delivery_enabled,pickup_enabled,cold_chain_capable,restricted_capable,driver_available,merchant_name,payment_instructions,capabilities)
            VALUES(:id,:chain,:name,:address,:lat,:lon,:active,:verified,:agreement,:delivery,:pickup,:cold,:restricted,:driver,:merchant,:instructions,CAST(:capabilities AS jsonb))
            """)
                    .params(values)
                    .update();
        else
            changed(
                    jdbc.sql(
                                    """
            UPDATE branch SET chain_id=:chain,name=:name,address=:address,latitude=:lat,longitude=:lon,active=:active,
              verified=:verified,agreement_accepted=:agreement,delivery_enabled=:delivery,pickup_enabled=:pickup,
              cold_chain_capable=:cold,restricted_capable=:restricted,driver_available=:driver,merchant_name=:merchant,
              payment_instructions=:instructions,capabilities=CAST(:capabilities AS jsonb),version=version+1,updated_at=now()
            WHERE id=:id AND version=:version
            """)
                            .params(values)
                            .update(),
                    "branch",
                    id);
        audit.record(
                actor,
                id == null ? "BRANCH_CREATED" : "BRANCH_UPDATED",
                "branch",
                actualId,
                values);
        return jdbc.sql(BRANCH_SELECT + " WHERE b.id=:id")
                .param("grace", config.current().graceDays())
                .param("demo", mode.equals("DEMO"))
                .param("id", actualId)
                .query((rs, row) -> branch(rs, true))
                .single();
    }

    public List<Stock> stock(UUID branchId) {
        branchOperator(branchId);
        return jdbc.sql(
                        """
            SELECT s.*,p.name AS product_name FROM branch_stock s JOIN product p ON p.id=s.product_id
            WHERE s.branch_id=:branch ORDER BY p.name
            """)
                .param("branch", branchId)
                .query((rs, row) -> stock(rs, row))
                .list();
    }

    private Stock stock(ResultSet rs, int row) throws SQLException {
        return new Stock(
                uuid(rs, "branch_id"),
                uuid(rs, "product_id"),
                rs.getString("product_name"),
                rs.getInt("quantity"),
                rs.getInt("reserved_quantity"),
                rs.getInt("quantity") - rs.getInt("reserved_quantity"),
                rs.getBigDecimal("price").toPlainString(),
                rs.getString("currency"),
                rs.getLong("version"),
                rs.getString("source"),
                instant(rs, "observed_at"));
    }

    @Transactional
    public Stock saveStock(UUID branchId, UUID productId, StockInput input) {
        var actor = branchOperator(branchId);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM product WHERE id=:id AND active)")
                .param("id", productId)
                .query(Boolean.class)
                .single())
            throw AppException.notFound(
                    "PRODUCT_NOT_FOUND", "El producto no existe o no está activo.");
        var currency = config.current().currency();
        // A missing row is inserted once; concurrent creators cannot reset an existing reservation.
        var inserted =
                jdbc.sql(
                                """
            INSERT INTO branch_stock(branch_id,product_id,quantity,price,currency,version)
            VALUES(:branch,:product,0,:price,:currency,1) ON CONFLICT(branch_id,product_id) DO NOTHING
            """)
                        .param("branch", branchId)
                        .param("product", productId)
                        .param("price", new BigDecimal(input.price()))
                        .param("currency", currency)
                        .update();
        var before =
                jdbc.sql(
                                "SELECT quantity,reserved_quantity,version,currency FROM branch_stock WHERE branch_id=:branch AND product_id=:product FOR UPDATE")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(
                                (rs, row) ->
                                        new StockState(
                                                rs.getInt("quantity"),
                                                rs.getInt("reserved_quantity"),
                                                rs.getLong("version"),
                                                rs.getString("currency")))
                        .single();
        var effectiveExpected =
                input.expectedVersion() == 0 && inserted == 1 ? 1 : input.expectedVersion();
        validateStockChange(before, input.quantity(), effectiveExpected);
        var lotTotal =
                jdbc.sql(
                                "SELECT sum(quantity) FROM stock_lot WHERE branch_id=:branch AND product_id=:product AND status='AVAILABLE' AND (expires_on IS NULL OR expires_on>=CURRENT_DATE)")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(Long.class)
                        .optional()
                        .orElse(null);
        if (lotTotal != null && lotTotal != input.quantity())
            throw AppException.conflict(
                    "LOT_STOCK_MISMATCH",
                    "Este producto tiene lotes registrados. El saldo general debe coincidir con sus cantidades; registrá el ajuste de lotes antes de cambiarlo.");
        var changed =
                jdbc.sql(
                                """
            UPDATE branch_stock SET quantity=:quantity,price=:price,currency=:currency,source='MANUAL',observed_at=now(),updated_at=now(),version=version+1
            WHERE branch_id=:branch AND product_id=:product AND version=:version AND reserved_quantity<=:quantity
            """)
                        .param("branch", branchId)
                        .param("product", productId)
                        .param("quantity", input.quantity())
                        .param("price", new BigDecimal(input.price()))
                        .param("currency", currency)
                        .param("version", before.version())
                        .update();
        if (changed != 1)
            throw AppException.conflict(
                    "STOCK_CHANGED",
                    "El stock cambió durante la operación. Actualizá la pantalla.");
        jdbc.sql(
                        """
            INSERT INTO stock_movement(id,branch_id,product_id,actor_user_id,kind,quantity_delta,reserved_delta,reason)
            VALUES(:id,:branch,:product,:actor,'ADJUST',:delta,0,:reason)
            """)
                .param("id", UUID.randomUUID())
                .param("branch", branchId)
                .param("product", productId)
                .param("actor", actor.id())
                .param("delta", input.quantity() - before.quantity())
                .param("reason", input.reason())
                .update();
        audit.record(
                actor,
                "STOCK_UPDATED",
                "branch_stock",
                productId,
                Map.of(
                        "branchId",
                        branchId,
                        "beforeQuantity",
                        before.quantity(),
                        "quantity",
                        input.quantity(),
                        "reservedQuantity",
                        before.reserved(),
                        "price",
                        input.price(),
                        "reason",
                        input.reason(),
                        "previousVersion",
                        before.version()));
        return jdbc.sql(
                        "SELECT s.*,p.name AS product_name FROM branch_stock s JOIN product p ON p.id=s.product_id WHERE branch_id=:branch AND product_id=:product")
                .param("branch", branchId)
                .param("product", productId)
                .query((rs, row) -> stock(rs, row))
                .single();
    }

    public record StockState(int quantity, int reserved, long version, String currency) {}

    public static void validateStockChange(StockState before, int quantity, long expectedVersion) {
        if (before.version() != expectedVersion)
            throw AppException.conflict(
                    "STOCK_CHANGED", "El stock cambió. Actualizá la pantalla antes de guardar.");
        if (quantity < before.reserved())
            throw AppException.conflict(
                    "STOCK_RESERVED",
                    "La cantidad no puede quedar por debajo de las unidades reservadas.");
        if (quantity < 0)
            throw AppException.badRequest("INVALID_STOCK", "La cantidad no puede ser negativa.");
    }

    public Lots lots(UUID branchId, UUID productId) {
        branchOperator(branchId);
        var version =
                jdbc.sql(
                                "SELECT version FROM branch_stock WHERE branch_id=:branch AND product_id=:product")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        AppException.notFound(
                                                "OFFER_NOT_FOUND",
                                                "Cargá el precio y la oferta antes de registrar lotes."));
        var values =
                jdbc.sql(
                                "SELECT * FROM stock_lot WHERE branch_id=:branch AND product_id=:product ORDER BY expires_on NULLS LAST,lot_code")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(
                                (rs, row) ->
                                        new Lot(
                                                uuid(rs, "id"),
                                                rs.getString("lot_code"),
                                                rs.getObject("expires_on", LocalDate.class),
                                                rs.getInt("quantity"),
                                                rs.getInt("reserved_quantity"),
                                                rs.getString("status"),
                                                rs.getLong("version")))
                        .list();
        return new Lots(branchId, productId, version, values);
    }

    @Transactional
    public Lots saveLot(UUID branchId, UUID productId, UUID lotId, LotInput input) {
        var actor = branchOperator(branchId);
        var before =
                jdbc.sql(
                                "SELECT quantity,reserved_quantity,version,currency FROM branch_stock WHERE branch_id=:branch AND product_id=:product FOR UPDATE")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(
                                (rs, row) ->
                                        new StockState(
                                                rs.getInt("quantity"),
                                                rs.getInt("reserved_quantity"),
                                                rs.getLong("version"),
                                                rs.getString("currency")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        AppException.notFound(
                                                "OFFER_NOT_FOUND",
                                                "Cargá el precio y la oferta antes de registrar lotes."));
        if (before.version() != input.stockVersion())
            throw AppException.conflict(
                    "STOCK_CHANGED", "El stock cambió. Actualizá los lotes antes de guardar.");
        boolean special =
                jdbc.sql("SELECT cold_chain OR restricted FROM product WHERE id=:id")
                        .param("id", productId)
                        .query(Boolean.class)
                        .single();
        if (input.status().equals("AVAILABLE")
                && special
                && (input.expiresOn() == null || input.expiresOn().isBefore(LocalDate.now(clock))))
            throw AppException.badRequest(
                    "LOT_EXPIRY_REQUIRED",
                    "Un lote especial disponible requiere vencimiento comprobado y vigente.");
        var actualId = lotId == null ? UUID.randomUUID() : lotId;
        if (lotId == null) {
            if (input.expectedVersion() != 0)
                throw AppException.conflict(
                        "LOT_VERSION", "Un lote nuevo no tiene una versión anterior.");
            jdbc.sql(
                            """
                INSERT INTO stock_lot(id,branch_id,product_id,lot_code,expires_on,quantity,status)
                VALUES(:id,:branch,:product,:code,:expiry,:quantity,:status)
                """)
                    .param("id", actualId)
                    .param("branch", branchId)
                    .param("product", productId)
                    .param("code", input.lotCode().trim())
                    .param("expiry", input.expiresOn())
                    .param("quantity", input.quantity())
                    .param("status", input.status())
                    .update();
        } else {
            var previous =
                    jdbc.sql(
                                    "SELECT quantity,reserved_quantity,version FROM stock_lot WHERE id=:id AND branch_id=:branch AND product_id=:product FOR UPDATE")
                            .param("id", lotId)
                            .param("branch", branchId)
                            .param("product", productId)
                            .query(
                                    (rs, row) ->
                                            new StockState(
                                                    rs.getInt("quantity"),
                                                    rs.getInt("reserved_quantity"),
                                                    rs.getLong("version"),
                                                    before.currency()))
                            .optional()
                            .orElseThrow(
                                    () ->
                                            AppException.notFound(
                                                    "LOT_NOT_FOUND",
                                                    "El lote no existe en esta sucursal y presentación."));
            validateStockChange(previous, input.quantity(), input.expectedVersion());
            if (previous.reserved() > 0 && !input.status().equals("AVAILABLE"))
                throw AppException.conflict(
                        "LOT_RESERVED",
                        "El lote tiene reservas. Resolvé los pedidos afectados antes de cambiar su disponibilidad.");
            changed(
                    jdbc.sql(
                                    """
                UPDATE stock_lot SET lot_code=:code,expires_on=:expiry,quantity=:quantity,status=:status,version=version+1
                WHERE id=:id AND branch_id=:branch AND product_id=:product AND version=:version
                """)
                            .param("id", lotId)
                            .param("branch", branchId)
                            .param("product", productId)
                            .param("code", input.lotCode().trim())
                            .param("expiry", input.expiresOn())
                            .param("quantity", input.quantity())
                            .param("status", input.status())
                            .param("version", input.expectedVersion())
                            .update(),
                    "stock_lot",
                    lotId);
        }
        var available =
                jdbc.sql(
                                "SELECT COALESCE(sum(quantity),0) FROM stock_lot WHERE branch_id=:branch AND product_id=:product AND status='AVAILABLE' AND (expires_on IS NULL OR expires_on>=CURRENT_DATE)")
                        .param("branch", branchId)
                        .param("product", productId)
                        .query(Long.class)
                        .single();
        if (available > Integer.MAX_VALUE || available < before.reserved())
            throw AppException.conflict(
                    "LOT_STOCK_RESERVED",
                    "El saldo utilizable de los lotes no puede quedar por debajo de las reservas vigentes.");
        jdbc.sql(
                        "UPDATE branch_stock SET quantity=:quantity,version=version+1,source='MANUAL',observed_at=now(),updated_at=now() WHERE branch_id=:branch AND product_id=:product AND version=:version")
                .param("quantity", available.intValue())
                .param("branch", branchId)
                .param("product", productId)
                .param("version", before.version())
                .update();
        jdbc.sql(
                        """
            INSERT INTO stock_movement(id,branch_id,product_id,lot_id,actor_user_id,kind,quantity_delta,reserved_delta,reason)
            VALUES(:id,:branch,:product,:lot,:actor,'ADJUST',:delta,0,:reason)
            """)
                .param("id", UUID.randomUUID())
                .param("branch", branchId)
                .param("product", productId)
                .param("lot", actualId)
                .param("actor", actor.id())
                .param("delta", available.intValue() - before.quantity())
                .param("reason", input.reason())
                .update();
        audit.record(
                actor,
                "LOT_UPDATED",
                "stock_lot",
                actualId,
                Map.of(
                        "branchId",
                        branchId,
                        "productId",
                        productId,
                        "lotCode",
                        input.lotCode(),
                        "quantity",
                        input.quantity(),
                        "status",
                        input.status(),
                        "reason",
                        input.reason()));
        return lots(branchId, productId);
    }

    private List<Plan> plans() {
        return jdbc.sql("SELECT * FROM plan ORDER BY min_branches").query(this::plan).list();
    }

    private Plan plan(ResultSet rs, int row) throws SQLException {
        return new Plan(
                uuid(rs, "id"),
                rs.getString("name"),
                rs.getInt("min_branches"),
                (Integer) rs.getObject("max_branches"),
                money(rs, "symbolic_units"),
                money(rs, "monthly_price"),
                rs.getString("currency"),
                rs.getBoolean("active"),
                rs.getLong("version"));
    }

    @Transactional
    public Plan savePlan(UUID id, PlanInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        var amount = optionalMoney(input.monthlyPrice());
        var units = optionalMoney(input.symbolicUnits());
        if ((amount == null) != (input.currency() == null)
                || amount != null && amount.signum() <= 0
                || units != null && units.signum() <= 0
                || input.maxBranches() != null && input.maxBranches() < input.minBranches())
            throw AppException.badRequest(
                    "INVALID_PLAN",
                    "Revisá los rangos, las unidades y el precio con moneda. Una U.M. no es un cobro real.");
        var values = new HashMap<String, Object>();
        values.put("id", actualId);
        values.put("name", input.name().trim());
        values.put("min", input.minBranches());
        values.put("max", input.maxBranches());
        values.put("units", units);
        values.put("amount", amount);
        values.put("currency", input.currency());
        values.put("active", input.active());
        values.put("version", input.version());
        if (id == null)
            jdbc.sql(
                            """
            INSERT INTO plan(id,name,min_branches,max_branches,symbolic_units,monthly_price,currency,active)
            VALUES(:id,:name,:min,:max,:units,:amount,:currency,:active)
            """)
                    .params(values)
                    .update();
        else
            changed(
                    jdbc.sql(
                                    """
            UPDATE plan SET name=:name,min_branches=:min,max_branches=:max,symbolic_units=:units,monthly_price=:amount,
              currency=:currency,active=:active,version=version+1 WHERE id=:id AND version=:version
            """)
                            .params(values)
                            .update(),
                    "plan",
                    id);
        audit.record(actor, id == null ? "PLAN_CREATED" : "PLAN_UPDATED", "plan", actualId, values);
        return jdbc.sql("SELECT * FROM plan WHERE id=:id")
                .param("id", actualId)
                .query(this::plan)
                .single();
    }

    private Actor admin() {
        var actor = access.actor();
        actor.requireRole("ADMIN");
        return actor;
    }

    private Actor branchOperator(UUID branchId) {
        access.requireBranchRole(branchId, "ADMIN", "PHARMACY", "PHARMACIST");
        return access.actor();
    }

    private Map<String, Object> safeMetadata(Map<String, Object> value) {
        if (value == null) return Map.of();
        if (json.encode(value).length() > 30000)
            throw AppException.badRequest(
                    "METADATA_TOO_LARGE", "La configuración adicional es demasiado extensa.");
        return value;
    }

    private void changed(int count, String table, UUID id) {
        if (count == 1) return;
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single()) throw AppException.notFound("NOT_FOUND", "El registro no existe.");
        throw AppException.conflict(
                "VERSION_CHANGED", "El registro cambió. Actualizá la pantalla antes de guardar.");
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String money(ResultSet rs, String column) throws SQLException {
        var value = rs.getBigDecimal(column);
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal optionalMoney(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    // Subscription and membership writes keep historical payment periods and audit data.
    private List<Subscription> subscriptions() {
        return jdbc.sql(
                        """
            SELECT sc.*,(SELECT max(sp.ends_at) FROM subscription_period sp WHERE sp.contract_id=sc.id AND sp.status='PAID') AS paid_through
            FROM subscription_contract sc ORDER BY sc.created_at DESC
            """)
                .query((rs, row) -> subscription(rs))
                .list();
    }

    private Subscription subscription(ResultSet rs) throws SQLException {
        var id = uuid(rs, "id");
        var covered =
                jdbc.sql(
                                "SELECT branch_id FROM branch_subscription WHERE contract_id=:id AND active ORDER BY branch_id")
                        .param("id", id)
                        .query(UUID.class)
                        .list();
        return new Subscription(
                id,
                uuid(rs, "plan_id"),
                uuid(rs, "chain_id"),
                uuid(rs, "branch_id"),
                covered,
                money(rs, "monthly_price"),
                rs.getString("currency"),
                rs.getString("status"),
                instant(rs, "starts_at"),
                instant(rs, "paid_through"),
                rs.getLong("version"));
    }

    @Transactional
    public Subscription saveSubscription(UUID id, SubscriptionInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        if ((input.chainId() == null) == (input.branchId() == null))
            throw AppException.badRequest(
                    "SUBSCRIPTION_SCOPE", "Elegí una cadena o una farmacia como contratante.");
        var amount = optionalMoney(input.monthlyPrice());
        if ((amount == null) != (input.currency() == null)
                || amount != null && amount.signum() <= 0)
            throw AppException.badRequest(
                    "SUBSCRIPTION_PRICE", "La cuota requiere importe positivo y moneda reales.");
        if (List.of("ACTIVE", "CANCEL_AT_PERIOD_END").contains(input.status()) && amount == null)
            throw AppException.badRequest(
                    "SUBSCRIPTION_PRICE",
                    "Configurá el precio contractual real antes de activar. Las U.M. son simbólicas.");
        var selected = input.branchIds().stream().distinct().sorted().toList();
        if (selected.size() != input.branchIds().size())
            throw AppException.badRequest(
                    "DUPLICATE_BRANCH", "La lista de establecimientos contiene duplicados.");
        var plan =
                jdbc.sql("SELECT * FROM plan WHERE id=:id AND active")
                        .param("id", input.planId())
                        .query(this::plan)
                        .optional()
                        .orElseThrow(
                                () ->
                                        AppException.notFound(
                                                "PLAN_NOT_FOUND", "El plan no está disponible."));
        if (selected.size() < plan.minBranches()
                || plan.maxBranches() != null && selected.size() > plan.maxBranches())
            throw AppException.badRequest(
                    "PLAN_BRANCH_COUNT",
                    "La cantidad de establecimientos no corresponde al nivel del plan.");
        for (var branchId : selected) {
            jdbc.sql("SELECT id FROM branch WHERE id=:id FOR UPDATE")
                    .param("id", branchId)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(
                            () ->
                                    AppException.notFound(
                                            "BRANCH_NOT_FOUND", "El establecimiento no existe."));
            var valid =
                    jdbc.sql(
                                    """
                SELECT EXISTS(SELECT 1 FROM branch WHERE id=:id AND active AND
                    ((CAST(:chain AS uuid) IS NOT NULL AND chain_id=:chain) OR (CAST(:single AS uuid) IS NOT NULL AND id=:single)))
                """)
                            .param("id", branchId)
                            .param("chain", input.chainId())
                            .param("single", input.branchId())
                            .query(Boolean.class)
                            .single();
            if (!valid)
                throw AppException.badRequest(
                        "SUBSCRIPTION_BRANCH_SCOPE",
                        "Cada establecimiento debe pertenecer al contratante y estar activo.");
        }
        if (id != null) {
            jdbc.sql("SELECT id FROM subscription_contract WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(
                            () ->
                                    AppException.notFound(
                                            "SUBSCRIPTION_NOT_FOUND", "La suscripción no existe."));
            boolean paid =
                    jdbc.sql(
                                    "SELECT EXISTS(SELECT 1 FROM subscription_period WHERE contract_id=:id AND status='PAID' AND ends_at>now())")
                            .param("id", id)
                            .query(Boolean.class)
                            .single();
            if (paid) {
                var beforeBranches =
                        jdbc.sql(
                                        "SELECT branch_id FROM branch_subscription WHERE contract_id=:id AND active ORDER BY branch_id")
                                .param("id", id)
                                .query(UUID.class)
                                .list();
                var scopeUnchanged =
                        jdbc.sql(
                                        "SELECT (chain_id IS NOT DISTINCT FROM CAST(:chain AS uuid)) AND (branch_id IS NOT DISTINCT FROM CAST(:branch AS uuid)) FROM subscription_contract WHERE id=:id")
                                .param("id", id)
                                .param("chain", input.chainId())
                                .param("branch", input.branchId())
                                .query(Boolean.class)
                                .single();
                if (!scopeUnchanged || !beforeBranches.equals(selected))
                    throw AppException.conflict(
                            "PAID_SCOPE_CHANGE",
                            "El contratante y los establecimientos del período pagado se conservan hasta su finalización.");
            }
        }
        Instant starts = input.startsAt() == null ? Instant.now() : input.startsAt();
        Instant cancellation = null;
        if (id != null && input.status().equals("CANCEL_AT_PERIOD_END")) {
            cancellation =
                    jdbc.sql(
                                    "SELECT max(ends_at) FROM subscription_period WHERE contract_id=:id AND status='PAID'")
                            .param("id", id)
                            .query((rs, row) -> instant(rs, "max"))
                            .optional()
                            .orElse(null);
            if (cancellation == null || cancellation.isBefore(Instant.now()))
                throw AppException.badRequest(
                        "NO_PAID_PERIOD",
                        "No hay un período pagado vigente para programar la cancelación.");
        }
        if (id != null
                && input.status().equals("CANCELLED")
                && jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM subscription_period WHERE contract_id=:id AND status='PAID' AND ends_at>now())")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
            throw AppException.badRequest(
                    "PAID_PERIOD_ACTIVE",
                    "Usá cancelación al final del período pagado; no se elimina el derecho ya abonado.");
        var values = new HashMap<String, Object>();
        values.put("id", actualId);
        values.put("plan", input.planId());
        values.put("chain", input.chainId());
        values.put("branch", input.branchId());
        values.put("amount", amount);
        values.put("currency", input.currency());
        values.put("status", input.status());
        values.put("starts", Timestamp.from(starts));
        values.put("cancel", cancellation == null ? null : Timestamp.from(cancellation));
        values.put("version", input.version());
        if (id == null)
            jdbc.sql(
                            """
            INSERT INTO subscription_contract(id,plan_id,chain_id,branch_id,monthly_price,currency,status,starts_at,cancel_effective_at)
            VALUES(:id,:plan,:chain,:branch,:amount,:currency,:status,:starts,:cancel)
            """)
                    .params(values)
                    .update();
        else
            changed(
                    jdbc.sql(
                                    """
            UPDATE subscription_contract SET plan_id=:plan,chain_id=:chain,branch_id=:branch,monthly_price=:amount,
              currency=:currency,status=:status,starts_at=:starts,cancel_effective_at=:cancel,version=version+1 WHERE id=:id AND version=:version
            """)
                            .params(values)
                            .update(),
                    "subscription_contract",
                    id);
        jdbc.sql(
                        "UPDATE branch_subscription SET active=false,ends_at=now() WHERE contract_id=:id AND active")
                .param("id", actualId)
                .update();
        for (var branchId : selected)
            jdbc.sql(
                            """
            INSERT INTO branch_subscription(contract_id,branch_id,active,starts_at)
            VALUES(:contract,:branch,true,now()) ON CONFLICT(contract_id,branch_id) DO UPDATE SET active=true,ends_at=NULL
            """)
                    .param("contract", actualId)
                    .param("branch", branchId)
                    .update();
        if (input.periodStartsAt() != null
                || input.periodEndsAt() != null
                || input.paymentReference() != null) {
            if (amount == null
                    || input.periodStartsAt() == null
                    || input.periodEndsAt() == null
                    || !input.periodEndsAt().isAfter(input.periodStartsAt())
                    || input.paymentReference() == null
                    || input.paymentReference().isBlank()
                    || input.evidenceDocumentId() == null)
                throw AppException.badRequest(
                        "PAYMENT_EVIDENCE_REQUIRED",
                        "Para acreditar un período indicá sus fechas, referencia, importe real y comprobante verificado.");
            if (!monthlyPeriodEnd(input.periodStartsAt(), clock.getZone())
                    .equals(input.periodEndsAt()))
                throw AppException.badRequest(
                        "MONTHLY_PERIOD",
                        "El período debe cubrir un mes calendario desde la fecha de inicio.");
            if (!jdbc.sql(
                            "SELECT EXISTS(SELECT 1 FROM document WHERE id=:id AND kind='PAYMENT_RECEIPT')")
                    .param("id", input.evidenceDocumentId())
                    .query(Boolean.class)
                    .single())
                throw AppException.badRequest(
                        "PAYMENT_DOCUMENT", "El comprobante de pago no está registrado.");
            if (jdbc.sql(
                            "SELECT EXISTS(SELECT 1 FROM subscription_period WHERE contract_id=:id AND status='PAID' AND starts_at<:ends AND ends_at>:starts)")
                    .param("id", actualId)
                    .param("starts", Timestamp.from(input.periodStartsAt()))
                    .param("ends", Timestamp.from(input.periodEndsAt()))
                    .query(Boolean.class)
                    .single())
                throw AppException.conflict(
                        "PERIOD_OVERLAP", "El período se superpone con otro pago registrado.");
            jdbc.sql(
                            """
                INSERT INTO subscription_period(id,contract_id,starts_at,ends_at,amount,currency,status,evidence_document_id,
                  verified_by,verified_at,payment_reference,source,snapshot)
                VALUES(:id,:contract,:starts,:ends,:amount,:currency,'PAID',:document,:actor,now(),:reference,'MANUAL',CAST(:snapshot AS jsonb))
                """)
                    .param("id", UUID.randomUUID())
                    .param("contract", actualId)
                    .param("starts", Timestamp.from(input.periodStartsAt()))
                    .param("ends", Timestamp.from(input.periodEndsAt()))
                    .param("amount", amount)
                    .param("currency", input.currency())
                    .param("document", input.evidenceDocumentId())
                    .param("actor", actor.id())
                    .param("reference", input.paymentReference())
                    .param(
                            "snapshot",
                            json.encode(
                                    Map.of(
                                            "planId",
                                            input.planId(),
                                            "planVersion",
                                            plan.version(),
                                            "branchIds",
                                            selected,
                                            "contractVersion",
                                            id == null ? 1 : input.version() + 1,
                                            "verifiedBy",
                                            actor.id(),
                                            "snapshotOrigin",
                                            "MANUAL_PERIOD_VERIFICATION")))
                    .update();
        }
        audit.record(
                actor,
                id == null ? "SUBSCRIPTION_CREATED" : "SUBSCRIPTION_UPDATED",
                "subscription_contract",
                actualId,
                Map.of(
                        "status",
                        input.status(),
                        "branchIds",
                        selected,
                        "monthlyPrice",
                        input.monthlyPrice() == null ? "" : input.monthlyPrice()));
        return jdbc.sql(
                        "SELECT sc.*,(SELECT max(ends_at) FROM subscription_period WHERE contract_id=sc.id AND status='PAID') AS paid_through FROM subscription_contract sc WHERE id=:id")
                .param("id", actualId)
                .query((rs, row) -> subscription(rs))
                .single();
    }

    private List<Member> memberships() {
        return jdbc.sql(
                        "SELECT m.*,u.name,u.email FROM membership m JOIN app_user u ON u.id=m.user_id ORDER BY u.name,m.role")
                .query(this::member)
                .list();
    }

    private Member member(ResultSet rs, int row) throws SQLException {
        var capabilities = json.decodeMap(rs.getString("capabilities"));
        return new Member(
                uuid(rs, "id"),
                uuid(rs, "user_id"),
                rs.getString("name"),
                rs.getString("email"),
                uuid(rs, "branch_id"),
                rs.getString("role"),
                rs.getBoolean("active"),
                Boolean.TRUE.equals(capabilities.get("coldChain")),
                Boolean.TRUE.equals(capabilities.get("restricted")),
                capabilityUuid(capabilities, "capabilityEvidenceDocumentId"),
                capabilities.get("verifiedAt") == null
                        ? null
                        : String.valueOf(capabilities.get("verifiedAt")),
                capabilityUuid(capabilities, "verifiedBy"),
                rs.getLong("version"));
    }

    private static UUID capabilityUuid(Map<String, Object> capabilities, String key) {
        var value = capabilities.get(key);
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    @Transactional
    public Member saveMember(UUID id, MemberInput input) {
        var actor = admin();
        var actualId = id == null ? UUID.randomUUID() : id;
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND active)")
                .param("id", input.userId())
                .query(Boolean.class)
                .single())
            throw AppException.notFound("USER_NOT_FOUND", "La cuenta no existe o no está activa.");
        var capabilities = new HashMap<String, Object>();
        if (id != null) {
            var previous =
                    jdbc
                            .sql(
                                    "SELECT user_id,branch_id,role,capabilities FROM membership WHERE id=:id FOR UPDATE")
                            .param("id", id)
                            .query()
                            .listOfRows()
                            .stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            AppException.notFound(
                                                    "MEMBERSHIP_NOT_FOUND",
                                                    "El permiso no existe."));
            if (input.userId().equals(previous.get("user_id"))
                    && input.branchId().equals(previous.get("branch_id"))
                    && input.role().equals(previous.get("role")))
                capabilities.putAll(json.decodeMap(String.valueOf(previous.get("capabilities"))));
        }
        if (input.coldChainCapable() || input.restrictedCapable()) {
            if (!"DRIVER".equals(input.role()))
                throw AppException.badRequest(
                        "DRIVER_CAPABILITY_ROLE",
                        "Las capacidades de transporte se acreditan en el permiso de cadete.");
            if (input.capabilityEvidenceDocumentId() == null
                    || !jdbc.sql(
                                    "SELECT EXISTS(SELECT 1 FROM document WHERE id=:id AND kind='CAPABILITY' AND branch_id=:branch)")
                            .param("id", input.capabilityEvidenceDocumentId())
                            .param("branch", input.branchId())
                            .query(Boolean.class)
                            .single())
                throw AppException.badRequest(
                        "DRIVER_CAPABILITY_EVIDENCE",
                        "Adjuntá la evidencia de capacidad del cadete para esta sucursal antes de acreditarla.");
            capabilities.put(
                    "capabilityEvidenceDocumentId",
                    input.capabilityEvidenceDocumentId().toString());
            capabilities.put("verifiedAt", Instant.now().toString());
            capabilities.put("verifiedBy", actor.id().toString());
        } else {
            capabilities.remove("capabilityEvidenceDocumentId");
            capabilities.remove("verifiedAt");
            capabilities.remove("verifiedBy");
        }
        capabilities.put("coldChain", input.coldChainCapable());
        capabilities.put("restricted", input.restrictedCapable());
        var encodedCapabilities = json.encode(capabilities);
        if (id == null)
            jdbc.sql(
                            "INSERT INTO membership(id,user_id,branch_id,role,active,capabilities) VALUES(:id,:user,:branch,:role,:active,CAST(:capabilities AS jsonb))")
                    .param("id", actualId)
                    .param("user", input.userId())
                    .param("branch", input.branchId())
                    .param("role", input.role())
                    .param("active", input.active())
                    .param("capabilities", encodedCapabilities)
                    .update();
        else
            changed(
                    jdbc.sql(
                                    "UPDATE membership SET user_id=:user,branch_id=:branch,role=:role,active=:active,capabilities=CAST(:capabilities AS jsonb),version=version+1 WHERE id=:id AND version=:version")
                            .param("id", id)
                            .param("user", input.userId())
                            .param("branch", input.branchId())
                            .param("role", input.role())
                            .param("active", input.active())
                            .param("capabilities", encodedCapabilities)
                            .param("version", input.version())
                            .update(),
                    "membership",
                    id);
        jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:user,:role) ON CONFLICT DO NOTHING")
                .param("user", input.userId())
                .param("role", input.role())
                .update();
        audit.record(
                actor,
                id == null ? "MEMBERSHIP_CREATED" : "MEMBERSHIP_UPDATED",
                "membership",
                actualId,
                Map.of(
                        "userId",
                        input.userId(),
                        "branchId",
                        input.branchId(),
                        "role",
                        input.role(),
                        "active",
                        input.active(),
                        "coldChainCapable",
                        input.coldChainCapable(),
                        "restrictedCapable",
                        input.restrictedCapable(),
                        "capabilityEvidenceDocumentId",
                        input.capabilityEvidenceDocumentId() == null
                                ? ""
                                : input.capabilityEvidenceDocumentId().toString(),
                        "capabilityVerifiedBy",
                        actor.id().toString()));
        return jdbc.sql(
                        "SELECT m.*,u.name,u.email FROM membership m JOIN app_user u ON u.id=m.user_id WHERE m.id=:id")
                .param("id", actualId)
                .query(this::member)
                .single();
    }
}
