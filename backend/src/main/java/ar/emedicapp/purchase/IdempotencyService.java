package ar.emedicapp.purchase;

import ar.emedicapp.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Runs inside the caller's transaction, including its durable result and domain changes. */
@Service
public class IdempotencyService {
    private final JdbcClient db;
    private final Json json;
    private final PurchaseStore store;

    public IdempotencyService(JdbcClient db, Json json, PurchaseStore store) {
        this.db = db;
        this.json = json;
        this.store = store;
    }

    public Map<String, Object> run(
            Actor actor,
            String scope,
            String key,
            Object request,
            Supplier<Map<String, Object>> action) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw AppException.badRequest(
                    "IDEMPOTENCY_REQUIRED",
                    "Enviá una clave de operación de hasta 128 caracteres.");
        String hash = hash(json.encode(request));
        UUID id = UUID.randomUUID();
        int inserted =
                db.sql(
                                "INSERT INTO idempotency_record(id,actor_user_id,scope,request_key,request_hash,status,expires_at) VALUES(?,?,?,?,?,'PROCESSING',now()+interval '30 days') ON CONFLICT(actor_user_id,scope,request_key) DO NOTHING")
                        .params(id, actor.id(), scope, key, hash)
                        .update();
        Map<String, Object> existing =
                db.sql(
                                "SELECT * FROM idempotency_record WHERE actor_user_id=? AND scope=? AND request_key=? FOR UPDATE")
                        .params(actor.id(), scope, key)
                        .query()
                        .singleRow();
        if (!hash.equals(existing.get("request_hash")))
            throw AppException.conflict(
                    "IDEMPOTENCY_MISMATCH",
                    "Esta clave ya se usó con otros datos. Consultá la operación original.");
        if (inserted == 0) {
            if ("SUCCEEDED".equals(existing.get("status"))) {
                Map<String, Object> saved =
                        json.readMap(String.valueOf(existing.get("response_body")));
                // The effect remains durable; the response is projected through current access
                // permissions.
                return saved.containsKey("orders") && saved.get("id") != null
                        ? store.view(PurchaseStore.id(saved.get("id")))
                        : saved;
            }
            throw AppException.conflict(
                    "OPERATION_PENDING",
                    "La operación original sigue pendiente; consultá su estado.");
        }
        Map<String, Object> result = action.get();
        db.sql(
                        "UPDATE idempotency_record SET status='SUCCEEDED',response_status=?,response_body=cast(? as jsonb) WHERE id=?")
                .params(
                        Boolean.TRUE.equals(result.get("receiptRejected")) ? 409 : 200,
                        json.encode(result),
                        id)
                .update();
        return result;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
