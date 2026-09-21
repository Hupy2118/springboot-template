import { useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import qs from 'qs';
import { CURRENT_URL, USER_INFO_KEY } from '@/constants/login';
import { PAGE_ROUTE } from '@/constants/routes';
import { useIdentity } from '@/platform/identity/useIdentity';
import { loginApi } from '@/apis/login';

export default function LoginPage() {
  const previousUrl = window.localStorage.getItem(CURRENT_URL);
  const { code } = qs.parse(window.location.href.split('?')[1]);
  const navigate = useNavigate();
  const { setIdentity } = useIdentity();
  const replace = useCallback((url: string) => navigate(url, { replace: true }), [navigate]);

  useEffect(() => {
    if (!code) {
      replace(`/${PAGE_ROUTE}`);
      return;
    }
    const redirect = previousUrl?.startsWith(`/${PAGE_ROUTE}`) ? previousUrl : `/${PAGE_ROUTE}`;
    loginApi(String(code))
      .then((identity) => {
        sessionStorage.setItem(USER_INFO_KEY, JSON.stringify(identity));
        setIdentity(identity);
      })
      .catch((error) => console.error('登录失败', error))
      .finally(() => {
        window.localStorage.removeItem(CURRENT_URL);
        replace(redirect);
      });
  }, [code, previousUrl, replace, setIdentity]);
  return <div>正在登录中</div>;
}
