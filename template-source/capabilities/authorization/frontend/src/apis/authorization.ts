import service, { ServiceResponseError } from '@/apis/service';
import type { Member, MemberList, MemberResources, MockLoginRequest, PermissionResource, ResourceList, Role, RoleList, RoleMembers, RoleResources, RoleStatusRequest, RoleUpsertRequest } from '@/typings/authorization';

export type PageParams = { current?: number; pageSize?: number };
export class AuthorizationApiError extends Error { constructor(public readonly returnCode: string, message?: string) { super(message || '授权服务请求失败'); } }
const request = <T>(promise: Promise<T>): Promise<T> => promise.catch((error: unknown) => {
  if (error instanceof ServiceResponseError) {
    throw new AuthorizationApiError(error.returnCode, error.message);
  }
  throw error;
});

export const mockAuthorizationLogin = (body: MockLoginRequest) => request<Member>(service.post<Member>('/api/authorization/mock-login', body));
export const getMyResources = () => request<MemberResources>(service.get<MemberResources>('/api/authorization/me/resources'));
export const listAuthorizationResources = (params: PageParams = {}) => request<ResourceList>(service.get<ResourceList>('/api/authorization/resources', { params }));
export const getAuthorizationResource = (resourceKey: string) => request<PermissionResource>(service.get<PermissionResource>(`/api/authorization/resources/${encodeURIComponent(resourceKey)}`));
export const listAuthorizationRoles = (params: PageParams = {}) => request<RoleList>(service.get<RoleList>('/api/authorization/roles', { params }));
export const createAuthorizationRole = (body: RoleUpsertRequest) => request<Role>(service.post<Role>('/api/authorization/roles', body));
export const getAuthorizationRole = (roleId: string) => request<Role>(service.get<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}`));
export const updateAuthorizationRole = (roleId: string, body: RoleUpsertRequest) => request<Role>(service.put<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}`, body));
export const deleteAuthorizationRole = (roleId: string) => request<Record<string, never> | null>(service.delete<Record<string, never> | null>(`/api/authorization/roles/${encodeURIComponent(roleId)}`)).then(() => undefined);
export const setAuthorizationRoleStatus = (roleId: string, body: RoleStatusRequest) => request<Role>(service.put<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}/status`, body));
export const getAuthorizationRoleResources = (roleId: string) => request<RoleResources>(service.get<RoleResources>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`));
export const setAuthorizationRoleResources = (roleId: string, resourceKeys: string[]) => request<RoleResources>(service.put<RoleResources>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`, resourceKeys));
export const getAuthorizationRoleMembers = (roleId: string) => request<RoleMembers>(service.get<RoleMembers>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`));
export const bindAuthorizationRoleMembers = (roleId: string, members: Member[]) => request<RoleMembers>(service.put<RoleMembers>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`, members));
export const listAuthorizationMembers = (params: PageParams = {}) => request<MemberList>(service.get<MemberList>('/api/authorization/members', { params }));
