export interface PermissionResource {
  resourceKey: string;
  name: string;
  description?: string | null;
  origin: 'system' | 'business';
  type: 'system' | 'page' | 'operation';
  targetResourceRef?: string | null;
}

export interface Role {
  roleId: string;
  name: string;
  description?: string | null;
  enable: boolean;
  readonly system: boolean;
}

export interface Member {
  memberId: string;
  memberName: string;
}

export interface MockLoginRequest {
  memberId: string;
}

export interface MemberResources {
  memberId: string;
  resourceKeys: string[];
}

export interface RoleUpsertRequest {
  name: string;
  description?: string | null;
}

export interface RoleStatusRequest {
  enable: boolean;
}

export interface RoleResources {
  roleId: string;
  resourceKeys: string[];
}

export interface RoleMembers {
  roleId: string;
  members: Member[];
}

interface PageResult<T> {
  total: number;
  list: T[];
  current: number;
  pageSize: number;
  totalPage: number;
}

export type ResourceList = PageResult<PermissionResource>;
export type RoleList = PageResult<Role>;
export type MemberList = PageResult<Member>;
