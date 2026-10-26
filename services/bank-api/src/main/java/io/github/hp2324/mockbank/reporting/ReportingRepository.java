package io.github.hp2324.mockbank.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Read-only reporting queries written as plain SQL against the Flyway-managed schema. */
@Repository
public class ReportingRepository {
    /** Every member with account count and total balance; members without accounts still appear. */
    static final String MEMBER_PORTFOLIOS = """
            SELECT m.member_id,
                   m.name,
                   m.status,
                   COUNT(a.id)                 AS account_count,
                   COALESCE(SUM(a.balance), 0) AS total_balance,
                   MAX(a.opened_at)            AS last_account_opened_at
            FROM members m
            LEFT JOIN accounts a ON a.member_id = m.member_id
            GROUP BY m.member_id, m.name, m.status
            ORDER BY total_balance DESC, m.member_id
            """;

    /** Accounts opened through the console, with the member and the operator from the audit log. */
    static final String RECENT_SUBACCOUNT_OPENINGS = """
            SELECT a.account_number,
                   a.account_type,
                   a.balance,
                   a.opened_at,
                   m.member_id,
                   m.name        AS member_name,
                   e.operator_id AS opened_by
            FROM accounts a
            JOIN members m      ON m.member_id = a.member_id
            JOIN audit_events e ON e.account_number = a.account_number
                               AND e.event_type = 'SUBACCOUNT_OPENED'
            ORDER BY a.opened_at DESC, a.id DESC
            LIMIT :limit
            """;

    /** Per-member counts of audited actions; members with no activity are listed last. */
    static final String MEMBER_ACTIVITY = """
            SELECT m.member_id,
                   m.name,
                   COUNT(e.id) FILTER (WHERE e.event_type = 'SUBACCOUNT_OPENED')       AS subaccounts_opened,
                   COUNT(e.id) FILTER (WHERE e.event_type = 'HUMAN_APPROVAL_REQUIRED') AS approvals_requested,
                   COUNT(e.id) FILTER (WHERE e.event_type = 'PERMISSION_DENIED')       AS permission_denials,
                   MAX(e.created_at)                                                   AS last_activity_at
            FROM members m
            LEFT JOIN audit_events e ON e.member_id = m.member_id
            GROUP BY m.member_id, m.name
            ORDER BY last_activity_at DESC NULLS LAST, m.member_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MemberPortfolio> memberPortfolios() {
        return jdbc.query(MEMBER_PORTFOLIOS, (rs, row) -> new MemberPortfolio(
                rs.getString("member_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getInt("account_count"),
                rs.getBigDecimal("total_balance"),
                instant(rs, "last_account_opened_at")
        ));
    }

    public List<SubAccountOpening> recentSubAccountOpenings(int limit) {
        return jdbc.query(RECENT_SUBACCOUNT_OPENINGS, new MapSqlParameterSource("limit", limit), (rs, row) -> new SubAccountOpening(
                rs.getString("account_number"),
                rs.getString("account_type"),
                rs.getBigDecimal("balance"),
                instant(rs, "opened_at"),
                rs.getString("member_id"),
                rs.getString("member_name"),
                rs.getString("opened_by")
        ));
    }

    public List<MemberActivity> memberActivity() {
        return jdbc.query(MEMBER_ACTIVITY, (rs, row) -> new MemberActivity(
                rs.getString("member_id"),
                rs.getString("name"),
                rs.getInt("subaccounts_opened"),
                rs.getInt("approvals_requested"),
                rs.getInt("permission_denials"),
                instant(rs, "last_activity_at")
        ));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
