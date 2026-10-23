import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { AUTHORIZATION_KEY } from '@/constants';
import { USER_INFO_KEY } from '@/constants/login';
import { useIdentity } from '@/platform/identity/useIdentity';
import { logoutApi } from '@/apis/login';

export default function LogoutPage() {
  const { setIdentity } = useIdentity();
  const navigate = useNavigate();
  useEffect(() => {
    void logoutApi().then(() => {
      setIdentity(null);
      sessionStorage.removeItem(USER_INFO_KEY);
      sessionStorage.removeItem(AUTHORIZATION_KEY);
      navigate('/', { replace: true });
    }).catch((error) => console.error('登出失败', error));
  }, [navigate, setIdentity]);
  return <div>正在登出中</div>;
}
