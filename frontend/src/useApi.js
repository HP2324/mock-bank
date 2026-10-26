import { useLayoutEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { beginWork, endWork } from './appState';

/**
 * Loads data for a page and keeps the app "busy" until the result is on screen.
 * Returns { status: 'loading' | 'ready' | 'error', data, error }.
 */
export function useApi(load, deps) {
  const [state, setState] = useState({ status: 'loading' });

  useLayoutEffect(() => {
    let active = true;
    let finished = false;
    const finish = () => {
      if (!finished) {
        finished = true;
        endWork();
      }
    };

    beginWork();
    setState((current) => (current.status === 'loading' ? current : { status: 'loading' }));
    load()
      .then(
        (data) => active && flushSync(() => setState({ status: 'ready', data })),
        (error) => active && flushSync(() => setState({ status: 'error', error })),
      )
      .finally(finish);

    return () => {
      active = false;
      finish();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return state;
}
