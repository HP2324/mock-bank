import { NavLink, Outlet } from 'react-router-dom';
import { useSession } from '../session';

export function Layout() {
  const { operator } = useSession();
  return (
    <>
      <div className="topbar">
        <table>
          <tbody>
            <tr>
              <td>
                <strong>Northstar Credit Union - Staff Console</strong>
              </td>
              <td style={{ textAlign: 'right' }}>
                Operator: {operator ?? 'Not signed in'}
                {operator && (
                  <>
                    {' '}
                    &nbsp;|&nbsp; <a href="/logout">Sign out</a>
                  </>
                )}
              </td>
            </tr>
          </tbody>
        </table>
        {operator && (
          <nav className="nav" aria-label="Main">
            <NavLink to="/members/search">Member lookup</NavLink>
            <NavLink to="/ops/audit">Audit log</NavLink>
            <NavLink to="/ops/reports">Reports</NavLink>
          </nav>
        )}
      </div>
      <main className="shell">
        <Outlet />
      </main>
    </>
  );
}
