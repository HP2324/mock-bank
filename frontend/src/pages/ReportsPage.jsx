import { api } from '../api';
import { Banner, Panel, usePageTitle } from '../components/ui';
import { formatMoney, formatTimestamp } from '../format';
import { useApi } from '../useApi';

async function loadReports() {
  const [portfolios, openings, activity] = await Promise.all([
    api.memberPortfolios(),
    api.subAccountOpenings(20),
    api.memberActivity(),
  ]);
  return { portfolios, openings, activity };
}

export function ReportsPage() {
  usePageTitle('Reports');
  const reports = useApi(loadReports, []);

  if (reports.status !== 'ready') {
    return (
      <Panel title="Reports">
        {reports.status === 'loading' && <p className="muted">Running reports…</p>}
        {reports.status === 'error' && <Banner type="error">{reports.error.message}</Banner>}
      </Panel>
    );
  }

  const { portfolios, openings, activity } = reports.data;
  return (
    <>
      <Panel title="Member portfolios">
        <table className="data-grid">
          <thead>
            <tr>
              <th>Member ID</th>
              <th>Name</th>
              <th>Status</th>
              <th className="num">Accounts</th>
              <th className="num">Total balance</th>
              <th>Last account opened</th>
            </tr>
          </thead>
          <tbody>
            {portfolios.map((row) => (
              <tr key={row.memberId}>
                <td>{row.memberId}</td>
                <td>{row.name}</td>
                <td>{row.status}</td>
                <td className="num">{row.accountCount}</td>
                <td className="num">{formatMoney(row.totalBalance)}</td>
                <td>{formatTimestamp(row.lastAccountOpenedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      <Panel title="Recent sub-account openings">
        <table className="data-grid">
          <thead>
            <tr>
              <th>Opened</th>
              <th>Account number</th>
              <th>Product</th>
              <th className="num">Opening balance</th>
              <th>Member</th>
              <th>Opened by</th>
            </tr>
          </thead>
          <tbody>
            {openings.length === 0 && (
              <tr>
                <td colSpan={6} className="muted">
                  No sub-accounts have been opened from the console yet.
                </td>
              </tr>
            )}
            {openings.map((row) => (
              <tr key={row.accountNumber}>
                <td className="nowrap">{formatTimestamp(row.openedAt)}</td>
                <td>{row.accountNumber}</td>
                <td>{row.product}</td>
                <td className="num">{formatMoney(row.openingBalance)}</td>
                <td>
                  {row.memberName} ({row.memberId})
                </td>
                <td>{row.openedBy ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      <Panel title="Member activity">
        <table className="data-grid">
          <thead>
            <tr>
              <th>Member</th>
              <th className="num">Sub-accounts opened</th>
              <th className="num">Approvals requested</th>
              <th className="num">Permission denials</th>
              <th>Last activity</th>
            </tr>
          </thead>
          <tbody>
            {activity.map((row) => (
              <tr key={row.memberId}>
                <td>
                  {row.name} ({row.memberId})
                </td>
                <td className="num">{row.subAccountsOpened}</td>
                <td className="num">{row.approvalsRequested}</td>
                <td className="num">{row.permissionDenials}</td>
                <td>{formatTimestamp(row.lastActivityAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
    </>
  );
}
