import service from './service';

export interface MockLoginRequest {
  memberId: string;
  memberName: string;
}

export interface MockLoginResponse {
  memberId: string;
  memberName: string;
}

interface ResponseEnvelope<T> {
  code: string | number;
  message?: string;
  data: T;
}

export async function mockLogin(body: MockLoginRequest): Promise<MockLoginResponse> {
  const response = await service.post<ResponseEnvelope<MockLoginResponse>>('/api/login/mock', body);
  if (String(response.data.code) !== '0') throw new Error(response.data.message || '登录失败');
  return response.data.data;
}
