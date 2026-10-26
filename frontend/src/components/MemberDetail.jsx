import { Link } from 'react-router-dom';
import { formatMoney } from '../format';
import { FieldTable, Panel } from './ui';

export const PERMISSION_DENIED_MESSAGE =
  'Permission denied. This member is restricted and requires a supervisor override.';

export function MemberDetail({ member, banner = null }) {
  const action = member.canOpenSubAccount ? (
    <Link className="button" to={`/members/${member.id}/subaccounts/new`}>
      Open sub-account
    </Link>
  ) : (
    <span className="muted">Sub-account opening disabled for restricted members.</span>
  );

  return (
    <Panel title="Member detail">
      {banner}
      <FieldTable
        rows={[
          ['Member ID', member.id],
          ['Name', member.name],
          ['Status', member.status],
          ['Available action', action],
        ]}
      />
      <h2>Accounts</h2>
      <table className="data-grid">
        <thead>
          <tr>
            <th>Account number</th>
            <th>Account type</th>
            <th>Current balance</th>
          </tr>
        </thead>
        <tbody>
          {member.accounts.map((account) => (
            <tr key={account.number}>
              <td>{account.number}</td>
              <td>{account.type}</td>
              <td>{formatMoney(account.balance)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <Link className="button secondary" to="/members/search">
          Back to search
        </Link>
      </p>
    </Panel>
  );
}
