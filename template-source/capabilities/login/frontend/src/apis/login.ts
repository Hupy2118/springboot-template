import service from '@/apis/service';

export interface MockLoginRequest {
  memberId: string;
  memberName: string;
}

export interface MockLoginResponse {
  memberId: string;
  memberName: string;
}

export async function mockLogin(body: MockLoginRequest): Promise<MockLoginResponse> {
  return service.post<MockLoginResponse>('/api/login/mock', body);
}
