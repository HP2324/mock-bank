import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, onUnauthorized } from '../api';

function respond(status, body) {
  return Promise.resolve(new Response(body === undefined ? '' : JSON.stringify(body), { status }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  onUnauthorized(null);
});

describe('api client', () => {
  it('sends JSON and returns the parsed body', async () => {
    const fetchMock = vi.fn(() => respond(201, { accountNumber: 'SAV-345-3' }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.openSubAccount('12345', 'Savings Plus', '100.00');

    expect(result.accountNumber).toBe('SAV-345-3');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/members/12345/subaccounts');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ productName: 'Savings Plus', initialDeposit: '100.00' });
  });

  it('turns error responses into ApiError with the server code and message', async () => {
    vi.stubGlobal('fetch', () => respond(404, { code: 'MEMBER_NOT_FOUND', message: 'No member record was found for ID 00000.' }));

    const error = await api.lookupMember('00000').catch((err) => err);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.code).toBe('MEMBER_NOT_FOUND');
    expect(error.message).toBe('No member record was found for ID 00000.');
  });

  it('encodes member IDs typed by the operator', async () => {
    const fetchMock = vi.fn(() => respond(400, { code: 'INVALID_MEMBER_ID', message: 'Invalid' }));
    vi.stubGlobal('fetch', fetchMock);

    await api.lookupMember('12 34&x').catch(() => {});

    expect(fetchMock.mock.calls[0][0]).toBe('/api/members/lookup?memberId=12%2034%26x');
  });

  it('notifies the unauthorized handler on 401 from signed-in endpoints only', async () => {
    const handler = vi.fn();
    onUnauthorized(handler);
    vi.stubGlobal('fetch', () => respond(401, { code: 'AUTHENTICATION_REQUIRED', message: 'Please sign in to continue.' }));

    await api.getMember('12345').catch(() => {});
    await api.signIn('operator', 'wrong').catch(() => {});

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('reports network failures as ApiError status 0', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('Failed to fetch')));

    const error = await api.auditEvents().catch((err) => err);

    expect(error.status).toBe(0);
    expect(error.code).toBe('NETWORK_ERROR');
  });
});
