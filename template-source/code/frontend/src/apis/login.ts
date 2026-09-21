import service from '@/apis/service';

/** 登录与登出接口。 */
export function loginApi(_code: string) {
  return Promise.resolve({ userId: '001', userName: 'haha' });
}

export function logoutApi() {
  return service.post('/logout');
}
