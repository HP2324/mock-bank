import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { flushSync } from 'react-dom';
import { Navigate, Outlet, useNavigate } from 'react-router-dom';
import { api, onUnauthorized } from './api';
import { releaseInitialLoad } from './appState';

const SessionContext = createContext(null);

export function SessionProvider({ children }) {
  const navigate = useNavigate();
  const [state, setState] = useState({ status: 'loading', operator: null });

  useEffect(() => {
    let active = true;
    api
      .currentSession()
      .then((session) => session.operatorId, () => null)
      .then((operator) => {
        if (!active) {
          return;
        }
        flushSync(() => setState({ status: 'ready', operator }));
        // The routed page has mounted (and marked its own loading work) by now.
        releaseInitialLoad();
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    onUnauthorized(() => {
      flushSync(() => {
        setState({ status: 'ready', operator: null });
        navigate('/login?reason=expired');
      });
    });
    return () => onUnauthorized(null);
  }, [navigate]);

  const value = useMemo(
    () => ({
      status: state.status,
      operator: state.operator,
      setOperator: (operator) => setState({ status: 'ready', operator }),
    }),
    [state],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  return useContext(SessionContext);
}

/** Route guard for pages that need a signed-in operator. */
export function RequireOperator() {
  const { status, operator } = useSession();
  if (status === 'loading') {
    return null;
  }
  if (!operator) {
    return <Navigate to="/login?reason=required" replace />;
  }
  return <Outlet />;
}
