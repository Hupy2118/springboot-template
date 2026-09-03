import { Alert, Button, Result, Spin } from 'antd';
import { PropsWithChildren } from 'react';
import { type AuthState, useAuth } from '@/providers/AuthProvider';
import { usePermission } from '@/hooks/usePermission';

export function AuthStateView({ state }: { state: AuthState }) {
  const { refreshPermissions } = useAuth();
  if (state === 'loading') return <div className="authorization-state"><Spin tip="正在加载权限…" size="large" /></div>;
  if (state === 'not-ready') return <Result status="warning" title="权限运行时尚未就绪" subTitle="请在服务初始化完成后重试。" extra={<Button onClick={() => refreshPermissions()}>重试</Button>} />;
  if (state === 'unauthenticated') return <Result status="403" title="登录状态已失效" subTitle="请重新登录后再访问系统。" />;
  if (state === 'forbidden') return <Result status="403" title="无权限访问" subTitle="当前成员没有访问该页面的权限。" />;
  return <Alert type="error" showIcon message="权限状态加载失败" description="暂时无法验证您的访问权限，请稍后重试。" action={<Button onClick={() => refreshPermissions()}>重试</Button>} />;
}

export function RouteGuard({ resourceKey, children }: PropsWithChildren<{ resourceKey: string }>) {
  const { state } = useAuth();
  const { hasPermission } = usePermission();
  return hasPermission(resourceKey) ? <>{children}</> : <AuthStateView state={state} />;
}
