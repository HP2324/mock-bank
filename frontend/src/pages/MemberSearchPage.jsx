import { useState } from 'react';
import { flushSync } from 'react-dom';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { track } from '../appState';
import { Banner, bannerTypeFor, Panel, usePageTitle } from '../components/ui';

export function MemberSearchPage() {
  usePageTitle('Member Lookup');
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [banner, setBanner] = useState(null);

  function handleSubmit(event) {
    event.preventDefault();
    const memberId = String(new FormData(event.currentTarget).get('memberId') ?? '').trim();
    track(async () => {
      try {
        const member = await api.lookupMember(memberId);
        flushSync(() => navigate(`/members/${member.id}`));
      } catch (err) {
        if (err.status !== 401) {
          flushSync(() => setBanner({ type: bannerTypeFor(err), text: err.message }));
        }
      }
    });
  }

  const shown =
    banner ?? (params.get('status') ? { type: 'success', text: 'Ready for next member lookup.' } : null);

  return (
    <Panel title="Member lookup">
      {shown && <Banner type={shown.type}>{shown.text}</Banner>}
      <form onSubmit={handleSubmit}>
        <table className="field-table">
          <tbody>
            <tr>
              <th>
                <label htmlFor="memberId">Member ID</label>
              </th>
              <td>
                <input id="memberId" name="memberId" inputMode="numeric" />
              </td>
            </tr>
            <tr>
              <th>Examples</th>
              <td>
                <span className="muted">12345 happy path, 77777 restricted, 00000 not found</span>
              </td>
            </tr>
            <tr>
              <th></th>
              <td>
                <button type="submit">Search member records</button>
              </td>
            </tr>
          </tbody>
        </table>
      </form>
    </Panel>
  );
}
