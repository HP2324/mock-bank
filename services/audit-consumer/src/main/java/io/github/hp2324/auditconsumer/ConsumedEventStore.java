package io.github.hp2324.auditconsumer;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ConsumedEventStore {
    private final NamedParameterJdbcTemplate jdbc;

    public ConsumedEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredEvent(
            UUID eventId,
            String type,
            String operatorId,
            String memberId,
            String accountNumber,
            String message,
            Instant occurredAt,
            int partition,
            long offset,
            Instant consumedAt
    ) {
    }

    public record TypeCount(String type, long count, Instant lastOccurredAt) {
    }

    /** @return false when this event ID was already stored (a redelivery). */
    public boolean save(AuditEventMessage event, int partition, long offset) {
        int inserted = jdbc.update("""
                INSERT INTO consumed_audit_events
                    (event_id, event_type, operator_id, member_id, account_number, message, occurred_at,
                     kafka_partition, kafka_offset)
                VALUES (:eventId, :type, :operatorId, :memberId, :accountNumber, :message, :occurredAt,
                        :partition, :offset)
                ON CONFLICT (event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("type", event.type())
                .addValue("operatorId", event.operatorId())
                .addValue("memberId", event.memberId())
                .addValue("accountNumber", event.accountNumber())
                .addValue("message", event.message())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("partition", partition)
                .addValue("offset", offset));
        return inserted == 1;
    }

    public List<StoredEvent> recent(int limit) {
        return jdbc.query("""
                SELECT event_id, event_type, operator_id, member_id, account_number, message, occurred_at,
                       kafka_partition, kafka_offset, consumed_at
                FROM consumed_audit_events
                ORDER BY occurred_at DESC, kafka_offset DESC
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), (rs, row) -> new StoredEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("operator_id"),
                rs.getString("member_id"),
                rs.getString("account_number"),
                rs.getString("message"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getInt("kafka_partition"),
                rs.getLong("kafka_offset"),
                rs.getTimestamp("consumed_at").toInstant()
        ));
    }

    public List<TypeCount> countsByType() {
        return jdbc.query("""
                SELECT event_type, COUNT(*) AS event_count, MAX(occurred_at) AS last_occurred_at
                FROM consumed_audit_events
                GROUP BY event_type
                ORDER BY event_count DESC, event_type
                """, (rs, row) -> new TypeCount(
                rs.getString("event_type"),
                rs.getLong("event_count"),
                rs.getTimestamp("last_occurred_at").toInstant()
        ));
    }
}
