import { useCallback, useEffect, useContext } from 'react';
import { useNavigate } from 'react-router-dom';
import qs from 'qs';
import { CURRENT_URL, USER_INFO_KEY } from '@/constants';
import { loginApi } from '@/apis/login';
import { GlobalContext } from '@/providers/index';

export default function Login() {
  const prevUrl = window.localStorage.getItem(CURRENT_URL);
  const { code } = qs.parse(window.location.href.split('?')[1]);

  const navigate = useNavigate();
  const replace = useCallback((url: string) => navigate(url, { replace: true }), [navigate]);

  const { setUserInfo } = useContext(GlobalContext);

  useEffect(() => {
    if (code) {
      const redirect = prevUrl?.startsWith('/page') ? String(prevUrl) : '/page';
      loginApi(String(code))
        .then((res: any) => {
          if(res) {
            sessionStorage.setItem(USER_INFO_KEY, JSON.stringify(res));
            setUserInfo(() => ({ ...res }));
          }
        })
        .catch((e) => {
          console.log('登录失败', e);
        })
        .finally(() => {
          window.localStorage.removeItem(CURRENT_URL);
          replace(redirect);
        });
    } else {
      replace('/');
    }
  }, [code, prevUrl, replace, setUserInfo]);

  return <div>正在登录中</div>;
}
