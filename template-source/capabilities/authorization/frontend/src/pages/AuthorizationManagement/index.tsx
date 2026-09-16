import { useCallback, useMemo, useState } from 'react';
import { useRequest } from 'ahooks';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  bindAuthorizationRoleMembers,
  createAuthorizationRole,
  deleteAuthorizationRole,
  getAuthorizationRoleMembers,
  getAuthorizationRoleResources,
  listAuthorizationMembers,
  listAuthorizationResources,
  listAuthorizationRoles,
  setAuthorizationRoleResources,
  setAuthorizationRoleStatus,
  updateAuthorizationRole,
} from '@/apis/authorization';
import type { Member, PermissionResource, Role } from '@/typings/authorization';
import { Permission } from '@/components/authorization/Permission';
import { RESOURCES } from '@/constants/resources';
import { usePermission } from '@/hooks/usePermission';

const errorMessage = (error: unknown) =>
  (error as { message?: string })?.message || '请求失败，请稍后重试。';

export default function AuthorizationManagementPage() {
  const { hasPermission, hasAnyPermission } = usePermission();
  const [roleModal, setRoleModal] = useState<Role | null | undefined>();
  const [resourceRole, setResourceRole] = useState<Role | null>(null);
  const [memberRole, setMemberRole] = useState<Role | null>(null);
  const [roleMembers, setRoleMembers] = useState<Member[]>([]);
  const [memberId, setMemberId] = useState('');
  const [roleCurrent, setRoleCurrent] = useState(1);
  const [form] = Form.useForm();

  const {
    data: rolePage,
    loading: rolesLoading,
    error: rolesError,
    refreshAsync: refreshRoles,
  } = useRequest(
    () => listAuthorizationRoles({ current: roleCurrent, pageSize: 100 }),
    { refreshDeps: [roleCurrent] },
  );
  const {
    data: memberPage,
    loading: membersLoading,
    error: membersError,
    refreshAsync: refreshMembers,
  } = useRequest(() => listAuthorizationMembers({ current: 1, pageSize: 100 }));
  const {
    data: resourcePage,
    loading: resourcesLoading,
    error: resourcesError,
    refreshAsync: refreshResources,
  } = useRequest(() =>
    listAuthorizationResources({ current: 1, pageSize: 100 }),
  );
  const {
    runAsync: loadRoleResources,
    loading: roleResourcesLoading,
  } = useRequest(getAuthorizationRoleResources, { manual: true });
  const {
    runAsync: saveRoleResources,
    loading: savingRoleResources,
  } = useRequest(setAuthorizationRoleResources, { manual: true });
  const {
    runAsync: loadRoleMembers,
    loading: roleMembersLoading,
  } = useRequest(getAuthorizationRoleMembers, { manual: true });
  const {
    runAsync: bindRoleMembers,
    loading: bindingRoleMembers,
  } = useRequest(bindAuthorizationRoleMembers, { manual: true });
  const {
    runAsync: createRole,
    loading: creatingRole,
  } = useRequest(createAuthorizationRole, { manual: true });
  const {
    runAsync: updateRole,
    loading: updatingRole,
  } = useRequest(updateAuthorizationRole, { manual: true });
  const {
    runAsync: deleteRole,
    loading: deletingRole,
  } = useRequest(deleteAuthorizationRole, { manual: true });
  const {
    runAsync: setRoleStatus,
    loading: settingRoleStatus,
  } = useRequest(setAuthorizationRoleStatus, { manual: true });

  const refreshAll = useCallback(async () => {
    await Promise.all([refreshRoles(), refreshMembers(), refreshResources()]);
  }, [refreshMembers, refreshResources, refreshRoles]);
  const roles = (rolePage?.list ?? []) as Role[];
  const members = (memberPage?.list ?? []) as Member[];
  const resources = (resourcePage?.list ?? []) as PermissionResource[];
  const visibleMembers = useMemo(
    () =>
      memberId.trim()
        ? members.filter((member) => member.memberId.includes(memberId.trim()))
        : roleMembers,
    [memberId, members, roleMembers],
  );

  const submitRole = async () => {
    const values = await form.validateFields();
    try {
      if (roleModal) await updateRole(roleModal.roleId, values);
      else await createRole(values);
      message.success(roleModal ? '角色已更新' : '角色已创建');
      setRoleModal(undefined);
      await refreshAll();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const changeRoleStatus = async (role: Role, enable: boolean) => {
    try {
      await setRoleStatus(role.roleId, { enable });
      message.success('角色状态已更新');
      await refreshAll();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const openResourceDialog = async (role: Role) => {
    try {
      const relation = await loadRoleResources(role.roleId);
      setResourceRole(role);
      form.setFieldsValue({ resourceKeys: relation.resourceKeys });
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const submitResources = async () => {
    if (!resourceRole) return;
    try {
      await saveRoleResources(
        resourceRole.roleId,
        form.getFieldValue('resourceKeys') || [],
      );
      message.success('角色资源已更新');
      setResourceRole(null);
      await refreshAll();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const openMemberDialog = async (role: Role) => {
    try {
      const relation = await loadRoleMembers(role.roleId);
      setMemberRole(role);
      setRoleMembers(relation.members);
      setMemberId('');
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const addMember = async () => {
    if (!memberRole || !memberId.trim()) {
      message.warning('请输入成员 ID');
      return;
    }
    const member = members.find((item) => item.memberId === memberId.trim());
    if (!member) {
      message.warning('成员目录中未找到该成员 ID');
      return;
    }
    if (roleMembers.some((item) => item.memberId === member.memberId)) {
      message.info('该成员已绑定当前角色');
      return;
    }
    try {
      const relation = await bindRoleMembers(memberRole.roleId, [
        ...roleMembers,
        member,
      ]);
      setRoleMembers(relation.members);
      setMemberId('');
      message.success(`已将 ${member.memberName} 添加到 ${memberRole.name}`);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const removeMember = async (member: Member) => {
    if (!memberRole) return;
    try {
      const relation = await bindRoleMembers(
        memberRole.roleId,
        roleMembers.filter((item) => item.memberId !== member.memberId),
      );
      setRoleMembers(relation.members);
      message.success(`已从 ${memberRole.name} 移除 ${member.memberName}`);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };
  const removeRole = async (role: Role) => {
    try {
      await deleteRole(role.roleId);
      message.success('角色已删除');
      await refreshAll();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const canManageRoles = hasAnyPermission([
    RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT,
  ]);
  const columns = useMemo<ColumnsType<Role>>(
    () => [
      {
        title: '角色',
        dataIndex: 'name',
        render: (name, role) => (
          <Space>
            <Typography.Text strong>{name}</Typography.Text>
            {role.system && <Tag color='blue'>系统</Tag>}
          </Space>
        ),
      },
      { title: '说明', dataIndex: 'description', ellipsis: true },
      ...(hasPermission(RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT)
        ? [
            {
              title: '状态',
              render: (_: unknown, role: Role) => (
                <Permission
                  resourceKey={RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT}
                  mode='disabled'
                >
                  <Switch
                    checked={role.enable}
                    disabled={role.system || settingRoleStatus}
                    checkedChildren='启用'
                    unCheckedChildren='停用'
                    onChange={(enable) => changeRoleStatus(role, enable)}
                  />
                </Permission>
              ),
            },
          ]
        : []),
      ...(canManageRoles
        ? [
            {
              title: '操作',
              render: (_: unknown, role: Role) => (
                <Space wrap>
                  {hasPermission(RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT) && (
                    <Button
                      type='link'
                      disabled={role.system}
                      onClick={() => {
                        setRoleModal(role);
                        form.setFieldsValue(role);
                      }}
                    >
                      编辑
                    </Button>
                  )}
                  {hasPermission(RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT) && (
                    <Button
                      type='link'
                      loading={
                        roleResourcesLoading &&
                        resourceRole?.roleId === role.roleId
                      }
                      onClick={() => openResourceDialog(role)}
                    >
                      授权资源
                    </Button>
                  )}
                  {hasPermission(RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT) && (
                    <Button
                      type='link'
                      loading={
                        roleMembersLoading && memberRole?.roleId === role.roleId
                      }
                      onClick={() => openMemberDialog(role)}
                    >
                      配置成员
                    </Button>
                  )}
                  {hasPermission(RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT) && (
                    <Popconfirm
                      title='删除该角色？'
                      disabled={role.system}
                      onConfirm={() => removeRole(role)}
                    >
                      <Button
                        type='link'
                        danger
                        disabled={role.system}
                        loading={deletingRole}
                      >
                        删除
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
          ]
        : []),
    ],
    [
      canManageRoles,
      changeRoleStatus,
      deletingRole,
      form,
      hasPermission,
      memberRole?.roleId,
      openMemberDialog,
      openResourceDialog,
      removeRole,
      resourceRole?.roleId,
      roleMembersLoading,
      roleResourcesLoading,
      settingRoleStatus,
    ],
  );
  const error = rolesError || membersError || resourcesError;

  return (
    <div className='authorization-page'>
      <Card className='authorization-hero' bordered={false}>
        <Space direction='vertical' size={4}>
          <Typography.Title level={2}>角色管理</Typography.Title>
          <Typography.Text type='secondary'>
            管理角色、资源点及成员。
          </Typography.Text>
        </Space>
        <Button
          loading={rolesLoading || membersLoading || resourcesLoading}
          onClick={() => refreshAll()}
        >
          刷新
        </Button>
      </Card>
      {error ? (
        <Alert
          className='authorization-alert'
          type='error'
          showIcon
          message='授权数据加载失败'
          description={errorMessage(error)}
          action={<Button onClick={() => refreshAll()}>重试</Button>}
        />
      ) : null}
      <Card
        extra={
          <Permission
            resourceKey={RESOURCES.SYSTEM.AUTHORIZATION_MANAGEMENT}
            mode='disabled'
          >
            <Button
              type='primary'
              onClick={() => {
                setRoleModal(null);
                form.resetFields();
              }}
            >
              创建角色
            </Button>
          </Permission>
        }
      >
        <Table
          rowKey='roleId'
          loading={rolesLoading}
          dataSource={roles}
          columns={columns}
          locale={{ emptyText: <Empty description='暂无角色' /> }}
          pagination={{
            current: rolePage?.current ?? roleCurrent,
            pageSize: rolePage?.pageSize ?? 100,
            total: rolePage?.total,
            hideOnSinglePage: true,
            onChange: (current) => setRoleCurrent(current),
          }}
        />
      </Card>
      <Modal
        open={roleModal !== undefined}
        title={roleModal ? '修改角色' : '创建角色'}
        confirmLoading={creatingRole || updatingRole}
        onCancel={() => setRoleModal(undefined)}
        onOk={submitRole}
      >
        <Form form={form} layout='vertical'>
          <Form.Item
            name='name'
            label='角色名称'
            rules={[{ required: true, whitespace: true, max: 32 }]}
          >
            <Input autoFocus />
          </Form.Item>
          <Form.Item name='description' label='说明' rules={[{ max: 128 }]}>
            <Input.TextArea rows={4} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        open={!!resourceRole}
        title={`授权资源 · ${resourceRole?.name || ''}`}
        confirmLoading={savingRoleResources}
        onCancel={() => setResourceRole(null)}
        onOk={submitResources}
      >
        <Form form={form} layout='vertical'>
          <Form.Item name='resourceKeys' label='资源点（保存时全量替换）'>
            <Select
              mode='multiple'
              optionFilterProp='label'
              options={resources.map((resource: PermissionResource) => ({
                value: resource.resourceKey,
                label: `${resource.name}（${resource.resourceKey}）`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        open={!!memberRole}
        title={`配置成员 · ${memberRole?.name || ''}`}
        footer={null}
        onCancel={() => setMemberRole(null)}
      >
        <Space.Compact style={{ display: 'flex', marginBottom: 16 }}>
          <Input
            value={memberId}
            placeholder='输入成员 ID'
            onChange={(event) => setMemberId(event.target.value)}
            onPressEnter={addMember}
          />
          <Button
            type='primary'
            loading={bindingRoleMembers}
            onClick={addMember}
          >
            添加
          </Button>
        </Space.Compact>
        <Typography.Paragraph type='secondary'>
          仅可绑定成员目录中已有的成员；输入成员 ID 可筛选目录。
        </Typography.Paragraph>
        <Table
          rowKey='memberId'
          size='small'
          dataSource={visibleMembers}
          loading={membersLoading || roleMembersLoading}
          pagination={false}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description='该角色暂未配置成员'
              />
            ),
          }}
          columns={[
            { title: '成员 ID', dataIndex: 'memberId' },
            { title: '名称', dataIndex: 'memberName' },
            {
              title: '操作',
              render: (_, member) =>
                roleMembers.some(
                  (item) => item.memberId === member.memberId,
                ) ? (
                  <Popconfirm
                    title={`从 ${memberRole?.name} 移除该成员？`}
                    onConfirm={() => removeMember(member)}
                  >
                    <Button type='link' danger loading={bindingRoleMembers}>
                      移除
                    </Button>
                  </Popconfirm>
                ) : (
                  <Button
                    type='link'
                    onClick={() => setMemberId(member.memberId)}
                  >
                    选择
                  </Button>
                ),
            },
          ]}
        />
      </Modal>
    </div>
  );
}
