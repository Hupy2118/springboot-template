import service from '@/apis/service';
import type { EmptyEnvelope, Member, MemberEnvelope, MemberList, MemberListEnvelope, MemberResources, MemberResourcesEnvelope, MockLoginRequest, PermissionResource, PermissionResourceEnvelope, ResourceList, ResourceListEnvelope, Role, RoleEnvelope, RoleList, RoleListEnvelope, RoleMembers, RoleMembersEnvelope, RoleResources, RoleResourcesEnvelope, RoleStatusRequest, RoleUpsertRequest, ResponseEnvelope } from '@/typings/authorization';

export type PageParams = { current?: number; pageSize?: number };
export class AuthorizationApiError extends Error { constructor(public readonly returnCode: string, message?: string) { super(message || '授权服务请求失败'); } }
type Envelope<T> = ResponseEnvelope & { body?: T };
const unwrap = <T>(envelope: Envelope<T>): T => {
  if (envelope.returnCode !== 'SUC0000') throw new AuthorizationApiError(envelope.returnCode, envelope.errorMsg || undefined);
  return envelope.body as T;
};
const request = <T>(promise: Promise<Envelope<T>>) => promise.then(unwrap);

export const mockAuthorizationLogin = (body: MockLoginRequest) => request<Member>(service.post<MemberEnvelope>('/api/authorization/mock-login', body));
export const getMyResources = () => request<MemberResources>(service.get<MemberResourcesEnvelope>('/api/authorization/me/resources'));
export const listAuthorizationResources = (params: PageParams = {}) => request<ResourceList>(service.get<ResourceListEnvelope>('/api/authorization/resources', { params }));
export const getAuthorizationResource = (resourceKey: string) => request<PermissionResource>(service.get<PermissionResourceEnvelope>(`/api/authorization/resources/${encodeURIComponent(resourceKey)}`));
export const listAuthorizationRoles = (params: PageParams = {}) => request<RoleList>(service.get<RoleListEnvelope>('/api/authorization/roles', { params }));
export const createAuthorizationRole = (body: RoleUpsertRequest) => request<Role>(service.post<RoleEnvelope>('/api/authorization/roles', body));
export const getAuthorizationRole = (roleId: string) => request<Role>(service.get<RoleEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}`));
export const updateAuthorizationRole = (roleId: string, body: RoleUpsertRequest) => request<Role>(service.put<RoleEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}`, body));
export const deleteAuthorizationRole = (roleId: string) => request<Record<string, never> | null>(service.delete<EmptyEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}`)).then(() => undefined);
export const setAuthorizationRoleStatus = (roleId: string, body: RoleStatusRequest) => request<Role>(service.put<RoleEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}/status`, body));
export const getAuthorizationRoleResources = (roleId: string) => request<RoleResources>(service.get<RoleResourcesEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`));
export const setAuthorizationRoleResources = (roleId: string, resourceKeys: string[]) => request<RoleResources>(service.put<RoleResourcesEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`, resourceKeys));
export const getAuthorizationRoleMembers = (roleId: string) => request<RoleMembers>(service.get<RoleMembersEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`));
export const bindAuthorizationRoleMembers = (roleId: string, members: Member[]) => request<RoleMembers>(service.put<RoleMembersEnvelope>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`, members));
export const listAuthorizationMembers = (params: PageParams = {}) => request<MemberList>(service.get<MemberListEnvelope>('/api/authorization/members', { params }));
