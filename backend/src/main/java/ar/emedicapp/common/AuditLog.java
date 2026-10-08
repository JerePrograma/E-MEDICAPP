package ar.emedicapp.common;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AuditLog {
    private final JdbcClient jdbc;
    private final Json json;

    public AuditLog(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(
            Actor actor,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, ?> details) {
        jdbc.sql(
                        """
                INSERT INTO audit_event (id, actor_user_id, action, entity_type, entity_id, after_data, reason)
                VALUES (:id, :actorId, :action, :type, :resourceId, CAST(:details AS jsonb), :reason)
                """)
                .param("id", UUID.randomUUID())
                .param("actorId", actor == null ? null : actor.id())
                .param("action", action)
                .param("type", resourceType)
                .param("resourceId", resourceId)
                .param("details", json.encode(details == null ? Map.of() : details))
                .param("reason", "APPLICATION_ACTION")
                .update();
    }
}
