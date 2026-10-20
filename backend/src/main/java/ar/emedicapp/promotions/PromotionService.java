package ar.emedicapp.promotions;

import ar.emedicapp.catalog.CatalogDtos.Branch;
import ar.emedicapp.catalog.CatalogService;
import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PromotionService {
    private static final String[] MANAGER_ROLES = {
        "ADMIN", "PHARMACY_ADMIN", "PHARMACY", "PHARMACIST"
    };
    private static final String SELECT =
            "SELECT p.*, c.name AS chain_name FROM promotion p LEFT JOIN chain c ON c.id=p.chain_id";
    private final JdbcClient jdbc;
    private final Access access;
    private final AuditLog audit;
    private final CatalogService catalog;
    private final Clock clock;

    public PromotionService(
            JdbcClient jdbc, Access access, AuditLog audit, CatalogService catalog, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PromotionDtos.Promotion> current() {
        access.actor();
        var eligible = indexed(catalog.publicBranches());
        var now = Timestamp.from(clock.instant());
        return jdbc.sql(
                        SELECT
                                + " WHERE p.active AND p.starts_at<=:now AND p.ends_at>:now"
                                + " AND (c.id IS NULL OR c.active)"
                                + " ORDER BY p.featured DESC,p.starts_at DESC,p.title,p.id")
                .param("now", now)
                .query(this::row)
                .list()
                .stream()
                .map(p -> promotion(p, eligible, true))
                .filter(p -> !p.branches().isEmpty())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PromotionDtos.Promotion> managed() {
        var actor = manager();
        var branches = indexed(branches(actor));
        var allowed = branches.keySet();
        return jdbc.sql(SELECT + " ORDER BY p.updated_at DESC,p.id")
                .query(this::row)
                .list()
                .stream()
                .filter(p -> allowed.containsAll(selectedBranchIds(p.id())))
                .map(p -> promotion(p, branches, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Branch> manageableBranches() {
        return branches(manager());
    }

    private Actor manager() {
        var actor = access.actor();
        actor.requireRole(MANAGER_ROLES);
        return actor;
    }

    private List<Branch> branches(Actor actor) {
        // Reuse catalog eligibility for available branches; the fallback also lets a manager
        // pause a promotion after a participating branch becomes unavailable.
        var publicBranches = indexed(catalog.publicBranches());
        return jdbc.sql(
                        "SELECT b.*,c.logo_png IS NOT NULL AS chain_has_logo,"
                                + " c.version AS chain_version FROM branch b"
                                + " LEFT JOIN chain c ON c.id=b.chain_id WHERE :platform OR EXISTS ("
                                + " SELECT 1 FROM membership m JOIN user_role ur ON"
                                + " ur.user_id=m.user_id AND ur.role=m.role WHERE m.user_id=:actor"
                                + " AND m.branch_id=b.id AND m.active"
                                + " AND m.role IN ('PHARMACY_ADMIN','PHARMACY','PHARMACIST'))"
                                + " ORDER BY b.name,b.id")
                .param("platform", actor.hasRole("ADMIN"))
                .param("actor", actor.id())
                .query(
                        (rs, row) -> {
                            UUID id = rs.getObject("id", UUID.class);
                            return publicBranches.containsKey(id)
                                    ? publicBranches.get(id)
                                    : unavailableBranch(rs);
                        })
                .list();
    }

    @Transactional
    public PromotionDtos.Promotion save(UUID id, PromotionDtos.Input input) {
        var actor = manager();
        validateInput(input);
        UUID actualId = id == null ? UUID.randomUUID() : id;
        if (id != null) {
            var old =
                    jdbc.sql(SELECT + " WHERE p.id=:id FOR UPDATE OF p")
                            .param("id", id)
                            .query(this::row)
                            .optional()
                            .orElseThrow(
                                    () ->
                                            AppException.notFound(
                                                    "PROMOTION_NOT_FOUND",
                                                    "La promoción no está disponible."));
            // Both old and new scope must be allowed, even when removing a branch.
            for (var branch : selectedBranchIds(id)) access.requireBranchRole(branch, MANAGER_ROLES);
            if (old.version() != input.version())
                throw AppException.conflict(
                        "PROMOTION_CHANGED",
                        "La promoción cambió. Actualizá la pantalla antes de guardar.");
        } else if (input.version() != 0) {
            throw AppException.badRequest(
                    "INVALID_PROMOTION_VERSION", "La versión inicial de la promoción debe ser cero.");
        }

        List<UUID> selected = input.branchIds().stream().distinct().sorted().toList();
        for (var branch : selected) access.requireBranchRole(branch, MANAGER_ROLES);
        var branchRows =
                jdbc.sql(
                                "SELECT b.id,b.chain_id,b.active,c.active AS chain_active FROM branch b"
                                        + " LEFT JOIN chain c ON c.id=b.chain_id WHERE b.id IN (:ids)"
                                        + " ORDER BY b.id FOR SHARE OF b")
                        .param("ids", selected)
                        .query(
                                (rs, row) ->
                                        new BranchScope(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("chain_id", UUID.class),
                                                rs.getBoolean("active"),
                                                rs.getBoolean("chain_active")))
                        .list();
        if (branchRows.size() != selected.size())
            throw AppException.badRequest(
                    "PROMOTION_BRANCH_NOT_FOUND", "Alguna sucursal seleccionada ya no existe.");
        UUID chainId = branchRows.getFirst().chainId();
        if (branchRows.stream().anyMatch(b -> !Objects.equals(chainId, b.chainId()))
                || (chainId == null && selected.size() != 1))
            throw AppException.badRequest(
                    "PROMOTION_CHAIN_MISMATCH",
                    "Cada promoción debe corresponder a sucursales de una misma cadena.");
        if (input.active()
                && branchRows.stream()
                        .anyMatch(b -> !b.active() || (b.chainId() != null && !b.chainActive())))
            throw AppException.badRequest(
                    "PROMOTION_BRANCH_INACTIVE",
                    "Una promoción activa requiere sucursales y cadena activas.");
        var values = new HashMap<String, Object>();
        values.put("id", actualId);
        values.put("chain", chainId);
        values.put("title", input.title().trim());
        values.put("description", input.description().trim());
        values.put("starts", Timestamp.from(input.startsAt()));
        values.put("ends", Timestamp.from(input.endsAt()));
        values.put("featured", input.featured());
        values.put("active", input.active());
        values.put("actor", actor.id());
        if (id == null)
            jdbc.sql(
                            "INSERT INTO promotion(id,chain_id,title,description,starts_at,ends_at,"
                                    + " featured,active,created_by,updated_by)"
                                    + " VALUES(:id,:chain,:title,:description,:starts,:ends,:featured,"
                                    + " :active,:actor,:actor)")
                    .params(values)
                    .update();
        else {
            jdbc.sql(
                            "UPDATE promotion SET chain_id=:chain,title=:title,description=:description,"
                                    + " starts_at=:starts,ends_at=:ends,featured=:featured,active=:active,"
                                    + " updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id")
                    .params(values)
                    .update();
            jdbc.sql("DELETE FROM promotion_branch WHERE promotion_id=:id")
                    .param("id", actualId)
                    .update();
        }
        for (var branch : selected)
            jdbc.sql("INSERT INTO promotion_branch(promotion_id,branch_id) VALUES(:id,:branch)")
                    .param("id", actualId)
                    .param("branch", branch)
                    .update();
        audit.record(
                actor,
                id == null ? "PROMOTION_CREATED" : "PROMOTION_UPDATED",
                "promotion",
                actualId,
                Map.of(
                        "title", input.title().trim(),
                        "description", input.description().trim(),
                        "startsAt", input.startsAt(),
                        "endsAt", input.endsAt(),
                        "featured", input.featured(),
                        "active", input.active(),
                        "branchIds", selected,
                        "version", input.version() + 1,
                        "reason", input.reason().trim()));
        return promotion(
                jdbc.sql(SELECT + " WHERE p.id=:id")
                        .param("id", actualId)
                        .query(this::row)
                        .single(),
                indexed(branches(actor)),
                false);
    }

    static void validateInput(PromotionDtos.Input input) {
        if (input.startsAt() == null
                || input.endsAt() == null
                || !input.endsAt().isAfter(input.startsAt()))
            throw AppException.badRequest(
                    "INVALID_PROMOTION_DATES", "El fin debe ser posterior al inicio de la promoción.");
        if (input.branchIds() == null
                || input.branchIds().isEmpty()
                || input.branchIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(input.branchIds()).size() != input.branchIds().size())
            throw AppException.badRequest(
                    "INVALID_PROMOTION_BRANCHES", "Elegí sucursales distintas para la promoción.");
    }

    private PromotionDtos.Promotion promotion(
            Row row, Map<UUID, Branch> availableBranches, boolean publicView) {
        var ids = selectedBranchIds(row.id());
        var branches =
                ids.stream()
                        .map(availableBranches::get)
                        .filter(Objects::nonNull)
                        .filter(b -> !publicView || Objects.equals(b.chainId(), row.chainId()))
                        .toList();
        String chainName = row.chainName();
        if (chainName == null && !branches.isEmpty())
            chainName = branches.getFirst().merchantName();
        return new PromotionDtos.Promotion(
                row.id(),
                row.chainId(),
                chainName,
                row.title(),
                row.description(),
                row.startsAt(),
                row.endsAt(),
                row.featured(),
                row.active(),
                row.version(),
                publicView ? branches.stream().map(Branch::id).toList() : ids,
                branches);
    }

    private List<UUID> selectedBranchIds(UUID id) {
        return jdbc.sql(
                        "SELECT branch_id FROM promotion_branch WHERE promotion_id=:id ORDER BY branch_id")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    private static Map<UUID, Branch> indexed(List<Branch> branches) {
        return branches.stream().collect(Collectors.toMap(Branch::id, b -> b));
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("chain_id", UUID.class),
                rs.getString("chain_name"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                rs.getBoolean("featured"),
                rs.getBoolean("active"),
                rs.getLong("version"));
    }

    private Branch unavailableBranch(ResultSet rs) throws SQLException {
        UUID chain = rs.getObject("chain_id", UUID.class);
        return new Branch(
                rs.getObject("id", UUID.class),
                chain,
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
                "",
                "20.00",
                null,
                null,
                Map.of(),
                rs.getLong("version"),
                false,
                "NONE",
                rs.getBoolean("chain_has_logo")
                        ? "/api/catalog/chains/" + chain + "/logo?v=" + rs.getLong("chain_version")
                        : null);
    }

    private record Row(
            UUID id,
            UUID chainId,
            String chainName,
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            boolean featured,
            boolean active,
            long version) {}

    private record BranchScope(UUID id, UUID chainId, boolean active, boolean chainActive) {}
}
