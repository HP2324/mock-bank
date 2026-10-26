import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { Panel, usePageTitle } from './components/ui';
import { AuditLogPage } from './pages/AuditLogPage';
import { LoginPage } from './pages/LoginPage';
import { MemberDetailPage } from './pages/MemberDetailPage';
import { MemberSearchPage } from './pages/MemberSearchPage';
import { OpenSubAccountPage } from './pages/OpenSubAccountPage';
import { ReportsPage } from './pages/ReportsPage';
import { RequireOperator, useSession } from './session';

// Routes mirror the legacy server-rendered URLs so saved automation artifacts keep working.
export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireOperator />}>
          <Route path="members/search" element={<MemberSearchPage />} />
          <Route path="members/:memberId" element={<MemberDetailPage />} />
          <Route path="members/:memberId/subaccounts/new" element={<OpenSubAccountPage />} />
          <Route path="ops/audit" element={<AuditLogPage />} />
          <Route path="ops/reports" element={<ReportsPage />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

function Home() {
  const { status, operator } = useSession();
  if (status === 'loading') {
    return null;
  }
  return <Navigate to={operator ? '/members/search' : '/login'} replace />;
}

function NotFound() {
  usePageTitle('Page Not Found');
  return (
    <Panel title="Page not found">
      <p className="muted">That page does not exist in the staff console.</p>
    </Panel>
  );
}
