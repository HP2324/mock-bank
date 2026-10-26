import { api } from '../api';
import { Banner, Panel, usePageTitle } from '../components/ui';
import { formatTimestamp, humanizeEventType } from '../format';
import { useApi } from '../useApi';

export function AuditLogPage() {
  usePageTitle('Audit Log');
  const events = useApi(() => api.auditEvents(100), []);

  return (
    <Panel title="Audit log">
      <p className="muted">Every event below is also published to the Kafka topic mockbank.audit-events.</p>
      {events.status === 'loading' && <p className="muted">Loading events…</p>}
      {events.status === 'error' && <Banner type="error">{events.error.message}</Banner>}
      {events.status === 'ready' && (
        <table className="data-grid">
          <thead>
            <tr>
              <th>Time</th>
              <th>Event</th>
              <th>Operator</th>
              <th>Member</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {events.data.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">
                  No audit events yet.
                </td>
              </tr>
            )}
            {events.data.map((event) => (
              <tr key={event.eventId}>
                <td className="nowrap">{formatTimestamp(event.occurredAt)}</td>
                <td>{humanizeEventType(event.type)}</td>
                <td>{event.operatorId ?? '—'}</td>
                <td>{event.memberId ?? '—'}</td>
                <td>{event.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
