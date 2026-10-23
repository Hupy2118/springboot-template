import service from '@/apis/service';
import type { Identity } from '@/platform/identity/IdentityContext';

/** Standalone 模拟登录与登出接口。 */
export function loginApi(code: string) {
  return service.get<Identity>(`/api/sso/login?code=${encodeURIComponent(code)}`);
}

export function logoutApi() {
  return service.post<void>('/api/standalone/logout');
}
