import { useState } from 'react';
import { flushSync } from 'react-dom';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { track } from '../appState';
import { Banner, Panel, usePageTitle } from '../components/ui';
import { useSession } from '../session';

const REASONS = {
  expired: 'Your staff session expired. Please sign in again.',
  required: 'Please sign in to continue.',
};

export function LoginPage() {
  usePageTitle('Staff Sign In');
  const navigate = useNavigate();
  const { setOperator } = useSession();
  const [params] = useSearchParams();
  const [error, setError] = useState(null);
  const reason = params.get('reason');
  const reasonText = reason ? (REASONS[reason] ?? 'Please sign in.') : null;

  function handleSubmit(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    track(async () => {
      try {
        const session = await api.signIn(form.get('operatorId'), form.get('password'));
        flushSync(() => {
          setOperator(session.operatorId);
          navigate('/members/search');
        });
      } catch (err) {
        flushSync(() => setError(err.message));
      }
    });
  }

  return (
    <Panel title="Staff sign in">
      {reasonText && !error && <Banner type="warning">{reasonText}</Banner>}
      {error && <Banner type="error">{error}</Banner>}
      <form onSubmit={handleSubmit}>
        <table className="field-table">
          <tbody>
            <tr>
              <th>
                <label htmlFor="operatorId">Operator ID</label>
              </th>
              <td>
                <input id="operatorId" name="operatorId" autoComplete="username" />
              </td>
            </tr>
            <tr>
              <th>
                <label htmlFor="password">Password</label>
              </th>
              <td>
                <input id="password" name="password" type="password" autoComplete="current-password" />
              </td>
            </tr>
            <tr>
              <th>Demo credentials</th>
              <td>
                <span className="muted">operator / password</span>
              </td>
            </tr>
            <tr>
              <th></th>
              <td>
                <button type="submit">Sign in</button>
              </td>
            </tr>
          </tbody>
        </table>
      </form>
    </Panel>
  );
}
