-- Supports the reporting joins in ReportingRepository (per-member activity, opening history).
CREATE INDEX audit_events_member_created_idx ON audit_events (member_id, created_at DESC);
CREATE INDEX audit_events_type_account_idx ON audit_events (event_type, account_number);
CREATE INDEX audit_events_created_idx ON audit_events (created_at DESC, id DESC);
