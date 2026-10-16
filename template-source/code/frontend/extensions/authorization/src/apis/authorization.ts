import service from '@/apis/service';
import type { Member, MemberList, MemberResources, PermissionResource, ResourceList, Role, RoleList, RoleMembers, RoleResources, RoleStatusRequest, RoleUpsertRequest } from '@/typings/authorization';

export type PageParams = { current?: number; pageSize?: number };

export const getMyResources = () => service.get<MemberResources>('/api/authorization/me/resources');
export const listAuthorizationResources = (params: PageParams = {}) => service.get<ResourceList>('/api/authorization/resources', { params });
export const getAuthorizationResource = (resourceKey: string) => service.get<PermissionResource>(`/api/authorization/resources/${encodeURIComponent(resourceKey)}`);
export const listAuthorizationRoles = (params: PageParams = {}) => service.get<RoleList>('/api/authorization/roles', { params });
export const createAuthorizationRole = (body: RoleUpsertRequest) => service.post<Role>('/api/authorization/roles', body);
export const getAuthorizationRole = (roleId: string) => service.get<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}`);
export const updateAuthorizationRole = (roleId: string, body: RoleUpsertRequest) => service.put<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}`, body);
export const deleteAuthorizationRole = (roleId: string) => service.delete<Record<string, never> | null>(`/api/authorization/roles/${encodeURIComponent(roleId)}`).then(() => undefined);
export const setAuthorizationRoleStatus = (roleId: string, body: RoleStatusRequest) => service.put<Role>(`/api/authorization/roles/${encodeURIComponent(roleId)}/status`, body);
export const getAuthorizationRoleResources = (roleId: string) => service.get<RoleResources>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`);
export const setAuthorizationRoleResources = (roleId: string, resourceKeys: string[]) => service.put<RoleResources>(`/api/authorization/roles/${encodeURIComponent(roleId)}/resources`, resourceKeys);
export const getAuthorizationRoleMembers = (roleId: string) => service.get<RoleMembers>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`);
export const bindAuthorizationRoleMembers = (roleId: string, members: Member[]) => service.put<RoleMembers>(`/api/authorization/roles/${encodeURIComponent(roleId)}/members`, members);
export const listAuthorizationMembers = (params: PageParams = {}) => service.get<MemberList>('/api/authorization/members', { params });
