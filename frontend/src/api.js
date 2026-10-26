export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

let unauthorizedHandler = null;

/** Called when a signed-in API call returns 401 (expired or missing session). */
export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

async function readJson(response) {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export async function request(method, path, body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'The staff console could not reach the server.');
  }

  const payload = await readJson(response);
  if (response.ok) {
    return payload;
  }
  const error = new ApiError(
    response.status,
    payload?.code ?? `HTTP_${response.status}`,
    payload?.message ?? `Request failed with status ${response.status}.`,
  );
  if (response.status === 401 && !path.startsWith('/api/session') && unauthorizedHandler) {
    unauthorizedHandler(error);
  }
  throw error;
}

const member = (memberId) => `/api/members/${encodeURIComponent(memberId)}`;

export const api = {
  currentSession: () => request('GET', '/api/session'),
  signIn: (operatorId, password) => request('POST', '/api/session', { operatorId, password }),
  lookupMember: (memberId) => request('GET', `/api/members/lookup?memberId=${encodeURIComponent(memberId)}`),
  getMember: (memberId) => request('GET', member(memberId)),
  openSubAccount: (memberId, productName, initialDeposit) =>
    request('POST', `${member(memberId)}/subaccounts`, { productName, initialDeposit }),
  auditEvents: (limit = 100) => request('GET', `/api/audit-events?limit=${limit}`),
  memberPortfolios: () => request('GET', '/api/reports/member-portfolios'),
  subAccountOpenings: (limit = 20) => request('GET', `/api/reports/subaccount-openings?limit=${limit}`),
  memberActivity: () => request('GET', '/api/reports/member-activity'),
};
