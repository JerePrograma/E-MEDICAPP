package ar.emedicapp.replacements;

import static ar.emedicapp.purchase.PurchaseStore.*;

import ar.emedicapp.purchase.PurchaseStore;

import java.util.Map;
import java.util.UUID;

/** Called inside the owning fulfillment/cancellation transaction. Never restores old stock. */
public final class ReplacementLifecycle {
    private ReplacementLifecycle() {}

    public static Map<String, Object> lockOrder(PurchaseStore s, UUID orderId) {
        var order = s.order(orderId, false);
        Object requestId = s.snapshot(order).get("replacementRequestId");
        if (requestId != null) {
            var request =
                    s.row(
                            "SELECT original_order_id FROM replacement_request WHERE id=?",
                            id(requestId));
            lockOrder(s, id(request.get("original_order_id")));
            s.row("SELECT id FROM replacement_request WHERE id=? FOR UPDATE", id(requestId));
        }
        return s.order(orderId, true);
    }

    public static void close(
            PurchaseStore s, Map<String, Object> replacementOrder, boolean fulfilled) {
        Object requestId = s.snapshot(replacementOrder).get("replacementRequestId");
        if (requestId == null) return;
        var initial =
                s.row(
                        "SELECT original_order_id FROM replacement_request WHERE id=?",
                        id(requestId));
        var original = lockOrder(s, id(initial.get("original_order_id")));
        var request =
                s.row("SELECT * FROM replacement_request WHERE id=? FOR UPDATE", id(requestId));
        var originalSnapshot = s.snapshot(original);
        originalSnapshot.put("activeReplacementId", null);
        originalSnapshot.put("lastReplacementId", requestId);
        s.exec(
                "UPDATE replacement_request SET status=?,version=version+1,updated_at=now() WHERE"
                    + " id=?",
                fulfilled ? "FULFILLED" : "CANCELLED",
                id(requestId));
        s.exec(
                "UPDATE child_order SET snapshot=cast(? as"
                    + " jsonb),version=version+1,updated_at=now() WHERE id=?",
                s.json.encode(originalSnapshot),
                original.get("id"));
        if (fulfilled) {
            s.exec(
                    "UPDATE fulfillment SET"
                        + " status='RESOLVED',proof_code_hash=NULL,driver_user_id=NULL,version=version+1,updated_at=now()"
                        + " WHERE id=?",
                    request.get("original_fulfillment_id"));
            if (!"COMPLETED".equals(original.get("status")))
                s.exec("UPDATE child_order SET status='RESOLVED' WHERE id=?", original.get("id"));
            s.finishIfComplete(id(original.get("purchase_id")));
        }
    }
}
