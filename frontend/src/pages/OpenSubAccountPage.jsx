import { useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import { track } from '../appState';
import { MemberDetail, PERMISSION_DENIED_MESSAGE } from '../components/MemberDetail';
import { Banner, bannerTypeFor, FieldTable, Panel, usePageTitle } from '../components/ui';
import { formatMoney } from '../format';
import { useApi } from '../useApi';

export function OpenSubAccountPage() {
  const { memberId } = useParams();
  // A new member ID starts from a clean form rather than a previous confirmation.
  return <OpenSubAccount key={memberId} memberId={memberId} />;
}

function OpenSubAccount({ memberId }) {
  const member = useApi(() => api.getMember(memberId), [memberId]);
  const [banner, setBanner] = useState(null);
  const [denied, setDenied] = useState(false);
  const [confirmation, setConfirmation] = useState(null);
  const submitting = useRef(false);

  const title = confirmation
    ? 'Sub-Account Confirmation'
    : banner?.code === 'HUMAN_APPROVAL_REQUIRED'
      ? 'Human Approval Required'
      : 'Open Sub-Account';
  usePageTitle(title);

  function handleSubmit(event) {
    event.preventDefault();
    if (submitting.current) {
      return;
    }
    submitting.current = true;
    const form = new FormData(event.currentTarget);
    track(async () => {
      try {
        const result = await api.openSubAccount(memberId, form.get('productName'), form.get('initialDeposit'));
        flushSync(() => setConfirmation(result));
      } catch (err) {
        if (err.status === 401) {
          return;
        }
        flushSync(() => {
          if (err.status === 403) {
            setDenied(true);
          } else {
            const type = err.code === 'HUMAN_APPROVAL_REQUIRED' ? 'warning' : bannerTypeFor(err);
            setBanner({ type, code: err.code, text: err.message });
          }
        });
      } finally {
        submitting.current = false;
      }
    });
  }

  if (member.status === 'loading') {
    return (
      <Panel title="Open sub-account">
        <p className="muted">Loading member…</p>
      </Panel>
    );
  }
  if (member.status === 'error') {
    return (
      <Panel title="Open sub-account">
        <Banner type={bannerTypeFor(member.error)}>{member.error.message}</Banner>
      </Panel>
    );
  }
  if (denied || !member.data.canOpenSubAccount) {
    return <MemberDetail member={member.data} banner={<Banner type="error">{PERMISSION_DENIED_MESSAGE}</Banner>} />;
  }
  if (confirmation) {
    return <Confirmation confirmation={confirmation} />;
  }

  const { id, name, status } = member.data;
  return (
    <Panel title="Open sub-account">
      {banner && <Banner type={banner.type}>{banner.text}</Banner>}
      <FieldTable
        rows={[
          ['Member ID', id],
          ['Name', name],
          ['Status', status],
        ]}
      />
      <form onSubmit={handleSubmit}>
        <table className="field-table">
          <tbody>
            <tr>
              <th>
                <label htmlFor="productName">Product name</label>
              </th>
              <td>
                <input id="productName" name="productName" placeholder="Savings Plus" />
              </td>
            </tr>
            <tr>
              <th>
                <label htmlFor="initialDeposit">Initial deposit</label>
              </th>
              <td>
                <input id="initialDeposit" name="initialDeposit" placeholder="100.00" inputMode="decimal" />
              </td>
            </tr>
            <tr>
              <th>Risk rule</th>
              <td>
                <span className="muted">Deposits over $5,000 require human approval.</span>
              </td>
            </tr>
            <tr>
              <th></th>
              <td>
                <button type="submit">Open sub-account</button>{' '}
                <Link className="button secondary" to={`/members/${id}`}>
                  Cancel
                </Link>
              </td>
            </tr>
          </tbody>
        </table>
      </form>
    </Panel>
  );
}

function Confirmation({ confirmation }) {
  return (
    <Panel title="Confirmation">
      <Banner type="success">Sub-account opened successfully.</Banner>
      <FieldTable
        rows={[
          ['Member ID', confirmation.memberId],
          ['Member name', confirmation.memberName],
          ['New account number', confirmation.accountNumber],
          ['Product', confirmation.product],
          ['Opening balance', formatMoney(confirmation.openingBalance)],
          ['Checkpoint', confirmation.checkpoint, { id: 'confirmation-code' }],
        ]}
      />
      <p>
        <Link className="button" to={`/members/${confirmation.memberId}`}>
          Return to member detail
        </Link>{' '}
        <Link className="button secondary" to="/members/search?status=ready">
          New lookup
        </Link>
      </p>
    </Panel>
  );
}
