import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { IdentityContext } from '@/platform/identity/IdentityContext';
import { AUTHORIZATION_KEY } from '@/constants';
import { USER_INFO_KEY } from '@/constants/login';
import service from '@/apis/service';
import LogoutPage from '@/pages/Logout';

test('logout clears identity and cached credentials only after the server clears its cookie', async () => {
  let complete: () => void = () => undefined;
  jest.spyOn(service, 'post').mockReturnValue(new Promise<void>((resolve) => { complete = resolve; }));
  const setIdentity = jest.fn();
  sessionStorage.setItem(USER_INFO_KEY, '{"userId":"member-001"}');
  sessionStorage.setItem(AUTHORIZATION_KEY, 'local-test-token');
  render(
    <IdentityContext.Provider value={{ identity: { userId: 'member-001', userName: 'Local Admin' }, setIdentity }}>
      <MemoryRouter initialEntries={['/logout']}>
        <Routes>
          <Route path="/logout" element={<LogoutPage />} />
          <Route path="/" element={<div>已返回首页</div>} />
        </Routes>
      </MemoryRouter>
    </IdentityContext.Provider>,
  );
  expect(service.post).toHaveBeenCalledWith('/api/standalone/logout');
  expect(setIdentity).not.toHaveBeenCalled();
  expect(sessionStorage.getItem(AUTHORIZATION_KEY)).toBe('local-test-token');
  complete();
  await waitFor(() => expect(screen.getByText('已返回首页')).toBeInTheDocument());
  expect(setIdentity).toHaveBeenCalledWith(null);
  expect(sessionStorage.getItem(USER_INFO_KEY)).toBeNull();
  expect(sessionStorage.getItem(AUTHORIZATION_KEY)).toBeNull();
});
