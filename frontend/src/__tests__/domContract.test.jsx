// The Python automation replays saved artifacts against these exact ids, button/link texts,
// banner classes and <th>/<td> labels. These tests fail if the UI drifts from that contract.
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { SessionProvider } from '../session';

const mocks = vi.hoisted(() => ({
  currentSession: vi.fn(),
  signIn: vi.fn(),
  lookupMember: vi.fn(),
  getMember: vi.fn(),
  openSubAccount: vi.fn(),
}));

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal()),
  api: mocks,
}));

const { ApiError } = await import('../api');

const jordan = {
  id: '12345',
  name: 'Jordan Lee',
  status: 'Active',
  canOpenSubAccount: true,
  accounts: [{ number: 'SAV-1001', type: 'Primary Savings', balance: 1842.77 }],
};
const riley = { ...jordan, id: '77777', name: 'Riley Morgan', status: 'Restricted', canOpenSubAccount: false };

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: false, v7_relativeSplatPath: true }}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}

const fill = (selector, value) => fireEvent.change(document.querySelector(selector), { target: { value } });
const valueFor = (label) =>
  [...document.querySelectorAll('tr')].find((tr) => tr.querySelector('th')?.textContent.trim() === label)
    ?.querySelector('td')?.textContent;

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset());
  mocks.currentSession.mockResolvedValue({ operatorId: 'operator' });
  mocks.getMember.mockImplementation(async (id) => (id === '77777' ? riley : jordan));
});

afterEach(cleanup);

describe('DOM contract used by automation artifacts', () => {
  it('login page exposes #operatorId, #password and a Sign in button', async () => {
    mocks.currentSession.mockRejectedValue(new ApiError(401, 'AUTHENTICATION_REQUIRED', 'Please sign in.'));
    mocks.signIn.mockResolvedValue({ operatorId: 'operator' });
    renderAt('/login');

    const submit = await screen.findByRole('button', { name: 'Sign in' });
    fill('#operatorId', 'operator');
    fill('#password', 'password');
    fireEvent.click(submit);

    expect(await screen.findByRole('button', { name: 'Search member records' })).toBeTruthy();
    expect(document.querySelector('#memberId')).not.toBeNull();
    expect(mocks.signIn).toHaveBeenCalledWith('operator', 'password');
  });

  it('member not found shows a .banner.warning', async () => {
    mocks.lookupMember.mockRejectedValue(new ApiError(404, 'MEMBER_NOT_FOUND', 'No member record was found for ID 00000.'));
    renderAt('/members/search');

    const submit = await screen.findByRole('button', { name: 'Search member records' });
    fill('#memberId', '00000');
    fireEvent.click(submit);

    await waitFor(() => expect(document.querySelector('.banner.warning')?.textContent).toContain('No member record was found'));
  });

  it('invalid member ID shows a .banner.error', async () => {
    mocks.lookupMember.mockRejectedValue(new ApiError(400, 'INVALID_MEMBER_ID', 'Invalid member ID. Enter exactly five digits.'));
    renderAt('/members/search');

    const submit = await screen.findByRole('button', { name: 'Search member records' });
    fill('#memberId', 'abc');
    fireEvent.click(submit);

    await waitFor(() => expect(document.querySelector('.banner.error')?.textContent).toContain('Invalid member ID'));
  });

  it('member detail offers an "Open sub-account" link', async () => {
    renderAt('/members/12345');

    const link = await screen.findByRole('link', { name: 'Open sub-account' });
    expect(link.getAttribute('href')).toBe('/members/12345/subaccounts/new');
  });

  it('restricted member shows a Permission denied .banner.error', async () => {
    renderAt('/members/77777/subaccounts/new');

    await waitFor(() => expect(document.querySelector('.banner.error')?.textContent).toContain('Permission denied'));
    expect(screen.queryByRole('link', { name: 'Open sub-account' })).toBeNull();
  });

  it('deposit over the threshold shows a Human approval required .banner.warning', async () => {
    mocks.openSubAccount.mockRejectedValue(
      new ApiError(409, 'HUMAN_APPROVAL_REQUIRED', 'Human approval required. Initial deposits over $5,000 are treated as risky actions.'),
    );
    renderAt('/members/12345/subaccounts/new');

    const submit = await screen.findByRole('button', { name: 'Open sub-account' });
    fill('#productName', 'Savings Plus');
    fill('#initialDeposit', '5000.01');
    fireEvent.click(submit);

    await waitFor(() => expect(document.querySelector('.banner.warning')?.textContent).toContain('Human approval required'));
    expect(document.querySelector('#initialDeposit').value).toBe('5000.01');
  });

  it('confirmation exposes #confirmation-code and labelled outputs', async () => {
    mocks.openSubAccount.mockResolvedValue({
      memberId: '12345',
      memberName: 'Jordan Lee',
      accountNumber: 'SAV-345-3',
      product: 'Savings Plus',
      openingBalance: 100,
      checkpoint: 'CONFIRM-SUBACCOUNT-CREATED',
    });
    renderAt('/members/12345/subaccounts/new');

    const submit = await screen.findByRole('button', { name: 'Open sub-account' });
    fill('#productName', 'Savings Plus');
    fill('#initialDeposit', '100.00');
    fireEvent.click(submit);

    await waitFor(() => expect(document.querySelector('#confirmation-code')?.textContent).toBe('CONFIRM-SUBACCOUNT-CREATED'));
    expect(valueFor('Member ID')).toBe('12345');
    expect(valueFor('Member name')).toBe('Jordan Lee');
    expect(valueFor('New account number')).toBe('SAV-345-3');
    expect(valueFor('Product')).toBe('Savings Plus');
    expect(valueFor('Opening balance')).toBe('$100.00');
  });

  it('marks the page idle once data is on screen', async () => {
    let resolveMember;
    mocks.getMember.mockReturnValue(new Promise((resolve) => (resolveMember = resolve)));
    renderAt('/members/12345');

    await waitFor(() => expect(mocks.getMember).toHaveBeenCalled());
    expect(document.documentElement.dataset.appState).toBe('busy');

    await act(async () => resolveMember(jordan));

    expect(document.documentElement.dataset.appState).toBe('idle');
    expect(screen.getByText('Jordan Lee')).toBeTruthy();
  });
});
