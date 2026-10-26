// The automation layer (replay.py / browser_discovery.py) waits until
// <html data-app-state> is not "busy" after every step. An SPA renders after a fetch resolves, so
// without this signal a business-outcome banner could appear only after replay looked for it.
//
// Rules that keep the signal honest:
//   - work starts synchronously, inside the click/submit handler or a layout effect;
//   - results are applied with flushSync before the work ends, so the DOM is already updated.

let pending = 1; // the initial page load; released by releaseInitialLoad()
let initialLoadReleased = false;

function publish() {
  document.documentElement.dataset.appState = pending > 0 ? 'busy' : 'idle';
}

export function beginWork() {
  pending += 1;
  publish();
}

export function endWork() {
  pending = Math.max(0, pending - 1);
  publish();
}

export function releaseInitialLoad() {
  if (initialLoadReleased) {
    return;
  }
  initialLoadReleased = true;
  endWork();
}

export async function track(work) {
  beginWork();
  try {
    return await work();
  } finally {
    endWork();
  }
}
